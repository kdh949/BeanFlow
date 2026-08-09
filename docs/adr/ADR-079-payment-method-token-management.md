# ADR-079: 결제수단 등록·조회·폐기 lifecycle과 Provider Port

- **Status:** Accepted
- **Date:** 2026-08-09
- **Amended by:** [ADR-080](ADR-080-toss-v2-one-time-payment-window.md)

> 2026-08-10: PaymentMethod lifecycle과 공개 관리 API는 보존하지만 일회성 checkout의
> 인증 소스, 기본 선택 또는 fallback으로 사용하지 않는다. ADR-080의 Payment attempt가
> Standard Payment Window 준비·callback·승인·조회·취소를 소유한다.

## Context

BeanFlow에는 결제 승인에서 사용하는 `PaymentMethodEntity`와
`(customer_id, provider, token_reference)` unique 제약이 있지만, 결제수단을 등록·조회·폐기하는
운영 Controller와 Application Service가 없다. 이 결정을 시작할 때 target/runtime OpenAPI에도
`/payment-methods` path가 없었고, Entity를 생성하는 경로는 test와 `LocalDemoSeedCli`뿐이었다.
Accepted target OpenAPI에는 이 ADR의 path를 추가했지만 runtime은 구현 전 상태를 유지한다.

ADR-021은 카드 원문 비저장과 token 최소 저장을 정했지만, 공개 등록 request, Provider
registration/deactivation Port, 멱등성, 폐기 중 외부 결과 불명과 조회 정책은 아직 정하지
않았다. ADR-078의 토스 sandbox adapter는 발급된 빌링키와 `customerKey`를 소비하므로 이
lifecycle 계약과 후속 구현이 먼저 필요하다.

제품·보안·실패 결정은 2026-08-09 질문 기록으로 닫혔다. target OpenAPI와 후속
`payment-method-token-management` ExecPlan이 이 계약을 구현 대상으로 구체화한다.

## Decision

### 고객용 공개 등록 경계

- `POST /api/v1/payment-methods`는 Provider 결제창이 발급한 일회성 `authKey`를 받는다.
- 고객용 request schema와 운영 코드 경로는 카드번호, 유효기간, 생년월일, 카드 비밀번호와
  CVC를 받지 않는다. unknown request field도 거부한다.
- 서버는 `authKey`를 registration Port에 전달해 opaque token과 허용된 표시 메타데이터를
  받는다. 휴대폰 본인인증 등 Provider 결제창의 소유 확인을 BeanFlow가 우회하거나 추정하지
  않는다.
- 카드 원문을 BeanFlow 공개 API로 받아 Provider에 전달하는 API 방식은 MVP Non-goal이다.
- 자동 sandbox 검증용 합성 입력은 공개 request와 운영 코드 경로를 재사용하지 않는 별도
  테스트 전용 계약으로만 허용한다.

### MVP Provider 범위

- 제품 외부 provider는 `TOSS_PAYMENTS` 하나다. 고객 등록 request는 provider 이름이나 routing
  policy를 선택하지 않고 서버가 이 고정 provider Port를 호출한다.
- 새 공개 registration은 provider를 `TOSS_PAYMENTS`로 저장하고 응답은 그 사실만 투영한다. 다른
  외부 provider 값은 Application Service validation에서 거부한다. 기존 test/local provider row는
  migration에서 값을 바꾸지 않되 lifecycle 공개 목록·신규 등록 대상이 아니다.
- scripted local adapter는 명시적 local/test capability다. 고객이 선택할 수 있는 provider,
  non-local 응답의 provider 값과 운영 fallback으로 사용하지 않는다.
- `TOSS_PAYMENTS` 설정·credential 또는 adapter가 필요한 profile에서 없으면 시작을 실패시킨다.
  scripted local adapter로 자동 대체하지 않는다.
- 두 번째 외부 provider와 routing은 별도 Business Policy, ADR, migration, failure semantics와
  contract test를 먼저 요구한다.

### 저장·표시 metadata

- 고객 등록 request는 `authKey`와 `displayAlias`만 받는다. alias는 trim 뒤 1..80자이고
  control character를 허용하지 않는다.
- Provider registration result가 `cardBrand`와 `lastFour`를 제공한다. brand는 trim 뒤
  1..40자, last4는 숫자 4자리여야 한다. 누락·불일치는 placeholder, token parsing 또는 고객
  입력 fallback 없이 registration result 실패·복구 경로로 보낸다.
- 공개 PaymentMethod 표시 metadata는 `displayAlias`, `cardBrand`, `lastFour`로 닫는다. lifecycle
  identifier, provider, 공개 상태·시각은 resource metadata이며 카드 metadata 목록과 구분한다.
