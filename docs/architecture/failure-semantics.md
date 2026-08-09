# Failure Semantics

## Principle

BeanFlow는 실패한 의존성이나 작업을 암묵적 fallback으로 숨기지 않는다. 모든 실패는 API 오류, 도메인 상태, health 상태, metric, retry 상태 또는 운영자 case 중 하나 이상으로 관측 가능해야 한다.

## Failure classes

### Required startup dependency

Examples:

- DB 설정 누락
- 유효하지 않은 signing key
- 필수 Provider credential 누락
- 운영 profile에서 fake PG 활성화

Behavior:

- 애플리케이션 시작 실패
- secret을 노출하지 않는 실행 가능한 오류 메시지
- 부분 구성 상태로 서비스 시작 금지

### Request-critical dependency

Examples:

- 주문 생성 중 DB 장애
- 재고 예약 저장 실패
- 결제 승인 요청 결과 불명

Behavior:

- business success를 반환하지 않음
- 필요한 경우 `UNKNOWN` 또는 `RECONCILING` 상태 보존
- 문서화된 API error 또는 pending/unknown 응답
- correlation ID 제공
- 외부 결과가 불명확하면 reconciliation 생성

#### PaymentMethod registration and deactivation

- registration/deactivation Port는 성공, contract-test로 무부수효과가 확인된 거절, 결과불명,
  설정 결함을 닫힌 결과로 분리한다. 미등록 code·5xx·timeout·파싱 실패·필수 성공값 누락은
  `Unknown`이며 raw Provider message를 공개 오류나 로그로 전달하지 않는다.
- registration claim 뒤 결과가 불명확하면 일회성 authKey를 다시 보내거나 token·표시값을
  추정하지 않는다. lookup 없는 Toss 결과와 stale interrupted claim은 추가 외부 호출 없이 같은
  result/recovery transaction에서 즉시 `MANUAL_REVIEW`로 종결하고 고객에게는
  `PROCESSING + REGISTRATION_DELAYED`만 투영한다.
- deactivation Tx D1 commit부터 새 결제를 차단한다. claim 뒤 결과불명은 DELETE를 다시 보내거나
  not-found를 성공으로 간주하지 않고 검증된 `BILLING_DELETED`를 최대 96시간 기다린다. 기한 뒤
  `MANUAL_REVIEW`이며 `ACTIVE` fallback과 hard delete는 없다.
- credential·인증·계약·필수 설정 결함은 고객 거절이 아닌
  `PAYMENT_METHOD_PROVIDER_UNAVAILABLE` 또는 startup failure다. required Port 부재·다중 adapter와
  profile 충돌을 scripted/fake/no-op으로 대체하지 않는다.

#### Immutable settlement input

- Order 생성은 applicable Merchant fee-contract version, Promotion reservation의 final
  platform/store coupon legs와 Loyalty reservation의 immutable issuer allocation을 모두
  검증한 뒤 `OrderSettlementInputSnapshot`을 같은 local transaction에 저장한다.
- owner source가 없거나 ambiguous하고, share/allocation/formula/hash가 맞지 않거나 snapshot
  저장이 실패하면 `503 SETTLEMENT_INPUT_UNAVAILABLE`로 Order와 모든 owner reservation을
  rollback한다. fee 0, platform burden, 현재 Campaign/PointLot 또는 부분 snapshot으로
  대체하지 않는다.
- `OrderCompletedV2` factory는 persisted snapshot과 matching approved Payment fact만 받는다.
  approval amount/currency/source/time 또는 monetary tie-out이 다르면 event를 만들지 않는다.
  Plan 15는 이 검증까지만 제공하며 outbox 저장·V1→V2 activation과 Settlement consumer 실패
  처리는 Plan 20의 별도 transaction 경계다.

#### Immutable Refund and Loyalty publication

- Refund `SUCCEEDED` result는 immutable request/success allocation, Plan 15 settlement snapshot,
  exact logical source와 `PaymentRefundedV1` target publication을 같은 local transaction에서
  저장한다. 이 중 하나라도 없거나 invalid하고, tie-out 또는 target row 저장이 실패하면 Refund
  result owner transaction을 rollback한다. Provider 성공을 새 성공 event나 API success로 추정하지
  않고 동일 Provider key lookup/reconciliation으로 수렴한다.
- Loyalty gross `ACCRUAL`과 각 restoration owner result는 각각 `PointsAccruedV1` 또는
  `PointsRestoredV1` target publication과 함께 commit한다. publication failure를 0원 적립·복원,
  빈 event 또는 no-op success로 바꾸지 않는다. Refund 복원 transaction rollback 뒤 Payment work는
  `RETRY_SCHEDULED`, bounded retry 후 `MANUAL_REVIEW` 의미를 보존한다.
- 동일 source/version/동일 payload replay는 기존 owner result와 publication 한 벌을 반환한다.
  같은 source/version의 changed payload는 기존 row를 갱신하지 않고 conflict로 실패한다.
