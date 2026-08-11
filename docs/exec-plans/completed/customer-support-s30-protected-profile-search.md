# S30 owner 최소 프로필과 Vault Transit protected exact search를 구현한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s20-case-foundation.md`
> **Completed-At:** `2026-08-11`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. completed S20의 Support Context/Application Service/Audit/permission
boundary, Accepted ADR-083의 Vault Transit provider 계약과 Accepted initial SP-17을 direct implementation input으로
사용한다. 이 plan의 migration-writer lease는 `feature/s30-protected-profile-search`가 2026-08-11 획득해 V41을
선택했고 최초 validation 뒤 release했다. PR #53 review가 V41 rate-window lifecycle과 production Vault contract의
미완료를 발견해 2026-08-11 같은 branch가 remediation 동안 lease를 다시 획득했다. focused/full/document
validation을 완료하고 같은 날 lease를 release했다. Remote review reply/resolution은 PR 자체의 evidence로 남긴다.

## Purpose / Big Picture

Identity 고객, Merchant 매장, Delivery 외부 courier가 상담에 필요한 최소 프로필 원문을 각 owner Context에서
Vault Transit으로 보호하고, Support 상담원이 전화번호/이메일 exact search로 항상 마스킹된 후보만 찾게 한다.
Support는 raw criterion, 원문 프로필, ciphertext 또는 blind index를 장기 보관하지 않는다.

완료 후 `POST /support/searches`는 persistent permission, 지속형 rate guard와 PII-free committed Audit를 통과한
경우에만 최대 20개의 masked typed candidate를 반환한다. Vault/owner/Audit 실패는 503이고 production Vault
설정·key metadata가 누락되거나 잘못되면 startup을 실패시킨다.

## Current State

- Branch `feature/s30-protected-profile-search`의 HEAD와 `origin/feature/s30-protected-profile-search`는
  `619e9e8731f66b7cb8b9384158076326de7707e7`이고 `main`/`origin/main`은
  `037c3cb8cdd9261b47aa3851a443070803294714`이다. PR #53만 현재 열린 PR이다.
- 마지막 Flyway migration은 V40 `support_case` foundation이다. Identity에는 store membership만 있고 customer
  profile/table이 없다. Merchant `merchant_store`에는 주문 가능 상태만 있으며 검색용 public name/location은
  별도 `merchant_store_discovery_profile`이 소유한다. Delivery module/schema는 없다.
- V41이 Identity customer, Merchant store, Delivery external-courier의 owner-local encrypted profile와 blind-index
  table, 그리고 PII-free Support rate window를 추가했다. S30은 새 기본 permission grant를 만들지 않는다.
- PR #53 remediation은 실제 non-derived Vault metadata, 32 KiB streaming response bound, sanitized malformed-2xx
  cause, PostgreSQL-clock atomic limiter와 24시간/100행 bounded retention worker를 구현·검증했다.
- S20 Controller→Application Service→internal persistence와 Operations public API pattern은 구현돼 있다.
- ADR-083은 2026-08-11 Vault Transit + loopback Vault Proxy, 별도 AEAD/HMAC keyring, versioned rotation과
  fail-closed startup/runtime 정책으로 Accepted됐다. SP-17은 최소 profile, normalization, masking, result/rate
  bound와 search Audit를 Accepted initial policy로 고정한다.
- canonical target/runtime OpenAPI는 `POST /api/v1/support/searches`를 포함하며 현재 43 paths/47 operations와
  122 schemas로 일치한다.

## Definitions

- **Owner profile:** 각 Context가 자신의 원문 ciphertext, masked derivative와 index row를 함께 소유하는 최소
  profile Aggregate boundary.
- **Blind index:** normalized phone/email의 domain-separated bytes를 별도 Vault HMAC key version으로 계산한 32-byte
  digest. 암호문이 아니며 복호화할 수 없다.
- **AAD:** ciphertext를 owner Context, subject ID와 field type에 묶는 authenticated associated data.
- **Vault Proxy:** workload auto-auth/renewal과 Vault token 강제 주입을 담당하는 loopback sidecar. 앱에는 token이
  없다.
