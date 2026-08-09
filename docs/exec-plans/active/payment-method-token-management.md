# 결제수단 token lifecycle 구현

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객은 Provider 결제창에서 받은 일회성 `authKey`로 결제수단을 등록하고, 자기 결제수단을
정해진 순서로 조회하고, 표시용 default를 지정하고, Provider token까지 포함해 폐기할 수 있다.
BeanFlow는 카드 원문을 받거나 저장하지 않는다. 외부 결과가 불명확하면 등록·폐기 성공을
추정하지 않고 내구 상태와 운영 조사 경로를 남긴다.

완료 후 target OpenAPI의 PaymentMethod 4개 operation이 Controller·인가·계약 테스트와 함께
runtime OpenAPI로 이동한다. 이 계획은 provider-neutral Port와 명시적인 local/test scripted
adapter까지만 구현한다. Toss HTTP adapter, test card issuance와 webhook transport는 이 계획 완료를
기다리는 `toss-payments-sandbox-gateway-adapter` ExecPlan이 소유한다.

## Current State

- `payment_method`는 V6에서 생성됐고 `id`, `customer_id`, `provider`, `token_reference`,
  `display_alias`, `card_brand`, `last_four`, `ACTIVE|REVOKED`, 시각과 version만 가진다.
- `PaymentMethodEntity` 생성은 test와 `LocalDemoSeedCli`뿐이다. 운영 Controller/Application Service,
  registration/deactivation Port와 목록 Query Repository가 없다.
- 결제 승인 Tx1은 active PaymentMethod를 확인하지만 Provider request/lookup 시 현재 row를 다시
  읽는다. deactivation과 이미 시작된 Payment의 immutable 경계가 없다.
- target/runtime OpenAPI 모두 이 계획 전에는 `/payment-methods`가 없었다. ADR-079이 Accepted되며
  target에 GET/POST/DELETE/default PUT과 오류·축약 상태가 추가됐다. runtime은 구현 전 상태로 둔다.
- current `PaymentGateway`는 `local & !prod`의 scripted adapter뿐이며 lifecycle Port와 분리돼 있다.
- ADR-021/078/079, BR-29, authorization matrix와 PaymentMethod data-handling 문서가 이 계획의
  Accepted source다.

## Definitions

- **registration Port:** 일회성 authKey와 registration 원장에 고정된 provider customer reference를
  opaque token과 표시 metadata로 교환하는 provider-neutral outbound interface다.
- **deactivation Port:** opaque token을 Provider에서 폐기하는 provider-neutral outbound interface다.
- **exact binding:** owner, provider, token, provider customer reference, alias, brand, last4가 모두
  같은 기존 ACTIVE PaymentMethod다.
- **request snapshot:** Payment Tx1에서 생성해 approve/lookup/late recovery가 current
  PaymentMethod 대신 사용하는 immutable 내부 Provider 입력이다.
- **deactivation pending:** 내부 `DEACTIVATION_REQUESTED|DEACTIVATION_UNKNOWN|RECONCILING|
  MANUAL_REVIEW`의 고객 축약 상태다. 모두 신규 결제에 사용할 수 없다.
- **scripted adapter:** 외부 네트워크 없이 닫힌 결과를 만드는 local/test 전용 adapter다. 제품
  Provider나 운영 fallback이 아니다.

## Scope

### In Scope

- PaymentMethod Aggregate lifecycle, default 선호와 provider-neutral registration/deactivation Ports
- GET/POST `/api/v1/payment-methods`, DELETE `/api/v1/payment-methods/{id}`와 body 없는
  PUT `/api/v1/payment-methods/{id}/default`
- 등록/default/폐기 command 원장, claim, terminal retention과 96시간 deactivation deadline worker
- Provider notification을 검증 뒤 받아들이는 provider-neutral Application Port와 inbox/state 전이
- Payment Tx1의 immutable `PaymentProviderRequestSnapshot`과 approve/lookup/recovery loader 전환
- signed cursor 목록, owner authorization, 축약 customer projection과 stable error mapping
- PostgreSQL/Flyway schema, precheck, DB constraints/indexes와 concurrency tests
- explicit local/test scripted adapter, profile 상호배타와 startup guard
- runtime OpenAPI·문서·runbook을 실제 구현과 함께 갱신

