# ADR-100: 점주 주문보드의 상태·픽업 시간 중심 Query

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Store order board](../exec-plans/active/productization-60-store-order-board.md)

## Context

현재 점주 주문 조회는 `GET /store-orders/{orderId}` 단건이고, 화면은 UUID 입력창이다. 실제 매장
업무는 "지금 접수해야 할 주문", "제조 중인 주문", "픽업 대기 주문"을 픽업 예정 시각 순으로 보는
것이다. 단건 조회로는 이 업무를 할 수 없다.

주문보드는 고객 주문 목록과 접근 패턴이 다르다.

| 축 | 고객 목록 | 점주 보드 |
|---|---|---|
| 범위 | 한 고객의 전체 이력 | 한 매장의 모든 실행 주문 |
| 정렬 | 최신순 | 수락 deadline 또는 픽업 예정 시각순 |
| 크기 | 시간이 갈수록 증가 | 대체로 수십 건 이하 |
| 갱신 | 사용자 조작 시 | 수 초 주기 |
| 인가 | 소유권 | 매장 membership |

같은 인덱스와 같은 Projection으로 두 요구를 만족시키려 하면 둘 다 나빠진다.

## Decision

점주 주문보드 전용 Query Repository를 둔다.

```text
StoreOrderBoardQueryRepository   매장·상태·픽업시간 기준 Projection (읽기 전용)
```

### 쿼리 계약

- 범위는 **매장 + 실행 상태**다. 픽업 날짜와 무관하게 `PAID`, `ACCEPTED`, `PREPARING`, `READY`를
  반환한다. `PENDING_PAYMENT`와 종료 상태는 포함하지 않고 완료·취소 이력은 별도 경로로 조회한다.
- API lane `PENDING_ACCEPTANCE`는 Domain `PAID`의 표현 이름이며 새 상태가 아니다. 이 lane은
  `(acceptance_deadline_at ASC, id ASC)`, 나머지 lane은
  `(pickup_window_start_snapshot ASC, id ASC)`로 정렬한다. 응답은 `pickupBusinessDate`로 그룹화한다.
- 두 정렬을 지원하는 인덱스 후보는 다음이다. 실제 실행계획으로 둘 다 검증한다.

```sql
CREATE INDEX ix_ordering_order_store_board
    ON ordering_order (store_id, state, pickup_window_start_snapshot, id);
CREATE INDEX ix_ordering_order_store_acceptance_board
    ON ordering_order (store_id, state, acceptance_deadline_at, id)
    WHERE state = 'PAID';
```

- 상태별 열은 서버가 분류해 반환한다. 클라이언트가 상태 문자열로 분기하지 않는다.
- 각 주문에 `allowedActions`를 포함한다([ADR-099](ADR-099-customer-order-read-model.md)와 같은 계약).
- 표시 필드는 `publicReference`, `pickupNumber`, 메뉴 요약, 픽업 예정 시각, 상태와 수락 phase
  (`OPEN | WARNING | TIMEOUT_PENDING`)다. 고객 개인정보와 결제 식별자는 포함하지 않는다.
- 인가는 매 요청 `StoreAccessOperations`로 확인한다
  ([ADR-027](ADR-027-store-membership-authorization.md)). 매장 목록을 인증 객체에 캐시하지 않는다.
- 조회는 매장 범위를 SQL predicate에 포함한다. 조회 후 필터링하지 않는다.

### 상태 전이의 동시성

- 두 직원이 동시에 같은 주문을 전이시키는 상황을 정상 경로로 다룬다.
- 전이 요청은 사용자가 보드에서 본 `expectedStatus`를 함께 보낸다. 서버는 action과
  `expectedStatus`의 조합을 먼저 검증하고, 조합 자체가 허용되지 않으면
  `422 ORDER_ACTION_NOT_ALLOWED`를 반환한다.
- 전이는 기존 Order row의 `PESSIMISTIC_WRITE` 잠금과 Aggregate 전이를 재사용한다. 잠금 뒤 실제 상태가
  `expectedStatus`와 다르면 경쟁 또는 stale 화면으로 보고 `409 ORDER_STATE_CONFLICT`를 반환한다.
  마지막 쓰기가 이기게 두지 않는다.
- 409를 받은 클라이언트는 목록을 재조회한다. 프론트엔드가 상태를 낙관적으로 확정하지 않는다.
- 같은 Idempotency-Key의 exact replay는 상태 비교보다 먼저 저장된 최초 응답을 재생한다.

`expectedStatus`는 별도 version token이 아니다. 각 action의 유일한 출발 상태를 명시해 경쟁 요청과
계약상 불가능한 action/status 조합을 구분하는 command precondition이다.