- **External courier:** Delivery가 provider code와 opaque provider reference로 식별하는 최소 외부 courier
  reference. first-party Rider Aggregate가 아니다.

## Scope

### In Scope

- shared provider-neutral `PersonalDataCryptoPort`, `KeyedBlindIndexPort`, encrypted/index version value objects
- JDK HTTP client + existing Jackson 기반 Vault Transit adapter와 production startup precheck
- phone/email normalization v1, domain-separated canonical input와 masking v1
- Identity customer, Merchant store, Delivery external-courier owner-local protected profile/index schema와 masked query API
- Support `POST /support/searches`, persistent permission/rate guard, application orchestration와 PII-free Audit
- V41 constraints/indexes, owner query repositories, target/runtime OpenAPI와 architecture/security tests
- Vault Proxy/key provisioning·rotation·incident runbook and S30 traceability/evidence updates

### Non-goals

- profile create/change/recovery HTTP workflows 또는 existing row backfill source integration
- raw PII reveal, VerificationSession/DataAccessGrant와 break-glass
- fuzzy/prefix/address/order search, Elasticsearch와 materialized Support profile copy
- first-party rider workforce model, courier dispatch/location/provider webhook lifecycle
- Vault vendor fallback, application-held token, local key, production fake/mock/no-op implementation
- production Vault cluster/Proxy 실제 provisioning 또는 multi-region replication 선택

## Business Rules and Invariants

1. Identity/Merchant/Delivery만 자신의 raw profile ciphertext를 보관한다. Support는 typed ID와 masked DTO만 받는다.
2. 원문 AEAD keyring과 exact-index HMAC keyring은 서로 다르며 DB에서도 ciphertext와 digest를 분리한다.
3. 지원 계약은 검색값을 POST body로만 받고 query parameter를 거부한다. ingress/container가 거부 전 URI를
   관찰할 수 있으므로 production route는 query string을 access log에 기록하지 않아야 하며, 애플리케이션
   log/trace/metric/cursor/Audit/exception/snapshot/Support table에는 검색값을 남기지 않는다.
4. 성공 응답은 zero-result를 포함해 항상 masked이고 `Cache-Control: no-store`다.
5. CUSTOMER/STORE/RIDER typed IDs만 조합하며 JPA Entity/Repository를 Context 밖에 노출하지 않는다.
6. phone/email normalization v1과 criterion type/version domain separation이 exact equality를 결정한다.
7. 결과는 `(subjectType, subjectId)` stable order로 최대 20개이며 21번째 존재만 `hasMore`로 표현한다.
8. persistent `SUPPORT_SUBJECT_SEARCH`, actor별 5분 30회 rate window와 structured reason이 모두 필요하다.
9. final permission revalidation, all-owner query와 PII-free `PII_ACCESS` Audit가 commit되기 전 응답하지 않는다.
10. production Vault 설정/key metadata가 없거나 정책과 다르면 startup fail이다. Runtime 오류는 503이고 fallback이
    없다.
11. 암호문은 Transit version/AAD version/field type metadata를, index row는 HMAC key version을 명시한다.
12. 모든 owner/index write는 duplicate delivery를 unique key와 upsert/expected version으로 견딜 수 있어야 하나,
    profile change workflow 자체는 S30에서 노출하지 않는다.
13. PAN/CVC/password/OTP/MFA secret/token/key/주민번호 column, DTO, fixture와 log를 만들지 않는다.

## Architecture and Transaction Boundaries

### Search preflight transaction (Tx1)

Controller는 raw body DTO를 Application Service에만 전달한다. Application Service는 in-memory normalization 전에
구조와 actor를 검증하고 짧은 `REQUIRES_NEW` transaction에서 Operations public permission API로
`SUPPORT_SUBJECT_SEARCH`를 확인한 뒤 `support_subject_search_rate_window` row를 원자 증가시킨다. 이 transaction은
Vault/외부 HTTP를 호출하지 않는다. 허용된 attempt는 이후 Vault가 실패해도 rate budget을 소비한다.

### Vault call (transaction 밖)