- expiry month/year는 함께 전체 유효기간을 구성하므로 저장·응답하지 않는다. 카드번호, CVC,
  생년월일, 카드 비밀번호, token reference와 provider customer reference도 공개 schema에
  포함하지 않는다.
- alias·brand·last4는 결제수단 identity, 객체 수준 인가와 unique key가 아니다. 같은 고객의
  중복 표시값을 허용한다.

### 고객 지정 default

- 고객은 자신의 `ACTIVE` PaymentMethod 중 최대 하나를 default로 지정할 수 있다. 공개
  representation은 `isDefault`를 포함한다.
- default는 UI 선호 metadata다. identity, 객체 수준 인가, Provider 요청과 결제 승인 대상
  추론에 사용하지 않는다. ADR-080 이후 고객 one-time checkout은 PaymentMethod를 읽지 않고
  `POST /orders/{orderId}/payment-attempts`의 서버 snapshot으로만 결제를 준비한다. 이 조항의
  legacy `payment-confirmations` 의미는 신규 runtime 경로에서 superseded됐다.
- DB는 `is_default` boolean, `is_default=true`이면 `status=ACTIVE`인 CHECK와 customer별 active
  default 최대 하나인 partial unique index로 보호한다. 0개 상태는 허용한다.
- registration, default 변경과 deactivation은 같은 customer-scope advisory lock 뒤 필요한
  PaymentMethod row를 결정적 ID 순서로 잠가 unique 경쟁을 직렬화한다.
- default PaymentMethod의 deactivation Tx D1은 `is_default=false`를 의도·work와 함께 commit한다.
  다른 PaymentMethod를 자동 승격하지 않는다.
- 목록은 `isDefault DESC, createdAt DESC, paymentMethodId DESC`로 정렬하고 common signed cursor의
  default 20·maximum 100을 사용한다.
- 새 PaymentMethod는 항상 `isDefault=false`로 등록한다. 첫 결제수단도 자동 default로
  승격하지 않는다.
- 고객은 `PUT /api/v1/payment-methods/{paymentMethodId}/default`와 `Idempotency-Key`로만
  ACTIVE 결제수단을 default로 지정한다. request body와 별도 clear endpoint는 없다.
- default command는 customer-scope lock 아래 기존 default와 target을 원자적으로 바꾸고 최초
  200 response를 terminal 멱등 원장에 저장한다. 같은 key·같은 target은 최초 response를
  재생하고 작업을 다시 실행하지 않는다. 같은 key·다른 target은
  `409 IDEMPOTENCY_KEY_REUSED`다. 따라서 이전 default command의 지연 replay가 현재 default를
  되돌리지 않는다.

### sandbox 자동 발급의 test-only 경계

- `src/test`의 통합테스트 harness만 Toss registration adapter를 직접 호출한다. 공개·내부
  HTTP endpoint와 운영 Application Service consumer는 만들지 않는다.
- 실행에는 `toss-sandbox` profile, `prod` profile 부재, `test_sk_` secret과 별도
  synthetic-issuance enable 조건이 모두 필요하다. 하나라도 맞지 않으면 합성 값을 만들거나
  Provider를 호출하지 않고 명시적으로 실패한다.
- harness는 Provider가 요구하는 합성 카드번호·유효기간·생년월일·카드 비밀번호를 호출 시점
  메모리에서 생성한다. CVC는 만들거나 전송하지 않는다.
- 값은 source, fixture, 설정, seed, API schema, Entity, DB, 로그, trace, metric tag와 AuditRecord에
  남기지 않고 발급 응답 직후 참조를 폐기한다. 실제 사람의 카드·신원 값은 받지 않는다.
- test-only 카드 API 발급은 고객용 `authKey` request와 같은 DTO·Controller·Application Service
  경로를 재사용하지 않는다.

### Provider customer reference 저장

- 컬럼은 `payment_method.provider_customer_reference varchar(200)`다. token reference와
  구분되는 provider 보조값이며 결제수단 identity, 객체 수준 인가, API 표시 메타데이터와
  검색 key가 아니다.
- 기존 row와 참조 값을 요구하지 않는 provider를 위해 물리 컬럼은 nullable이다. DB CHECK와
  Application Service는 `provider = TOSS_PAYMENTS`이면 trim 뒤 non-blank를 필수로 하고,
  다른 provider에는 null만 허용한다. 이 CHECK가 기존 local/test provider를 제품 provider로
  허용한다는 뜻은 아니다. 향후 provider가 이 값을 요구하면 별도 결정과 migration으로 허용
  목록을 넓힌다.
