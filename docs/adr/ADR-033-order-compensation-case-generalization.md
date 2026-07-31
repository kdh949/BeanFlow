# ADR-033: 주문 보상 Case 일반화와 환불 요약 파생 원천

- **Status:** Accepted
- **Date:** 2026-07-31
- **Amended by:** ADR-038의 고객 환불 projection, ADR-050의 setup 손상 projection,
  ADR-059의 조건부 clean-cutover 전략

## Context

ADR-029가 미수락 `PAID` 고객 취소의 보상 대상을 매장 거절과 **동일**하다고 확정했다.
결제 환불, 확정 슬롯·재고 복원, 사용 쿠폰·포인트 복원, 고객 알림 여섯 가지다.

그러나 기존 보상 인프라는 거절 전용으로 고정돼 있다.
`V8__create_rejection_compensation.sql`의 `operations_rejection_compensation_case`는
`order_id`가 UNIQUE이고 `policy_version`이 NOT NULL FK이며 trigger 컬럼이 없다.
`operations_rejection_compensation_step`은 여섯 step type을 CHECK로 고정한다. 타입은
`RejectionCompensationCase/Step`, 매장 응답 필드는 `StoreOrderResult.rejectionRecovery`,
OpenAPI 스키마는 `RejectionRecoverySummary`/`RejectionRecoveryStep`이다.

또한 운영자용 보상 조회 endpoint가 존재하지 않는다. `RejectionCompensationOperations`
는 모듈 내부 API이고 현재 유일한 operations controller는 만료 혜택 정책뿐이다.

ADR-030이 "고객은 환불 진행 요약을 본다"고 정했으나 그 요약을 무엇에서 파생하는지는
정하지 않았다. 당시 공유 schema 이름은 `PaymentRecoverySummary`였고 그 `state`의
여덟 값은 `payment_refund.state`의
일곱 값에 `NOT_REQUIRED`를 더한 집합과 정확히 일치하고, 보상 step 어휘와는 다르다.
step에는 `RETRY_SCHEDULED`가 있고 `REQUESTED`, `FAILED`, `RECONCILING`이 없다.

## Decision

- 거절 전용 보상 Case를 **주문 보상 Case로 일반화**한다. 전용 취소 Case를 신설하지
  않는다.
- 이름을 `OrderCompensation` 계열로 통일한다.

  | 대상 | 변경 전 | 변경 후 |
  |---|---|---|
  | 테이블 | `operations_rejection_compensation_case` | `operations_order_compensation_case` |
  | 테이블 | `operations_rejection_compensation_step` | `operations_order_compensation_step` |
  | 모듈 API | `RejectionCompensationOperations` | `OrderCompensationOperations` |
  | 타입 | `RejectionCompensationCaseView` 외 | `OrderCompensationCaseView` 외 |
  | OpenAPI | `RejectionRecoverySummary` | `CompensationSummary` |
  | OpenAPI | `RejectionRecoveryStep` | `CompensationStep` |
  | 매장 응답 | `StoreOrderResult.rejectionRecovery` | `StoreOrderResult.compensationRecovery` |
  | OpenAPI | 매장용 축약 schema 없음 | `StoreCompensationSummary` |

- Case에 `trigger` 컬럼을 추가한다. 초기 값 집합은 `STORE_REJECTION`과
  `CUSTOMER_CANCELLATION`이며 CHECK로 강제하고 `CompensationSummary.trigger`로
  노출한다.
- 여섯 step type, case·step 상태 집합, `(case_id, step_type)` UNIQUE,
  `order_id`·`event_id`·`source_reference` UNIQUE, bounded retry와 `MANUAL_REVIEW`
  종결 규칙을 두 trigger가 **그대로 공유**한다. 실패·재시도 로직을 두 벌로 만들지
  않는다.
- `order_id` UNIQUE를 유지한다. 한 주문은 `REJECTED`와 `CANCELLED` 중 하나에만
  도달하므로 두 trigger의 Case가 같은 주문에 공존하지 않는다.
- 운영자 조회 endpoint는 `GET /api/v1/operations/orders/{orderId}/compensation`이며
  `OperatorCompensationView`를 반환한다. `CompensationSummary`와 operator-only
  setup issue/ReprocessingCase reference를 감싸며 step 상태, `attemptCount`,
  `lastErrorCode`와 setup detail은 이 경로에서만 노출한다.
- **Store projection amendment (2026-08-01):** `CompensationSummary`는 공용
  schema가 아니라 `OperatorCompensationView` 전용이다. 매장 응답
  `StoreOrderResult.compensationRecovery`는 `trigger`, case `state`와
  `updatedAt`만 담은 별도 `StoreCompensationSummary`를 사용한다. 기존 거절
  응답이 여섯 step과 `attemptCount`·`lastErrorCode`·`caseId`를 매장에 노출하던
  것은 authorization matrix의 "주문 보상 case step 상세 조회 = 매장 No"와 이
  ADR의 Verification을 위반하는 구현이므로 clean cutover에서 함께 축약한다.
  매장은 거절과 고객 취소를 `trigger`로 구분하고 보상이 진행 중인지 확인할 수
  있으며, 내부 보상 구조와 오류 code는 공개 계약이 되지 않는다.