- Settlement/Analytics consumer는 별도 후속 local transaction이다. Plan 20 이후 Settlement의
  `OrderCompletedV2`와 고객 취소 `PaymentRefundedV1` target은 실제 listener가 처리하지만 Analytics
  target과 completed/pre-completion Refund 조정은 후속 owner가 활성화할 때까지 성공으로 추정하지
  않는다. 미완료 target row는 downstream 성공이 아니다.
- 고객 취소 Refund 제외는 실제 Order `CUSTOMER_REQUEST`, Refund `SUCCEEDED`와 exact source/version,
  Item 부재가 모두 맞고 append-only Audit이 commit된 경우만 `NOT_APPLICABLE`이다. 누락·불일치·기존
  Item 또는 Audit 실패는 `SETTLEMENT_SOURCE_CONFLICT` 메시지의 retry/manual-review failure이며
  0원 Adjustment나 no-op success로 바꾸지 않는다.

### Asynchronous side effect

Examples:

- 준비 완료 알림 실패
- 이벤트 publication worker 장애
- Analytics projection 실패

Behavior:

- 이미 완료된 원본 거래를 부수효과 실패만으로 롤백하지 않음
- `RETRY_SCHEDULED`, `FAILED`, `MANUAL_REVIEW` 같은 영속 상태
- retry count와 last failure 노출
- 원본 거래 성공을 부수효과 성공으로 간주하지 않음

#### Source-aware event convergence

- 같은 owner source reference의 중복·지연 event는 기존 진행 또는 완료 상태를
  반환하고 새 부수효과와 attempt를 만들지 않는다.
- 아직 적용 가능한 owner 상태에는 부수효과를 한 번만 적용한다.
- 다른 source·trigger·aggregate version이 owner 상태를 이미 점유했거나 현재 상태가
  event와 모순이면 원하는 terminal 상태가 같더라도 성공으로 간주하지 않는다.
- 충돌 상태를 덮어쓰지 않고 `COMPENSATION_SOURCE_CONFLICT`로 publication을
  실패시켜 bounded retry와 `MANUAL_REVIEW`로 보낸다.
- Pickup·Stock의 `RELEASED_AFTER_TERMINATION`도 동일 source reference와 동일
  `restoration_trigger`일 때만 멱등 성공이다. 다른 source 또는 trigger는 terminal
  상태가 같아도 충돌이며 수량·원인을 덮어쓰지 않는다.
- Coupon·Points owner도 source reference, restoration trigger와 policy version ID가
  모두 같을 때만 멱등 성공이다. 결과 disposition이 같아도 metadata가 다르면
  `COMPENSATION_SOURCE_CONFLICT`이며 기존 issuance·lot·잔액·원장을 바꾸지 않는다.
- 보상 CouponIssuance의 terms snapshot이 없거나 불완전하면 live Campaign 또는
  기본값으로 fallback하지 않는다. COUPON owner transaction을 실패시켜 publication
  retry와 해당 step `MANUAL_REVIEW`로 보낸다.
- 비동기 owner 충돌 때문에 이미 확정된 Order terminal 상태를 되돌리지 않는다.
- listener publication retry가 소진되면 실패 listener에 대응하는 보상 step만
  `MANUAL_REVIEW`로 전환한다. 실패하지 않은 owner step을 함께 실패 처리하거나 자동
  처리를 중단하지 않는다.
- publication completion attempt와 owner business attempt를 분리해 기록한다.

#### Paid customer cancellation commit gate

- `202`를 반환하기 전에 Order 취소, 취소 멱등 응답, 주문 보상 Case, 필요한 Refund
  `REQUESTED`, 취소 접수 NotificationDelivery `PENDING`, AuditRecord와 네 owner
  영속 event publication이 한 로컬 transaction으로 commit돼야 한다.
- `CUSTOMER_CANCELLATION × COUPON/POINTS` policy head 또는 version이 없거나 Case의
  두 FK snapshot과 event 전체 snapshot이 일치하지 않으면 필수 설정·commit-gate
  손상이다. fallback policy나 최신 head 추측 없이 transaction을 rollback하고
  `503 DEPENDENCY_UNAVAILABLE`로 실패한다.
- 위 저장 중 하나라도 실패하면 전체 rollback하고 business success를 반환하지 않는다.
- `PENDING_PAYMENT` 취소도 접수 NotificationDelivery 저장 실패 시 Order와 네 예약
  해제를 함께 rollback한다. 두 상태 모두 Provider 발송은 transaction 밖에서
  수행하고 commit 후 발송 실패로 취소를 되돌리지 않는다.
- rollback된 요청은 취소 멱등 레코드를 남기지 않으며 같은 key 재시도가 명령을 다시
  실행한다.
- commit 후 owner listener나 Provider가 실패해도 Order `CANCELLED`를 되돌리지 않고
  publication, Refund와 compensation step의 retry·unknown·manual review 상태로
  보존한다.
- 취소 요청 환불액이 양수인데 고객 취소 Refund 또는 Payment recovery snapshot이
  없으면 `NOT_REQUIRED`로 대체하지 않고 내부 `SETUP_INCOMPLETE`, setup
  ReprocessingCase와 운영 alert를 남긴다. 고객에게는
  `PROCESSING + REFUND_DELAYED`로 투영하고 검증할 수 없는 금액을 0이나 현재값으로
  추정하지 않는다.