- 기존 `(customer_id, provider, token_reference)` unique를 유지하며
  `provider_customer_reference`에는 unique index를 추가하지 않는다. 같은 값은 소유권이나
  중복 결제수단 판정 근거가 아니다.
- PaymentMethod registration Application Service의 CSPRNG factory가 등록 시도당 한 번 값을
  생성한다. member ID, 이메일, 전화번호, sequence와 token에서 파생하지 않는다.
- 최초 Toss format은 CSPRNG 32 bytes를 padding 없는 Base64URL로 인코딩한 뒤 `bf_` prefix를 붙인
  46자 문자열이다. provider 허용 문자·길이 안에 있고 256-bit random source를 보존한다.
- 값은 외부 Provider 호출 전에 registration 멱등 원장에 고정한다. 성공 result transaction은
  같은 값을 PaymentMethod에 복사한다. adapter가 생성하거나 retry마다 바꾸거나 누락 시
  default를 만들지 않는다.
- API 응답, 로그, trace, metric tag와 AuditRecord에는 값과 hash를 모두 노출하지 않는다.

### Provider를 포함하는 soft deactivation

- `DELETE /api/v1/payment-methods/{paymentMethodId}`는 local tombstone과 Provider token 폐기
  요청을 하나의 lifecycle command로 다룬다. Provider 호출을 생략한 채 terminal 성공을
  반환하지 않는다.
- Tx D1은 PaymentMethod를 소유 고객 범위에서 잠그고 상태·멱등성을 검증한 뒤
  `DEACTIVATION_REQUESTED`, deactivation work와 최초 command 상태를 함께 commit한다. 이
  commit 시점부터 결제수단은 목록의 새 결제 선택 대상과 Payment 승인 준비에서 제외된다.
- Provider deactivation Port 호출은 Tx D1 밖에서 수행한다. DB transaction과 connection을
  Provider latency 동안 유지하지 않는다.
- 확인된 Provider 성공을 별도 Tx D2에서 저장한 경우만 soft terminal `DEACTIVATED`다. row,
  token reference와 provider customer reference는 hard delete하지 않는다.
- timeout, 응답 유실, 파싱 불가와 Provider 성공 뒤 Tx D2 실패는 성공이나 확정 실패로
  추정하지 않는다. `DEACTIVATION_UNKNOWN`은 Provider DELETE/lookup 없이 검증된 webhook을
  기다리는 reconciliation이며, inbox 적용 claim 동안 `RECONCILING`을 사용한다. 96시간 창이
  소진되면 `MANUAL_REVIEW`이며 `ACTIVE`로 되돌리지 않는다.
- `DEACTIVATION_REQUESTED`, `DEACTIVATION_UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`는 모두
  신규 결제 비활성 상태다. 이미 시작된 Payment fact는 아래 snapshot 경계대로 계속 수렴한다.

### 진행 중 Payment와 deactivation 경쟁

- Payment 승인 Tx1과 deactivation Tx D1은 같은 PaymentMethod row를 잠가 직렬화한다.
- Tx1이 먼저 commit하면 external Payment당 정확히 하나인 immutable
  `PaymentProviderRequestSnapshot`을 Payment와 같은 transaction에 저장한다. snapshot은
  `paymentId`, `paymentMethodId`, provider, token reference와 provider customer reference를
  포함하고 update/delete하지 않는다.
- Provider approve, lookup과 late-approval recovery는 current PaymentMethod를 다시 읽지 않고
  snapshot만 사용한다. snapshot 값은 API, log, trace, metric tag와 AuditRecord에 노출하지
  않는다.
- Tx1 뒤 Tx D1이 commit되면 PaymentMethod는 신규 선택에서 제외되지만 기존
  `APPROVING|UNKNOWN|RECONCILING|MANUAL_REVIEW` Payment와 Order lease·late-approval 정책은
  소급 변경하지 않는다. deactivation이 진행 Payment를 취소하거나 Provider 작업을 합치지 않는다.
- Tx D1이 먼저 commit하면 뒤 Payment Tx1은 snapshot과 Payment를 만들거나 Provider를 호출하지
  않고 결제수단 비활성 오류로 실패한다.
- snapshot 누락·PaymentMethod binding 불일치·provider 필수값 불완전은 current PaymentMethod나
  default method로 보정하지 않는 setup failure다. 후속 migration은 기존 external Payment의
  linked PaymentMethod에서 verified backfill하고 단일 원천을 만들 수 없으면 실패해야 한다.

### duplicate provider token binding