Application Service는 normalized/domain-separated bytes를 configured active blind-index key versions 각각에 대해
loopback Vault Proxy로 HMAC 요청한다. token, key material, request body, Vault response body를 기록하지 않는다.
timeout/interrupt/malformed/permission/5xx는 generic `DEPENDENCY_UNAVAILABLE`이다.

### Search and Audit transaction (Tx2)

별도 transactional coordinator가 persistent permission을 다시 lock/revalidate하고 Identity/Merchant/Delivery public
query API를 호출한다. 각 API는 자신의 JDBC query repository만 사용해 digest/version exact lookup을 수행하고
masked projection만 반환한다. coordinator가 bound/order/ambiguity를 계산하고 PII-free
`SUPPORT_PII_ACCESS_RECORDED` Audit를 append한다. Audit와 DB transaction commit 뒤에만 Controller가 DTO를
serialize한다. owner query 또는 Audit 하나라도 실패하면 전체 response가 503이며 partial/empty success가 없다.

Owner profile maintenance는 별도의 owner-local transaction에서 ciphertext, masked derivative와 모든 required
write-version index row를 함께 commit해야 한다. Vault encrypt/HMAC/rewrap 외부 호출은 그 transaction 전에
완료하고, stored version/subject AAD가 달라지면 write를 거부한다. S30은 이 maintenance command를 HTTP로
노출하지 않는다.

## Alternatives Considered

- Vault token을 앱에 주입: token exposure/renewal 책임 때문에 loopback Proxy 강제 주입을 선택했다.
- cloud KMS + application envelope encryption: DEK cache/provider별 adapter/rotation 비용이 커서 Vault Transit을
  선택했다.
- `pgcrypto`/plaintext index: DB access와 key/search 권한이 결합되고 breach impact가 커서 제외했다.
- deterministic encryption: 원문 암호화와 equality search/rotation을 같은 primitive에 결합해 제외했다.
- Support projection table: stale truth와 장기 PII 복제를 만들므로 owner synchronous query API를 선택했다.
- 하나의 generic profile/index table: owner/retention/FK boundary를 흐리므로 Context별 table을 선택했다.
- application-local rate limiter: restart/multi-instance 우회가 가능해 PostgreSQL persistent fixed window를 선택했다.
- Kotlin Vault SDK 추가: 현재 요구 API가 작고 dependency/upgrade 비용이 늘어 JDK HttpClient와 existing Jackson을
  선택했다.

## Failure Semantics

- invalid normalization, unknown field/type/reason 또는 empty subject selection: 400, submitted fragment를 echo하지 않음;
- missing/revoked permission: 403;
- rate bound 초과: 429 + bounded `Retry-After`, Vault/owner query 미호출;
- Vault config/key/policy startup mismatch: production startup failure;
- Vault runtime timeout/5xx/403/404/sealed response/malformed version: 503;
- owner DB/query/Audit/transaction failure: 503, partial candidates/empty 200 금지;
- genuine all-owner no match after successful dependencies/Audit: empty 200 masked result;
- no local/in-memory/fake/no-op/cache/plaintext scan fallback and no caught-exception success flow.

## Data and Migration

V41 `create_protected_support_profiles_and_search_guard` adds:

1. `identity_customer_support_profile` and `identity_customer_support_profile_exact_index`;
2. `merchant_store_support_profile` with FK to `merchant_store` and its exact-index table;
3. `delivery_external_courier_support_profile` and its exact-index table;
4. explicit `vault:vN:`/metadata consistency, masked-only shape, optional field tuple and forbidden-empty checks;
5. per-owner primary/unique/FK/check constraints and
   `(criterion_type, index_key_version, blind_index, subject_id)` B-tree indexes;
6. PII-free `support_subject_search_rate_window(actor_id, window_started_at, attempt_count)` with unique scope and cleanup
   index;
7. no raw search criterion, normalized value, PAN/CVC/token/OTP or default Support permission grant.

Existing V1–V40 are immutable. No generic cross-owner profile table, bidirectional JPA relationship or `@ManyToMany` is
added.

### Migration-writer lease evidence

