# ADR-078: 토스페이먼츠 sandbox PaymentGateway adapter

- **Status:** Accepted
- **Date:** 2026-08-09

## Context

현재 `PaymentGateway`의 유일한 구현은 `local & !prod` profile의 scripted bean이며
`tokenReference` 접미사로 결과를 만든다. 승인 UNKNOWN 복구, 부분 환불 배분, 매장 거절
환불과 ADR-037/038의 REQUEST·LOOKUP 예산은 전부 이 scripted gateway 위에서만 검증됐다.
실제 Provider의 HTTP status, 오류 code와 멱등 동작을 한 번도 통과한 적이 없어서,
ADR-038이 요구하는 "adapter가 소유하는 재시도 허용 code 목록"은 비어 있고
`GatewayRefundResult.RetryableFailed`를 만들어내는 코드 경로도 없다.

non-goals는 실제 PG 운영 계약을 범위 밖에 두면서 "PG는 명시적인 mock/sandbox Adapter로
실패와 reconciliation을 검증한다"고 정한다. 토스페이먼츠 테스트 시크릿 키(`test_sk_`)로
실제 sandbox API를 호출하는 adapter는 이 문장 안에 있고, ADR-021의 Revisit Condition인
"실제 PG sandbox 계약"에 해당한다.

## Decision

### 범위와 profile

- 이 ADR은 Toss sandbox adapter만 정한다. 실제 자금 이동, 운영 계약과 규제 준수 주장은
  non-goals에 남긴다.
- 용어를 분리한다. 기존 `local & !prod` bean은 외부 호출이 없으므로 **scripted local
  adapter**이고, 이 ADR이 추가하는 것은 실제 HTTP 호출을 하는 **Toss sandbox adapter**다.
  local demo runbook이 scripted bean을 "sandbox adapter"로 부르는 표현은 이 구분에 맞게
  고친다.
- adapter는 `toss-sandbox` profile에서만 bean으로 등록하고 scripted local adapter와
  상호배타로 둔다. 두 bean이 동시에 등록되는 조합은 startup 실패다.
- 어떤 profile에서도 `live_sk_`로 시작하는 secret key를 허용하지 않는다. live key 감지,
  secret key 부재와 `prod` profile에서의 이 adapter 선택은 모두 startup 실패다.
- local demo의 기본 gateway는 계속 scripted bean이다. sandbox 호출은 명시적 profile
  선택으로만 일어난다.
- MVP 제품 외부 provider는 `TOSS_PAYMENTS` 하나이며 provider routing을 만들지 않는다.
  scripted local adapter는 고객이 선택하거나 non-local API가 노출하는 provider가 아니다.

### 연동 방식

- 자동결제(빌링) API 방식을 사용한다. `PaymentMethod.tokenReference`는 토스 `billingKey`다.
- 토스 정책상 자동결제는 정기 구독형 서비스에만 허용되고 리스크 검토·추가 계약이 필요하다.
  BeanFlow의 커피 주문은 비정기 결제이므로 **이 선택은 sandbox 검증 한정**이며 라이브
  전환 경로가 아니다. 라이브를 논의할 때는 결제위젯 기반 일반결제로 다시 설계한다.
- 빌링키는 `customerKey`와 매핑되어야만 승인할 수 있다. `customerKey`는 유추 가능한 값이
  금지되므로 memberId·이메일·전화번호에서 파생하지 않고, PaymentMethod 등록 시 무작위
  값을 생성해 저장한다.
- 이 저장은 BR-29와 ADR-021의 원래 닫힌 필드 목록과 충돌한다. 두 결정은 provider,
  token reference, 사용자 식별자, 표시용 별칭, 카드 브랜드와 마지막 4자리"만" 저장하도록
  정했다. `customerKey`는 민감 결제정보가 아니지만 목록 밖의 새 컬럼이므로, BR-29의
  Provider Reference Amendment(2026-08-09)와 ADR-021의 같은 날짜 Amendment가 이 ADR과
  함께 제출된다. 두 개정이 Accepted 되기 전에는 컬럼을 추가하지 않는다.