- 결과 불명 또는 진행 중인 선행 Refund가 있으면 새 고객 취소 Refund를 추측해 만들지
  않고 Order 전이 전에 `409 PAYMENT_REFUND_UNRESOLVED`로 거부한다. Provider/DB lock
  장애는 이 business conflict로 바꾸지 않고 503으로 노출한다.
- 고객 취소 Refund의 최초 Provider 요청 결과가 불명확하면 요청을 재전송하지 않는다.
  같은 key로 10초, 30초, 2분, 5분, 15분 뒤 최대 다섯 번 조회하고, 최초 요청을
  포함한 여섯 번째 Provider 상호작용 뒤에도 불명이면 `MANUAL_REVIEW`로 전환한다.
  마지막 허용 claim이 결과 저장 전에 끊기면 lease 만료 뒤 추가 호출 없이 수동
  검토로 종결한다.
- Provider가 부수효과 없음과 같은 key 재실행 안전을 보장하고 adapter 코드의
  Provider별 allowlist에 포함된 명시 실패만 10초·30초 뒤 같은 key REQUEST로
  재시도한다. 미등록 code 또는 세 번째 retryable failure는 Refund `FAILED`,
  PAYMENT step과 Case `MANUAL_REVIEW`다.
- 고객에게는 내부 자동 재시도·불명·수동 검토 상태와 실패 code를 노출하지 않는다.
  자동 처리 중에는 `PROCESSING`, 내부 `FAILED`·`MANUAL_REVIEW`에는
  `PROCESSING + REFUND_DELAYED`를 반환한다. 운영자 조회에는 실제 상태와 원인을
  유지한다.
- 고객 취소 Refund의 실제 성공 또는 자동 처리 종료 지연은 Payment result
  transaction에서 전용 영속 event와 Notification publication을 함께 commit한다.
  publication 저장 실패를 무시하지 않는다. 외부 Provider 성공 뒤 이 result
  transaction이 rollback되면 새 REQUEST가 아니라 동일 key reconciliation으로
  수렴한다. commit 뒤 Notification listener 실패는 Refund를 되돌리지 않고
  publication retry와 ReprocessingCase로 남긴다.

### Optional capability

fallback은 제품이 명시적으로 degraded mode를 지원할 때만 허용한다.

필수 조건:

- 이름 있는 fallback policy
- 명확한 활성화 조건
- 응답 또는 health에 degraded 상태 표시
- metric과 structured log
- 활성화·복구 자동 테스트
- Accepted ADR

## Forbidden patterns

- catch-all exception 후 성공 응답
- 실패를 `0`, 빈 목록, null 또는 stale cache로 정상처럼 반환
- DB 장애 시 in-memory repository 자동 전환
- Provider 설정 누락 시 fake/local Adapter 자동 전환
- 이벤트 발행 실패를 삼키고 완료 처리
- timeout을 확정 실패로 단정
- HTTP 200을 반환하고 로그만 남김
- 기본 credential 또는 secret 사용
- 알림 실패 때문에 주문 완료를 롤백
- 캐시 장애를 cache miss와 동일하게 취급하고 관측하지 않음

## BeanFlow examples

| Situation | Required behavior | Forbidden behavior |
|---|---|---|
| PG timeout | `Payment.UNKNOWN`, reconciliation | 실패 확정 또는 성공 반환 |
| PG success, DB write fail | 운영 복구 case와 Provider 조회 | 새 결제 자동 승인 |
| Lease expiry, late PG approval | Order `EXPIRED` 유지, idempotent void/refund reconciliation | Order·예약 복구 또는 승인 성공 은폐 |
| Refund timeout | Refund `UNKNOWN`, reconciliation | 환불 성공액에 포함 또는 새 환불 중복 호출 |
| Partial-refund Loyalty write/ack failure | Payment restoration work `RETRY_SCHEDULED`, bounded retry 후 `MANUAL_REVIEW`; exact Refund source replay | 현금 성공 rollback, 포인트 0/성공 추정, inline no-op/fake 복원 |
| Notification provider fail | Order `READY`, Delivery retry state | Delivery 성공 처리 |
| DB unavailable | request/readiness failure | local DB 전환 |
| Redis unavailable | Accepted ADR에 정의된 동작 | local Map 전환 |
| Outbox worker down | Outbox pending과 alert | published로 간주 |
| Required env missing | startup failure | 임의 기본값 |
| Production profile with mock PG | startup failure | mock으로 계속 실행 |
| 감사형 point adjustment debit 부족 | 409, Account/Lot/원장/Audit rollback | 부분 차감·음수 잔액·pending 생성 |

## Test requirements

새 failure path는 다음을 검토한다.

- 실패가 사용자에게 어떤 상태·오류로 보이는가
- DB에 어떤 상태가 남는가
- 재시도가 같은 부작용을 만들지 않는가
- 운영자가 어떻게 발견하고 복구하는가
- metric, log와 correlation이 존재하는가
- fallback이 활성화되지 않았음을 어떻게 검증하는가