- 고객에게 반환하는 `CancellationRefundRecoverySummary.state`는 **이번 고객 취소 source의
  Refund 한 건에서만** 파생한다. 선행 부분 환불을 포함한 다른 Refund의 상태나 보상
  case PAYMENT step을 합성하지 않는다.
- ADR-038에 따라 내부 Refund state를 고객에게 그대로 통과시키지 않고 Payment
  Context의 단일 customer projection을 적용한다. 자동 복구 상태는 `PROCESSING`,
  내부 `FAILED`·`MANUAL_REVIEW`는 `PROCESSING + REFUND_DELAYED`로 표현한다.
- 요청액이 0인 경우에만 `NOT_REQUIRED`다. 요청액이 양수인데 Refund 또는 필수
  recovery snapshot이 없으면 내부 `SETUP_INCOMPLETE`이며, ADR-050에 따라 고객
  projection은 `PROCESSING + REFUND_DELAYED`, 운영자 조회는 실제 setup issue다.
- `BENEFIT_ONLY` 취소도 여섯 step을 모두 만들며 PAYMENT step은 Tx C1부터
  `NOT_REQUIRED`, attempt 0, error null이다. Refund는 만들지 않는다(ADR-039).
- 파생 로직은 Payment Context가 소유하는 단일 조회 지점에 둔다. `Cancellation` 응답과
  `Order.paymentRecovery`가 같은 값을 반환한다.
- `CancellationRefundRecoverySummary`는 `approvedAmountKrw`,
  `succeededRefundAmountBeforeCancellationKrw`,
  `cancellationRequestedRefundAmountKrw`, `remainingRefundableAmountKrw`를 함께
  반환한다. 앞의 세 금액은 Tx C1 snapshot이고 마지막 금액은 조회 시점의 성공 Refund
  합계로 동적 계산한다.
- ADR-059가 이 일반화를 pre-release clean cutover로 개정했다. 기존 production
  Case·publication·외부 consumer가 없음을 release gate에서 확인한 뒤 migration
  source가 최종 OrderCompensation schema를 직접 만들고 개발·테스트 DB를
  재생성한다. forward rename과 legacy row backfill은 만들지 않는다.

## Alternatives Considered

### 고객 취소 전용 Case 신설

- 거절 경로 회귀 위험이 0이고 두 흐름의 step 구성을 독립적으로 바꿀 수 있다.
- 그러나 ADR-029가 보상 대상을 동일하다고 확정했으므로 step이 갈릴 이유가 없고,
  worker·bounded retry·`MANUAL_REVIEW` 종결·publication 실패 처리가 두 벌이 되어
  운영 규칙이 서서히 어긋난다. 운영자도 두 endpoint를 봐야 한다.

### 이름을 유지하고 `trigger` 컬럼만 추가

- migration과 기존 테스트 변경이 가장 적지만 취소 보상이 `rejection_compensation`
  테이블에 저장되어 이름과 내용이 어긋난다. 저장소가 유지해 온 ubiquitous language를
  훼손한다.

### `paymentRecovery.state`를 보상 case의 PAYMENT step에서 파생

- 보상 진행을 한 곳에서 읽을 수 있지만 step 어휘가 달라 매핑 표가 필요하고
  `REQUESTED`, `FAILED`, `RECONCILING` 구분이 손실된다. 환불 사실의 source of truth가
  Refund라는 상태 머신 문서와도 어긋난다.

### Refund와 step을 합성

- 모든 경우를 답하지만 파생 규칙이 두 개가 되어 테스트와 장애 조사가 어려워진다.

## Rationale

두 흐름의 보상 대상이 같다는 것이 이미 확정된 사실이므로, 구조를 나누면 중복만 남고
갈라질 위험이 커진다. `order_id` UNIQUE가 상태 배타성 덕분에 그대로 성립해 일반화
비용이 예상보다 작다. 요약 원천을 Refund로 고정하고 customer projection을 한 곳에
두면 환불 성공 여부를 보상 진행 상태가 덮어쓰지 않으면서 내부 복구 상세도 고객에게
노출하지 않는다.

## Consequences

- clean cutover와 함께 `StoreOrderResult`, OpenAPI 스키마, 모듈 API 타입명이
  바뀐다. 기존 거절 회귀 테스트를 새 schema에서 함께 갱신해야 한다.
- 매장 응답에서 step 배열·`attemptCount`·`lastErrorCode`·`caseId`·policy version이
  사라지므로 매장 화면은 진행 여부만 표시하고 상세 문의는 운영자 경로로 넘긴다.
- 운영자 조회 endpoint가 신설되어 `PLATFORM_OPERATOR` 인가가 추가된다.
- `CompensationSummary.trigger`가 노출되므로 매장·운영자 화면이 거절과 취소를
  구분해 표시할 수 있다.