- **컬럼과 migration은 이 ADR이 만들지 않는다.** PaymentMethod 스키마는 결제수단 lifecycle
  결정과 그 ExecPlan이 소유한다. 이 ADR은 "승인에 이 값이 필요하다"는 요구사항의 출처이며,
  값이 없으면 Provider를 호출하지 않고 실패한다는 adapter 동작만 정한다.
- 두 개정이 정한 제약을 이 adapter도 그대로 따른다. `customerKey`는 무작위로 생성하고,
  결제수단 identity와 인가 근거로 쓰지 않으며, 응답·로그·metric tag·Audit에 남기지 않는다.
  값이 없으면 Provider를 호출하지 않고 명시적으로 실패한다.
- registration adapter는 토스 발급 결과에서 `cardBrand`와 `lastFour`를 검증해 Port result로
  반환한다. 누락·형식 불일치를 placeholder나 billingKey 파싱으로 보정하지 않고 불명·실패
  계약에 따라 올린다. expiry와 내부 reference는 표시 result에 포함하지 않는다.
- lifecycle 구현은 이 값을 `payment_method.provider_customer_reference`에 저장한다. 컬럼은
  nullable이지만 `TOSS_PAYMENTS`에는 DB CHECK로 non-blank를 요구하고 다른 provider에는 null을
  강제한다. 값은 registration Application Service가 외부 호출 전에 registration 멱등 원장에
  고정하며 이 adapter는 생성하거나 default하지 않는다.
- 대안으로 `customerKey`를 `tokenReference` 문자열에 합성해 필드 목록을 문자 그대로
  지킬 수 있으나, 하나의 값에 두 식별자를 섞으면 ADR-021의 `(member, provider, token
  reference)` unique constraint 의미가 흐려지므로 채택하지 않는다.
- 승인 요청의 토스 `orderId`는 BeanFlow `paymentId`(UUID)를 그대로 사용한다. 이것이
  `GET /v1/payments/orders/{orderId}` 조회를 가능하게 하는 유일한 안정 식별자다.
- 승인 응답의 `paymentKey`를 `providerTransactionReference`로 보존한다.

### 빌링키 확보

- PaymentMethod 등록·폐기 계약, 공개 API와 registration Port의 **계약 정의**는 결제수단
  lifecycle 결정이 소유한다. 이 ADR은 **그 Port의 토스 구현체**와, 발급된 token을 승인·취소·
  조회에 쓰는 규칙을 정한다.
- 토스 발급 경로의 제약은 다음과 같다.
  - 테스트 환경은 카드 번호 앞 6자리 BIN만 유효하면 자동결제가 등록되고, 승인되어도 실제
    출금이 없다. `src/test` 통합테스트 harness는
    `POST /v1/billing/authorizations/card`에 실행 시점에 생성한 합성 카드번호·유효기간·
    생년월일·카드 비밀번호를 보내 빌링키를 얻는다. CVC는 만들거나 전송하지 않는다.
  - 결제창 방식(`authKey`)은 휴대폰 본인인증이 필요해 자동 검증에 쓸 수 없다. 고객용 공개
    등록 API를 결제창 방식으로 닫으면 테스트 발급은 별도 예외 경로가 되며, 그 예외를 함께
    정의해야 한다.
  - 합성 값 취급은 BR-29의 Test Card Amendment와 ADR-021/079를 따른다. 공개·내부 HTTP
    endpoint 없이 test harness만 adapter를 직접 호출하고, `toss-sandbox`, `!prod`, `test_sk_`,
    별도 enable gate가 모두 맞아야 한다. 값은 소스·fixture·설정에 두지 않고 발급 응답 직후
    폐기한다.

### 빌링키 폐기

- ADR-079가 정의한 deactivation Port의 토스 구현체는
  `DELETE /v1/billing/{billingKey}`를 호출한다. Port 계약·로컬 상태·멱등 원장과
  reconciliation은 `payment-method-token-management` plan이 소유하고, 이 plan은 토스 HTTP
  요청과 응답 분류만 구현한다.
- lifecycle Tx D1이 commit되기 전에는 호출하지 않고, Provider latency 동안 DB transaction이나
  connection을 유지하지 않는다.
- timeout·응답 유실·파싱 불가는 성공이나 token 부재로 추정하지 않고 unknown result로 반환한다.
  adapter가 local tombstone 성공이나 scripted fallback을 만들지 않는다.