### Non-goals

- Toss 또는 다른 Provider HTTP 호출, credential, 오류 code allowlist와 webhook transport 인증
- 합성 카드 API 호출과 test card 입력 생성
- live Provider adapter와 prod lifecycle 활성화
- PAN, CVC, expiry, 생년월일, 카드 비밀번호 수신·저장
- 여러 Provider routing, 고객 provider 선택, alias 수정·default clear endpoint
- Payment 승인·환불의 상태 정책, 금액 계산과 기존 request/lookup 예산 변경
- deactivated token/snapshot의 최종 legal purge. live 전 별도 retention·탈퇴 정책이 필요하다
- 운영자 PaymentMethod 조회·manual-review 해소 HTTP command

## Business Rules and Invariants

- 제품 provider는 `TOSS_PAYMENTS` 하나다. request는 provider를 받지 않는다.
- request는 authKey(1..300)와 trim 뒤 alias(1..80, control character 없음)만 받는다.
- raw authKey는 저장하지 않는다. SHA-256만 등록 원장에 두고 raw/hash 모두 API와 관측 데이터에
  노출하지 않는다.
- Provider result의 brand는 trim 뒤 1..40, last4는 숫자 4자리다. 누락·불일치는 placeholder,
  token parsing과 고객값 fallback 없이 Unknown이다.
- provider customer reference는 registration Application Service가 CSPRNG로 한 번 생성해 R1에
  고정한다. `SecureRandom` 32 bytes를 padding 없는 Base64URL로 인코딩하고 `bf_` prefix를 붙인
  46자 값이다. member ID·이메일·전화번호·token에서 파생하거나 adapter가 생성하지 않는다.
- owner/provider/token reference unique를 유지한다. provider customer reference와 표시 metadata는
  identity·인가·unique key가 아니다.
- exact ACTIVE binding만 기존 resource로 수렴한다. 다른 owner/reference/metadata/state는 overwrite,
  alias update와 reactivation 없이 manual review다.
- customer별 ACTIVE default는 0..1이다. 새 method는 default가 아니며 폐기 시 default를 해제하고
  다른 method를 승격하지 않는다. 승인 request는 항상 paymentMethodId를 명시한다.
- D1 commit부터 method는 신규 결제에 사용할 수 없다. 확인된 Provider 성공 또는 검증된
  notification만 DEACTIVATED로 단조 전이한다. hard delete와 ACTIVE rollback은 없다.
- Tx1이 D1보다 먼저 commit한 Payment만 immutable snapshot으로 계속 수렴한다. D1 winner 뒤에는
  Payment, idempotency와 snapshot을 만들지 않는다.
- terminal command/inbox는 90일, unknown/reconciling/manual row는 해소 전 보존한다.

## Architecture and Transaction Boundaries

Controller는 Payment Repository를 호출하지 않는다. `PaymentMethodApplicationService`가 command와
transaction을 조정하고 Aggregate가 상태 전이·default guard를 보호한다. 목록은 customer-scoped DTO
Projection Query Repository를 사용한다.

### Registration

Tx R1은 `actorId + REGISTER_PAYMENT_METHOD_V1 + Idempotency-Key`, payload hash, authKey SHA-256,
intended PaymentMethod ID, fixed provider, alias, CSPRNG provider reference와 READY work를 insert-first로
commit한다. `(customer_id, provider, authorization_key_hash)` unique도 같은 authKey의 cross-key
재사용을 Provider 호출 전에 막는다. raw authKey는 R1 이후 보존하지 않는다.

registration canonical payload는 property order가 `provider`, `authorizationKeyHash`, `displayAlias`인
공백 없는 UTF-8 JSON이고 provider는 literal `TOSS_PAYMENTS`, hash는 64자리 lowercase hex, alias는
trim된 값이다. default/deactivation payload는 각각 property 하나 `paymentMethodId`를 lowercase
canonical UUID string으로 가진다. SHA-256 payload hash도 lowercase hex로 저장한다.

