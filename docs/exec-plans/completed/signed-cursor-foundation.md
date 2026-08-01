# 공통 signed cursor foundation을 단일 소유자로 구현한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-08-01`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

Nearby, point ledger와 Settlement 목록이 endpoint별 base64 cursor를 따로 만들지 않도록,
ADR-070의 HMAC codec, key-ring configuration, filter binding and startup validation을 shared
foundation으로 한 번 구현한다. 이후 endpoint plan은 자신의 typed sort/filter adapter만 제공하고
secret/fallback/key rotation policy를 다시 구현하지 않는다.

## Current State

- ADR-070은 token format, key source, 24-hour expiry와 common `limit=20/100`을 Accepted로 고정했다.
- Nearby와 Plan 20은 모두 common codec/configuration을 자신의 scope처럼 표현한다.
- public cursor endpoint implementation과 required HMAC configuration은 아직 없다.

## Definitions

- **CursorCodec:** endpoint/filter/sort/expiry payload를 sign and verify하는 shared application component.
- **Filter hash:** raw input을 저장하지 않는 canonical-filter SHA-256 digest.
- **Key ring:** active signing key와 24-hour verifier rotation window의 required configuration.

### Fixed v1 wire and key contract

- payload는 UTF-8의 whitespace-free JSON이고 property 순서는 정확히 `endpoint`, `filterHash`,
  `sort`, `issuedAt`, `expiresAt`다. 추가 property와 `null`은 거부한다.
- `sort`는 순서를 보존하는 JSON string array이고 UUID value는 lowercase canonical UUID string이다.
  `filterHash`는 64자리 lowercase SHA-256 hexadecimal string이며 `issuedAt`/`expiresAt`은 JSON
  integer epoch second다.
- payload/signature는 padding 없는 Base64URL이고 signature input은
  `v1.<key-id>.<encoded-payload>`의 UTF-8 bytes다. `now >= expiresAt`은 만료이며 public token은
  `2048`자를 넘을 수 없다.
- required runtime configuration은 `beanflow.pagination.cursor-hmac.active-key-id`와 duplicate를
  검출할 수 있는 `keys` list (`id`, `secret-base64-url`)다. secret은 padding 없는 Base64URL decode 뒤
  최소 32 bytes여야 하며 malformed encoding, duplicate ID, empty ring, unknown active key와 short
  secret은 startup failure다.
- source, 기본 설정, production/local runtime configuration에는 fallback secret을 두지 않는다. 공개된
  test-vector 전용 key material은 deterministic test source에서만 사용하고 runtime configuration,
  실제 deployment environment variable 이름, log, test output 또는 운영 fallback에 사용하지 않는다.

## Scope

### In Scope

- ADR-070 v1 canonical JSON/Base64URL/HMAC-SHA-256 codec and typed endpoint adapter contract
- required active key/key-ring configuration binding, startup validation and rotation verification
- shared malformed/scope/expiry failure mapping support and no-secret observability rules
- codec unit, integration and application-startup tests

### Non-goals

- Nearby PostGIS query, point ledger query, Settlement query or their database migrations
- endpoint-specific authorization, sort SQL or filter semantics
- encrypted/server-stored cursor, unsigned/local/default key fallback

## Business Rules and Invariants

- every public cursor endpoint uses one versioned codec and the ADR-070 format.
- missing/malformed/duplicate key configuration prevents application startup.
- codec verifies endpoint, filter hash, sort tuple, version, key and expiry before repository query.
- limit is validated by endpoint boundary as `1..100` with default 20; codec never treats limit as scope.

## Architecture and Transaction Boundaries

- shared codec is a stateless component configured at startup; it has no database state or migration.
- controllers delegate parsed cursor to their Application Query Service; repositories never parse untrusted token text.
- key rotation happens by configuration deployment, not inside a request transaction. Verification failure does
  not fall back to another codec/cache/default key.

## Alternatives Considered

- Nearby or Settlement implement their own codec: independent branches can diverge in signing/key startup behavior.
- server-side cursor table: adds lifecycle and database failure paths unsupported by the stable keyset requirement.
- local development default secret: hides production configuration failure.

## Failure Semantics

- invalid cursor is `400 INVALID_REQUEST` before query execution.
- missing secret/key ring is startup failure, not 400/200 or a local fallback.
- key-ring configuration/crypto failure after startup is `503 DEPENDENCY_UNAVAILABLE`; it is not converted to
  empty page or another endpoint's cursor acceptance.

## Data and Migration

No schema or Flyway migration is created. Actual deployment secret material comes only from required deployment
configuration and must not be written to source, fixture, database, AuditRecord, log, trace, metric tag or test
output. A clearly named public test-vector key is the sole test-source exception; it is never a runtime key.

## API and Event Contracts

- existing `Cursor` and `Limit` OpenAPI components remain the public surface.
- provides a typed `SignedCursorCodec` boundary that accepts endpoint identifier, canonical filter digest,
  sort tuple and `Clock`; endpoint plans own their exact tuple/filter canonicalization.
- does not publish a persistent event.

## Milestones

1. ADR-070 token/key/rotation test vectors을 lock한다.
2. required configuration and startup validator를 구현한다.
3. codec/signature/filter/expiry adapter와 failure mapping을 구현한다.
4. key rotation/startup/no-leak validation and consuming-plan handoff evidence를 기록한다.

## Required Tests

- deterministic v1 signing/verification test vectors: fixed property order, string-array sort, integer epoch
  timestamp, lowercase UUID/filter hash and padding-free Base64URL
- malformed base64/JSON, additional/null property, altered endpoint/filter/sort/version/key/signature,
  `now >= expiresAt`, 2048-char maximum and no repository invocation