### registration/deactivation Port 결과 구현

- ADR-079의 닫힌 결과 합을 그대로 구현한다. registration은 검증된 Billing 응답만 `Issued`,
  deactivation은 빈 `200`만 `Deactivated`다.
- contract test로 side effect 부재가 확인된 닫힌 allowlist code만
  `RejectedWithoutEffect`다. allowlist 밖 code, 5xx, timeout·연결 실패·응답 유실·parsing
  failure와 성공 필수값 누락은 `Unknown`이다.
- `UNAUTHORIZED_KEY`, 잘못된 Basic 인증, live key·secret 부재와 계약 미활성은
  `Misconfigured` 또는 startup failure이며 고객 거절로 반환하지 않는다.
- raw Provider code/message, billingKey와 customerKey는 공개 오류·로그·trace·metric tag·Audit에
  전달하지 않는다. observability에는 닫힌 BeanFlow reason과 operation만 사용한다.
- lifecycle scripted adapter는 `(local | test) & !toss-sandbox & !prod`, 이 Toss 구현은 `toss-sandbox`와
  `!prod`에서만 활성화한다. Toss 구현은 `test_sk_`를 필수로 하며 합성 발급 harness에는 별도
  enable flag를 더한다. missing/multiple Port, scripted와 sandbox 동시 활성과 `live_sk_`는
  startup failure다. Bean 부재를 scripted/fake/no-op으로 자동 대체하지 않는다.

### `BILLING_DELETED` webhook transport

- 이 ADR의 토스 구현은 Provider가 인증된 `BILLING_DELETED`를 ADR-079의 provider-neutral
  inbound contract로 변환한다. lifecycle plan이 소유한 inbox·상태 repository를 직접 호출하지
  않고 공개 Application Port만 호출한다.
- 토스의 authoritative transport 인증·서명 계약을 검증하기 전에는 endpoint를 활성화하지
  않는다. 인증 실패는 inbox에 넣거나 2xx로 승인하지 않고, secret·Authorization·raw payload를
  로그와 trace에 남기지 않는다.
- adapter는 stable provider notification ID와 token binding을 추출하되 raw billingKey와
  customerKey를 Audit·metric tag에 복제하지 않는다. 필수 식별자가 없거나 파싱이 불명확하면
  임의 mapping 없이 요청을 실패시킨다.
- lifecycle Port의 inbox 수락 또는 PaymentMethod mapping 결과 저장이 실패하면 Provider에 성공
  ACK를 반환하지 않는다. mapped/manual-review terminal 결과까지 내구화된 같은 notification ID의
  replay만 idempotent 2xx다.

### 오류 재현과 요청 제한

- 분류표 검증에는 `TossPayments-Test-Code` 헤더를 사용한다. 테스트 시크릿 키와 함께 보내면
  재현하려는 에러 코드를 그대로 응답받을 수 있으므로, 15행 전부를 실제 sandbox 응답으로
  결정적으로 검증한다. adapter는 이 헤더를 테스트에서만 주입하고 운영 호출 경로에 두지
  않는다.
- 테스트 환경은 API별 분당 100건 제한이 있다. ADR-037/038의 REQUEST·LOOKUP 예산을 반복
  검증하는 테스트는 이 한도를 넘지 않도록 직렬화한다. 429 계열 응답은 `Unknown`으로 분류해
  fail-closed를 유지한다.

| 포트 메서드 | 토스 API |
|---|---|
| `approve` | `POST /v1/billing/{billingKey}` |
| `lookup` | `GET /v1/payments/orders/{orderId}` |
| `void`, `refund`, `requestRefund` | `POST /v1/payments/{paymentKey}/cancel` (부분취소는 `cancelAmount`) |
| `lookupRefund` | `GET /v1/payments/orders/{orderId}`의 `cancels` |

- `void`는 별도 API가 아니라 전액 `cancel`이다. Provider에 별도 void 개념이 있다고
  가정하지 않는다.
- 따라서 ADR-007/013의 늦은 승인 경로에서 "void 우선 시도 후 필요하면 refund"는 이
  Provider에서 같은 취소 API의 재호출로 축약된다. adapter는 두 단계를 서로 다른 Provider
  기능으로 위장하지 않고, 호출자가 준 서로 다른 멱등키(`payment:{id}:late-void`,
  `payment:{id}:late-refund`)를 그대로 사용해 부수효과가 한 번만 일어나게 한다.
