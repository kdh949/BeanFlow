# Personal Data Vault Transit Runbook

## Scope

This runbook operates the S30 personal-data encryption and keyed exact-search boundary accepted by ADR-083. It covers
the two Transit keys, the loopback Vault Proxy, startup validation, failure handling and version rotation. It does not
authorize viewing plaintext, copying profile data into Support, or choosing a multi-region failover policy.

## Required topology

The application talks only to a Vault Proxy bound to `127.0.0.1`, `localhost` or `::1`. The Proxy authenticates with the
deployment identity, renews its token and forces that auto-auth token onto proxied API requests. The application neither
receives nor sends `X-Vault-Token`; no token or key material belongs in BeanFlow environment variables, files or logs.

The Vault-specific Java client explicitly uses HTTP/1.1 for the application-to-Proxy hop. An
`http2: invalid Upgrade request header: ["h2c"]` Proxy error means the client attempted an HTTP/2 upgrade
that cannot be forwarded to the TLS upstream. Deploy the image with this explicit protocol setting;
keep external Vault HTTPS, CA verification, AppRole authentication and startup key validation enabled.

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

`local node not active but active cluster node not found` from AppRole login means the upstream Vault
cannot find an active node to process the request. This response proves an HTTP connection to Vault,
but does not prove that AppRole credentials are valid. A subsequent Proxy startup timeout and refused
connection on application port 8080 are consequences: the JVM has not started.

Inspect the **Vault server** container logs and `vault status -format=json`, not only BeanFlow Proxy logs.
Use the configured TLS address and CA; do not disable certificate verification. Read `/v1/sys/health`
and `/v1/sys/leader` from the same network path. Determine the storage backend and expected node count
before diagnosing quorum, storage errors or cluster-port connectivity. [Vault health](https://developer.hashicorp.com/vault/api-docs/system/health) 474 means a standby
cannot reach its active node; 503 is sealed and 501 is uninitialized. 429 alone is not proof of a usable
leader. Do not initialize Vault again, delete its data volume or rewrite Raft peers as a generic fix.
The application cannot repair external cluster leadership.

If server logs show `write .../raft/raft.db: no space left on device`, storage exhaustion is the cause
even when a single voter repeatedly wins elections. Identify the mount backing the Vault data directory
with `docker inspect`, then check both free blocks (`df -h`) and inodes (`df -i`) inside that mount.
Inspect `docker system df` before choosing disposable build-cache/image cleanup or disk expansion.
Never delete `raft.db`, Raft snapshots or Vault volumes to make room. Preserve the deployed and rollback
images. After restoring space, verify stable leadership, unsealed health and actual AppRole/Transit
access before pulling another application image. If leadership does not recover, inspect fresh server
logs before a controlled restart; a manual-seal deployment may need unseal after restarting.

A CLI certificate error naming `127.0.0.1` can be separate from this storage failure: pass the configured
Vault address whose host/IP is present in the certificate SAN, and retain the trusted CA. Monitor the
backing filesystem's free bytes/inodes and set container-log rotation to prevent recurrence.

The entrypoint allows at most 60 seconds for health (200/429/473) **and** authenticated reads of both
configured Transit keys. DR secondary 472 is not usable. At timeout it prints the last HTTP status for
health, encryption metadata and blind-index metadata, without bodies or tokens. Metadata 403 calls for
an AppRole/policy check; repeated bad credentials can also trigger
[AppRole user lockout](https://developer.hashicorp.com/vault/docs/concepts/user-lockout). Check that state
after correcting credentials rather than disabling lockout. 404 calls for a mount/key check. The JVM
then validates metadata contents under ADR-083. Restore the upstream dependency and repeat startup;
do not extend the timeout to hide a persistent failure.

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

Known Vault 2.0.4 limitation: rewrap does not pass AAD to the cipher, so BeanFlow AAD ciphertext is rejected with
HTTP 400 and `DEPENDENCY_UNAVAILABLE`. The intended rewrap procedure below is blocked on a Provider implementation
verified to preserve AAD. Keep earlier decryption versions available; do not remove AAD or substitute application-side
decrypt/encrypt. This limitation does not prevent startup metadata validation or ordinary encrypt/decrypt/HMAC.

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

`bash scripts/deploy/test-backend-entrypoint.sh <backend-runtime-image>` runs the image's actual Transit adapter
against an isolated TLS Vault and the repository's AppRole policy. It checks startup key metadata, encrypt/decrypt
and HMAC as the JVM user, alongside secret ownership checks. It separately characterizes Vault 2.0.4's rejected AAD
rewrap as `DEPENDENCY_UNAVAILABLE`; it does not report successful rewrap. To check a newly built local artifact before
image publication, supply its boot jar as the second argument. The script prints which artifact is under test.
It also checks real invalid AppRole, denied key metadata and sealed states, then recovery through HA
step-down/re-election. A disposable PostgreSQL/PostGIS database runs all packaged Flyway migrations;
the signed test workload identity invokes the real initial GLOBAL policy bootstrap. The full `portfolio`
application must start, expose health/OIDC configuration, reject unauthenticated private access and restart
against that same database. AIStor is intentionally unavailable under ADR-120; external Keycloak login,
AIStor access and Toss payment are **not** verified. No test provider or bootstrap identity is installed
in the production application. The image build workflow runs this suite before publishing the API image.
Server deployment and real external Vault configuration still require separate evidence.

Run the focused Vault/startup, normalization, owner PostgreSQL query and Support PII-leak tests from the S30 ExecPlan.
The representative query-plan test compares the same 20,000-row fixture with and without the composite B-tree; it is
index-choice evidence, not a production latency or throughput claim.

Related records: ADR-083, `docs/product/support-protected-search-policy.md`,
`docs/security/support-pii-controls.md`, and the active S30 ExecPlan.