짧은 Tx RC가 READY work를 PROCESSING과 claim token/time으로 바꾼 뒤 Port를 transaction 밖에서
claim당 한 번 호출한다. claim 전 crash만 same-key retry가 claim할 수 있다. claim 뒤 crash/timeout/response
loss/parse failure는 REGISTRATION_UNKNOWN이며 authKey를 다시 보내지 않는다. Toss lookup이 없으므로
후속 adapter plan은 새 side effect 없이 MANUAL_REVIEW로 종결한다.

Issued 결과 Tx R2는 provider+token SHA-256에서 만든 transaction advisory lock을 획득하고 raw
provider+token으로 cross-owner binding을 조회한다. exact ACTIVE binding은 이 새 command의 200
terminal response로 기존 resource를 반환한다. 새 binding은 PaymentMethod를 저장하고 command를
201 terminal로 만든다. mismatch는
기존 row를 바꾸지 않고 terminal 409+manual review다. RejectedWithoutEffect는 최초 422, Misconfigured는
side effect 부재가 확인된 경우에만 `MISCONFIGURED_RETRYABLE` 503 상태로 두고 설정 수정 뒤
same-key 새 claim을 허용한다.

### Default

Tx M은 customer advisory lock을 잡고 current/target PaymentMethod를 UUID 결정 순서로 잠근다. target이
owner ACTIVE인지 검증한 뒤 이전 default를 false, target을 true로 바꾸고 최초 200 body를 terminal
command 원장에 같이 저장한다. same-key replay는 저장된 200을 반환하고 Tx M을 재실행하지 않는다.

### Deactivation

Tx D1은 owner PaymentMethod row와 command를 잠그고 `DEACTIVATION_REQUESTED`, default=false, READY
work와 최초 202 representation을 commit한다. 이 순간부터 신규 결제를 거부한다. Tx DC가 work를
PROCESSING으로 claim하고 Port DELETE를 transaction 밖에서 한 번 호출한다.

Deactivated 결과만 Tx D2에서 PaymentMethod DEACTIVATED와 최초 204를 terminal 저장한다. claim 뒤
timeout, response loss, parse failure, process loss와 Tx D2 failure는 DEACTIVATION_UNKNOWN,
`unknown_at`, `manual_review_at=unknown_at+96h`를 저장한다. worker는 due row를 MANUAL_REVIEW로만
전이하고 Port를 호출하지 않는다. RejectedWithoutEffect와 Misconfigured는 즉시 MANUAL_REVIEW와
202 delayed projection으로 보내고 DELETE를 다시 호출하지 않는다. same-key request는 204 또는
현재 202를 재생한다.

### Provider notification

Toss plan의 transport가 인증·파싱을 완료한 뒤 provider, stable notification ID, BILLING_DELETED,
raw token binding과 occurredAt을 provider-neutral Application Port에 넘긴다. Tx W1은 raw payload/token을
저장하지 않고 `(provider, notification_id)` unique, type/time과 token SHA-256 fingerprint를 inbox에
commit한다. 이 commit은 2xx의 필요조건이지만 아래 W2 terminal commit 전에는 충분조건이 아니다.

같은 Provider delivery 처리 안에서 Tx W2는 raw token binding을 메모리에서 사용해 정확한
PaymentMethod를 조회하고 row를 잠근다. 한 건이면 허용된 모든 비활성/active 상태를 DEACTIVATED로
단조 전이한다. 0건/다건은 owner를 추정하지 않고 inbox MANUAL_REVIEW다. W2의 mapped/manual terminal
결과가 commit된 뒤에만 2xx를 반환한다. W2 저장이 실패하면 non-2xx로 재전송을 유도하고, replay가
다시 제공한 raw token으로 기존 non-terminal inbox를 처리한다. terminal replay만 새 Audit/side effect
없이 2xx다.

### Payment request snapshot

Payment approval Tx1과 D1은 같은 PaymentMethod row lock으로 경쟁한다. Tx1은 Payment와 함께 exactly
one snapshot(`paymentId`, `paymentMethodId`, provider, token reference, provider customer reference,
createdAt)을 저장한다. `PaymentProviderRequestLoader`의 approve/lookup/late recovery는 snapshot만
읽는다. snapshot 누락·binding mismatch는 current method fallback 없는 setup failure다. 외부
PaymentGateway 호출과 이후 Tx2 규칙은 ADR-006/007대로 유지한다.