- ADR-013의 "승인 금액이 남아 있음이 확인된 뒤에만 refund"는 이 adapter가 만족시킬 수 없다.
  `ProviderPaymentResult`에는 잔액을 담을 필드가 없고 `Approved`는 reference와 금액·통화만
  가진다. late-void가 이미 전액 취소를 성공시킨 뒤의 late-refund는
  `ALREADY_CANCELED_PAYMENT`를 받아 `Unknown`이 되고 LOOKUP 경로로 넘어간다. 잔액 기반
  선판정이 필요하면 port에 잔액을 표현하는 별도 결정이 있어야 한다.

### 멱등키

- 모든 POST 요청에 포트가 넘긴 `providerIdempotencyKey`를 `Idempotency-Key` 헤더로 보낸다.
  adapter가 키를 새로 만들거나 재요청마다 바꾸지 않는다.
- 토스 멱등키의 유효기간은 최초 요청일로부터 15일이고 BR-26의 terminal 응답 보존은
  90일이다. **최초 REQUEST로부터 15일이 지난 뒤에는 같은 키의 REQUEST를 재사용해
  중복 방지를 기대할 수 없다.** 이 구간에서는 REQUEST를 보내지 않고 LOOKUP만 수행하며,
  LOOKUP으로도 확정되지 않으면 `MANUAL_REVIEW`로 종결한다.
- 현재 결정 중 15일 뒤에 REQUEST를 보내는 경로는 없다. ADR-007의 승인 조회와 ADR-037의
  LOOKUP은 모두 약 23분 안에 끝나고, ADR-038의 REQUEST 재시도는 10초·30초이며, ADR-075의
  운영자 재개는 LOOKUP만 허용한다. LOOKUP은 GET이라 멱등키를 쓰지도 않는다. 따라서 위
  규칙은 현재 도달 불가능한 방어 조항이며, 새 지연 재시도 경로를 도입할 때 먼저 확인해야
  할 제약으로 기록한다.

### 승인 결과 분류

| 관측 | `ProviderPaymentResult` |
|---|---|
| `200` + `status=DONE` + 비어 있지 않은 `paymentKey` | `Approved`(응답의 실제 금액·통화 그대로) |
| `200`이지만 `status≠DONE` 또는 `paymentKey` 부재 | `Unknown` |
| `403 REJECT_CARD_PAYMENT`, `403 REJECT_CARD_COMPANY` | `Declined` |
| `400 INVALID_BILL_KEY_REQUEST`, `400 NOT_MATCHES_CUSTOMER_KEY` | `Declined` |
| `500 FAILED_CARD_COMPANY_RESPONSE`, `500 FAILED_DB_PROCESSING`, `500 FAILED_INTERNAL_SYSTEM_PROCESSING` | `Unknown` |
| `409 IDEMPOTENT_REQUEST_PROCESSING` | `Unknown` |
| `401 UNAUTHORIZED_KEY`, `400 INVALID_IDEMPOTENCY_KEY` | `ProviderTransportFailure` |
| timeout, 연결 실패, 파싱 불가 응답 | `ProviderTransportFailure` |
| allowlist에 없는 code | `Unknown` |

- 승인의 fail-closed 기본값은 `Declined`가 아니라 `Unknown`이다. 모르는 실패를 거절로
  단정하면 실제로 승인된 결제를 잃는다.
- **금액·통화 판정은 adapter가 하지 않는다.** `Payment.applyProviderResult`가 이미
  `result.amountKrw == requestedAmountKrw && result.currency == currency`를 검사해 불일치를
  `RECONCILING`으로 보낸다. adapter가 불일치를 `Unknown`으로 바꾸면 `paymentKey`가 유실되고
  상태가 `UNKNOWN`으로 떨어져 기존 reconciliation 경로가 죽는다. adapter는 Provider가 준
  금액과 통화를 그대로 올린다.
- `UNAUTHORIZED_KEY`와 `INVALID_IDEMPOTENCY_KEY`는 고객 결제 거절이 아니라 상점 설정
  결함이다. ADR-009에 따라 `Declined`로 삼키지 않고 transport failure로 올려 운영에
  노출한다.