- registration result transaction은 provider와 token reference의 비가역 fingerprint에서 만든
  transaction advisory lock을 먼저 획득한다. 같은 token의 동시 result를 직렬화하되 fingerprint,
  raw token과 provider customer reference를 log·metric·Audit에 남기지 않는다.
- 기존 `ACTIVE` PaymentMethod의 customer owner, provider, token reference, provider customer
  reference, display alias, card brand와 last4가 모두 같을 때만 exact binding이다. 이 경우 새
  row나 Provider call 없이 기존 resource로 수렴하고 lifecycle preference인 `isDefault`를
  비교·변경하지 않는다.
- owner, provider customer reference, alias/brand/last4 중 하나라도 다르거나 기존 상태가
  `ACTIVE`가 아니면 overwrite, metadata update와 reactivation을 금지한다. registration work는
  닫힌 conflict reason과 함께 `MANUAL_REVIEW`로 가며 기존 PaymentMethod는 변하지 않는다.
- DB의 기존 `(customer_id, provider, token_reference)` unique는 유지하고 provider customer
  reference를 identity·unique에 추가하지 않는다. advisory lock 뒤 cross-owner token 조회로
  owner 충돌을 검출한다.
- migration precheck가 같은 provider+token의 cross-owner 기존 row를 찾으면 어느 owner도 추정하지
  않고 migration을 중단한다.

### registration idempotency와 결과 불명

- scope는 `actorId + REGISTER_PAYMENT_METHOD_V1 + Idempotency-Key`다. canonical payload는
  `TOSS_PAYMENTS`, raw를 저장하지 않은 `authKey` SHA-256과 정규화한 `displayAlias`다.
- registration ledger는 `(customer_id, provider, authorization_key_hash)` unique도 가져 다른
  Idempotency-Key로 같은 authKey를 재사용하는 요청을 Provider 호출 전에 막는다. hash와 raw
  authKey는 API, log, trace, metric tag와 Audit에 넣지 않는다.
- Tx R1은 intended PaymentMethod ID, CSPRNG provider customer reference, payload hash,
  `READY` work와 최초 상태를 commit한다. Provider customer reference는 이 시점 뒤 바뀌지 않는다.
- 짧은 claim transaction이 `PROCESSING`과 claim token/time을 저장한 뒤 Provider registration을
  DB transaction 밖에서 claim당 한 번 호출한다. claim 획득 전 crash는 같은 key retry가 claim할 수 있지만,
  claim 뒤 crash·timeout·응답 유실·parsing failure는 `REGISTRATION_UNKNOWN`이다.
- `REGISTRATION_UNKNOWN`에서는 일회성 authKey를 재전송하지 않는다. Provider Port가 결과
  lookup을 명시적으로 지원할 때만 조회하고, `TOSS_PAYMENTS`처럼 lookup 계약이 없으면 추가
  Provider 호출 없이 `MANUAL_REVIEW`로 종결한다. token과 표시 metadata를 추정하지 않는다.
- 같은 key·payload는 Provider side effect 없이 최초 terminal response 또는 현재 non-terminal
  representation을 반환한다. 같은 key·다른 payload는 `409 IDEMPOTENCY_KEY_REUSED`다.
- 명시적 무부수효과 실패와 success는 최초 HTTP status/body를 terminal 저장한다. terminal
  ledger는 90일 보존하고 `UNKNOWN`·`MANUAL_REVIEW`는 운영 해소 전 정리하지 않는다.

### deactivation idempotency와 단일 Provider 시도

- scope는 `actorId + DEACTIVATE_PAYMENT_METHOD_V1 + Idempotency-Key`다. canonical payload는
  소유권 검증 뒤의 `paymentMethodId`다.
- 같은 key·target은 Provider side effect 없이 최초 terminal response 또는 현재 non-terminal
  representation을 반환한다. 같은 key·다른 target은 `409 IDEMPOTENCY_KEY_REUSED`다.
- Tx D1은 `DEACTIVATION_REQUESTED`, default 해제, deactivation work와 최초 멱등 상태를 같이
  commit한다. 짧은 transaction이 work를 `PROCESSING`으로 claim한 뒤 Provider DELETE를 DB
  transaction 밖에서 한 번만 호출한다. claim 전 crash는 같은 logical operation이 claim할 수 있다.
- claim 뒤 crash·timeout·응답 유실·parsing failure와 Provider 성공 뒤 result transaction 실패는
  `DEACTIVATION_UNKNOWN`이다. 토스의 공식 계약에 DELETE 멱등키와 삭제 결과 lookup이 없으므로
  DELETE를 자동 재호출하거나 후속 not-found를 성공으로 간주하지 않는다.
