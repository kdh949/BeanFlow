import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const suite = await readFile(new URL('./beanflow-load.js', import.meta.url), 'utf8');
const probes = await readFile(new URL('./provider-probes.js', import.meta.url), 'utf8');

test('order load preserves quote fingerprint contract', () => {
  assert.match(suite, /\/api\/v1\/me\/order-quotes/);
  assert.match(suite, /expectedQuoteFingerprint: fingerprint/);
  assert.match(suite, /\/api\/v1\/orders/);
});

test('all requests carry bounded diagnostics metadata', () => {
  assert.match(suite, /X-BeanFlow-Test-Id/);
  assert.match(suite, /X-BeanFlow-Scenario/);
  assert.match(suite, /Unsupported BEANFLOW_LOAD_SCENARIO/);
  assert.doesNotMatch(suite, /Authorization: `Bearer \$\{customer/);
});

test('payment load distinguishes deterministic Toss outcomes and queries unknown state', () => {
  for (const outcome of ['toss-success', 'toss-decline', 'toss-timeout', 'toss-unknown']) {
    assert.match(suite, new RegExp(outcome));
  }
  assert.match(suite, /unknown payment remains explicit/);
  assert.match(suite, /UNKNOWN.*RECONCILING.*APPROVED.*MANUAL_REVIEW/);
});

test('load gates include latency, HTTP errors, dropped arrivals and exact idempotency replay', () => {
  assert.match(suite, /BEANFLOW_P95_MS \|\| '1000'/);
  assert.match(suite, /http_req_failed: \['rate<0\.01'\]/);
  assert.match(suite, /dropped_iterations: \['count==0'\]/);
  assert.match(suite, /'idempotency'/);
  assert.match(suite, /idempotency replay returns the original order/);
});

test('provider probes are one-shot and use fixed BeanFlow API paths', () => {
  assert.match(probes, /executor: 'per-vu-iterations'/);
  assert.match(probes, /\/api\/v1\/stores\/\$\{probe\.storeId\}\/image/);
  assert.match(probes, /\/api\/v1\/support\/data-access-grants\/\$\{probe\.grantId\}\/reveals/);
  assert.doesNotMatch(probes, /probe\.path/);
});