- 환불 요약과 보상 step 상태가 서로 다른 값을 보일 수 있다. 예를 들어 Refund가
  `SUCCEEDED`인데 PAYMENT step 갱신이 지연되면 두 값이 일시적으로 갈린다. 이는
  의도된 것이며 고객에게는 Refund 값이 정답이다.
- 선행 Refund가 여러 개여도 state는 고객 취소 Refund만 따르고, 전체 Refund 집합은
  성공 금액 합계를 계산할 때만 사용한다.
- ~~단일 `policy_version`이 NOT NULL FK이므로 고객 취소 Case의 정책 snapshot
  구조가 미결이었다.~~ ADR-041이 Case당 COUPON·POINTS 두 immutable policy version
  FK child row를 항상 저장하도록 확정했다. 단일 Case policy 컬럼은 신규 source로
  사용하지 않는다.

## Failure Scenarios

- pre-release release gate가 기존 row/publication을 놓치면 clean cutover가 실제
  보상 data를 이행하지 못한다. 하나라도 발견하면 배포를 중단하고 forward migration
  결정으로 전환한다.
- 두 trigger가 `order_id` UNIQUE를 공유하므로, 취소와 거절이 동시에 Case를 열려 하면
  하나가 실패한다. Order의 guarded transition이 이미 하나만 성공시키므로 정상
  경로에서는 발생하지 않지만, 실패 시 Order 상태를 신뢰하고 Case 생성을 재시도하지
  않는다.
- 고객 응답이 PAYMENT step에서 파생되면 `RETRY_SCHEDULED`처럼 `CancellationRefundRecoverySummary`
  enum에 없는 값이 새어나가거나 `FAILED`가 은폐된다.
- Refund가 필요한데 record 또는 recovery snapshot이 없으면 `NOT_REQUIRED`로
  위장하지 않고 내부 `SETUP_INCOMPLETE`, setup ReprocessingCase와 운영 alert를
  남긴다. 고객에게는 ADR-050의 지연 projection만 반환한다.
- 여러 Refund 중 최신 row를 고르거나 state precedence를 합성하면 선행 부분 환불의
  실패·불명 상태가 이번 취소 진행으로 잘못 보일 수 있다.

## Verification

- 거절과 취소가 같은 테이블·step 구성·retry 규칙을 사용하고 `trigger`로만 구분된다.
- 운영자 endpoint만 step 상세를 반환하고 고객·매장 응답에는 없다.
- 고객 요약이 항상 Refund 상태와 일치한다.

## Required Tests

- empty database 전체 migration 후 기존 거절 보상 통합 테스트 통과
- release gate의 기존 case/publication/external consumer 0 확인과 nonzero 차단
- 취소 Case와 거절 Case가 같은 step 집합과 retry schedule을 사용
- `trigger` 미허용 값 CHECK 위반 거부
- 같은 주문에 두 trigger Case 생성 시도 시 UNIQUE 위반
- 운영자 endpoint의 `PLATFORM_OPERATOR` 인가와 타 role `403`
- 매장 응답에 step 배열·`attemptCount`·`lastErrorCode`·`caseId`·policy version 부재
- 매장 응답의 `trigger`·case `state`·`updatedAt` 존재와 거절·취소 구분
- 존재하지 않는 주문 보상 조회 `404`
- 환불 필요액 0인 취소의 요약 `NOT_REQUIRED`
- 환불 필요액 양수인데 Refund/snapshot이 없는 취소의 고객 지연 projection과 운영
  setup issue
- 내부 Refund state별 고객 `PROCESSING`·`REFUND_DELAYED` projection
- 선행 Refund 상태가 고객 취소 Refund state를 바꾸지 않음
- 네 금액 필드의 Tx C1 snapshot·조회 시점 계산
- PAYMENT step과 Refund 상태가 갈린 상황에서 고객 요약이 Refund를 따름
- `Cancellation` 응답과 `Order.paymentRecovery`의 값 일치

## Metrics

- `beanflow.order.compensation.case.count{trigger,state}`
- `beanflow.order.compensation.step.count{trigger,type,state}`
- `beanflow.order.compensation.lag{trigger}`
- `beanflow.payment.refund.summary_divergence.count` — Refund 상태와 PAYMENT step
  상태가 갈린 관측 횟수

Order, Store, Customer ID는 metric tag로 사용하지 않는다.

## Revisit Conditions

두 trigger의 보상 대상이 실제로 달라지거나, 한 주문에 복수 보상 Case가 필요해지거나,
운영 보상 목록 조회가 order 단위 조회로 부족해질 때

## Related Decisions

- BR-06, BR-14
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-028](ADR-028-expired-benefit-restoration-policy.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-030](ADR-030-customer-cancellation-authorization.md)
- [ADR-031](ADR-031-customer-cancellation-api-contract.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
- [ADR-039](ADR-039-benefit-only-cancellation-payment-step.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