- 검증된 `BILLING_DELETED`가 오면 같은 PaymentMethod를 `DEACTIVATED`로 수렴한다. 최초
  `DEACTIVATION_UNKNOWN` 판정 시각부터 96시간까지 알림이 없으면 추가 Provider side effect 없이
  `MANUAL_REVIEW`로 종결한다. 이 창은 토스의 공식 최대 webhook 재전송 창 약 3일 19시간에
  5시간 여유를 더한 값이다. 어떤 경로도 `ACTIVE`로 되돌리지 않는다.
- 명시적 무부수효과 실패와 success는 최초 HTTP status/body를 terminal 저장한다. terminal
  ledger는 90일 보존하고 `UNKNOWN`·`MANUAL_REVIEW`는 운영 해소 전 정리하지 않는다.

### registration/deactivation Provider Port 결과

- registration Port 결과는 `Issued(tokenReference, cardBrand, lastFour)`,
  `RejectedWithoutEffect(reason)`, `Unknown(reason)`, `Misconfigured(reason)`의 닫힌 합이다.
- deactivation Port 결과는 `Deactivated`, `RejectedWithoutEffect(reason)`, `Unknown(reason)`,
  `Misconfigured(reason)`의 닫힌 합이다.
- `RejectedWithoutEffect`는 adapter contract test가 외부 side effect 부재를 입증한 닫힌
  allowlist code에만 쓴다. 이 결과만 고객이 새 authKey로 다시 시도할 수 있는 확정 거절 또는
  폐기 command의 확정 무부수효과 실패로 투영한다.
- timeout·연결 실패·응답 유실·5xx·parsing failure·성공 필수값 누락과 allowlist 밖 code는
  `Unknown`이다. registration은 새 authKey 재전송 없이 `REGISTRATION_UNKNOWN`, deactivation은
  DELETE 재호출 없이 `DEACTIVATION_UNKNOWN`으로 보존한다.
- credential·인증·Provider 계약·필수 설정 결함은 `Misconfigured`다. 이를 고객 거절·없는
  결제수단·성공으로 축소하지 않고 운영 오류와 metric으로 노출한다. 정상적인 Provider 실패
  분류는 예외로 숨기지 않으며 예외는 프로그래밍 결함과 Port 호출 계약 위반에만 사용한다.
- registration `Misconfigured`가 side effect 부재를 확인한 경우만 설정 수정 뒤 same-key 새 claim을
  허용한다. `Unknown`에서는 금지한다. deactivation `Misconfigured`는 D1 뒤
  `MANUAL_REVIEW`로 전이하고 DELETE를 다시 호출하지 않는다.
- deactivation `RejectedWithoutEffect`도 local ACTIVE rollback이나 새 DELETE가 아니라
  `MANUAL_REVIEW`와 고객 202 `DEACTIVATION_DELAYED`로 투영한다. Provider token이 남아 있음을
  local 성공으로 숨기지 않는다.
- 공개 API와 observability에는 닫힌 BeanFlow error/reason만 투영한다. raw Provider code/message,
  token reference와 provider customer reference는 포함하지 않는다.

### Provider adapter activation gate

- provider-neutral Port와 Application Service는 core에 둔다. scripted adapter는
  `(local | test) & !toss-sandbox & !prod`를 만족할 때만 명시적으로 활성화한다.
- Toss sandbox registration/deactivation adapter는 `toss-sandbox`, `!prod`, `test_sk_` secret을
  모두 요구한다. synthetic card issuance harness는 여기에 별도 enable flag까지 요구하며
  `src/test`에서만 consumer를 가진다.
- `prod`에는 이 결정으로 lifecycle Provider adapter를 제공하지 않는다. 라이브 adapter는 실제
  상품 적합성·계약·credential·오류 분류를 확정하는 별도 Business Policy와 ADR 없이는 추가하지
  않는다.
- lifecycle Controller/Application Service가 활성인데 필요한 Port bean이 없거나 둘 이상이면,
  scripted와 sandbox 조건이 겹치면, 또는 어떤 profile에서든 `live_sk_`가 감지되면 startup을
  실패시킨다.
- `@ConditionalOnMissingBean` 기반 scripted/fake/no-op fallback을 두지 않는다. scripted 결과는
  local/test 검증 capability일 뿐 제품 Provider 이름, 고객 선택과 non-local API 결과가 아니다.

### 공개 조회와 command 표현

- `GET /api/v1/payment-methods`는 인증된 고객 자신의 `ACTIVE`와 deactivation 진행·불명·수동 검토
  row만 반환하고 terminal `DEACTIVATED`는 일반 목록에서 제외한다. 다른 고객의 row는 반환하지
  않으며 지원 역할의 우회 목록 endpoint도 만들지 않는다.
