import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

const scenario = __ENV.BEANFLOW_LOAD_SCENARIO || 'quote-order';
const supportedScenarios = new Set([
  'quote-order',
  'idempotency',
  'board-polling',
  'toss-success',
  'toss-decline',
  'toss-timeout',
  'toss-unknown',
  'db-lock',
]);

if (!supportedScenarios.has(scenario)) {
  throw new Error(`Unsupported BEANFLOW_LOAD_SCENARIO: ${scenario}`);
}

const baseUrl = required('BEANFLOW_BASE_URL').replace(/\/$/, '');
const testId = required('BEANFLOW_TEST_ID');
if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(testId)) {
  throw new Error('BEANFLOW_TEST_ID must match the perf telemetry header contract');
}

const fixturePath = required('BEANFLOW_LOAD_FIXTURE');
const fixture = JSON.parse(open(fixturePath));
validateFixture(fixture, scenario);

const businessFailures = new Rate('beanflow_business_failures');
const quoteDuration = new Trend('beanflow_quote_duration', true);
const orderDuration = new Trend('beanflow_order_duration', true);
const paymentDuration = new Trend('beanflow_payment_duration', true);
const boardNotModified = new Counter('beanflow_board_not_modified');
const workflowStarted = new Counter('beanflow_workflow_started');
const workflowCompleted = new Counter('beanflow_workflow_completed');
const workflowSuccesses = new Counter('beanflow_workflow_successes');
const workflowFailures = new Rate('beanflow_workflow_failures');
const workflowDuration = new Trend('beanflow_workflow_duration', true);
const ordersCreated = new Counter('beanflow_orders_created');
const paymentsApproved = new Counter('beanflow_payments_approved');
const paymentOutcomes = new Counter('beanflow_payment_outcomes');
const targetRate = new Gauge('beanflow_target_iterations_per_second');

const duration = __ENV.BEANFLOW_DURATION || '1m';
const arrivalRate = positiveInteger(__ENV.BEANFLOW_RATE || '1', 'BEANFLOW_RATE');
const preAllocatedVUs = positiveInteger(__ENV.BEANFLOW_PREALLOCATED_VUS || '4', 'BEANFLOW_PREALLOCATED_VUS');
const maxVUs = positiveInteger(__ENV.BEANFLOW_MAX_VUS || '20', 'BEANFLOW_MAX_VUS');
const boardVUs = positiveInteger(__ENV.BEANFLOW_BOARD_VUS || '4', 'BEANFLOW_BOARD_VUS');
const p95Limit = positiveInteger(__ENV.BEANFLOW_P95_MS || '1000', 'BEANFLOW_P95_MS');
if (maxVUs < preAllocatedVUs) throw new Error('BEANFLOW_MAX_VUS must be at least BEANFLOW_PREALLOCATED_VUS');

export const options = {
  discardResponseBodies: false,
  // Raw URL and error text contain order/payment IDs; retain only bounded route names.
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'check', 'error_code'],
  tags: { testid: testId, load_scenario: scenario, application: 'beanflow' },
  summaryTrendStats: ['min', 'avg', 'med', 'max', 'p(95)', 'p(99)'],
  scenarios:
    scenario === 'board-polling'
      ? {
          beanflow: {
            executor: 'constant-vus',
            exec: 'boardPolling',
            vus: boardVUs,
            duration,
            gracefulStop: '10s',
          },
        }
      : {
          beanflow: {
            executor: 'constant-arrival-rate',
            exec: scenario.startsWith('toss-') ? 'tossPayment' : 'quoteOrder',
            rate: arrivalRate,
            timeUnit: '1s',
            duration,
            preAllocatedVUs,
            maxVUs,
            gracefulStop: '20s',
          },
        },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_duration: [`p(95)<${p95Limit}`],
    http_req_failed: ['rate<0.01'],
    dropped_iterations: ['count==0'],
    beanflow_business_failures: ['rate<0.01'],
    beanflow_workflow_failures: ['rate<0.01'],
  },
};

