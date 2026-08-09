# 결제 실패와 reconciliation을 토스페이먼츠 sandbox 실호출로 검증한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/payment-method-token-management.md`
> **Completed-At:** `2026-08-10`

> 이 plan은 구현 완료가 아니라 제품 결정 변경으로 종료됐다. ADR-080과
> `docs/exec-plans/completed/toss-v2-one-time-payment-window.md`가 billing 경로를 대체했다.

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

BeanFlow의 결제 실패 경로는 전부 scripted local adapter 위에서만 검증됐다. 승인 결과 불명
복구, 부분 환불 배분, 매장 거절 환불과 ADR-037/038의 REQUEST·LOOKUP 예산은 우리가 직접
작성한 응답만 통과했고, 실제 Provider의 HTTP status·오류 code·멱등 동작을 본 적이 없다.

이 계획은 토스페이먼츠 테스트 시크릿 키로 실제 sandbox API를 호출하는 `PaymentGateway`
adapter를 추가하고, 이미 구현된 네 결제 시나리오를 그 위에서 다시 통과시킨다. 완료 후
"Provider가 이렇게 실패하면 우리는 이렇게 판정한다"가 문서가 아니라 실행 가능한 contract
test와 실측 결과로 남는다.

이 계획은 실제 자금 이동, 운영 계약과 라이브 전환을 만들지 않는다.

## Current State

- `PaymentGateway` port는 `approve`, `lookup`, `void`, `refund`, `requestRefund`,
  `lookupRefund`를 이미 정의하고 `ExternalPaymentService`, `PaymentReconciliationService`,
  `RejectionRefundService`, `PartialRefundPaymentService`가 이를 소비한다.
- 유일한 production 구현은 `LocalPaymentGatewayConfiguration`의 scripted local adapter이며
  `@Profile("local & !prod")`이다. `prod`에는 bean이 없어 context 기동이 실패한다.
- `GatewayRefundResult.RetryableFailed`를 만들어내는 adapter는 없다. ADR-038이 요구한
  "adapter가 소유하는 재시도 허용 code 목록"은 존재하지 않는다.
- V37과 runtime lifecycle은 `PaymentMethodEntity`의 provider customer reference, 상태/default 제약,
  command/inbox 원장과 immutable Payment request snapshot을 구현했다.
- 결제수단 GET/POST/DELETE/default PUT이 Runtime OpenAPI와 Controller에 존재하고, provider-neutral
  registration/deactivation Port와 explicit local/test scripted adapter가 구현됐다. 실제 Toss token을
  발급·폐기하는 HTTP adapter는 여전히 이 계획의 범위다.
- **PaymentMethod의 lifecycle·스키마·등록/폐기 계약은
  [`payment-method-token-management`](../completed/payment-method-token-management.md)
  ExecPlan이 소유한다.** 이 계획은 provider 참조 값 컬럼과 registration Port 계약을 다시 만들지
  않고, 선행 결과 위에서 그 Port의 토스 빌링키 발급 구현과 승인·취소·조회를 제공한다. direct
  dependency는 canonical metadata의 completed path에 기입했고 선행 Outcomes evidence도 존재한다.
- HTTP client 의존성은 추가할 필요가 없다. `spring-boot-starter-webmvc`가 이미 들어와 있다.
- ADR-078과 BR-29·ADR-021의 2026-08-09 amendment는 ADR-079와 함께 `Accepted`다. 남은 gate는
  `BILLING_DELETED` transport의 authoritative 인증 계약 확인이다.
  현재 공식 webhook 가이드는 event/retry는 설명하지만 signature 계약을 확인시키지 못했으므로
  이 계획을 dependency 완료만으로 자동 ready로 올리지 않는다.

## Definitions

- **scripted local adapter:** 외부 호출 없이 `tokenReference` 접미사로 결과를 만드는 기존
  `local & !prod` bean이다.
- **Toss sandbox adapter:** `test_sk_` 키로 실제 토스 API를 호출하는 이 계획의 새 bean이다.
- **Provider 참조 값:** provider가 승인을 위해 token reference와 함께 요구하는
  non-sensitive 값이다. 이 계획에서는 토스 자동결제의 `customerKey` 하나다.