- lifecycle Query/target scope는 새 계약의 `TOSS_PAYMENTS` row로 한정한다. migration으로 보존한
  legacy local/test provider row는 목록·default·폐기 resource로 간주하지 않아 404이며 기존
  Payment snapshot backfill/회귀 테스트에만 남는다.
- 고객 상태는 `ACTIVE` 또는 `DEACTIVATION_PENDING`으로 축약한다. 96시간 만료로 수동 검토가
  필요해도 내부 상태와 failure code를 노출하지 않고 `DEACTIVATION_DELAYED` notice만 선택적으로
  반환한다.
- 목록은 common signed cursor와 default 20·maximum 100을 사용한다. stable tuple은
  `(isDefault DESC, createdAt DESC, paymentMethodId DESC)`이고 customer scope를 cursor filter hash에
  묶는다. default·상태가 요청 사이 바뀌면 전체 목록 snapshot을 보장하지 않으며 caller가 최신
  첫 page를 다시 읽는다.
- 공개 PaymentMethod는 `paymentMethodId`, fixed provider `TOSS_PAYMENTS`, `displayAlias`,
  `cardBrand`, `lastFour`, `isDefault`, 축약 상태와 `createdAt`·`updatedAt`만 담는다. token,
  provider customer reference, expiry, 내부 work/attempt/failure는 포함하지 않는다.
- 등록은 새 binding을 commit하면 `201`, exact ACTIVE binding으로 기존 resource에 수렴하면 `200`에
  확정된 PaymentMethod를 반환한다. Provider 결과 불명·수동 검토는 새 외부 호출
  없이 `202`의 `PaymentMethodRegistration` 진행 표현으로 반환하고, 명시적 무부수효과 거절은
  terminal `422 PAYMENT_METHOD_REGISTRATION_REJECTED`다. 설정·인증 결함은
  `503 PAYMENT_METHOD_PROVIDER_UNAVAILABLE`이며 수정 뒤 같은 key로만 다시 시도한다.
- duplicate token binding이 exact ACTIVE 조건을 만족하지 않으면 기존 row를 바꾸지 않고 terminal
  `409 PAYMENT_METHOD_TOKEN_CONFLICT`와 내부 manual-review work를 저장한다.
- 폐기는 확인된 Provider 성공과 Tx D2 commit 뒤 `204`다. 그 전에는 `202`의
  `PaymentMethodDeactivation` 진행 표현을 반환한다. same-key replay는 최초 terminal response 또는
  현재 진행 표현을 Provider 호출 없이 반환한다.
- default 지정은 request body 없는 `PUT /api/v1/payment-methods/{paymentMethodId}/default`이며
  원자적 변경 뒤 `200` PaymentMethod를 반환한다. inactive target은
  `409 PAYMENT_METHOD_STATE_CONFLICT`다.
- 등록·default·폐기의 missing/reused Idempotency-Key, validation, 소유권과 dependency 오류는
  공통 API 규약을 따른다. raw Provider code/message는 어떤 공개 error detail에도 넣지 않는다.

### 외부 Provider deactivation 알림

- provider-neutral inbound contract는 검증을 통과한 provider, stable notification ID,
  notification type, token binding과 Provider 발생 시각을 받는다. 최초 지원 type은 토스
  `BILLING_DELETED`다.
- transport adapter가 Provider 인증·서명을 먼저 검증한다. 실패한 요청은 business inbox에
  넣지 않고 non-2xx로 거부하며 secret·raw payload를 기록하지 않는다. 정확한 토스 transport
  인증과 mapping은 ADR-078 ExecPlan이 소유한다.
- lifecycle plan은 provider-neutral notification inbox와 migration을 소유한다. Tx W1은
  `(provider, provider_notification_id)` unique로 중복을 중재하고 검증된 입력을 내구 저장한다.
  DB commit 실패를 Provider 2xx로 응답하지 않는다. raw token은 저장하지 않으므로 transport의
  같은 delivery 처리 안에서 다음 Tx W2까지 메모리로만 전달한다.
- Tx W2는 notification의 token binding을 정확히 하나의 PaymentMethod에 매핑하고 row를 잠근다.
  0건 또는 다건이면 소유자를 추정하거나 임의 row를 바꾸지 않고 inbox를 `MANUAL_REVIEW`로
  보존한다. raw token과 provider customer reference는 inbox·Audit에 복제하지 않고, 실패
  증적에는 비가역 fingerprint와 닫힌 reason만 남긴다.