### 환불 결과 분류

| 관측 | `GatewayRefundResult` |
|---|---|
| `200` + 요청 금액이 반영된 `cancels` 항목 | `Succeeded`(해당 `transactionKey`) |
| `404 NOT_FOUND_PAYMENT`, `403 NOT_CANCELABLE_PAYMENT`, `403 NOT_CANCELABLE_AMOUNT`, `403 EXCEED_MAX_REFUND_DUE`, `403 NOT_ALLOWED_PARTIAL_REFUND`, `400 INVALID_REFUND_ACCOUNT_INFO` | `Failed` (REQUEST에서만) |
| `400 ALREADY_CANCELED_PAYMENT` | `Unknown` |
| `500 FAILED_REFUND_PROCESS`, `500 FAILED_METHOD_HANDLING_CANCEL`, `500 FAILED_INTERNAL_SYSTEM_PROCESSING`, `400 PROVIDER_ERROR` | `Unknown` |
| `409 IDEMPOTENT_REQUEST_PROCESSING` | `Unknown` |
| allowlist에 없는 code | `Unknown` |

- **이 adapter의 `RetryableFailed` allowlist는 공집합으로 시작한다.** ADR-038은 Provider가
  부수효과 없음과 같은-key 재실행 안전을 계약으로 보장할 때만 `RetryableFailed`를
  허용한다. 토스의 5xx와 `PROVIDER_ERROR`는 "잠시 뒤 다시 시도"를 안내할 뿐 이번 호출의
  환불 부수효과 부재를 보장하지 않으므로 조건을 만족하지 못한다. sandbox에서 특정 code의
  무부수효과를 실측으로 확인하기 전에는 allowlist에 넣지 않는다.
- `ALREADY_CANCELED_PAYMENT`를 성공으로 단정하지 않는다. 같은 멱등키 안에서는 원 응답이
  재생되므로 이 code는 다른 키 또는 15일 초과 재요청에서만 관측되고, 그 취소가 우리
  요청의 결과인지 확인할 수 없다. `Unknown`으로 두고 LOOKUP이 판정한다.
- `lookupRefund`는 `GET /v1/payments/orders/{paymentId}`의 `cancels`로 판정한다. 순수
  GET이므로 ADR-037의 "LOOKUP 경로에서 REQUEST를 보내지 않는다"를 그대로 지킨다.
- `paymentKey`나 `orderId`만으로는 이 logical refund를 특정할 수 없다. `payment_refund`의
  payment당 unique 제약은 `reason = 'STORE_ORDER_REJECTED'`에만 있으므로 한 Payment에
  부분 환불이 여러 건, 같은 금액으로도 쌓일 수 있다. 매장 품절 부분취소 뒤 고객의 파손·
  오수령 부분취소가 이어지는 것이 정상 시나리오다. 토스 `cancels` 항목에는 우리 멱등키가
  없다.
- 다만 이 다중 환불은 **순차적**이다. `findUnresolvedByPaymentId` 가드가 미해결 Refund가
  있는 동안 새 Refund 접수를 `ORDER_STATE_CONFLICT`로 거부하고,
  `UNRESOLVED_REFUND_STATES`는 `MANUAL_REVIEW`까지 포함한다. 새 환불을 막지 않는 상태는
  `SUCCEEDED`와 `FAILED`뿐이므로 한 Payment의 미해결 Refund는 항상 최대 하나다.
- 따라서 다음 차집합 규칙으로 판정한다.
  1. 같은 Payment의 다른 Refund가 이미 확정한 `provider_refund_reference`와 같은
     `transactionKey`를 가진 `cancels` 항목을 후보에서 제외한다.
  2. 남은 후보 중 `cancelAmount`가 이 Refund의 요청 금액과 같은 것이 정확히 하나면
     `Succeeded`이며 그 `transactionKey`를 `providerRefundReference`로 확정한다.
  3. **남은 후보가 없으면 `Unknown`이다.** 취소가 Provider에서 아직 반영되지 않은 시점의
     조회도 빈 `cancels`를 돌려주므로, 부재를 실행되지 않음의 확인으로 쓰지 않는다.
     ADR-037의 LOOKUP 5회 예산을 모두 소진해도 계속 부재면 `MANUAL_REVIEW`로 간다.
     ADR-038의 "lookup의 명시 실패는 terminal"은 Provider가 명시적으로 실패를 답할 때만
     적용하고 부재 추론에는 적용하지 않는다.
  4. 남은 후보가 둘 이상이면 자동 판정하지 않고 `Unknown`을 반환한다.
  5. 결제 조회 자체가 실패하면 `Unknown`이다.