- **분류표:** ADR-078이 고정한 승인 9행·환불 6행의 관측 → 결과 매핑이다.
- **결과 불명:** `ProviderPaymentResult.Unknown`, `GatewayRefundResult.Unknown` 또는
  `ProviderTransportFailure`로 판정되어 LOOKUP 예산으로 넘어가는 상태다.

## Scope

### In Scope

- `toss-sandbox` profile의 `PaymentGateway` 구현과 profile 상호배타·startup guard
- 선행 plan이 정의한 결제수단 registration Port의 **토스 구현체**와 `src/test` 통합테스트
  harness: 실행 시점 합성 카드번호·유효기간·생년월일·카드 비밀번호로 빌링키를 발급하고
  응답 직후 참조를 폐기하며 어디에도 남기지 않는다. CVC는 만들지 않는다
- 선행 plan이 정의한 deactivation Port의 **토스 구현체**:
  `DELETE /v1/billing/{billingKey}` 호출과 fail-closed 결과 분류
- 선행 plan의 provider-neutral inbound Port를 호출하는 검증된 `BILLING_DELETED` webhook
  transport와 provider event mapping
- `TossPayments-Test-Code` 헤더를 사용한 분류표 전 행의 adapter contract test
- 승인에 provider 참조 값을 싣고 환불 LOOKUP에 차집합 입력을 넘기기 위한 port 확장
- 승인 결과 불명 복구, 부분 환불, 매장 거절 환불, 늦은 승인 void/refund의 sandbox 재검증
- provider 호출 metric·log 경계와 runbook, ADR-078이 지목한 문서 3건 갱신

### Non-goals

- 실제 자금 이동, 운영 계약, 규제 준수 주장, 라이브 키 사용
- **PaymentMethod 등록·조회·폐기 계약, 공개 API, 스키마와 migration.**
  `payment-method-token-management` plan이 소유한다.
- **registration Port의 계약 정의와 scripted local adapter.** 같은 plan이 소유한다.
  이 계획은 그 Port의 토스 구현체만 추가하며 signature를 다시 설계하지 않는다.
- provider-neutral webhook inbox schema·PaymentMethod 상태 전이와 migration. 선행
  `payment-method-token-management` plan이 소유한다
- 결제위젯·일반결제 연동
- scripted local adapter 제거 또는 local demo 기본값 변경
- `RetryableFailed` allowlist 채우기

## Business Rules and Invariants

- BR-29와 ADR-021 amendment: provider 참조 값은 카드 데이터를 담지 않고, 무작위로 생성하며,
  identity·인가 근거가 아니고, 응답·로그·trace·metric tag·Audit에 노출하지 않는다.
- BR-26: terminal 멱등 응답은 90일 보존한다. 토스 멱등키는 15일이므로 15일이 지난 뒤에는
  같은 키의 REQUEST로 중복 방지를 기대하지 않는다.
- ADR-006: Provider 호출 구간에 DB transaction과 connection을 유지하지 않는다.
- ADR-007/013: 승인 `UNKNOWN`은 재요청하지 않고 10초·30초·2분·5분·15분에 조회한다. 늦은
  승인은 Order를 되살리지 않고 void를 먼저 시도한다.
- ADR-037/038: REQUEST 3회와 LOOKUP 5회 예산은 독립이다. `RetryableFailed`는 Provider가
  무부수효과와 같은-key 재실행 안전을 보장할 때만 쓴다. allowlist 밖은 fail-closed다.
- ADR-009: 상점 설정 결함을 고객 결제 거절로 표현하지 않는다.
- ADR-079: 제품 외부 provider는 `TOSS_PAYMENTS` 하나이며 scripted local adapter는 제품
  provider나 운영 fallback이 아니다.
- 승인의 fail-closed 기본값은 `Unknown`이다. 모르는 실패를 `Declined`로 단정하지 않는다.

## Architecture and Transaction Boundaries

- adapter는 `payment.internal`에 두고 port 계약만 구현한다. Ordering·Payment Application
  Service, 상태 기계와 예약 확정 경계는 바꾸지 않는다.
- 모든 Provider 호출은 ADR-006대로 Tx1 commit 이후, Tx2 시작 이전에 transaction 밖에서
  수행한다. adapter는 Repository와 `EntityManager`를 참조하지 않는다.
- adapter는 상태를 저장하지 않는다. 멱등키 생성·보존과 예산 판단은 기존 호출자가 소유하고
  adapter는 받은 키를 그대로 헤더에 싣는다.
