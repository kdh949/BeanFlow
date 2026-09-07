import http from 'node:http';
import { pathToFileURL } from 'node:url';

const MAX_BODY_BYTES = 32 * 1024;
const MAX_RETAINED_PAYMENTS = 10_000;

export function createTossDriverServer({ timeoutDelayMs = 9000 } = {}) {
  const paymentsByKey = new Map();
  const paymentKeyByOrder = new Map();
  const confirmationsByKey = new Map();
  const cancellationsByKey = new Map();

  const server = http.createServer(async (request, response) => {
    try {
      if (request.method === 'GET' && request.url === '/healthz') {
        json(response, 200, { status: 'UP' });
        return;
      }
      if (!authorized(request)) {
        json(response, 401, { code: 'UNAUTHORIZED' });
        return;
      }

      const url = new URL(request.url, 'http://toss-driver:8080');
      if (request.method === 'POST' && url.pathname === '/v1/payments/confirm') {
        const payload = await readJson(request);
        if (!validConfirmation(payload) || !validIdempotencyKey(request)) {
          json(response, 400, { code: 'INVALID_REQUEST' });
          return;
        }
        await confirm(payload, response, paymentsByKey, paymentKeyByOrder, timeoutDelayMs);
        return;
      }

      const cancelMatch = url.pathname.match(/^\/v1\/payments\/([^/]+)\/cancel$/);
      if (request.method === 'POST' && cancelMatch) {
        const payload = await readJson(request);
        if (!validIdempotencyKey(request) || typeof payload.cancelReason !== 'string') {
          json(response, 400, { code: 'INVALID_REQUEST' });
          return;
        }
        const payment = paymentsByKey.get(cancelMatch[1]);
        if (!payment) {
          json(response, 404, { code: 'NOT_FOUND_PAYMENT' });
          return;
        }
        const idempotencyKey = request.headers['idempotency-key'];
        const cancellations = cancellationsByKey.get(payment.paymentKey) ?? new Map();
        const signature = JSON.stringify([payload.cancelReason, payload.cancelAmount ?? null]);
        const previous = cancellations.get(idempotencyKey);
        if (previous) {
          json(response, previous.signature === signature ? 200 : 409,
            previous.signature === signature ? previous.body : { code: 'IDEMPOTENCY_KEY_REUSED' });
          return;
        }
        const remaining = payment.totalAmount - payment.cancels.reduce((sum, entry) => sum + entry.cancelAmount, 0);
        const amount = payload.cancelAmount ?? remaining;
        if (!Number.isSafeInteger(amount) || amount <= 0 || amount > remaining) {
          json(response, 400, { code: 'INVALID_CANCEL_AMOUNT' });
          return;
        }
        const cancel = {
          cancelAmount: amount,
          cancelReason: payload.cancelReason,
          cancelStatus: 'DONE',
          transactionKey: `${payment.paymentKey}-cancel-${payment.cancels.length + 1}`,
        };
        payment.cancels.push(cancel);
        payment.status = amount === remaining ? 'CANCELED' : 'PARTIAL_CANCELED';
        cancellations.set(idempotencyKey, { signature, body: structuredClone(payment) });
        cancellationsByKey.set(payment.paymentKey, cancellations);
        json(response, 200, payment);
        event('cancel', 'success');
        return;
      }

      const orderMatch = url.pathname.match(/^\/v1\/payments\/orders\/([^/]+)$/);
      if (request.method === 'GET' && orderMatch) {
        const paymentKey = paymentKeyByOrder.get(orderMatch[1]);
        lookup(response, paymentKey ? paymentsByKey.get(paymentKey) : null);
        return;
      }
      const paymentMatch = url.pathname.match(/^\/v1\/payments\/([^/]+)$/);
      if (request.method === 'GET' && paymentMatch) {
        lookup(response, paymentsByKey.get(paymentMatch[1]));
        return;
      }

      json(response, 404, { code: 'NOT_FOUND' });
    } catch (failure) {
      const code = failure instanceof RequestError ? failure.status : 500;
      json(response, code, { code: code === 413 ? 'PAYLOAD_TOO_LARGE' : 'INVALID_REQUEST' });
      event('request', 'failure');
    }
  });

  server.requestTimeout = 15_000;
  server.headersTimeout = 5_000;
  server.on('clientError', (_failure, socket) => socket.destroy());
  return server;

  async function confirm(payload, response, byKey, byOrder, delayMs) {
    const existing = confirmationsByKey.get(payload.paymentKey);
    if (existing) {
      const matches = existing.orderId === payload.orderId && existing.totalAmount === payload.amount;
      json(response, matches ? 200 : 409, matches ? existing : { code: 'PAYMENT_BINDING_MISMATCH' });
      return;
    }
    const scenario = scenarioOf(payload.paymentKey);
    if (scenario === 'decline') {
      json(response, 400, { code: 'INVALID_REJECT_CARD', message: 'declined' });
      event('confirm', 'declined');
      return;
    }
    if (scenario === 'error') {
      json(response, 500, { code: 'INTERNAL_SERVER_ERROR' });
      event('confirm', 'unknown');
      return;
    }
    if (scenario === 'malformed') {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end('{malformed');
      event('confirm', 'malformed');
      return;
    }

    const payment = {
      paymentKey: payload.paymentKey,
      orderId: payload.orderId,
      status: scenario === 'unknown' ? 'IN_PROGRESS' : 'DONE',
      totalAmount: payload.amount,
      currency: 'KRW',
      cancels: [],
    };
    retain(payment, byKey, byOrder);
    confirmationsByKey.set(payment.paymentKey, structuredClone(payment));
    if (scenario === 'timeout') {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
    json(response, 200, payment);
    event('confirm', scenario === 'unknown' ? 'unknown' : 'success');
  }

  function retain(payment, byKey, byOrder) {
    if (byKey.size >= MAX_RETAINED_PAYMENTS) {
      const oldestKey = byKey.keys().next().value;
      const oldest = byKey.get(oldestKey);
      byKey.delete(oldestKey);
      confirmationsByKey.delete(oldestKey);
      cancellationsByKey.delete(oldestKey);
      if (oldest) byOrder.delete(oldest.orderId);
    }
    byKey.set(payment.paymentKey, payment);
    byOrder.set(payment.orderId, payment.paymentKey);
  }
}

function lookup(response, payment) {
  if (!payment) {
    json(response, 404, { code: 'NOT_FOUND_PAYMENT' });
    event('lookup', 'unknown');
    return;
  }
  json(response, 200, payment);
  event('lookup', payment.status === 'DONE' ? 'success' : 'unknown');
}

function scenarioOf(paymentKey) {
  for (const scenario of ['success', 'decline', 'unknown', 'malformed', 'error', 'timeout']) {
    if (paymentKey.startsWith(`perf-${scenario}-`)) return scenario;
  }
  return 'unknown';
}

function authorized(request) {
  const authorization = request.headers.authorization;
  if (!authorization?.startsWith('Basic ')) return false;
  try {
    return Buffer.from(authorization.slice(6), 'base64').toString('utf8').startsWith('test_sk_');
  } catch {
    return false;
  }
}

function validIdempotencyKey(request) {
  const value = request.headers['idempotency-key'];
  return typeof value === 'string' && /^[\x21-\x7E]{1,300}$/.test(value);
}

function validConfirmation(payload) {
  return (
    payload &&
    typeof payload.paymentKey === 'string' &&
    /^perf-[a-z]+-[A-Za-z0-9._-]{1,80}$/.test(payload.paymentKey) &&
    typeof payload.orderId === 'string' &&
    /^bf_[A-Za-z0-9._-]{1,100}$/.test(payload.orderId) &&
    Number.isSafeInteger(payload.amount) &&
    payload.amount > 0
  );
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new RequestError(413);
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new RequestError(400);
  }
}

function json(response, status, body) {
  if (response.headersSent || response.destroyed) return;
  response.writeHead(status, { 'content-type': 'application/json' });
  response.end(JSON.stringify(body));
}

function event(operation, outcome) {
  process.stdout.write(`${JSON.stringify({ event: 'toss_perf_driver', operation, outcome })}\n`);
}

class RequestError extends Error {
  constructor(status) {
    super('request rejected');
    this.status = status;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number.parseInt(process.env.PORT ?? '8080', 10);
  const server = createTossDriverServer();
  server.listen(port, '0.0.0.0', () => {
    process.stdout.write(`${JSON.stringify({ event: 'toss_perf_driver_ready', port })}\n`);
  });
}
