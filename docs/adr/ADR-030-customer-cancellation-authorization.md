# ADR-030: 고객 취소 인가와 보상 진행 조회 범위

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-029가 고객 취소의 기능 범위와 Order 모델을 확정했다. 남은 인가 문제는 세 가지다.

첫째, 타 고객 주문에 대한 취소 시도의 응답 코드가 정해지지 않았다. `GetOrderService`는
이미 `ACCESS_DENIED`(403)를 반환하고 `OrderControllerContractTest` 세 지점이 이를
단언하지만, `openapi/beanflow-v1.yaml`의 `cancelOrder`는 403과 404를 모두 정의해
계약만으로는 결정되지 않는다. `docs/api/api-conventions.md`는 존재 은닉이 필요한
리소스에 404를 허용하되 같은 operation 안에서 일관되게 적용하라고만 규정한다.

둘째, `docs/security/authorization-matrix.md`는 `고객 주문 취소`의 Platform Operator
권한을 `Approved operation`으로 표기하지만 운영자 취소 명령의 actor 모델, 사유 필수
규칙과 승인 절차가 어디에도 정의돼 있지 않다.

셋째, `PAID` 취소는 보상이 비동기이므로 진행 상태를 누가 어느 수준까지 조회하는지
정해야 한다. 매장 거절은 `GET /api/v1/store-orders/{orderId}`가 매장 구성원에게
`RejectionRecoverySummary`의 6개 step을 노출하는 선례를 이미 갖고 있으나 고객용
보상 조회 경로는 존재하지 않는다.

## Decision

- 취소 명령의 소유권 검증은 Application Service가 수행한다. JWT `subject`에서 파생한
  `customerId`와 Order의 `customerId`를 비교하며, Controller는 `hasRole('CUSTOMER')`
  역할 검사만 담당한다.
- 존재하지 않는 주문은 `404 RESOURCE_NOT_FOUND`, 타 고객 소유 주문은
  `403 ACCESS_DENIED`를 반환한다. 이는 `GET /api/v1/orders/{orderId}`의 기존 동작과
  같으며, 같은 리소스가 operation에 따라 다른 코드를 내지 않는다.
- 운영자와 매장 구성원의 고객 주문 취소 실행은 이번 범위의 Non-goal이다. 고객 취소
  endpoint는 `CUSTOMER` 역할만 허용하고 운영자·매장 role의 호출은 `403`이다.
  `authorization-matrix.md`의 `Approved operation`은 후속 Feature로 유지한다.