- `RestClient`는 connect/read timeout을 명시한 단일 bean으로 구성한다. timeout과 연결 실패,
  파싱 불가 응답은 `ProviderTransportFailure`로 올린다.
- provider 참조 값 `provider_customer_reference`의 CSPRNG 생성·registration 멱등 원장 고정·
  PaymentMethod 저장은 `payment-method-token-management` plan이 소유하고, 이 계획의 adapter는
  등록·승인 요청을 조립할 때 받은 값을 그대로 사용한다. adapter가 생성하거나 default하지 않는다.
- 승인·lookup·late recovery request는 선행 plan의 immutable `PaymentProviderRequestSnapshot`에서
  조립된다. adapter는 current PaymentMethod 상태를 조회하거나 deactivation 뒤 기존 Payment를
  취소하지 않는다.

## Alternatives Considered

- **HTTP mock 서버로 오류 code 재현:** 재현할 응답을 우리가 정하므로 "실제 Provider의 실제
  응답"이라는 이 계획의 목적이 사라진다.
- **분류표를 설정으로 외부화:** ADR-038이 금지한다. 재시도 안전성은 코드 리뷰와 contract
  test를 거쳐야 한다.
- **scripted local adapter를 토스 adapter로 대체:** local demo가 외부 네트워크와 키에
  의존하게 되어 오프라인 재현성을 잃는다.
- **결제위젯 기반 일반결제로 먼저 전환:** 서버 주도 승인 흐름과 ADR-021 저장 모델을 함께
  바꿔야 해서 sandbox 검증 목적에 비해 범위가 크다.

## Failure Semantics

- 승인: `200 + status=DONE +` 비어 있지 않은 `paymentKey`는 Provider의 실제 금액·통화를 담은
  `Approved`다. `Payment.applyProviderResult`가 요청 금액·통화를 비교하고 불일치를
  `RECONCILING`으로 보내 `paymentKey`를 보존한다. `200`이지만 `status≠DONE` 또는 paymentKey가
  없으면 `Unknown`이다. `REJECT_CARD_PAYMENT`, `REJECT_CARD_COMPANY`,
  `INVALID_BILL_KEY_REQUEST`, `NOT_MATCHES_CUSTOMER_KEY`는 `Declined`다. 5xx와
  `IDEMPOTENT_REQUEST_PROCESSING`, 미등록 code는 `Unknown`이다. `UNAUTHORIZED_KEY`와
  `INVALID_IDEMPOTENCY_KEY`는 `ProviderTransportFailure`로 올려 운영에 노출한다.
- 환불: 요청 금액이 반영된 `cancels` 항목만 `Succeeded`다. `NOT_FOUND_PAYMENT`,
  `NOT_CANCELABLE_PAYMENT`, `NOT_CANCELABLE_AMOUNT`, `EXCEED_MAX_REFUND_DUE`,
  `NOT_ALLOWED_PARTIAL_REFUND`, `INVALID_REFUND_ACCOUNT_INFO`는 `Failed`다.
  `ALREADY_CANCELED_PAYMENT`, 5xx, `PROVIDER_ERROR`, 미등록 code는 `Unknown`이다.
- `RetryableFailed`는 이 계획에서 한 번도 반환하지 않는다. 따라서 환불 실패는 terminal
  `Failed` 또는 LOOKUP 경로로만 간다.
- provider 참조 값이 필요한데 없으면 Provider를 호출하지 않고 명시적으로 실패한다. 임의
  값으로 대체하거나 scripted adapter로 조용히 fallback하지 않는다.
- deactivation은 선행 lifecycle Tx D1 commit 뒤 `DELETE /v1/billing/{billingKey}`를 호출한다.
  durable claim마다 정확히 한 번만 호출하며 timeout·응답 유실·파싱 불가는 성공으로 바꾸지 않고
  unknown result로 반환한다. DELETE 멱등키·결과 lookup이 공식 계약에 없으므로 adapter와 worker는
  자동 재호출하거나 후속 not-found를 성공으로 승격하지 않는다.
- registration은 검증된 Billing 응답만 `Issued`, deactivation은 빈 `200`만 `Deactivated`다.
  side effect 부재가 contract test로 확인된 allowlist만 `RejectedWithoutEffect`이고, 5xx·timeout·
  파싱 실패·필수 성공값 누락·미등록 code는 `Unknown`이다. 인증·credential·계약 결함은
  `Misconfigured` 또는 startup failure로 운영에 노출한다.