- **Acquired:** 2026-08-11 by `feature/s30-protected-profile-search` after branch creation.
- **Base:** current `HEAD`, `main` and `origin/main` are the same SHA
  `037c3cb8cdd9261b47aa3851a443070803294714`.
- **Task inventory:** Codex task inventory shows this S30 task as the only active task whose cwd is BeanFlow. Older S20,
  S10 and S00 BeanFlow tasks are idle/not-loaded. The other active task is in a different repository.
- **Worktree inventory:** four other BeanFlow worktrees are old feature/detached worktrees; none is an executing
  migration-writing task. Active Analytics metadata is a ready plan, not an acquired lease under ADR-072.
- **Selection:** repository migration inventory ends at
  `V40__create_support_case_foundation.sql`; this sole writer selects
  `V41__create_protected_support_profiles_and_search_guard.sql`.
- **Lease lifetime:** 최초 focused/full validation과 atomic completion/readiness handoff 뒤 2026-08-11 release했다.
  PR #53 remediation에서 V41 lifecycle 검토가 다시 필요해 같은 날 sole open PR branch가 lease를 재획득했고,
  remediation focused/full/PostgreSQL/document validation과 atomic completion handoff 뒤 같은 날 다시 release했다.
  V1–V40 수정, number reservation, checksum repair와 migration renumbering은 없다.

## API and Event Contracts

`POST /support/searches` accepts strict `SearchSupportSubjectsRequest` with one PHONE/EMAIL criterion, non-empty unique
CUSTOMER/STORE/RIDER selection and one SP-17 reason. It returns `SupportSubjectSearchResult` with opaque search ID,
masked typed candidates, bounded count, ambiguity and `hasMore`; it never returns the criterion or crypto metadata.

Target and runtime OpenAPI gain the operation only with Controller and parity tests. 400/403/429/503 responses are
explicit; 429/503 may carry `Retry-After` without leaking provider detail. No event is introduced because search is a
synchronous read plus local committed Audit.

Owner public contracts are endpoint-independent Kotlin DTO/query operations. They accept typed blind-index value/version
objects and a bound and return masked projections only. Support imports these APIs, never owner internal persistence.

## Milestones

1. Accept ADR-083/SP-17, acquire migration lease, author this detailed plan and pass documentation validation.
2. RED: add normalization/masking and Vault request/metadata/startup failure tests; implement shared ports/adapter.
3. RED: add V41 fresh migration/constraint/index-plan tests; implement Context-owned schema/query APIs.
4. RED: add Support authorization/rate/Vault/owner/Audit/PII-leak/API tests; implement application flow and Controller.
5. Add target/runtime OpenAPI, runbook, threat model/traceability updates and focused security/architecture validation.
6. Run full validation, review diff, move this plan to completed and atomically update orchestration/direct successor
   readiness metadata.

## Required Tests

- normalization vectors: Unicode NFKC, Korean domestic/international phone equivalence, punctuation, IDNA/case email,
  invalid length/control/multiple-`@`, generic errors and type/version domain separation
- masking vectors for names/phones/emails, Unicode code points, every response and error/log negative corpus
- Vault adapter HTTP contract: Base64 plaintext/AAD/input, no token header, key/version parsing, AEAD round trip metadata,
  HMAC exact equality/different version, rewrap metadata and safe malformed response handling
- production context startup failure: missing properties, non-loopback URI, same keys, wrong type/policy/version,
  unreachable/sealed/malformed Vault; explicit valid mock Proxy success
- PostgreSQL Testcontainers fresh V1–V41 migration, CHECK/FK/UNIQUE/ciphertext-version/index digest length/rate concurrency
- actual owner query SQL + representative fixed fixture `EXPLAIN` proves the intended B-tree plan; no SLO claim
- Application Service permission/rate/revocation between Tx1/Tx2, Vault outside transaction, each owner failure, Audit
  rollback/no response, zero-result success only after dependencies and bounded/deduplicated stable order