let boardEtag;

export function handleSummary(data) {
  const metrics = data.metrics;
  const count = metrics.beanflow_workflow_completed?.values.count ?? 'unavailable';
  const failures = metrics.beanflow_workflow_failures?.values.rate;
  const result = { stdout: `BeanFlow ${testId}: completed=${count}, workflow_failure_rate=${failures ?? 'unavailable'}\n` };
  if (__ENV.BEANFLOW_SUMMARY_PATH) result[__ENV.BEANFLOW_SUMMARY_PATH] = JSON.stringify(data, null, 2);
  return result;
}

export function quoteOrder() {
  return workflow(() => Boolean(createQuotedOrder(customerForVu(), scenario)));
}

export function tossPayment() {
  return workflow(confirmPayment);
}

function workflow(operation) {
  const started = Date.now();
  let success = false;
  workflowStarted.add(1);
  workflowSuccesses.add(0);
  ordersCreated.add(0);
  paymentsApproved.add(0);
  if (scenario !== 'board-polling') targetRate.add(arrivalRate);
  try {
    success = operation() === true;
    return success;
  } finally {
    workflowCompleted.add(1);
    workflowSuccesses.add(Number(success));
    workflowFailures.add(!success);
    workflowDuration.add(Date.now() - started);
  }
}

function confirmPayment() {
  const customer = customerForVu();
  const order = createQuotedOrder(customer, scenario);
  if (!order) return;

  const attemptKey = idempotencyKey('attempt');
  const attemptResponse = http.post(
    `${baseUrl}/api/v1/orders/${order.orderId}/payment-attempts`,
    null,
    requestParams(customer, scenario, 'POST /api/v1/orders/{orderId}/payment-attempts', attemptKey),
  );
  const attemptOk = check(attemptResponse, {
    'payment attempt is 200': (response) => response.status === 200,
  });
  businessFailures.add(!attemptOk);
  if (!attemptOk) return;

  const attempt = jsonBody(attemptResponse, 'payment attempt');
  const tossOutcome = scenario.slice('toss-'.length);
  const paymentKey = `perf-${tossOutcome}-${testId}-${exec.vu.idInTest}-${exec.scenario.iterationInTest}`;
  const expectedStatuses = tossOutcome === 'success' ? [200] : tossOutcome === 'decline' ? [422] : [202];
  const confirmResponse = http.post(
    `${baseUrl}/api/v1/payments/${attempt.paymentId}/confirmations`,
    JSON.stringify({
      paymentKey,
      orderId: attempt.providerOrderId,
      amount: attempt.amount.value,
    }),
    requestParams(
      customer,
      scenario,
      'POST /api/v1/payments/{paymentId}/confirmations',
      idempotencyKey('confirm'),
      expectedStatuses,
    ),
  );
  paymentDuration.add(confirmResponse.timings.duration);
  const confirmOk = check(confirmResponse, {
    'payment confirmation preserves expected outcome': (response) => expectedStatuses.includes(response.status),
  });
  businessFailures.add(!confirmOk);
  if (!confirmOk) {
    paymentOutcomes.add(1, { outcome: 'unexpected_response' });
    return false;
  }
  if (tossOutcome === 'success') {
    const approved = check(confirmResponse, {
      'payment is approved': (response) => jsonBody(response, 'confirmation').approvalState === 'APPROVED',
    });
    if (approved) paymentsApproved.add(1);
    paymentOutcomes.add(1, { outcome: approved ? 'approved' : 'unexpected_state' });
    return approved;
  }
  if (tossOutcome === 'decline') {
    const declined = check(confirmResponse, {
      'payment is explicitly declined': (response) => jsonBody(response, 'decline').code === 'PAYMENT_DECLINED',
    });
    paymentOutcomes.add(1, { outcome: declined ? 'declined' : 'unexpected_state' });
    return declined;
  }

  if (tossOutcome === 'timeout' || tossOutcome === 'unknown') {
    const initial = jsonBody(confirmResponse, 'pending confirmation').approvalState;
    const explicit = check(initial, {
      'confirmation keeps an explicit pending state': (value) => ['UNKNOWN', 'RECONCILING'].includes(value),
    });
    if (!explicit) return false;
    paymentOutcomes.add(1, { outcome: initial.toLowerCase(), observation: 'confirmation' });
    sleep(1);
    const statusResponse = http.get(
      `${baseUrl}/api/v1/payments/${attempt.paymentId}`,
      requestParams(customer, scenario, 'GET /api/v1/payments/{paymentId}'),
    );
    const statusOk = check(statusResponse, {
      'unknown payment remains explicit': (response) => {
        if (![200, 202].includes(response.status)) return false;
        const body = jsonBody(response, 'payment status');
        return ['UNKNOWN', 'RECONCILING', 'APPROVED', 'MANUAL_REVIEW'].includes(body.approvalState);
      },
    });
    businessFailures.add(!statusOk);
    if (statusOk) {
      const state = jsonBody(statusResponse, 'payment status').approvalState.toLowerCase();
      paymentOutcomes.add(1, { outcome: state, observation: 'follow_up' });
      if (state === 'approved') paymentsApproved.add(1);
    }
    return statusOk;
  }
  return false;
}