- 3번 때문에 이 adapter의 `lookupRefund`는 `Failed`를 반환하지 않는다. 환불이 실행되지
  않았다는 확인은 REQUEST 시점의 명시적 실패 code(`NOT_CANCELABLE_PAYMENT` 등)로만 얻는다.
- 4번은 방어 조항이다. 순차 접수 가드와 3번 때문에 두 Refund가 동시에 매핑되지 않은
  실행된 취소를 남길 경로는 현재 없다. 가드가 약해지면 이 분기가 유일한 안전망이 된다.
- `uq_payment_refund_provider_reference`의 전역 unique index가 같은 `transactionKey`를 두
  Refund에 매핑하는 것을 막는다. 이 규칙의 안전성은 그 제약에 의존한다.
- **이 규칙은 port 확장을 요구한다.** `lookupRefund`는 이 Refund의 요청 금액과, 같은
  Payment에서 이미 확정된 provider refund reference 집합을 받아야 한다. adapter는 상태를
  저장하지 않으므로 두 값은 호출자가 조립해 넘긴다.
- 금액만으로 식별하지 않는다. 위 1번의 제외 단계 없이 금액만 비교하지 않는다.
- `cancelReason`에는 내부 식별자를 넣지 않는다. 사람이 읽는 사유만 보낸다.

### 보안과 관측

- secret key는 환경변수로만 주입하고 저장소·설정 파일·로그·metric tag·AuditRecord에 두지
  않는다. 원본 카드정보는 이 adapter를 통해서도 저장하지 않는다(BR-29, ADR-021).
- `billingKey`, `customerKey`, `paymentKey`와 Authorization 헤더는 로그와 trace에 남기지
  않는다. 실패 진단에는 정규화한 code와 `paymentId`만 사용한다.
- 요청은 ADR-006대로 DB transaction 밖에서 수행하고 connect/read timeout을 명시한다.
  timeout 초과는 `ProviderTransportFailure`이며 결과 불명 경로로 들어간다.

### 이 ADR이 정하지 않는 것

- provider-neutral inbox schema, PaymentMethod 상태 전이와 migration. ADR-079와
  `payment-method-token-management` ExecPlan이 소유한다.
- 라이브 전환, 결제위젯 연동과 규제 범위.

## Alternatives Considered

### 결제위젯·결제창 기반 일반결제

- 토스가 권장하는 신규 연동 방식이고 비정기 결제에 정책 제약이 없다.
- 클라이언트 결제창이 `paymentKey`를 만들고 서버가 `POST /v1/payments/confirm`으로
  확정하므로, 저장된 결제수단으로 서버가 승인을 시작하는 현재 흐름과 ADR-021의
  tokenReference 저장 모델을 바꿔야 한다. sandbox 검증 목적에 비해 변경 범위가 크다.

### scripted gateway를 HTTP mock 서버로 대체

- 실제 Provider 계약 없이 오류 code와 지연을 재현할 수 있다.
- 재현할 code와 동작을 우리가 정하므로, 검증하려던 "실제 Provider의 실제 응답"이라는
  가치가 사라진다.

### `cancelReason`에 환불 상관 토큰을 심어 조회한다

- 토스는 `cancelReason`을 `cancels` 항목에 그대로 되돌려주므로 port 확장 없이 모든
  경우에 결정적으로 판정할 수 있다. 같은 금액의 미해결 환불이 둘이어도 모호하지 않다.
- 내부 refund 식별자가 Provider 기록과 영수증 텍스트에 남는다. 이 저장소는 좌표·고객
  식별자·운영 사유를 외부 표현에서 반복 제거해 왔고(ADR-020, ADR-054, ADR-055), 사람이
  읽는 사유 필드를 내부 ID 전달 통로로 쓰는 것은 그 방향과 어긋난다.
