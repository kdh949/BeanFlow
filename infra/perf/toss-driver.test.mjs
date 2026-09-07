import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';

import { createTossDriverServer } from './toss-driver.mjs';

let server;
let baseUrl;

before(async () => {
  server = createTossDriverServer({ timeoutDelayMs: 30 });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(async () => {
  await new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
});

test('success confirmation echoes the required Toss binding fields', async () => {
  const response = await confirm('perf-success-payment');

  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.paymentKey, 'perf-success-payment');
  assert.equal(body.orderId, 'bf_order_123');
  assert.equal(body.status, 'DONE');
  assert.equal(body.totalAmount, 1000);
  assert.equal(body.currency, 'KRW');
});

test('decline and malformed scenarios are deterministic', async () => {
  const declined = await confirm('perf-decline-payment');
  const malformed = await confirm('perf-malformed-payment');

  assert.equal(declined.status, 400);
  assert.equal((await declined.json()).code, 'INVALID_REJECT_CARD');
  assert.equal(malformed.status, 200);
  assert.equal(await malformed.text(), '{malformed');
});

test('timeout confirmation is retained for a later lookup', async () => {
  const startedAt = Date.now();
  const response = await confirm('perf-timeout-payment');
  assert.ok(Date.now() - startedAt >= 25);
  assert.equal(response.status, 200);

  const lookup = await fetch(`${baseUrl}/v1/payments/perf-timeout-payment`, { headers: authHeaders() });
  assert.equal(lookup.status, 200);
  assert.equal((await lookup.json()).status, 'DONE');
});

test('missing test authorization is rejected', async () => {
  const response = await fetch(`${baseUrl}/v1/payments/perf-success-payment`);
  assert.equal(response.status, 401);
});

test('rejection refund accepts the application colon-delimited idempotency key and is replayable', async () => {
  const paymentKey = 'perf-success-rejection-refund';
  await confirm(paymentKey);
  const key = 'refund:rejection:12345678-1234-4234-8234-123456789abc';
  const first = await cancel(paymentKey, key, { cancelReason: 'BeanFlow refund [bf:test]', cancelAmount: 1000 });
  assert.equal(first.status, 200);
  const body = await first.json();
  assert.equal(body.status, 'CANCELED');
  assert.equal(body.cancels.length, 1);
  const replay = await cancel(paymentKey, key, { cancelReason: 'BeanFlow refund [bf:test]', cancelAmount: 1000 });
  assert.equal(replay.status, 200);
  assert.deepEqual(await replay.json(), body);
  const lookup = await fetch(`${baseUrl}/v1/payments/${paymentKey}`, { headers: authHeaders() });
  assert.equal((await lookup.json()).cancels.length, 1);
});

test('same cancellation key with a changed payload is rejected without another cancellation', async () => {
  const paymentKey = 'perf-success-refund-conflict';
  await confirm(paymentKey);
  assert.equal((await cancel(paymentKey, 'refund:conflict', { cancelReason: 'first', cancelAmount: 400 })).status, 200);
  const conflict = await cancel(paymentKey, 'refund:conflict', { cancelReason: 'changed', cancelAmount: 600 });
  assert.equal(conflict.status, 409);
  const lookup = await fetch(`${baseUrl}/v1/payments/${paymentKey}`, { headers: authHeaders() });
  const body = await lookup.json();
  assert.equal(body.cancels.length, 1);
  assert.equal(body.cancels[0].cancelAmount, 400);
});

test('confirmation retry preserves cancellation history and refund cannot exceed remaining amount', async () => {
  const paymentKey = 'perf-success-refund-history';
  await confirm(paymentKey);
  const first = await cancel(paymentKey, 'refund:first', { cancelReason: 'first', cancelAmount: 400 });
  assert.equal(first.status, 200);
  await confirm(paymentKey);
  const excessive = await cancel(paymentKey, 'refund:excessive', { cancelReason: 'excessive', cancelAmount: 1000 });
  assert.equal(excessive.status, 400);
  const lookup = await fetch(`${baseUrl}/v1/payments/${paymentKey}`, { headers: authHeaders() });
  assert.equal((await lookup.json()).cancels.length, 1);
  const rest = await cancel(paymentKey, 'refund:rest', { cancelReason: 'rest' });
  const body = await rest.json();
  assert.equal(body.status, 'CANCELED');
  assert.deepEqual(body.cancels.map((entry) => entry.cancelAmount), [400, 600]);
});

test('idempotency key accepts 300 printable characters and rejects longer keys', async () => {
  const paymentKey = 'perf-success-key-limit';
  await confirm(paymentKey);
  assert.equal((await cancel(paymentKey, 'r'.repeat(301), { cancelReason: 'too long' })).status, 400);
  assert.equal((await cancel(paymentKey, 'r'.repeat(300), { cancelReason: 'valid' })).status, 200);
});

test('refund transaction references are distinct across payments', async () => {
  const references = [];
  for (const suffix of ['first', 'second']) {
    const paymentKey = `perf-success-distinct-${suffix}`;
    await confirm(paymentKey);
    const response = await cancel(paymentKey, `refund:${suffix}`, { cancelReason: 'full refund' });
    assert.equal(response.status, 200);
    references.push((await response.json()).cancels[0].transactionKey);
  }
  assert.equal(new Set(references).size, 2);
});

test('capacity rejects new payments without evicting facts or blocking replay and refund', async () => {
  const limited = createTossDriverServer({ maxRetainedPayments: 1 });
  await new Promise((resolve) => limited.listen(0, '127.0.0.1', resolve));
  const root = `http://127.0.0.1:${limited.address().port}`;
  const headers = { ...authHeaders(), 'content-type': 'application/json', 'idempotency-key': 'capacity:test' };
  const submit = (suffix) => fetch(`${root}/v1/payments/confirm`, {
    method: 'POST', headers,
    body: JSON.stringify({ paymentKey: `perf-success-${suffix}`, orderId: `bf_${suffix}`, amount: 1000 }),
  });
  try {
    const first = await submit('retained');
    assert.equal(first.status, 200);
    const original = await first.json();
    const overflow = await submit('overflow');
    assert.equal(overflow.status, 503);
    assert.equal((await overflow.json()).code, 'DRIVER_CAPACITY_EXCEEDED');
    assert.deepEqual(await (await submit('retained')).json(), original);
    const lookup = await fetch(`${root}/v1/payments/orders/bf_retained`, { headers });
    assert.deepEqual(await lookup.json(), original);
    assert.equal((await fetch(`${root}/v1/payments/perf-success-overflow`, { headers })).status, 404);
    const refund = await fetch(`${root}/v1/payments/perf-success-retained/cancel`, {
      method: 'POST', headers, body: JSON.stringify({ cancelReason: 'capacity boundary refund' }),
    });
    assert.equal(refund.status, 200);
    assert.equal((await refund.json()).status, 'CANCELED');
  } finally {
    await new Promise((resolve, reject) => limited.close((error) => error ? reject(error) : resolve()));
  }
});

function cancel(paymentKey, key, payload) {
  return fetch(`${baseUrl}/v1/payments/${paymentKey}/cancel`, {
    method: 'POST',
    headers: { ...authHeaders(), 'content-type': 'application/json', 'idempotency-key': key },
    body: JSON.stringify(payload),
  });
}

function confirm(paymentKey) {
  return fetch(`${baseUrl}/v1/payments/confirm`, {
    method: 'POST',
    headers: {
      ...authHeaders(),
      'content-type': 'application/json',
      'idempotency-key': `idem-${paymentKey}`,
    },
    body: JSON.stringify({ paymentKey, orderId: 'bf_order_123', amount: 1000 }),
  });
}

function authHeaders() {
  return { authorization: `Basic ${Buffer.from('test_sk_perf:').toString('base64')}` };
}