### 전이 응답

- 실행 중 주문의 `lane`과 `PAID`의 `acceptancePhase`만 값이 있다. `COMPLETE`와 `REJECT`처럼 전이 직후
  종료 상태가 된 응답은 `lane`과 `acceptancePhase`를 생략하고 `allowedActions=[]`를 반환한다.
- `REJECT`는 기존 보상 경계를 유지해 202를 반환할 수 있고, 응답에는 축약
  `compensationRecovery`만 포함한다. step, attempt, 내부 오류와 case 식별자는 포함하지 않는다.

### 조회 갱신

갱신 방식은 [ADR-102](ADR-102-polling-before-sse.md)를 따른다. 이 ADR은 Query 계약만 소유한다.

## Alternatives Considered

### 1. 고객 목록과 같은 Query Repository 공유

- 장점: 코드가 하나다.
- 단점: 정렬 키, 범위, 인가 축이 모두 다르다. 공유하면 파라미터 분기가 늘고 인덱스가 어느 쪽에도
  맞지 않는다.

### 2. Order Aggregate 목록 로딩

- 장점: 상태 전이 코드와 같은 모델을 쓴다.
- 단점: 보드는 초 단위로 조회된다. Aggregate 로딩을 초 단위로 반복하면 비용이 그대로 누적된다.

### 3. 보드 전용 비정규화 테이블 유지

- 장점: 조회가 가장 빠르다.
- 단점: 주문 상태 변경마다 동기화가 필요하고 불일치라는 새 실패 모드가 생긴다. 보드 크기가 수십
  건인 상황에서 정당화되지 않는다.

## Rationale

보드의 데이터 크기는 작고 조회 빈도는 높다. 이 조합에서는 인덱스가 잘 맞는 단순 쿼리가 가장
좋은 선택이다. 비정규화는 크기가 아니라 조인 복잡도가 문제일 때 쓰는 도구인데, 여기서는 스냅샷
컬럼([ADR-098](ADR-098-order-display-snapshots.md)) 덕분에 조인이 없다.

## Consequences

- 인덱스가 하나 추가된다. `state`가 자주 바뀌므로 인덱스 갱신 비용이 쓰기 경로에 더해진다.
  측정 대상이다.
- 상태 전이 API가 `orderReference` 경로로 바뀐다. 기존 `PATCH /store-orders/{orderId}/status`는
  전환 기간 동안 유지한다.
- 409 충돌이 정상 응답이 되므로 프론트엔드가 이를 오류 화면이 아니라 재조회로 처리해야 한다.
- command 계약에 `expectedStatus`가 추가되고, 불가능한 action/status 조합은
  `422 ORDER_ACTION_NOT_ALLOWED`로 분리된다.
- 완료·취소 주문의 매장 조회 경로가 별도로 필요하다. 이 ADR 범위 밖이며 P1이다.

## Verification

- 다른 매장의 주문이 목록에 나타나지 않는지, membership 없는 점주가 403인지 검증한다.
- `REVOKED` membership이 즉시 차단되는지 검증한다.
- 두 요청이 동시에 같은 주문을 전이시킬 때 하나만 성공하고 다른 하나가 409인지 검증한다.
- 잘못된 상태 전이 요청이 409 또는 422로 명확히 구분되는지 검증한다.
- `expectedStatus`가 같은 두 동시 요청 중 하나만 성공하고 패자는 409인지, action/status 조합 자체가
  불가능하면 422인지 검증한다.
- 활성 주문 50건 기준으로 보드 조회 SQL 수가 고정인지 검증한다.
- `EXPLAIN ANALYZE`로 인덱스 사용을 확인하고 인덱스 추가 전후를 같은 조건에서 비교한다.
- `allowedActions`가 실제 전이 성공 여부와 일치하는지 검증한다.

## Metrics

- 주문보드 조회 p50·p95·p99
- 조회 주기별 요청 수와 DB CPU
- 상태 전이 충돌(409) 비율
- 잘못된 전이 요청 수
- 매장 권한 거부 수
- 주문 생성부터 보드 노출까지의 시간

## Revisit Conditions

- 활성 주문 수가 커져 단순 조회로 목표 지연을 만족하지 못한다고 측정될 때
- 보드에 완료 이력과 통계를 함께 표시해야 할 때
- 상태 전이 충돌 비율이 실제 운영에서 문제가 될 때

## Related Decisions

- [ADR-099](ADR-099-customer-order-read-model.md)
- [ADR-098](ADR-098-order-display-snapshots.md)
- [ADR-102](ADR-102-polling-before-sse.md)
- [ADR-027](ADR-027-store-membership-authorization.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
