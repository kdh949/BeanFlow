import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import encoding from 'k6/encoding';
import { Rate } from 'k6/metrics';

const provider = __ENV.BEANFLOW_PROVIDER_PROBE;
if (!['aistor', 'vault'].includes(provider)) {
  throw new Error('BEANFLOW_PROVIDER_PROBE must be aistor or vault');
}

const baseUrl = required('BEANFLOW_BASE_URL').replace(/\/$/, '');
const testId = required('BEANFLOW_TEST_ID');
const fixture = JSON.parse(open(required('BEANFLOW_LOAD_FIXTURE')));
const probes = provider === 'aistor' ? fixture.aistorProbes : fixture.vaultRevealProbes;
if (!Array.isArray(probes) || !probes.length) {
  throw new Error(`fixture does not contain ${provider} probes`);
}

const failures = new Rate('beanflow_provider_probe_failures');

export const options = {
  scenarios: {
    provider_probe: {
      executor: 'per-vu-iterations',
      vus: probes.length,
      iterations: 1,
      maxDuration: '1m',
    },
  },
  thresholds: { checks: ['rate==1'], beanflow_provider_probe_failures: ['rate==0'] },
};

export default function () {
  const probe = probes[exec.vu.idInTest - 1];
  if (provider === 'aistor') {
    runAistorProbe(probe);
  } else {
    runVaultRevealProbe(probe);
  }
}

function runAistorProbe(probe) {
  const png = encoding.b64decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  );
  const response = http.put(
    `${baseUrl}/api/v1/stores/${probe.storeId}/image`,
    { image: http.file(png, `beanflow-perf-${testId}.png`, 'image/png') },
    {
      headers: {
        Cookie: `BEANFLOW_MERCHANT_SESSION=${probe.session}; BEANFLOW_MERCHANT_XSRF=${probe.xsrf}`,
        'X-BEANFLOW-CSRF': probe.xsrf,
        'X-BeanFlow-Test-Id': testId,
        'X-BeanFlow-Scenario': 'aistor',
      },
      tags: { name: 'PUT /api/v1/stores/{storeId}/image', route: '/api/v1/stores/{storeId}/image' },
      timeout: '30s',
    },
  );
  const ok = check(response, { 'AIStor image probe is 200': (result) => result.status === 200 });
  failures.add(!ok);
}

function runVaultRevealProbe(probe) {
  const response = http.post(
    `${baseUrl}/api/v1/support/data-access-grants/${probe.grantId}/reveals`,
    JSON.stringify({ fields: probe.fields }),
    {
      headers: {
        Authorization: `Bearer ${probe.bearerToken}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': `${testId}-vault-${exec.vu.idInTest}`,
        'X-Access-Reason': probe.accessReason,
        'X-BeanFlow-Test-Id': testId,
        'X-BeanFlow-Scenario': 'vault',
      },
      tags: {
        name: 'POST /api/v1/support/data-access-grants/{grantId}/reveals',
        route: '/api/v1/support/data-access-grants/{grantId}/reveals',
      },
      timeout: '30s',
    },
  );
  const ok = check(response, { 'Vault reveal probe is 200': (result) => result.status === 200 });
  failures.add(!ok);
}

function required(name) {
  const value = __ENV[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}