- missing/empty/duplicate/malformed/short active key/key ring application startup failure
- active/retired key 24-hour verification, removal behavior and no key/payload logging
- endpoint adapters can vary limit 1/20/100 without changing cursor scope
- Modulith boundary and production/local profile has no default/fallback/test-vector key

## Validation Commands

```bash
./gradlew test --tests '*Cursor*' --tests '*Pagination*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

- `beanflow.pagination.cursor.validation.count{endpoint,outcome}`
- `beanflow.pagination.cursor.startup.validation.count{outcome}`

Only endpoint and closed outcome are tags. Cursor, key ID, filter hash and secret are never tags or logs.

## Documentation Updates

- ADR-070, API conventions/OpenAPI verification evidence, configuration/runbook and consuming ExecPlans
- this plan's actual startup/rotation and no-leak test outcomes

## Progress

- [x] 2026-08-01 계약·구현 감사와 실행 계획 기록 — `shared.api`에 typed codec/scope 계약을,
  `shared.internal`에 key-ring binding, startup validation, HMAC implementation과 closed-vocabulary
  metrics를 둔다. 예상 변경은 shared Kotlin source, Cursor/Pagination tests, test-source-only public
  vector configuration, minor decision, ExecPlan dependency paths와 `scripts/verify-docs.sh`다.
  endpoint별 codec, server-side cursor table, local/default secret은 ADR-070의 대안 배제로 유지하며,
  DB/Flyway/Nearby/point-ledger/Settlement query는 만들지 않는다. deterministic vector, invalid
  token/query-before rejection, key-ring startup, rotation, no-leak, Modulith, build, docs와 diff 검증을
  실행한다.
- [x] `shared.api.SignedCursorCodec`/typed `SignedCursorScope`와 `shared.internal` implementation을
  추가 — endpoint adapter가 typed sort를 encode/decode하고 repository에 token text를 전달하지 않는다.
- [x] required key-ring/startup validator와 HMAC-SHA-256 implementation 완료 — empty/duplicate/malformed/
  short/unknown active key는 generic secret-free startup failure이며 test source만 public vector를 제공한다.
- [x] signature/filter/expiry/2048/rotation/no-leak tests 완료 — fixed vector, canonical whitespace/property
  order, additional/null/malformed JSON, version/key/signature/scope/sort/expiry rejection과 closed metric tag
  검증을 `*Cursor*` selection에서 통과했다.
- [x] rotation/no-leak and consumer handoff 완료 — previous verifier key는 unexpired token을 검증하고
  key ring 제거 뒤 400으로 거부한다. direct successor 4개의 canonical dependency path를 completed
  path로 원자적으로 갱신했으며, Nearby는 이 foundation만의 direct dependency가 완료되어 ready다.
- [x] full validation 완료 — Cursor/Pagination selection, Modulith, clean build는 성공했다. completion
  path와 documentation graph 검증은 completed path를 대상으로 실행한다.

## Surprises & Discoveries

- 2026-08-01: Nearby and Settlement plans independently claimed shared codec/configuration ownership.
- 2026-08-01: canonical payload order, key-ring decoding and the public test-vector-only exception were made
  explicit so deterministic tests do not create a production fallback path.
- 2026-08-01: `scripts/verify-docs.sh` currently names this active path directly; plan completion therefore
  requires its required-file and contract-reader paths to move atomically with every direct successor path.
- 2026-08-01: existing full application tests need a test-source-only public vector so required runtime
  configuration remains absent from main and local configuration while every test application context starts.
- 2026-08-01: the repository uses Jackson 3 (`tools.jackson.*`), where object property traversal is
  `propertyNames()` and array traversal uses the iterable node contract; the codec uses those APIs after a
  compile check rather than relying on Jackson 2 method names.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | shared codec/configuration has one foundation owner | prevent duplicate HMAC/key startup implementations | ADR-070 |
| 2026-08-01 | Local | test application contexts receive the public vector from test source only | production/local source remains keyless while integration tests exercise the real required binding | MD-2026-003 |
| 2026-08-01 | Local | canonical payload uses a codec-private default ObjectMapper and insertion-ordered map | application ObjectMapper customizations cannot alter the locked cursor wire bytes | MD-2026-004 |
| 2026-08-01 | Completed | shared signed cursor foundation verification complete | all fixed v1, startup, rotation and no-leak checks passed without a DB migration or endpoint query | this ExecPlan |

## Outcomes & Retrospective

완료했다. `shared.api.SignedCursorCodec`와 typed scope adapter, required key-ring binding, startup
validation, HMAC-SHA-256 v1 codec 및 closed-vocabulary metrics를 구현했다. Invalid tokens fail before
typed sort decoding/repository handoff with `400 INVALID_REQUEST`; runtime crypto failures map to
`503 DEPENDENCY_UNAVAILABLE`; missing or invalid configuration fails startup without a secret-bearing
fallback. `src/main/resources/application*.yaml`에는 cursor key가 없고 public test vector는 test source에만
있다. SQL, Flyway migration, Nearby, point-ledger, Settlement query는 만들거나 변경하지 않았다.

검증 결과:

- PASS — `./gradlew test --tests '*Cursor*' --tests '*Pagination*'`
- PASS — `./gradlew test --tests '*ModularityTests'`
- PASS — `./gradlew clean build` (31s, exit 0)
- PASS — `bash scripts/verify-docs.sh` (32 business policies, 72 ADRs, 132 Markdown files, 23 ExecPlans)
- PASS — `git diff --check`

## Revision Notes

- 2026-08-01: ADR-070 common cursor ownership conflict를 닫기 위해 independent foundation으로 작성했다.
- 2026-08-01: v1 codec/key-ring foundation을 완료하고 completed path로 이동했다. Direct successors와
  documentation verifier paths는 같은 completion diff에서 갱신했다.