- mapped 또는 manual-review terminal 결과가 Tx W2에 commit된 뒤에만 transport가 2xx를 반환한다.
  W2 저장이 실패하면 non-2xx로 재전송을 유도하고, 같은 notification replay가 다시 제공한 raw
  token으로 non-terminal inbox를 처리한다. terminal replay만 새 Audit·부수효과 없이 2xx다.
- `ACTIVE`, `DEACTIVATION_REQUESTED`, `DEACTIVATION_UNKNOWN`, `RECONCILING`,
  `MANUAL_REVIEW`는 검증된 알림으로 `DEACTIVATED`에 단조 수렴한다. 이미 `DEACTIVATED`이면
  같은 notification replay는 새 Audit·부수효과 없이 성공한다. 어떤 알림도 재활성화하지 않는다.
- webhook은 이미 시작된 Payment 승인 fact와 과거 Payment를 소급 변경하지 않는다. 이후 신규
  승인 준비만 거부한다.

## Alternatives Considered

### BeanFlow가 카드 원문을 받는 API 방식

- 휴대폰 본인인증 없이 sandbox 자동 발급을 구성하기 쉽다.
- 공개 API가 카드번호·유효기간·생년월일·카드 비밀번호를 직접 처리하게 되어 ADR-021과
  현재 Non-goals의 보안 경계를 크게 넓히고 PCI·개인정보 책임을 추가한다.
- MVP 공개 등록 방식으로 채택하지 않는다.

### 고객용 등록 API를 제공하지 않음

- 카드 데이터 경계는 가장 작지만, 고객이 운영 경로에서 사용할 PaymentMethod를 만들 수
  없어 현재 결제 승인 API가 완결된 제품 흐름이 되지 못한다.
- 후속 구현을 생략하지 않고 결제창 `authKey` 계약을 만든다.

## Rationale

Provider 결제창이 카드 입력과 본인인증을 소유하게 하면 BeanFlow가 민감 입력을 수신하는
표면을 만들지 않으면서 기존 token 기반 결제 승인 모델을 유지할 수 있다. 자동 sandbox
검증은 필요하지만 고객용 보안 경계를 낮추는 근거로 사용하지 않고 별도 테스트 계약으로
격리한다.

## Consequences

- 고객용 등록은 Provider 결제창과 휴대폰 본인인증을 선행 조건으로 가진다.
- sandbox 자동 검증은 같은 공개 endpoint로 실행할 수 없고 test harness에서만 실행한다.
- registration 멱등 원장이 Provider 호출 전에 추가되어야 하며, 성공 PaymentMethod와의
  참조 값 일치를 DB·Application 테스트로 보호해야 한다.
- deactivation은 local-only 명령이 아니므로 외부 결과 불명 상태, recovery work와 운영
  `MANUAL_REVIEW` 비용이 추가된다.
- provider-neutral notification inbox·retention과 운영 manual-review 절차가 추가되고, 토스
  transport 구현은 lifecycle plan 완료 뒤 ADR-078 plan에서 제공한다.
- PaymentProviderRequestSnapshot이 token 비공개 값을 중복 보존하지만 deactivation과 이미 시작된
  Payment fact를 분리하고 current PaymentMethod 상태에 따른 승인 drift를 막는다.
- exact duplicate는 안전하게 수렴하지만 metadata를 바꾸려면 향후 별도 alias update 계약이
  필요하며, binding 충돌은 운영 manual review 비용을 만든다.
- raw authKey를 보존하지 않으므로 Toss registration unknown은 자동 lookup이 생기기 전까지
  수동 조사만 가능하지만 중복 발급을 위해 request를 재전송하지 않는다.
- 단일 provider로 API와 adapter activation은 단순해지지만 두 번째 provider 추가는 호환성·DB
  CHECK·운영 실패 계약을 다시 결정해야 한다.
- Provider 표시 metadata가 불완전한 외부 성공은 local registration 성공으로 확정할 수 없어
  registration reconciliation과 운영 복구가 필요하다.
- Port 결과 variant와 adapter별 `RejectedWithoutEffect` allowlist contract test가 추가된다.
- 명시적인 adapter profile과 startup guard가 추가되며 현재 prod는 lifecycle Provider Port가 없어
  기능을 활성화하면 기동할 수 없다.
- customer-scope lock과 partial unique가 추가되며 default deactivation 뒤 고객에게 default가
  없는 상태를 허용한다.
- default 지정 command의 terminal 멱등 원장과 보존·정리 작업이 추가된다.
- Provider DELETE의 문서화되지 않은 재호출 안전성을 추정하지 않으므로 불명 결과의 자동 복구율은
  낮아지고 webhook·manual review 운영 의존성은 커진다.
