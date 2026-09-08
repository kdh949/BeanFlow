import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const available = spawnSync('k6', ['version'], { stdio: 'ignore' }).status === 0;
for (const [scenario, mode, expectedFailure, expectedApproved] of [
  ['quote-order', 'normal', false, 0],
  ['idempotency', 'wrong-replay', true, 0],
  ['toss-success', 'normal', false, 3],
  ['toss-decline', 'normal', false, 0],
  ['toss-decline', 'wrong-decline', true, 0],
  ['toss-unknown', 'normal', false, 0],
  ['quote-order', 'server-error', true, 0],
  ['quote-order', 'invalid-json', true, 0],
  ['quote-order', 'runner', false, 0],
]) {
  test(`real k6: ${scenario}/${mode}`, { skip: !available, timeout: 25000 }, async () => {
    const directory = await mkdtemp(join(tmpdir(), 'beanflow-k6-runtime-'));
    let quotes = 0;
    let confirmations = 0;
    const server = createServer(async (request, response) => {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const body = Buffer.concat(chunks).toString();
      const route = request.url;
      response.setHeader('Content-Type', 'application/json');
      assert.ok(request.headers['x-beanflow-test-id']);
      const send = (status, value) => { response.statusCode = status; response.end(JSON.stringify(value)); };
      if (route === '/api/v1/me/order-quotes') {
        quotes++;
        if (mode === 'server-error') return send(500, { code: 'INTERNAL_ERROR' });
        if (mode === 'invalid-json') return response.end('not json');
        return send(200, { quoteFingerprint: 'a'.repeat(64) });
      }
      if (route === '/api/v1/orders') {
        assert.equal(JSON.parse(body).expectedQuoteFingerprint, 'a'.repeat(64));
        return send(201, { order: { orderId: mode === 'wrong-replay' ? String(Math.random()) : 'test-order' } });
      }
      if (route.endsWith('/payment-attempts')) return send(200, { paymentId: 'test-payment', providerOrderId: 'test-provider-order', amount: { value: 1000 } });
      if (route.endsWith('/confirmations')) {
        confirmations++;
        if (scenario === 'toss-success') return send(200, { approvalState: 'APPROVED' });
        if (scenario === 'toss-decline') return send(422, { code: mode === 'wrong-decline' ? 'INVALID_REQUEST' : 'PAYMENT_DECLINED' });
        return send(202, { approvalState: 'UNKNOWN' });
      }
      return send(200, { approvalState: 'RECONCILING' });
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    try {
      const fixture = join(directory, 'fixture.json');
      const testId = `contract-${scenario}-${mode}`;
      const summary = mode === 'runner' ? join(directory, testId, 'summary.json') : join(directory, 'summary.json');
      await writeFile(fixture, JSON.stringify({ order: { storeId: 'test-store', pickupSlotIds: ['test-slot'], lines: [{ menuId: 'test-menu', quantity: 1 }] }, customerSessions: [{ session: 'fixture-only', xsrf: 'fixture-only' }] }), { mode: 0o600 });
      const command = mode === 'runner' ? ['python3', fileURLToPath(new URL('./run.py', import.meta.url)), '--output-dir', directory, '--local-only'] : ['k6', 'run', '--quiet', fileURLToPath(new URL('./beanflow-load.js', import.meta.url))];
      const child = spawn(command[0], command.slice(1), {
        env: { ...process.env, BEANFLOW_DEPLOYMENT_ID: 'contract-fixture', BEANFLOW_DATASET_ID: 'contract-v1', BEANFLOW_GRAFANA_URL: 'http://grafana.invalid', BEANFLOW_SUMMARY_PATH: summary, BEANFLOW_BASE_URL: `http://127.0.0.1:${server.address().port}`, BEANFLOW_TEST_ID: testId, BEANFLOW_LOAD_FIXTURE: fixture, BEANFLOW_LOAD_SCENARIO: scenario, BEANFLOW_DURATION: '3s', BEANFLOW_RATE: '1' },
        stdio: ['ignore', 'ignore', 'pipe'],
      });
      let error = '';
      child.stderr.on('data', chunk => { error += chunk; });
      const code = await new Promise(resolve => child.on('close', resolve));
      assert.equal(code, expectedFailure ? 99 : 0, error);
      const metrics = JSON.parse(await readFile(summary, 'utf8')).metrics;
      const values = name => metrics[name].values || metrics[name];
      assert.ok(quotes >= 3 && quotes <= 4, 'arrival boundary may include the final scheduled iteration');
      assert.equal(values('beanflow_workflow_started').count, quotes);
      assert.equal(values('beanflow_workflow_completed').count, quotes);
      assert.equal(values('beanflow_workflow_failures').rate, expectedFailure ? 1 : 0);
      assert.equal(values('beanflow_payments_approved').count, expectedApproved ? quotes : 0);
      if (scenario.startsWith('toss-')) assert.equal(confirmations, quotes);
      if (scenario === 'toss-decline') assert.equal(values('http_req_failed').rate, 0);
      if (mode === 'server-error') assert.equal(values('http_req_failed').rate, 1);
      if (mode === 'runner') {
        const manifest = JSON.parse(await readFile(join(directory, testId, 'manifest.json'), 'utf8'));
        assert.equal(manifest.status, 'PASSED');
        assert.equal(manifest.result.workflow_completed, quotes);
        assert.equal(manifest.result.workflow_failure_rate, 0);
        assert.equal(manifest.telemetry_ingest, 'NOT_SENT');
        assert.equal(manifest.annotation, 'NOT_PUBLISHED');
        assert.match(manifest.grafana_url, /from=\d+&to=\d+&var-test_id=contract-quote-order-runner/);
        assert.ok(manifest.end_ms > manifest.start_ms);
        assert.ok(manifest.generator_usage.max_rss_bytes > 0);
        assert.doesNotMatch(JSON.stringify(manifest), /fixture-only/);
      }
    } finally {
      await new Promise(resolve => server.close(resolve));
      await rm(directory, { recursive: true, force: true });
    }
  });
}
