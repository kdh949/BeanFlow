# Personal Data Vault Transit Runbook

## Scope

This runbook operates the S30 personal-data encryption and keyed exact-search boundary accepted by ADR-083. It covers
the two Transit keys, the loopback Vault Proxy, startup validation, failure handling and version rotation. It does not
authorize viewing plaintext, copying profile data into Support, or choosing a multi-region failover policy.

## Required topology

The application talks only to a Vault Proxy bound to `127.0.0.1`, `localhost` or `::1`. The Proxy authenticates with the
deployment identity, renews its token and forces that auto-auth token onto proxied API requests. The application neither
receives nor sends `X-Vault-Token`; no token or key material belongs in BeanFlow environment variables, files or logs.

Provision two distinct Transit keys at the configured mount:

- encryption key: `aes256-gcm96`, derived/convergent/exportable/deletion disabled;
- blind-index key: `hmac`, derived/exportable/deletion disabled, HMAC SHA-256 use only.

The Vault policy granted to the Proxy identity permits only the required `encrypt`, `decrypt`, `rewrap`, `hmac` and key
metadata paths for these two keys. It must not grant key export, deletion or arbitrary Transit key access.

The deployment supplies these non-secret settings:

| Environment variable | Requirement |
|---|---|
| `BEANFLOW_VAULT_PROXY_BASE_URI` | loopback HTTP(S) origin only; no path, query, userinfo or fragment |
| `BEANFLOW_VAULT_TRANSIT_MOUNT` | Transit mount path without leading/trailing slash |
| `BEANFLOW_VAULT_PERSONAL_DATA_ENCRYPTION_KEY` | encryption key name |
| `BEANFLOW_VAULT_PERSONAL_DATA_BLIND_INDEX_KEY` | distinct HMAC key name |
| `BEANFLOW_VAULT_BLIND_INDEX_WRITE_VERSION` | positive version included in the search set |
| `BEANFLOW_VAULT_BLIND_INDEX_SEARCH_VERSIONS` | comma-separated positive active versions |
| `BEANFLOW_VAULT_CONNECT_TIMEOUT` | optional ISO-8601 duration, at most 10 seconds |
| `BEANFLOW_VAULT_REQUEST_TIMEOUT` | optional ISO-8601 duration, at most 30 seconds |

Production startup reads metadata for both keys and verifies type, policy flags, latest/minimum versions and every
configured active HMAC version. Missing settings, a non-loopback URI, reused key name, unreachable/sealed Vault,
permission denial or malformed metadata must stop startup. Do not bypass this check by changing profile, installing a
local HMAC, or preloading cached results.

For a normal `derived=false` Transit key, Vault may omit `convergent_encryption`; absence is accepted as false, while a
present true or non-boolean value fails startup. BeanFlow reads at most 32 KiB of every Provider response and cancels
oversized Content-Length or chunked bodies. A malformed 2xx body is reported only as a fixed dependency error; do not
attach the parser exception or raw body to startup/runtime exception chains.

## Runtime incident handling

Vault timeout, connection failure, permission denial, absent version or malformed response yields generic
`503 DEPENDENCY_UNAVAILABLE`. Support search consumes the persistent rate attempt before the Vault call but writes no
search result. Do not interpret 503 as no match, and do not query encrypted columns with a plaintext scan. Provider
response bodies, request bodies, key URI segments, ciphertext, digest and raw/normalized search input must not be put in
incident tickets, logs, metrics or Audit payloads.

The application cannot remove a client-supplied query from infrastructure that sees the URI before Controller
rejection. Ingress, load-balancer, reverse-proxy and container access logs for `POST /api/v1/support/searches` must
therefore record the route path only or redact the complete query string. BeanFlow rejects every query parameter, pins
Spring MVC/Security request logging to INFO and fails startup if those sensitive categories are effectively
DEBUG-enabled. Production enablement is blocked until the upstream path-only/redaction control is verified. Do not
override this guard during an incident.

## Search rate-window retention

The database clock defines each five-minute quota. Do not replace it with application-node time. Fixed-window policy can
admit the adjacent-window burst documented in SP-17; represent it as that known limitation, not as a sliding-window
guarantee.

`support_subject_search_rate_window` rows expire after 24 hours. The scheduled worker runs every five minutes by default
and deletes at most 100 rows in `(window_started_at, actor_id)` order with `SKIP LOCKED`. Monitor the PII-free deleted,
remaining-backlog, oldest-retained-age and failure metrics. A failed cleanup run is retried independently and must not be
used to change a search response. The two-year `PII_ACCESS` Audit, not this transient table, is the access-history record.

During an outage:

1. confirm the application reports startup failure or generic 503, without enabling TRACE parameter logging;
2. inspect Vault/Proxy health, seal state, authentication renewal and policy from the infrastructure boundary;
3. verify the expected mount/key metadata without exporting a key or decrypting a profile;
4. restore the dependency and repeat startup validation;
5. use the PII-free Audit/rate rows and correlation ID to establish impact. Never reconstruct search values.

There is no implicit region failover. A regional switch requires an accepted replication/residency/failover decision.

## Encryption-key rotation

The encryption key may use a 90-day Vault rotation period. After rotation, owner-local maintenance calls Transit
`rewrap` with the original AAD, validates the returned `vault:vN:` prefix and atomically updates ciphertext plus its key
version. Coverage must reach zero stale rows before raising a minimum decryption version. Plaintext must never be
returned to a maintenance job solely for rotation.

## Blind-index rotation

Blind-index rotation is an explicit dual-read rollout:

1. rotate the HMAC key and deploy configuration with the old and new versions in the search set while the new version is
   the write version;
2. owner-local bounded jobs add the new version row for every searchable phone/email;
3. verify version coverage, duplicate subject tuples and collision candidates using counts only;
4. keep searches on all configured versions through the observation window;
5. remove the old search version only after every owner has zero missing rows and rollback has been approved.

Partial owner coverage is not success. Never delete the old index rows or advance a minimum version merely because new
writes use the new version.

## Validation

Run the focused Vault/startup, normalization, owner PostgreSQL query and Support PII-leak tests from the S30 ExecPlan.
The representative query-plan test compares the same 20,000-row fixture with and without the composite B-tree; it is
index-choice evidence, not a production latency or throughput claim.

Related records: ADR-083, `docs/product/support-protected-search-policy.md`,
`docs/security/support-pii-controls.md`, and the active S30 ExecPlan.
