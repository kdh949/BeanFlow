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