- lifecycle scripted adapter는 `(local | test) & !toss-sandbox & !prod`, 이 Toss 구현은
  `toss-sandbox & !prod`와 `test_sk_`에서만 활성화한다. missing/multiple Port와 adapter 조건
  중첩, `live_sk_`는 startup failure이며 자동 scripted/fake/no-op fallback은 없다.
- `BILLING_DELETED` transport는 Provider 인증·서명 검증 뒤에만 선행 lifecycle inbound Port를
  호출한다. inbox 수락 또는 mapping 결과 저장 실패, malformed payload와 인증 실패를 2xx로
  숨기지 않는다. 같은 delivery 안에서 W2 terminal 결과까지 commit된 뒤에만 ACK한다.
- secret key 부재, `live_sk_` 감지, `prod` profile에서의 이 adapter 선택, scripted adapter와
  동시 활성은 모두 startup 실패다. 실행 중 우회 경로를 두지 않는다.
- 환불 LOOKUP은 매핑되지 않은 `cancels` 후보가 정확히 하나일 때만 `Succeeded`다. 후보가
  없거나 둘 이상이면 `Unknown`이며 예산 소진 후 `MANUAL_REVIEW`로 간다.

## Data and Migration

- **이 계획은 migration을 쓰지 않는다.** `Writes-Migration=false`이며 migration-writer lease를
  요구하지 않는다.
- 필요한 provider 참조 값 컬럼은 `payment-method-token-management` plan이 PaymentMethod
  스키마와 함께 만든다. 컬럼은 `provider_customer_reference varchar(200)`이고
  `TOSS_PAYMENTS`에는 non-blank 필수, 다른 provider에는 null인 CHECK를 가진다. 이 계획은
  그 컬럼을 읽기만 한다.
- 환불 LOOKUP의 차집합 판정에 필요한 데이터는 이미 있다. `payment_refund.provider_refund_reference`와
  `uq_payment_refund_provider_reference` 전역 unique index가 그대로 근거다.
- 선행 plan이 참조 값 컬럼을 만들지 않기로 결정하면 이 계획은 시작할 수 없다. 그 경우
  Decision Log에 기록하고 두 계획의 경계를 다시 연다.

## API and Event Contracts

- 고객용 PaymentMethod API 계약은 바꾸지 않는다. authoritative 인증·stable notification ID 계약을
  확인한 뒤 `BILLING_DELETED` Provider callback path/schema를 target과 runtime OpenAPI에 함께
  추가한다. source gate가 닫히기 전에는 추측한 callback 계약을 쓰거나 endpoint를 활성화하지 않는다.
- 이벤트 계약 변경은 없다. 승인·환불 결과 이벤트의 payload와 version은 그대로다.
- `PaymentGateway`의 method signature는 바꾸지 않지만 **request 객체는 바뀐다.**
  `GatewayApprovalRequest`에 provider 참조 값 필드를 추가해야 승인에 `customerKey`를 실을
  수 있다. 이것도 port 계약 변경이므로 diff에 명시한다.
- `lookupRefund`도 port가 넓어진다. 이 Refund의 요청 금액과 같은 Payment에서 이미 확정된
  provider refund reference 집합을 받아야 차집합 판정이 가능하다. adapter는 상태를 저장하지
  않으므로 두 값은 `RejectionRefundService`·`PartialRefundPaymentService`가 조립한다.
  `requestLoader.loadLookup(paymentId)`가 결제 단위 정보만 만드는 현재 구조로는 부족하다.
- provider 참조 값은 어떤 응답 schema에도 추가하지 않는다.

## Milestones

0. **선행 gate.** Accepted 제품 결정은 닫혔다. `payment-method-token-management` plan이 provider
   참조 값 컬럼, provider-neutral Port, 공개 lifecycle과 request snapshot을 완료한다. 해당 plan을
   completed path로 이동한 evidence와 Toss의 authoritative webhook 인증·stable notification ID
   mapping 계약을 모두 확인한다. 인증 계약이 없거나 불명확하면 endpoint를 구현·활성화하지 않고
   `Implementation-Ready=false`를 유지한 채 security decision을 다시 연다.