- Controller/OpenAPI strict body, no query parameter, auth, 400/403/429/503, `no-store`, target/runtime parity
- Modulith/ArchUnit: Controller→Service, Support→owner public API, no Repository/Entity crossing, no Delivery relationship
- PII log/Audit/metric/snapshot scan with raw and normalized phone/email, ciphertext, digest and Vault body canaries

## Validation Commands

- `./scripts/verify-docs.sh`
- `./gradlew test --tests PersonalDataNormalizerTest --tests PersonalDataMaskerTest --tests VaultTransitPersonalDataAdapterTest --tests VaultTransitStartupValidationTest`
- `./gradlew test --tests ProtectedSupportProfileMigrationTest --tests ProtectedSupportProfileQueryPlanTest --tests ProtectedSupportProfileQueryIntegrationTest`
- `./gradlew test --tests SupportSubjectSearchIntegrationTest --tests SupportSubjectSearchOpenApiContractTest --tests SupportSubjectSearchPiiLeakTest --tests SupportArchitectureTest --tests ModularityTests --tests RuntimeOpenApiParityTest`
- `./gradlew spotlessCheck`
- `./gradlew test`
- `./gradlew build`
- `git diff --check`
- `git status --short`

### Completion evidence (2026-08-11)

- Initial `./scripts/verify-docs.sh` — exit 0; target/runtime OpenAPI 43 paths/47 operations와 122 schemas, 33
  business policies, 91 ADRs, 228 Markdown files와 37 ExecPlans를 검증했다.
- Focused 14-class command covering `PersonalDataNormalizerTest`, `PersonalDataMaskerTest`,
  `VaultTransitPersonalDataAdapterTest`, `VaultTransitStartupValidationTest`,
  `JdbcParameterLoggingSafetyConfigurationTest`, all three protected-profile PostgreSQL tests, all three Support search
  tests, `SupportArchitectureTest`, `ModularityTests` and `RuntimeOpenApiParityTest` — exit 0,
  `BUILD SUCCESSFUL in 47s`; JUnit XML reports 14 suites/42 tests, 0 failures and 0 errors.
- `./gradlew test --tests io.github.kdh949.beanflow.shared.internal.ProtectedSupportProfileQueryPlanTest` — exit 0.
  A comparable 20,000-row fixture chose `Seq Scan` without the Identity lookup index and `Index Only Scan using
  idx_identity_customer_support_profile_exact_lookup` with it. This is index-selection evidence, not a production
  performance claim; bytea literals are redacted in output.
- First final `./gradlew test` — exit 1 after 9m 14s; 712 tests, 5 failures and 1 skipped. Only
  `AcceptanceTimeoutWorkIntegrationTest` failed while Flyway opened a new Testcontainers PostgreSQL connection
  (`SQLSTATE 08P01`, SSL setup failure). Its isolated rerun
  `./gradlew test --tests io.github.kdh949.beanflow.ordering.internal.AcceptanceTimeoutWorkIntegrationTest` — exit 0,
  `BUILD SUCCESSFUL in 16s` (5/5).
- Second final `./gradlew test` — exit 1 after 9m; 712 tests, 2 failures and 1 skipped. Only
  `SettlementBatchQueryIntegrationTest` failed during Flyway's new PostgreSQL socket connection. Its isolated rerun
  `./gradlew test --tests io.github.kdh949.beanflow.settlement.internal.SettlementBatchQueryIntegrationTest` — exit 0,
  `BUILD SUCCESSFUL in 20s` (2/2). The failures moved between unrelated existing classes and occurred before test logic.
- The shared Test task now sets `spring.test.context.cache.maxSize=8`, retaining real isolated PostgreSQL Testcontainers
  while bounding simultaneously cached Spring contexts/containers. Final `./gradlew test` — exit 0,
  `BUILD SUCCESSFUL in 9m 50s`; JUnit XML reports 154 suites/712 tests, 0 failures, 0 errors and 1 skipped.
- XML canary scan for raw/normalized phone and email, URL/body values, digest and Vault ciphertext markers — exit 0 with
  no matches. Query-plan bytea-literal scan and main-source PII logging-call scan — exit 0 with no matches.