## Alternatives Considered

- **PAN 기반 공개 등록:** 자동 sandbox 검증은 쉽지만 PCI·개인정보 경계를 넓혀 거절했다.
- **local tombstone만 폐기:** Provider credential이 살아 남아 거절했다.
- **DELETE 자동 재호출/not-found 성공:** Provider가 멱등키·lookup을 보장하지 않아 거절했다.
- **현재 PaymentMethod를 승인 시 재조회:** D1 뒤 이미 시작된 Payment가 drift하므로 snapshot을 택했다.
- **첫 method 자동 default:** 숨은 승인 선택 규칙과 stale replay 위험 때문에 명시 PUT을 택했다.
- **missing Port 자동 scripted fallback:** 운영 실패를 성공처럼 숨기므로 startup failure를 택했다.

## Failure Semantics

- Port 결과는 registration `Issued|RejectedWithoutEffect|Unknown|Misconfigured`, deactivation
  `Deactivated|RejectedWithoutEffect|Unknown|Misconfigured`의 닫힌 합이다.
- RejectedWithoutEffect는 provider adapter contract test가 side effect 부재를 입증한 allowlist만
  사용한다. scripted adapter는 각 variant를 결정적으로 재현한다.
- timeout·connection failure·response loss·5xx·parse failure·필수 성공 필드 누락·미등록 code는
  Unknown이다. 예외는 programming/Port contract violation에만 사용한다.
- Misconfigured는 고객 거절이 아니다. registration은 side effect 부재가 확인될 때만
  `PAYMENT_METHOD_PROVIDER_UNAVAILABLE` 503과 same-key retry를 허용한다. deactivation은 D1 뒤
  manual review로 보내고 DELETE를 다시 호출하지 않는다. raw Provider code/message는
  response/log/metric/Audit에 넣지 않는다.
- database, Audit, command/work 저장 실패는 success가 아니다. R1/D1 실패면 Provider를 호출하지
  않고, Provider 성공 뒤 R2/D2 실패면 unknown/manual recovery 증적을 보존한다.
- scripted adapter는 `(local | test) & !toss-sandbox & !prod`, Toss adapter는 후속 plan의 `toss-sandbox & !prod`에서만
  활성화한다. missing/multiple Port, profile overlap와 live key는 startup failure다.

## Data and Migration

이 계획은 Flyway migration 하나를 쓴다. 시작 전 ADR-072 migration-writer lease를 획득하고 최신
main의 마지막 번호를 읽어 파일 번호를 정한다. 번호를 문서에서 선점하지 않는다.

Migration은 다음 precheck를 먼저 수행한다.

- 기존 `TOSS_PAYMENTS` row에 verified provider customer reference가 없으면 migration을 중단한다.
  값을 생성·추정하지 않는다. 다른 기존 local/test provider row는 provider 값을 rewrite하지 않고
  reference null을 유지하며 새 lifecycle 공개 query에서 제외한다.
- provider+token cross-owner duplicate와 Payment의 method binding 손상을 검사하고 ambiguity가 있으면
  중단한다.
- external Payment의 snapshot backfill은 linked PaymentMethod가 정확히 한 건이고 provider별 필수값이
  완전할 때만 허용한다. non-TOSS legacy provider reference는 null일 수 있다. 단일 원천을 만들 수
  없으면 중단한다.

Schema 목표:

- `payment_method`: `provider_customer_reference varchar(200)`, `is_default boolean default false`,
  lifecycle status varchar 확대와 CHECK, TOSS reference nonblank/다른 provider reference null CHECK,
  ACTIVE-default CHECK, customer별 `is_default=true AND status='ACTIVE'` partial unique,
  `(provider, token_reference)` lookup index. 기존 owner/provider/token unique 유지.
  기존 `REVOKED`는 `DEACTIVATED`로 의미 보존 rename하고 `ACTIVE`는 유지한다.