export function boardPolling() {
  return workflow(pollBoard);
}

function pollBoard() {
  const merchant = merchantForVu();
  const headers = telemetryHeaders('board-polling');
  headers.Cookie = merchantCookie(merchant);
  if (boardEtag) headers['If-None-Match'] = boardEtag;

  const response = http.get(`${baseUrl}/api/v1/stores/${fixture.order.storeId}/orders`, {
    headers,
    tags: { name: 'GET /api/v1/stores/{storeId}/orders', route: '/api/v1/stores/{storeId}/orders' },
    timeout: '10s',
  });
  const ok = check(response, {
    'board poll is 200 or 304': (result) => result.status === 200 || result.status === 304,
  });
  businessFailures.add(!ok);
  const responseEtag = response.headers.ETag || response.headers.Etag || response.headers.etag;
  if (response.status === 200 && responseEtag) boardEtag = responseEtag;
  if (response.status === 304) boardNotModified.add(1);
  sleep(Number(__ENV.BEANFLOW_BOARD_POLL_SECONDS || '3'));
  return ok;
}

function createQuotedOrder(customer, requestScenario) {
  const quoteRequest = {
    storeId: fixture.order.storeId,
    pickupSlotId: fixture.order.pickupSlotIds[exec.scenario.iterationInTest % fixture.order.pickupSlotIds.length],
    lines: fixture.order.lines,
    pointsToUseKrw: fixture.order.pointsToUseKrw || 0,
  };
  if (fixture.order.couponIssuanceId) quoteRequest.couponIssuanceId = fixture.order.couponIssuanceId;

  const quoteResponse = http.post(
    `${baseUrl}/api/v1/me/order-quotes`,
    JSON.stringify(quoteRequest),
    requestParams(customer, requestScenario, 'POST /api/v1/me/order-quotes'),
  );
  quoteDuration.add(quoteResponse.timings.duration);
  const quoteOk = check(quoteResponse, {
    'quote is 200': (response) => response.status === 200,
    'quote has fingerprint': (response) => /^[0-9a-f]{64}$/.test(jsonBody(response, 'quote').quoteFingerprint || ''),
  });
  businessFailures.add(!quoteOk);
  if (!quoteOk) return null;

  const fingerprint = jsonBody(quoteResponse, 'quote').quoteFingerprint;
  const orderPayload = JSON.stringify({ ...quoteRequest, expectedQuoteFingerprint: fingerprint });
  const orderKey = idempotencyKey('order');
  const orderResponse = http.post(
    `${baseUrl}/api/v1/orders`,
    orderPayload,
    requestParams(customer, requestScenario, 'POST /api/v1/orders', orderKey),
  );
  orderDuration.add(orderResponse.timings.duration);
  const orderOk = check(orderResponse, {
    'order is created from current quote': (response) => response.status === 201,
  });
  businessFailures.add(!orderOk);
  if (!orderOk) return null;

  const order = jsonBody(orderResponse, 'order').order;
  if (!order?.orderId) return null;
  ordersCreated.add(1);
  if (requestScenario === 'idempotency') {
    const replayResponse = http.post(
      `${baseUrl}/api/v1/orders`,
      orderPayload,
      requestParams(customer, requestScenario, 'POST /api/v1/orders', orderKey),
    );
    orderDuration.add(replayResponse.timings.duration);
    const replayOrder = replayResponse.status === 201 ? jsonBody(replayResponse, 'idempotency replay').order : null;
    const replayOk = check(replayResponse, {
      'idempotency replay returns the original order': (response) =>
        response.status === 201 && replayOrder?.orderId === order.orderId,
    });
    businessFailures.add(!replayOk);
    if (!replayOk) return null;
  }

  return order;
}