- 차집합 규칙이 남기는 모호 케이스는 같은 Payment에 같은 금액의 미해결 환불이 동시에 둘
  이상 있을 때뿐이고, 그때는 `MANUAL_REVIEW`가 안전한 귀결이다.

### adapter를 만들되 결과 분류를 설정으로 외부화

- code 목록을 배포 없이 조정할 수 있다.
- ADR-038이 명시적으로 금지한다. 재시도 안전성은 코드 리뷰와 contract test를 거쳐야 한다.

## Rationale

포트는 이미 올바른 자리에 잘려 있어서 adapter 추가는 도메인·transaction 경계를 바꾸지
않는다. 남은 위험은 전부 "Provider의 실패를 어떻게 읽을 것인가"에 있고, 그 판단은
ADR-037/038이 이미 정한 예산과 fail-closed 원칙 위에서만 의미가 있다. 그래서 이 ADR은
연결 방법보다 분류표와 그 보수적 기본값을 결정의 중심에 둔다.

## Consequences

- 승인 UNKNOWN 복구, 부분 환불, 거절 환불 시나리오가 처음으로 실제 Provider 응답 위에서
  검증된다.
- PaymentMethod에 `provider_customer_reference` 저장이 추가되어 스키마 migration이 필요하지만,
  컬럼과 provider-neutral registration Port 계약은 결제수단 lifecycle ExecPlan이 소유한다. 이
  ADR의 adapter ExecPlan은 그 Port의 토스 빌링키 발급 구현을 포함하는 "외부 Provider 연동"에만
  해당하며 migration을 쓰지 않는다.
- 따라서 실행 순서는 결제수단 계약·구현이 먼저이고 이 adapter가 뒤다. 순서를 뒤집으면
  adapter 계획이 registration Port를 test 코드로 미리 짓게 되어 재작업이 확정된다.
- **선행 조건:** BR-29와 ADR-021의 저장 필드 목록 개정이 이 ADR과 함께 Accepted 되어야
  한다. 세 문서는 같이 승인하거나 같이 보류한다.
- 다음 문서가 이 ADR과 함께 갱신되어야 한다.
  - `docs/architecture/failure-semantics.md`의 "Production profile with mock PG" 행은
    mock만 다룬다. 외부 호출을 하는 sandbox adapter의 prod 금지를 함께 적어야 한다.
  - `docs/architecture/architecture-overview.md`의 "mock/fake Adapter는 test 또는 명시적
    local profile에서만 활성화한다"는 문장은 mock이 아닌 sandbox adapter의 profile 축을
    포함하지 않는다.
  - `docs/operations/local-demo-runbook.md`가 scripted local adapter를 "sandbox adapter"로
    부르는 표현은 이 ADR의 용어 분리에 맞춰 고친다.
- `RetryableFailed`가 공집합이므로 환불 실패는 terminal `Failed` 또는 `Unknown` 경로로만
  간다. ADR-038의 REQUEST 3회 예산은 이 adapter에서 실질적으로 1회로 동작한다.
- 코드의 `GatewayRefundResult.Failed`가 ADR-038 본문의 `TerminalFailed` 역할을 한다.
  이름 정합은 이 ADR의 범위가 아니며 필요하면 별도 minor decision으로 정리한다.
- 멱등키 15일과 보존 90일의 간극이 운영 절차로 남는다. 15일이 지난 미확정 건은 자동
  복구 대상이 아니다.
- sandbox 계정·키 관리가 새 운영 의존성이 된다.

## Verification

- `toss-sandbox`와 scripted gateway가 동시에 활성일 때 startup 실패
- `live_sk_` 키, secret key 부재, `prod` profile 조합에서 각각 startup 실패
- 승인 성공 시 `paymentKey` 보존과 금액·통화 tie-out
- `200`이지만 `status≠DONE` 또는 paymentKey 누락은 `Unknown`; 금액·통화 불일치는 adapter가
  실제 값을 담은 `Approved`로 올리고 Payment domain이 `RECONCILING`으로 전이해 reference를 보존