- `payment_method_registration`: actor/operation/key, customer, intended method ID, fixed provider,
  auth hash, payload hash, alias, provider reference, status, claim, first status/body, 시각·retention.
  actor/operation/key와 customer/provider/auth hash unique, status/claim/terminal CHECK.
- `payment_method_default_command`: actor/operation/key, customer/target, payload hash, first 200 body,
  terminal/retention 시각과 unique/check.
- `payment_method_deactivation`: actor/operation/key, customer/method, payload hash, status, claim,
  unknown/manual deadline, first status/body, 시각·retention. method당 active work partial unique.
- `payment_provider_notification_inbox`: provider/notification ID unique, type, token fingerprint,
  occurred/received/processed 시각, status와 closed reason. raw token/payload/reference column 금지.
- `payment_provider_request_snapshot`: payment ID PK/FK, payment method ID FK, provider, token reference,
  nullable provider customer reference, createdAt. TOSS snapshot에는 nonblank를 요구하고 update/delete
  권한과 application mutation 경로를 금지한다.

모든 terminal command/inbox table은 `(retention_expires_at, id)` keyset cleanup index를 갖고
non-terminal retention null/terminal nonnull CHECK를 갖는다. unknown/reconciling/manual rows는 cleanup
query에서 제외한다. token, provider reference와 response body를 DB error/log로 출력하지 않는다.

## API and Event Contracts

- target OpenAPI가 source다. GET list는 common cursor default 20/max 100과 default/created/id sort다.
- lifecycle query/default/deactivation target은 owner의 `TOSS_PAYMENTS` row만 포함한다. legacy
  local/test provider row는 404이고 기존 Payment 회귀 경로에서만 읽는다.
- POST request는 unknown field를 거부한다. exact existing binding은 200, 새 binding은 201,
  registration progress는 202, confirmed rejection은 422, key/auth/token conflict는 409,
  provider/dependency failure는 503이다.
- DELETE는 Idempotency-Key를 요구하고 204 confirmed 또는 202 pending이다. owner 404/403,
  invalid state/key reuse 409, dependency 503을 유지한다.
- default PUT는 body 없음, 200 PaymentMethod이며 inactive target은 409다.
- 공개 PaymentMethod는 ID/provider/alias/brand/last4/default/축약 state/notice/시각만 가진다.
- provider-neutral notification은 public customer API가 아니다. Toss transport path/schema는 후속
  plan이 인증 계약과 함께 target/runtime에 추가한다.
- lifecycle Controller와 contract/security tests가 같은 변경에 존재할 때 target 4개 operation을
  runtime OpenAPI에 복사하고 `RuntimeOpenApiParityTest`를 갱신한다.

## Milestones

1. **Migration lease와 clean precheck.** repository-wide lease를 획득하고 최신 번호로 migration,
   Entity/enum/repository와 migration failure tests를 완성한다.
2. **Aggregate·Port·원장.** 닫힌 Port result, lifecycle Aggregate, registration/default/deactivation
   command/work와 explicit scripted adapter를 구현한다.
3. **고객 API·query.** owner authorization, signed cursor DTO projection, 네 operation과 error mapping,
   target/runtime contract tests를 완성한다.
4. **Payment snapshot.** Tx1 snapshot 생성과 모든 approve/lookup/late-recovery loader 전환, D1 경쟁
   테스트를 통과시킨다.
5. **notification·worker·retention.** provider-neutral inbox Port, 96시간 deadline, terminal cleanup과
   profile/startup guard를 구현한다.
6. **전체 검증·문서.** 구조/DB/API/보안/민감정보 검증, runtime OpenAPI와 capability evidence를
   실제 결과로 갱신하고 active plan을 completed로 이동한다.

각 milestone은 앞 milestone의 실제 테스트 없이 다음으로 넘어가지 않는다.

## Required Tests

- Aggregate: 허용 상태 전이, 재활성화/hard delete 부재, default 0..1, alias/brand/last4 boundary
- PostgreSQL migration: empty precheck success, legacy non-TOSS null-reference/REVOKED rename success,
  TOSS reference 누락·ambiguous snapshot·cross-owner duplicate failure,
  CHECK/partial unique/index/retention constraints