1. **port 확장.** `GatewayApprovalRequest`에 provider 참조 값을, `lookupRefund`에 환불 금액과
   확정된 provider refund reference 집합을 전달하도록 넓힌다. scripted local adapter가 새
   계약에서도 기존 동작을 유지하는지 먼저 확인한다.
2. **adapter와 분류표.** `toss-sandbox` profile bean, `RestClient` timeout 구성, startup
   guard, 선행 plan Port의 토스 registration 구현체(합성 카드번호 → 빌링키)와 분류표 15행
   전부의 contract test를 완성한다.
3. **시나리오 재검증.** 승인 결과 불명 복구, 부분 환불 배분, 매장 거절 환불, 늦은 승인
   void/refund를 sandbox 실호출로 통과시키고 실제 관측 code를 기록한다.
4. **운영과 문서.** metric·log 경계, sandbox runbook, ADR-078이 지목한 문서 3건과 local
   demo runbook 용어를 갱신한다.

각 마일스톤은 앞 마일스톤의 실제 결과 없이 시작하지 않는다.

## Required Tests

- profile 조합: `toss-sandbox` 단독 기동, scripted adapter와 동시 활성 실패, `prod` 조합
  실패, secret key 부재 실패, `live_sk_` 감지 실패
- 분류표 승인 9행과 환불 6행 각각의 adapter contract test. `TossPayments-Test-Code`로
  재현하고, 헤더가 운영 호출 경로에 존재하지 않음을 함께 검증한다
- 테스트 환경 분당 100건 제한 안에서 예산 검증 테스트가 직렬로 수행됨
- port 확장 후에도 scripted local adapter와 local demo smoke가 기존 동작을 유지함
- 합성 카드번호·유효기간·생년월일·카드 비밀번호가 소스·fixture·설정·seed·로그·trace·
  metric·Audit·DB 어디에도 없고 발급 응답 뒤 참조가 폐기됨
- `src/test` harness만 synthetic issuance를 호출하며 공개·내부 HTTP route와 운영 Application
  Service consumer가 없음
- `toss-sandbox`, `!prod`, `test_sk_`, 별도 enable gate 중 하나라도 불충족하면 합성 값 생성과
  Provider 호출 없이 실패함
- authKey registration timeout·응답 유실에서 같은 authKey 재전송과 임의 lookup 없이 unknown
  result를 반환하고 lifecycle이 MANUAL_REVIEW로 수렴함
- registration/deactivation 닫힌 결과 variant 전 행과 allowlist 밖 code의 Unknown fail-closed
- 인증·credential·계약 결함의 Misconfigured/startup failure와 고객 거절 변환 부재
- lifecycle scripted/Toss sandbox profile 상호배타, missing/multiple bean과 live key fail-start
- 발급된 빌링키가 `tokenReference`로 저장되고 원본 카드 필드는 어디에도 저장되지 않음
- 발급 결과의 brand 1..40자·last4 숫자 4자리 검증, 누락·불일치 placeholder 부재와 expiry
  비저장·비응답
- 같은 billingKey의 exact ACTIVE owner/customerKey/alias/brand/last4 binding만 기존 resource로
  수렴하고 다른 binding·state는 overwrite·reactivation 없이 manual review로 감
- `200 + status≠DONE` 또는 `paymentKey` 부재가 `Approved`가 되지 않음
- 금액·통화 불일치 응답에서 adapter가 Provider 값을 그대로 올리고, `Payment`가
  `RECONCILING`으로 전이하며 `paymentKey`가 보존됨
- 미등록 code가 승인·환불 모두에서 `Unknown`으로 fail-closed
- `UNAUTHORIZED_KEY`가 `Declined`로 관측되지 않고 transport failure로 올라옴
- 같은 `providerIdempotencyKey` 재요청 시 adapter가 키를 바꾸지 않음
- 승인 timeout 뒤 `GET /v1/payments/orders/{paymentId}` 조회로 승인 사실 확정
- `ALREADY_CANCELED_PAYMENT`가 `Succeeded`로 승격되지 않음
- 같은 Payment 다건 부분취소에서 매핑된 `transactionKey` 제외 후 단일 후보만 `Succeeded`
- 취소 반영 전 조회의 빈 `cancels`가 `Failed`가 아니라 `Unknown`으로 남고, 예산 소진 후
  `MANUAL_REVIEW`로 감