- `./gradlew spotlessCheck` — exit 0, `BUILD SUCCESSFUL in 713ms` (up-to-date); `git diff --check` — exit 0.
- `./gradlew build` — exit 0, `BUILD SUCCESSFUL in 1s` (2 executed, 9 up-to-date).
- After the atomic active-to-completed move/readiness update, `./scripts/verify-docs.sh` — exit 0; target/runtime OpenAPI
  43 paths/47 operations and 122 schemas, 33 business policies, 91 ADRs, 228 Markdown files and 37 ExecPlans validated.
  `git diff --check` — exit 0. `git status --short` — exit 0 and lists only the expected unstaged S30 source,
  migration, test, OpenAPI and documentation changes; no commit/stage/push was performed.

The successful full suite emitted the existing OpenJDK class-sharing warning, Gradle 10 deprecation notice and one
shutdown-time unfinished `PaymentRefundedV1` publication report from a failure-path fixture. It did not fail the task.
Docker/Testcontainers failures were not replaced with H2 or hidden.

### PR #53 remediation evidence (2026-08-11)

- First sandboxed Vault focused invocation — exit 1 before Gradle execution because the sandbox denied the Gradle
  wrapper lock under the user cache. The same command was rerun with the approved Gradle test permission.
- Vault RED command covering `VaultTransitPersonalDataAdapterTest` and `VaultTransitStartupValidationTest` — exit 1,
  13 tests/1 failure: the actual non-derived metadata fixture without `convergent_encryption` failed startup as the
  review predicted. After implementation the same command — exit 0, `BUILD SUCCESSFUL in 2s`.
- Initial skew harness invocation — exit 1 because a manually constructed preflight bypassed Spring's required
  transaction; no product assertion was inferred. The corrected repository-level RED invocation — exit 1 with the
  expected quota-split assertion after 31 requests used two application timestamps. The final three-test PostgreSQL
  rate command — exit 0, `BUILD SUCCESSFUL in 16s`; concurrent/skewed requests share one DB-clock row capped at 30.
- Retention worker RED invocation — exit 1 at test compilation because the retention service/result/worker did not yet
  exist. The first post-implementation invocation exposed only a Kotlin AssertJ test syntax error and exited 1. After
  correcting the test, the worker metric/failure-log, concurrent bounded cleanup/reexecution and cleanup-index plan
  command — exit 0, `BUILD SUCCESSFUL in 21s`.
- `./gradlew spotlessApply` — exit 0, `BUILD SUCCESSFUL in 1s`. The 15-class S30 focused command covering Vault,
  startup, normalization/masking, migration/query/query-plan, Support integration/retention/API/PII and architecture —
  exit 0, `BUILD SUCCESSFUL in 47s`.
- `./gradlew spotlessCheck` — exit 0, `BUILD SUCCESSFUL in 1s`. `./scripts/verify-docs.sh` — exit 0; target/runtime
  OpenAPI 43 paths/47 operations and 122 schemas, 33 policies, 91 ADRs, 228 Markdown files and 37 ExecPlans validated.
  `git diff --check` and main-source sensitive logging scans — exit 0.
- First final `./gradlew test` — exit 1 after 9m 31s; 722 tests, 1 failure and 1 skipped. The only failure was the
  unrelated existing `SettlementRefundAdjustmentIntegrationTest` random-fixture Audit PII heuristic. Its isolated
  seven-test class rerun — exit 0, `BUILD SUCCESSFUL in 17s`.
- Second final `./gradlew test` — exit 0, `BUILD SUCCESSFUL in 10m 38s`; JUnit XML reports 155 suites/722 tests,
  0 failures, 0 errors and 1 skipped. Full XML canary scan for raw/normalized phone/email and Vault ciphertext markers —
  exit 0 with no matches.
- `./gradlew build` — exit 0, `BUILD SUCCESSFUL in 2s` (4 executed, 7 up-to-date). No H2, fake Vault fallback or
  suppressed failure replaced the required PostgreSQL/Vault contract validation. A live Vault container was not run;
  the startup fixture matches the official non-derived Transit key response shape and this limitation is explicit.