- registration: same-key same-payload, cross-payload, cross-key same auth hash, claim 전/후 crash,
  Issued/Rejected/Unknown/Misconfigured, exact duplicate/concurrent cross-owner conflict
- raw authKey 비저장, unknown 뒤 Provider 재호출 부재, provider reference 생성 1회·retry 불변
- default: 동시 두 target 단일 winner, stale same-key replay current preference 비변경, inactive/other owner
- deactivation: D1 commit 전 Port 무호출, Port 구간 transaction inactive, success 204, timeout/result-save
  failure 202, claim 뒤 DELETE 재호출 부재, 96시간 경계와 no ACTIVE fallback
- notification: auth-completed Port input의 duplicate/out-of-order, 0/1/many mapping, W1/W2 DB failure
  ACK 금지, W2 terminal commit 뒤 2xx, raw payload/token 비저장, terminal replay 무부수효과
- Payment Tx1/D1 concurrency winner 두 방향, snapshot exactly one, deactivation 뒤 기존 Payment의 같은
  snapshot approve/lookup/recovery, missing snapshot fallback 부재
- API: CUSTOMER only, owner 404/403 metadata 비노출, request unknown/sensitive field 거부, 모든 status/code,
  공개 schema 내부 reference/expiry/attempt/raw failure 부재
- cursor: default/created/id ties, default 20/max 100, tamper/expiry/other customer, state/default mutation 뒤
  first-page refresh contract
- profile: explicit local/test success, prod scripted/sandbox refusal, missing/multiple Port와 overlap fail-start,
  automatic fake/no-op fallback 부재
- observability: log/trace/metric/Audit capture에 authKey/hash, token/reference, snapshot와 synthetic input 부재
- Spring Modulith/ArchUnit: Controller→Application Service, internal Repository 미노출, adapter→Port 방향
- runtime OpenAPI parity와 target reference validation

## Validation Commands

- `./gradlew test --tests '*PaymentMethod*' --tests '*PaymentConfirmation*'`
- `./gradlew test --tests '*ModularityTests' --tests '*Architecture*'`
- `./gradlew clean build`
- `bash scripts/verify-docs.sh`
- `git diff --check`

PostgreSQL Testcontainers가 실제로 실행되지 않았거나 Docker가 없으면 통과로 쓰지 않고 `Not run` 또는
정확한 failure로 기록한다. Toss sandbox test는 이 계획에서 실행하지 않는다.

## Observability

- `beanflow.payment_method.command{operation,outcome}`: operation은
  `REGISTER|SET_DEFAULT|DEACTIVATE`, outcome은 닫힌 공개/내부 분류
- `beanflow.payment_method.provider{operation,outcome}`: scripted/향후 provider Port 결과 count
- `beanflow.payment_method.work{kind,state}`와 96시간 deadline/manual-review count
- `beanflow.payment_method.notification{type,outcome}`: verified input의 accepted/duplicate/mapped/manual
- provider call latency는 adapter layer에서 기록하되 authKey, token, customer reference, method/customer ID,
  idempotency key, raw code/message와 payload hash를 tag로 쓰지 않는다.
- structured log는 correlation ID, aggregate ID, operation과 closed reason만 쓴다. AuditRecord에는
  중요한 lifecycle target/state와 standard reason만 기록하고 Provider 값은 넣지 않는다.

## Documentation Updates

- runtime OpenAPI, API conventions/error catalog와 authorization matrix를 구현 shape와 맞춘다.
- aggregate invariants, state machines, transaction boundaries, failure semantics와 capability map을 실제
  class/table/status 이름으로 갱신한다.
- `payment-method-data-handling.md`의 storage/retention 검증 결과와 local demo runbook의 scripted
  용어·fixture를 갱신한다.
- 완료 시 이 plan을 completed로 이동하고 Toss plan의 dependency path를 completed path로 바꾼다.
  Toss plan은 별도 authoritative webhook 인증·stable notification ID mapping gate가 남으므로
  dependency 완료만으로 `Implementation-Ready=true`로 올리지 않는다.

## Progress