- 미해결 Refund 존재 시 새 Refund 접수 거부(`ORDER_STATE_CONFLICT`)로 순차 가정 유지
- 매장 품절 부분취소 후 고객 파손 부분취소의 순차 시나리오에서 각 건이 자기
  `transactionKey`만 확정
- 참조 값 부재 시 Provider 호출 없는 명시적 실패. 임의 값 대체와 scripted adapter fallback 부재
- 결제수단 deactivation 성공·명시 실패·timeout·응답 유실의 토스 contract 분류와 동일 logical
  operation same-key replay의 Provider 무호출, durable claim 뒤 DELETE 단일 호출과
  96시간 내 `BILLING_DELETED`/기한 만료 MANUAL_REVIEW 수렴
- `BILLING_DELETED` 인증 실패·malformed·중복·W1/W2 DB Port 실패의 ACK 계약, W2 terminal commit
  전 2xx 부재와 raw token 비노출
- adapter가 provider customer reference를 생성·재생성하지 않고 registration 원장에 고정된
  값을 그대로 사용함
- Payment Tx1 뒤 deactivation된 method를 참조하는 기존 Payment가 같은 request snapshot으로
  approve·lookup·late recovery를 계속하고 신규 Payment만 차단됨
- secret key, `billingKey`, `customerKey`, `paymentKey`가 응답·로그·metric tag·Audit에 없음
- Provider 호출 구간의 DB transaction·connection 미유지
- ADR-037/038 예산이 sandbox 응답에서도 REQUEST 3회·LOOKUP 5회로 지켜짐
- 기존 scripted local adapter 경로와 local demo smoke의 무회귀

## Validation Commands

- `./gradlew test --tests '*Payment*' --tests '*Refund*' --tests '*Toss*'`
- `./gradlew test --tests '*ModularityTests'`
- `./gradlew clean build`
- `bash scripts/verify-docs.sh`
- `git diff --check`

sandbox 실호출 test는 키가 있을 때만 실행하고, 키가 없으면 skip이 아니라 명시적으로
"실행되지 않음"을 드러낸다. 통과하지 않은 검증을 통과로 기록하지 않는다.

## Observability

- `beanflow.payment.provider.call{operation,outcome}` — operation은
  `REGISTER|DEACTIVATE|APPROVE|LOOKUP|CANCEL|LOOKUP_CANCEL`, outcome은 닫힌 결과 분류다.
- `beanflow.payment.provider.code{operation,code}` — 정규화한 provider code만 tag로 쓴다.
- provider 호출 latency 분포와 timeout count
- `paymentId`, `billingKey`, `customerKey`, `paymentKey`, 멱등키, raw message, 금액은 metric
  tag에 넣지 않는다. log에는 닫힌 failure reason과 correlation ID만 둔다.

## Documentation Updates

- `docs/architecture/failure-semantics.md`: mock PG 행에 더해 외부 호출 adapter의 prod 금지
- `docs/architecture/architecture-overview.md`: mock/fake가 아닌 sandbox adapter의 profile 축
- `docs/operations/local-demo-runbook.md`: scripted local adapter 용어 정정
- `docs/operations/payment-reconciliation-runbook.md`: sandbox 키 관리, 미확정 건 처리,
  15일 경과 건의 수동 판정 절차
- authoritative source 확인 뒤 target/runtime OpenAPI: 인증된 Provider callback path/schema와 ACK 오류
- `docs/index.md`: 이 ExecPlan 등록

## Progress

- [x] 차단 결정 2건(빌링키 확보 경로, `lookupRefund` 판정 규칙) 해소 — 2026-08-09
- [ ] 선행 lifecycle plan 완료와 dependency completed-path 갱신
- [ ] authoritative webhook 인증·stable notification ID mapping 확인 뒤 readiness 재평가
- [ ] port 확장과 scripted local adapter 무회귀
- [ ] `toss-sandbox` adapter, startup guard와 분류표 contract test
- [ ] 네 시나리오 sandbox 재검증과 실제 관측 code 기록
- [ ] 운영 metric·runbook·문서 갱신
- [ ] full validation

## Surprises & Discoveries

- 2026-08-09: 토스 자동결제는 정기 구독형 전용이고 리스크 검토·추가 계약이 필요하다.
  빌링키 방식은 sandbox 검증 한정이며 라이브 전환 경로가 아니다.
- 2026-08-09: 토스 5xx와 `PROVIDER_ERROR`는 재시도를 안내하지만 부수효과 부재를 보장하지
  않는다. ADR-038의 `RetryableFailed` 조건을 만족하는 code가 없어 allowlist는 공집합이다.