function requestParams(actor, requestScenario, route, idempotency, expectedStatuses) {
  const headers = telemetryHeaders(requestScenario);
  headers['Content-Type'] = 'application/json';
  headers.Cookie = customerCookie(actor);
  headers['X-BEANFLOW-CSRF'] = actor.xsrf;
  if (idempotency) headers['Idempotency-Key'] = idempotency;
  return {
    headers,
    tags: { name: route, route: route.slice(route.indexOf(' ') + 1) },
    timeout: '20s',
    ...(expectedStatuses ? { responseCallback: http.expectedStatuses(...expectedStatuses) } : {}),
  };
}

function telemetryHeaders(requestScenario) {
  return {
    'X-BeanFlow-Test-Id': testId,
    'X-BeanFlow-Scenario': requestScenario,
  };
}

function customerCookie(actor) {
  return `BEANFLOW_CUSTOMER_SESSION=${actor.session}; BEANFLOW_CUSTOMER_XSRF=${actor.xsrf}`;
}

function merchantCookie(actor) {
  return `BEANFLOW_MERCHANT_SESSION=${actor.session}; BEANFLOW_MERCHANT_XSRF=${actor.xsrf}`;
}

function customerForVu() {
  return fixture.customerSessions[(exec.vu.idInTest - 1) % fixture.customerSessions.length];
}

function merchantForVu() {
  return fixture.merchantSessions[(exec.vu.idInTest - 1) % fixture.merchantSessions.length];
}

function idempotencyKey(prefix) {
  return `${testId}-${prefix}-${exec.vu.idInTest}-${exec.scenario.iterationInTest}`;
}

function jsonBody(response, label) {
  try {
    return response.json();
  } catch (_failure) {
    fail(`${label} response was not JSON (status=${response.status})`);
  }
}

function required(name) {
  const value = __ENV[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) throw new Error(`${name} must be a positive integer`);
  return parsed;
}

function validateFixture(value, selectedScenario) {
  if (!value.order || !value.order.storeId || !Array.isArray(value.order.pickupSlotIds) || !value.order.pickupSlotIds.length) {
    throw new Error('fixture.order requires storeId and at least one pickupSlotId');
  }
  if (!Array.isArray(value.order.lines) || !value.order.lines.length) {
    throw new Error('fixture.order.lines must contain at least one menu line');
  }
  if (selectedScenario === 'board-polling') {
    validateActors(value.merchantSessions, 'merchantSessions');
  } else {
    validateActors(value.customerSessions, 'customerSessions');
  }
}

function validateActors(actors, field) {
  if (!Array.isArray(actors) || !actors.length || actors.some((actor) => !actor.session || !actor.xsrf)) {
    throw new Error(`${field} must contain session and xsrf values`);
  }
}