- [ ] migration-writer lease 획득과 latest-main precheck
- [ ] schema/Aggregate/Port/원장
- [ ] 고객 API·signed cursor·인가
- [ ] Payment request snapshot
- [ ] provider-neutral inbox·deadline/retention worker·profile guards
- [ ] required tests/build/docs validation
- [ ] active→completed 이동과 successor metadata 갱신

## Surprises & Discoveries

- 2026-08-09: 기존 Payment request loader가 승인/lookup 때마다 current PaymentMethod를 읽어 D1 이후
  진행 Payment가 실패할 수 있었다. Tx1 immutable snapshot이 lifecycle 구현의 선행 불변식이 됐다.
- 2026-08-09: 현재 PaymentMethod 생성 경로는 test/local demo뿐이라 기존 TOSS row의 Provider
  reference를 정당하게 backfill할 production source가 없다. migration은 TOSS 누락을 실패시키고
  다른 legacy provider는 null을 유지한다.
- 2026-08-09: Toss DELETE는 empty 200만 성공으로 문서화하고 idempotency key/result lookup을
  보장하지 않는다. webhook은 실패 시 약 3일 19시간까지 재전송하므로 96시간 창을 정했다.
- 2026-08-09: local demo가 외부 HTTP를 호출하지 않는 adapter를 sandbox라고 불렀다. scripted와
  external sandbox 용어를 분리했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-09 | Accepted | 고객 등록은 payment-window authKey만 수신 | PAN 계열 입력 경계 차단 | BR-29, ADR-021/079 |
| 2026-08-09 | Accepted | 합성 카드 발급은 four-gate src/test harness 전용 | 공개/운영 경로와 분리 | BR-29, ADR-079 |
| 2026-08-09 | Accepted | TOSS provider reference를 R1 전 CSPRNG로 생성·고정 | 승인 필수값과 재시도 일관성 | ADR-079 |
| 2026-08-09 | Accepted | DELETE는 Provider detach 포함 soft lifecycle | credential 잔존 방지 | ADR-079 |
| 2026-08-09 | Accepted | verified BILLING_DELETED inbox 포함 | 외부 폐기를 단조 수렴 | ADR-079 |
| 2026-08-09 | Accepted | MVP 제품 provider는 TOSS 하나 | routing/운영 실패 범위 제한 | BR-29 |
| 2026-08-09 | Accepted | 표시값은 alias/brand/last4, expiry 제외 | 최소 공개·저장 | BR-29 |
| 2026-08-09 | Accepted | 고객 지정 default와 명시 PUT | 숨은 승인 선택 방지 | ADR-079 |
| 2026-08-09 | Accepted | Tx1 winner는 immutable request snapshot 사용 | D1 이후 기존 Payment 보존 | ADR-006/079 |
| 2026-08-09 | Accepted | duplicate token은 exact ACTIVE binding만 수렴 | cross-owner overwrite 방지 | ADR-079 |
| 2026-08-09 | Accepted | registration pre-ledger, unknown 뒤 authKey 재전송 금지 | 일회성 요청 중복 side effect 방지 | ADR-007/079 |
| 2026-08-09 | Accepted | deactivation durable claim 뒤 DELETE 한 번 | 문서화되지 않은 멱등성 비추정 | ADR-007/079 |
| 2026-08-09 | Accepted | Port 결과를 success/rejected/unknown/misconfigured로 분리 | fail-closed 상태 전이 | ADR-079 |
| 2026-08-09 | Accepted | scripted/sandbox/prod profile 명시 분리 | 자동 fallback 금지 | ADR-021/079 |
| 2026-08-09 | Accepted | deactivation unknown webhook 창 96시간 | Toss 최대 재전송 창+5시간 | BR-29, ADR-079 |

## Outcomes & Retrospective

미시작이다. 계약과 구현 범위는 닫혔고 direct ExecPlan dependency가 없어
`Implementation-Ready=true`다. 다만 migration writer lease가 없으면 실행할 수 없다. 완료 시 실제
schema 번호, 테스트 수, command 결과, build와 문서 검증 결과를 기록한다. 성능·Provider latency는
아직 측정하지 않았다.

## Revision Notes

- 2026-08-09: Accepted BR-29/ADR-021/078/079, target OpenAPI, security/privacy와 transaction 계약에서
  최초 implementation-ready 계획 작성.