- 2026-08-09: 빌링키 승인에는 `customerKey`가 필수라 BR-29·ADR-021의 닫힌 저장 필드 목록을
  개정해야 한다. adapter만 추가하면 되는 작업이 아니었다.
- 2026-08-09: local demo runbook이 scripted local bean을 이미 "sandbox adapter"라고 불러서
  용어가 겹친다.
- 2026-08-09: 코드 대조 결과 `lookupRefund`에는 환불 금액도 환불 reference도 전달되지
  않는다. `RejectionRefundService.callProvider`가 `requestLoader.loadLookup(paymentId)`로
  만든 결제 단위 request만 넘긴다. 토스 `cancels`에는 우리 멱등키가 없어 자동 판정이
  불가능하다.
- 2026-08-09: `Payment.applyProviderResult`가 이미 금액·통화 불일치를 `RECONCILING`으로
  보낸다. adapter가 불일치를 `Unknown`으로 바꾸면 `paymentKey`가 유실된다. 금액 판정은
  도메인에 남긴다.
- 2026-08-09: `ProviderPaymentResult`에 잔액 필드가 없어 ADR-013의 "승인 금액이 남아 있음
  확인 후 refund"를 adapter가 만족시킬 수 없다.
- 2026-08-09: 빌링키 API 발급이 원본 카드 데이터를 요구해 ADR-021의 test fixture 금지와
  정면 충돌한다. → 테스트 환경은 BIN 6자리만 유효하면 등록되고 출금이 없으므로, 실행 시점
  합성 카드번호 생성으로 해소했다.
- 2026-08-09: `TossPayments-Test-Code` 헤더로 모든 에러 코드를 테스트 환경에서 재현할 수
  있다. 분류표 15행을 실제 sandbox 응답으로 결정적으로 검증할 수 있게 됐다.
- 2026-08-09: `payment_refund`의 payment당 unique 제약은 `STORE_ORDER_REJECTED`에만 있어
  한 결제에 같은 금액의 부분 환불이 여러 건 존재할 수 있다. 반면
  `uq_payment_refund_provider_reference`가 `provider_refund_reference`를 전역 unique로
  묶어서, 이미 매핑된 `transactionKey`를 제외하는 차집합 판정이 성립한다.
- 2026-08-09: 테스트 환경은 API별 분당 100건 제한이 있어 예산 반복 검증 테스트를 직렬화해야
  한다.
- 2026-08-09: `findUnresolvedByPaymentId` 가드 덕분에 한 Payment의 미해결 Refund는 항상
  최대 1건이다. 다중 부분환불은 순차적이므로 차집합 후보는 정상 경로에서 항상 1건이고,
  "같은 금액 동시 미해결" 모호 케이스는 정상 경로로 만들어지지 않는다.
- 2026-08-09: 이 결정을 시작할 때 결제수단 등록 경로가 프로덕션에 없었다. 당시 OpenAPI에
  `/payment-methods` path가 없고
  `PaymentMethodEntity(...)` 생성은 test와 `LocalDemoSeedCli`뿐이다. 이 계획이 만들려던
  "테스트 전용 빌링키 발급 유틸"은 사실상 결제수단 registration Port를 test 코드로 미리 짓는
  것이었고, `payment-method-token-management` plan과 중복된다. 소유권을 그쪽으로 넘겼다.
- 2026-08-09: 처음 세운 "후보 0건 → `Failed`" 규칙은 위험했다. 취소가 Provider에서 반영되기
  전 조회도 빈 `cancels`를 돌려주므로, 진행 중인 환불을 미실행으로 확정해 유실시킬 수 있다.
  0건은 `Unknown`으로 두고 ADR-037의 LOOKUP 예산에 맡긴다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-09 | Accepted | 토스 테스트 키로 실제 sandbox 호출하는 adapter 추가 | scripted 응답만 통과한 실패 경로를 실물로 검증 | ADR-078 |