- After the completion move, the full Support integration/retention worker plus `spotlessCheck` command — exit 0,
  `BUILD SUCCESSFUL in 18s`. Final `./scripts/verify-docs.sh`, `git diff --check`, XML canary scan and stale active-link
  scan — exit 0; document counts remain 43/47 operations, 122 schemas, 33 policies, 91 ADRs, 228 Markdown and 37 plans.

## Observability

Metrics use only operation, owner type, generic outcome and key-version coverage; no key name/version pair, subject ID,
criterion, masked value, digest or ciphertext label. Search Audit uses `PII_ACCESS` retention and generated search ID with
criterion type/reason/subject-type set/bounded count/ambiguity/truncation only. Vault logs expose generic operation and
status class, never URI key segment or response body. Rate/anomaly reporting is based on PII-free actor/window/count.

## Documentation Updates

- Accepted ADR-083, ADR index, Business Policy SP-17 and protected-search policy
- personal-data classification, Support PII controls/query model/threat model
- Support API surface, target/runtime OpenAPI and error catalog
- context map, transaction boundaries and Vault Transit runbook/security configuration reference
- S00 support requirement traceability and program orchestration
- this living ExecPlan progress/command evidence, then completed move/successor readiness

## Progress

- [x] mandatory docs/current code/schema and S00 traceability read
- [x] user selected Vault Transit; ADR-083/provider policy accepted
- [x] branch created, sole migration-writer lease acquired and V41 selected
- [x] SP-17 minimum profile/normalization/masking/rate/Audit initial policy accepted
- [x] documentation validation after plan/readiness update
- [x] RED shared crypto/normalization/masking tests and implementation
- [x] RED V41/owner query tests and implementation
- [x] RED Support search/Audit/API/security tests and implementation
- [x] focused and full validation
- [x] completion move and atomic successor/readiness handoff; V41 migration-writer lease released
- [x] PR #53 review 6건의 provider 계약, 정보 노출, resource bound, DB clock, retention, URI 보장 타당성 확인
- [x] plan을 ACTIVE로 복귀하고 V41 remediation migration-writer lease 재획득
- [x] RED: 실제 non-derived Vault metadata, malformed 200 cause-chain, Content-Length/chunked oversized response
- [x] RED: 서로 다른 application clock 인스턴스의 단일 DB quota와 bounded retention cleanup 재실행
- [x] Vault adapter, atomic DB-clock limiter, retention worker/PII-free metrics 구현
- [x] SP-17/OpenAPI/runbook/traceability 보완
- [x] focused/full/docs/PII/query-plan validation, diff review와 atomic completion handoff

## Surprises & Discoveries

- Identity has no customer Aggregate/table and Merchant store has no legal/contact fields; the existing discovery profile
  is public name/location only and must not be repurposed for encrypted support contact.
- Delivery Context is absent. S30 therefore introduces only an external courier support-profile owner/reference, not the
  canonical fulfillment/provider state machine reserved for S110.
- V39 already contains the dormant exact permission and PII Audit category/action, avoiding a new grant or retention
  mapping. Audit metadata validation rejects sensitive key names, so S30 summaries use semantic non-PII labels only.
- No Vault SDK is present. JDK HttpClient plus existing Jackson covers the small Transit contract without a production
  dependency, while Vault Proxy remains a new mandatory operational dependency.
- Root DEBUG capture initially exposed request bodies and query-bearing URI through Spring framework loggers. Strict DTO
  redaction, production web/security INFO floors and startup logging guards now make that unsafe configuration fail fast;
  query parameters are rejected before the Application Service.
- The full repository suite cached enough distinct Spring/Testcontainers contexts to intermittently time out new
  PostgreSQL connections. Bounding the Test task context cache at 8 preserved PostgreSQL isolation and produced a full
  green run after both moving failures passed in isolation.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-11 | User decision | HashiCorp Vault Transit | resolves provider/key/rotation/outage gate without mock fallback | ADR-083 |