- target OpenAPI에는 `authKey` 기반 등록만 추가하고 runtime OpenAPI는 구현 Goal이 Controller와
  계약 테스트를 함께 제공할 때까지 변경하지 않는다.
- 후속 lifecycle ExecPlan은 모든 제품 결정이 닫혀 `Implementation-Ready=true`지만 migration을
  쓰므로 ADR-072의 repository-wide migration-writer lease를 획득한 뒤에만 시작할 수 있다.

## Verification

- target OpenAPI의 고객용 등록 request에 카드번호·유효기간·생년월일·카드 비밀번호·CVC가 없음
- 고객용 request의 unknown field 거부
- 공개·운영 profile에 카드 원문 기반 등록 Controller·DTO·Port entry point가 없음
- test harness의 profile/key/enable gate 중 하나라도 불충족하면 합성 값 생성과 Provider 호출 부재
- 공개·내부 HTTP route에서 synthetic issuance 호출 불가
- 합성 카드번호·유효기간·생년월일·카드 비밀번호가 source·fixture·설정·seed·DB·로그·trace·
  metric·Audit에 남지 않고 발급 뒤 참조가 폐기됨
- `TOSS_PAYMENTS`의 `provider_customer_reference` 필수, 다른 provider의 null 강제와 legacy
  local/test provider 값 비변경 migration
- 같은 등록 시도의 retry·timeout·성공 result에서 CSPRNG 참조 값이 한 번만 생성되고 불변임
- 참조 값이 unique와 객체 수준 인가에 참여하지 않음
- Tx D1 commit 뒤 Provider 호출 전·중 새 결제 선택과 승인 준비가 거부됨
- Provider 성공·명시 실패·timeout·응답 유실·Tx D2 실패에서 terminal 성공 오판과 ACTIVE 복귀 부재
- deactivation retry/reconciliation의 단일 Provider side effect와 bounded MANUAL_REVIEW 수렴
- 인증된 `BILLING_DELETED` 중복·지연·순서 역전의 단일 terminal 전이와 Audit 중복 부재
- transport 인증 실패, inbox DB 실패와 0건/다건 mapping의 무변경·non-success 처리
- Payment Tx1·deactivation Tx D1 경쟁의 선형화, 기존 Payment snapshot 수렴과 이후 신규 승인 차단
- snapshot exactly-one/immutable/binding 검증과 누락 시 current-value fallback 부재
- duplicate token advisory lock, exact ACTIVE binding 수렴과 다른 binding의 무변경·manual review
- registration pre-ledger, cross-key authKey unique와 claim 전후 crash·unknown 재전송 금지
- deactivation same/cross-key 중재와 durable claim 뒤 Provider DELETE 1회 호출
- deactivation timeout·응답 유실·result 저장 실패에서 재호출·not-found 성공 추정 부재
- 검증된 `BILLING_DELETED` 또는 96시간 만료 `MANUAL_REVIEW` 수렴
- registration/deactivation Port의 성공·확정 거절·불명·설정 결함 분리
- 미등록 code·5xx·timeout·불완전 성공의 Unknown과 raw Provider message 비노출
- local/test scripted, toss-sandbox와 prod의 상호배타 gate 및 missing/multiple Port fail-start
- 고객 request의 provider routing 입력 부재, non-local `TOSS_PAYMENTS` 고정과 scripted local
  fallback 부재
- alias/brand/last4 validation, 표시값 중복 허용과 expiry·내부 reference 비노출
- Provider 표시 결과 누락·불일치에서 placeholder·token 파싱 fallback 없는 명시적 실패
- active default 0..1, default-first deterministic ordering과 승인 request 자동 보충 부재
- 동시 default 변경·등록·deactivation의 단일 winner와 deactivation 시 자동 successor 부재
- 첫 등록 `isDefault=false`, default PUT same-key replay와 cross-target conflict, 지연 replay의
  현재 default 비변경
- registration Port가 `authKey`를 opaque 값으로 취급하고 응답·로그·metric tag·Audit에 남기지 않음
- 실제 Provider 호출과 자동 검증은 후속 구현 Goal의 contract test로 검증

## Metrics

- **Not measured:** 등록 성공률, 결제창 이탈률, 휴대폰 본인인증 소요시간, Provider latency

## Revisit Conditions

Provider가 카드 원문 비수신 registration API를 폐기하거나 다른 ownership verification 계약을
요구할 때, 라이브 결제위젯·일반결제로 전환할 때

## Related Decisions

- BR-29
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-021](ADR-021-payment-method-tokenization.md)
- [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)
- [ADR-078](ADR-078-toss-payments-sandbox-gateway-adapter.md)