| 2026-08-09 | Accepted | 자동결제(빌링) 방식 채택, sandbox 한정 | ADR-021 저장 모델과 일치하나 토스 정책상 비정기 결제 제한 | ADR-078 |
| 2026-08-09 | Accepted | `RetryableFailed` allowlist 공집합 시작 | 무부수효과 보장 code 부재, ADR-038 fail-closed | ADR-078 |
| 2026-08-09 | Accepted | 승인 fail-closed 기본값은 `Unknown` | 모르는 실패를 거절로 단정하면 승인된 결제를 잃음 | ADR-078 |
| 2026-08-09 | Accepted | 설정 결함은 `Declined`가 아닌 transport failure | 운영 문제를 고객 거절로 삼키지 않음 | ADR-009, ADR-078 |
| 2026-08-09 | Accepted | provider 참조 값 저장 허용, identity·인가 제외 | 승인 필수값 저장과 최소 개정 | BR-29, ADR-021 |
| 2026-08-09 | Accepted | 금액·통화 판정은 adapter가 아니라 도메인이 한다 | `Payment.applyProviderResult`의 `RECONCILING` 경로와 `paymentKey` 보존 | ADR-078, Payment.kt |
| 2026-08-09 | Accepted | 빌링키는 실행 시점 생성한 합성 카드번호로 발급 | 테스트 환경은 BIN 6자리만 유효하면 등록되고 출금이 없음 | BR-29, ADR-021, ADR-078 |
| 2026-08-09 | Accepted | 분류표 검증에 `TossPayments-Test-Code` 사용 | 실제 sandbox 응답으로 15행을 결정적으로 재현 | ADR-078 |
| 2026-08-09 | Accepted | 환불 LOOKUP은 `orderId` 조회 + 매핑된 `transactionKey` 차집합으로 판정 | 순수 GET 유지, `cancelReason`에 내부 ID 비노출, 전역 unique reference에 의존 | ADR-037, ADR-038, ADR-078 |
| 2026-08-09 | Accepted | 차집합 후보 0건은 `Unknown`, 2건 이상도 `Unknown` | 반영 전 조회의 빈 `cancels`를 미실행 확정으로 쓰면 환불 유실 | ADR-037, ADR-038, ADR-078 |
| 2026-08-09 | Accepted existing | 한 Payment의 미해결 Refund는 최대 1건 | `findUnresolvedByPaymentId` 가드와 `MANUAL_REVIEW` 포함 상태 집합 | PartialRefundPaymentService, CustomerCancellationPaymentService |
| 2026-08-09 | Accepted | PaymentMethod 스키마·공개 API·registration Port 계약은 `payment-method-token-management` plan이 소유 | 결제수단 등록 경로가 프로덕션에 없고, 이 계획이 만들면 그 plan과 중복·재작업 | Codex 02-06A/02-06B |
| 2026-08-09 | Accepted | 그 Port의 토스 구현체(빌링키 발급)는 이 계획이 소유 | 한 plan이 한 provider의 승인·취소·발급 adapter를 모두 가져 분류·키 취급이 일관됨 | 사용자 결정 |
| 2026-08-09 | Accepted | 소유권 이관에 따라 `Writes-Migration`을 `false`로 내림 | 차집합 판정에 필요한 데이터가 이미 존재해 새 스키마가 없음 | ADR-072 |

## Outcomes & Retrospective

미시작이다. ADR-078과 amendment, 선행 `payment-method-token-management` 구현·검증은 완료됐지만
authoritative webhook 인증·stable ID mapping이 미확인이라 `Implementation-Ready=false`다. source
gate가 별도로 닫힌 뒤 readiness를 재평가한다. 완료 시 실제 관측한 provider code, 시나리오별 통과 결과와 provider
호출 latency를 측정값으로 기록한다. 측정하지 않은 값은 `Not measured`로 남긴다.

## Revision Notes

- 2026-08-09: 최초 작성.
- 2026-08-09: PaymentMethod 스키마·공개 등록 계약과 provider-neutral Port 소유권을
  `payment-method-token-management` plan으로 이관하고 `Writes-Migration`을 `false`로 내렸다.
  토스 빌링키 발급 구현은 이 plan에 남기고 port 확장을 마일스톤 1로 분리했다.
- 2026-08-09: 선행 lifecycle plan 경로를 canonical `Depends-On`에 기입하고 Accepted 결정·금액/통화
  domain 판정·registration/deactivation Port 경계를 정합화했다.
- 2026-08-10: 선행 lifecycle plan 완료에 따라 dependency와 Current State를 completed 구현으로
  갱신했다. webhook 인증·stable ID gate가 남아 readiness는 `false`로 유지했다.