| 2026-08-11 | Architecture | loopback Vault Proxy forced auto-auth token injection | app never stores/renews/logs Vault token | ADR-083 |
| 2026-08-11 | Security | separate AEAD and HMAC keyrings with versioned dual-index rotation | separates reveal/search capability and prevents rotation gaps | ADR-083 |
| 2026-08-11 | Product initial policy | CUSTOMER/STORE/external RIDER minimum profile, exact normalization/masking, 20 result and 30/5m rate bounds | gives S30 testable non-speculative behavior | SP-17 |
| 2026-08-11 | Data | Context-specific profile/index tables and PII-free Support rate table | preserves owner/retention boundary | this plan |
| 2026-08-11 | Execution | acquire sole migration lease and select V41 | current active task/worktree/base inventory satisfies ADR-072 | this plan |
| 2026-08-11 | Test infrastructure | bound the Spring test context cache at 8 | prevents simultaneous PostgreSQL container accumulation without replacing Testcontainers or sharing test data | completion evidence above |
| 2026-08-11 | Completion | V41 owner profiles, Vault Transit exact search and masked Support API validated; V41 lease released | focused, full, PII, PostgreSQL plan, build and documentation gates passed | completion evidence above |
| 2026-08-11 | Review remediation | reopen S30 and reacquire the V41 writer lease on the sole open PR branch | review found production Vault response mismatch and an unbounded V41 rate-window lifecycle, so prior completion/readiness is suspended | PR #53 threads and this plan |
| 2026-08-11 | Security/operations | retain fixed-window enforcement rows for 24 hours and delete at most 100 per scheduled transaction by default | Audit is the canonical access record; the limiter needs only short replay/diagnostic state and bounded retryable cleanup | SP-17 and this plan |
| 2026-08-11 | Rate semantics | derive window and retry time from one PostgreSQL clock; retain fixed-window boundary burst as an explicit initial-policy limitation | prevents application clock skew from splitting a window while avoiding an unapproved sliding-window redesign | SP-17 and this plan |
| 2026-08-11 | Remediation completion | release the V41 lease and restore S30 completion/successor input | 15-class focused, 155-suite full, PII, PostgreSQL plan, build and documentation gates passed | remediation evidence below |

## Outcomes & Retrospective

V41 now owns encrypted minimal profiles and versioned blind indexes in Identity, Merchant and Delivery; Support persists
only the PII-free rate guard and committed Audit metadata. The strict POST-body endpoint performs persistent permission
and rate preflight, computes versioned HMACs outside DB transactions through loopback Vault Proxy, revalidates permission,
queries owner public APIs and returns only bounded masked DTOs after Audit commit. Vault/owner/Audit failures are 503,
rate overflow is 429, and missing/invalid production Vault metadata fails startup without fallback.

PR #53 remediation now accepts actual non-derived Vault metadata without weakening the derived/convergent policy,
streams at most 32 KiB, sanitizes parser causes, shares one PostgreSQL-clock quota across skewed application clocks and
deletes 24-hour rate state in concurrent/retryable 100-row chunks with PII-free observability. The supported API contract
is body-only while upstream query-redaction remains an explicit production enablement control. The PostgreSQL fixtures
prove intended B-tree index selection only; no production latency/throughput claim is made. Actual Vault cluster/Proxy
provisioning, HA/replication and production deployment remain operational work outside S30. S50/S100 regain the completed
S30 input but remain not ready on their independent gates.

## Revision Notes

- 2026-08-11: authored from current `main` after Vault Transit decision; recorded Accepted provider/product contracts,
  V41 lease evidence, exact transaction/failure/security/test plan and implementation readiness.
- 2026-08-11: implemented V41, Vault Transit ports/adapter, owner-local masked query APIs and Support exact search; passed
  focused/full/PII/OpenAPI/PostgreSQL/build validation, released the V41 lease and moved the plan to completed.
- 2026-08-11: PR #53 review findings reopened the plan; reacquired the V41 migration-writer lease, suspended completion
  and successor readiness, and added bounded retention/provider-contract/distributed-rate remediation gates.
- 2026-08-11: implemented and fully validated all six review remediations, released the V41 lease, restored successor
  input and moved the plan back to completed; PR push/thread resolution follows the recorded validation.