- 위 두 분류표의 각 행에 대한 adapter contract test
- 미등록 code가 승인에서 `Unknown`, 환불에서 `Unknown`으로 fail-closed
- `UNAUTHORIZED_KEY`가 `Declined`로 관측되지 않음
- 같은 `providerIdempotencyKey` 재요청 시 adapter가 키를 바꾸지 않음
- 승인 timeout 뒤 `GET /v1/payments/orders/{paymentId}`로 실제 승인 사실 확정
- `ALREADY_CANCELED_PAYMENT`가 `Succeeded`로 승격되지 않음
- 같은 Payment의 다건 부분취소에서 이미 매핑된 `transactionKey` 제외 후 단일 후보만
  `Succeeded`로 확정
- 취소 반영 전 조회의 빈 `cancels`가 `Failed`로 종결되지 않고 `Unknown`으로 남음
- 남은 후보 2건 이상에서 `Unknown`과 자동 성공 판정 부재
- 같은 `transactionKey`가 두 Refund에 매핑되지 않음
- 미해결 Refund가 있는 Payment의 새 Refund 접수가 거부되어 순차 가정이 유지됨
- 순차 다중 부분환불(매장 품절 → 고객 파손)에서 앞선 `SUCCEEDED` 건의 `transactionKey`가
  제외되고 현재 건만 확정됨
- secret key, `billingKey`, `customerKey`, `paymentKey`가 로그·metric tag·AuditRecord에 없음
- registration result의 brand/last4 검증과 expiry·내부 reference 비노출
- registration/deactivation 닫힌 Port 결과 전 행, allowlist 밖 code·5xx·timeout의 Unknown
- Provider 인증·계약 결함의 고객 거절 변환 부재와 raw message 비노출
- lifecycle scripted/Toss sandbox adapter의 profile 상호배타와 missing/multiple bean fail-start
- Provider 호출 구간에 DB transaction·connection 미유지(ADR-006)
- 명시적 결제수단 DELETE가 `DELETE /v1/billing/{billingKey}`를 호출하고 timeout을 성공으로
  분류하지 않음
- 인증된 `BILLING_DELETED` 중복 전달이 provider-neutral Port에서 한 번만 terminal 처리되고 미검증·
  malformed 요청과 inbox/mapping DB 저장 실패가 2xx로 응답되지 않음

## Metrics

- provider operation(`APPROVE|LOOKUP|CANCEL|LOOKUP_CANCEL`)별 결과 분류 count
- 정규화한 provider code별 count. raw message는 tag에 넣지 않는다.
- provider 호출 latency 분포와 timeout count
- `paymentId`, `billingKey`, `customerKey`, 멱등키는 metric tag에 넣지 않는다.
- **Not measured:** sandbox와 라이브의 지연·오류율 차이, 카드사별 응답 특성

## External Contract Sources

2026-08-09에 다음 토스페이먼츠 공식 문서를 확인했다. 구현 시 같은 URL의 현재 계약을 다시
확인하고 변경이 있으면 이 ADR/ExecPlan을 먼저 갱신한다.

- [Core API reference](https://docs.tosspayments.com/reference): authKey 기반 빌링키 발급,
  customerKey 요구, `DELETE /v1/billing/{billingKey}`와 empty 200 성공
- [Billing integration guide](https://docs.tosspayments.com/guides/v2/billing/integration): authKey의
  일회성, billingKey/customerKey mapping과 빌링키 재조회 부재
- [API error codes](https://docs.tosspayments.com/reference/error-codes): 빌링키 발급·승인과 인증
  오류의 HTTP/code 목록
- [Webhook guide](https://docs.tosspayments.com/guides/v2/webhook): `BILLING_DELETED`, 10초 내 200 ACK,
  실패 시 최대 7회·최초 전송부터 약 3일 19시간의 재전송 창

## Revisit Conditions

라이브 전환 또는 결제위젯 연동을 검토할 때, `BILLING_DELETED` 웹훅을 수신할 때,
sandbox 실측으로 무부수효과가 확인된 code를 `RetryableFailed`에 넣을 때,
토스 멱등키 유효기간 또는 자동결제 정책이 바뀔 때

## Related Decisions

- BR-26, BR-29
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
- [ADR-021](ADR-021-payment-method-tokenization.md)
- [ADR-079](ADR-079-payment-method-token-management.md)
- [ADR-037](ADR-037-customer-cancellation-refund-reconciliation-budget.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