- 취소한 고객은 자기 주문의 취소 결과와 **보상 진행 요약**을 조회한다. 요약은
  `PaymentRecoverySummary`(`state`, `lastUpdatedAt`)이며 `state`는
  `NOT_REQUIRED`, `REQUESTED`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`,
  `RECONCILING`, `MANUAL_REVIEW`를 구분한다.
- `PENDING_PAYMENT` 취소의 요약 `state`는 항상 `NOT_REQUIRED`다. 외부 환불이
  존재하지 않는다는 사실을 고객 응답에서 명시적으로 표현하며 `SUCCEEDED`로 위장하지
  않는다.
- 보상 case의 step 단위 상태, `attemptCount`와 `lastErrorCode`는 운영자 전용이다.
  고객 응답에 step 배열, 내부 오류 코드, case 식별자와 정책 version을 노출하지
  않는다.
- 매장 구성원은 고객 취소된 주문의 취소 사실과 상태만 조회하고 결제 환불 진행
  상태는 조회하지 않는다. 매장은 자기 매장 주문의 운영 판단에 필요한 정보만 본다.
- `cancellation_detail`은 어떤 역할에게도 API로 노출하지 않는다. 운영자는 감사·운영
  경로에서만 접근한다.

## Alternatives Considered

### 타 고객 주문에 404 반환

- 주문 존재 여부를 은닉해 ID 열거 시도를 무의미하게 만든다.
- 그러나 일관성을 유지하려면 `GET /api/v1/orders/{orderId}`와 계약 테스트 세 곳,
  OpenAPI를 함께 바꿔야 하고 기존 클라이언트가 영향을 받는다. Order ID가 UUID이므로
  열거 위협이 낮아 변경 비용이 이득을 넘는다.

### 취소만 404, 조회는 403

- 변경 범위는 가장 작지만 같은 주문이 조회 403과 취소 404를 반환해 오히려 존재가
  드러나고, `api-conventions.md`의 operation 내 일관성 규칙에 위배된다.

### 운영자 취소를 이번에 함께 구현

- `authorization-matrix.md`와 코드가 즉시 일치하지만 운영자 actor 모델, 자유 입력
  사유 필수 규칙, 승인 절차, 감사와 멱등성 계약이 모두 추가되어 고객 취소의 범위를
  흐린다.

### 고객에게 step 상세 전부 노출

- 구현은 단순하지만 `PAYMENT`, `PICKUP`, `STOCK`, `COUPON`, `POINTS`,
  `CUSTOMER_NOTIFICATION` 내부 보상 구조와 오류 코드가 공개 계약이 되어 이후 변경이
  어려워진다.

### 고객에게 취소 결과만 노출

- 정보 최소화에는 가장 부합하지만 게시된 `Cancellation` 스키마에서 `required`
  `paymentRecovery`를 제거하는 계약 축소가 필요하고, 환불 지연·`MANUAL_REVIEW`
  상황을 고객이 API로 인지할 수 없어 알림 실패 시 상태를 알 방법이 없다.

## Rationale

403 유지는 기존 코드, 계약 테스트와 게시된 조회 동작이 이미 정한 선택을 존중하며
추가 변경 없이 operation 간 일관성을 만족한다. 운영자 취소 분리는 서로 다른 actor
모델과 승인 계약이 필요한 두 기능을 한 Feature에 섞지 않기 위함이다. 요약 노출은
매장이 거절 보상 진행을 조회하는 기존 선례와 대칭이면서, `state` 한 값만 노출해
내부 보상 구조를 계약에서 분리한다. 현재 게시된 `Cancellation` 스키마가 이미
`paymentRecovery`를 `required`로 두고 있어 계약 변경도 발생하지 않는다.

## Consequences

- 고객 앱은 `paymentRecovery.state` 하나로 환불 진행 표시를 구성할 수 있다.
- 보상 step 상세를 노출하는 운영자 조회 경로가 필요하다. 그 endpoint 계약은 후속
  API ADR이 소유한다.
- 매장 조회 응답에 결제 환불 진행이 포함되지 않으므로 매장 문의는 운영자 경로로
  넘어간다.
- `authorization-matrix.md`에 취소 결과 조회와 보상 step 조회 행을 추가한다.
- 운영자 취소가 미구현으로 남으므로 `ACCEPTED` 이후 취소 요구가 발생해도 우회
  경로가 존재하지 않는다. 이는 ADR-029의 금지와 일치한다.

## Failure Scenarios

- 소유권 검증을 Controller나 Repository 조회 조건에만 두면 다른 고객의 주문이
  `404`로 새어나가 operation 간 응답이 갈린다. Application Service 단일 지점에서
  검증한다.
- 보상 요약을 Order 상태에서 파생하면 `CANCELLED`를 환불 성공으로 오인해 표시한다.
  요약은 Refund와 보상 case의 실제 상태에서만 파생한다.
- `PENDING_PAYMENT` 취소에 `SUCCEEDED`를 반환하면 존재하지 않는 환불이 완료된 것으로
  보인다. `NOT_REQUIRED`로 구분한다.
- 운영자 전용 필드가 고객 응답 DTO에 실수로 포함되면 내부 오류 코드가 유출된다.
  응답 계약 테스트로 필드 집합을 고정한다.
- `cancellation_detail`이 응답에 포함되면 고객이 입력한 개인정보가 다른 역할에게
  노출된다.

## Verification

- 타 고객 주문 취소와 조회가 같은 `403 ACCESS_DENIED`를 반환한다.
- 고객 응답 필드 집합에 step 배열, `lastErrorCode`, `caseId`, `policyVersion`과
  `cancellation_detail`이 존재하지 않는다.
- `PENDING_PAYMENT` 취소 응답의 `paymentRecovery.state`가 `NOT_REQUIRED`다.

## Required Tests

- 존재하지 않는 주문 취소 `404 RESOURCE_NOT_FOUND`
- 타 고객 주문 취소 `403 ACCESS_DENIED`
- 같은 주문에 대한 조회와 취소의 응답 코드 일치
- `STORE_OWNER`, `STORE_STAFF`, `PLATFORM_OPERATOR` role의 고객 취소 endpoint 호출 `403`
- 인증 없는 취소 요청 `401`
- `PENDING_PAYMENT` 취소 응답 `paymentRecovery.state = NOT_REQUIRED`
- `PAID` 취소 응답이 환불 진행 요약을 반환하고 step 배열을 반환하지 않음
- 고객 응답 DTO에 `cancellation_detail`과 운영자 전용 필드 부재
- 운영자 조회가 6개 step, `attemptCount`와 `lastErrorCode`를 반환
- 매장 조회 응답에 결제 환불 진행이 포함되지 않음

## Metrics

- `beanflow.order.customer_cancellation.authorization.count{outcome}` —
  `OWNED`, `FORBIDDEN`, `NOT_FOUND` 분포
- `beanflow.order.customer_cancellation.recovery_state.count{state}` — 고객에게
  반환한 요약 state 분포
- **Not measured:** 실제 인가 실패율과 환불 요약 조회 빈도

Order, Store, Customer ID는 metric tag로 사용하지 않는다.

## Revisit Conditions

운영자 취소가 Accepted Business Policy로 확정되거나, 주문 ID 열거 위협이 확인되어
존재 은닉이 필요해지거나, 고객이 보상 진행을 더 세분해서 알아야 한다는 CS 요구가
측정될 때

## Related Decisions

- BR-14, BR-27, BR-30
- [ADR-027](ADR-027-store-membership-authorization.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
