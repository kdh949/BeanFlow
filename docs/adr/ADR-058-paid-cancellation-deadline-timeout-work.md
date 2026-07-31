# ADR-058: 기한 후 PAID 고객 취소의 즉시 timeout work

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-029는 고객 취소와 매장 수락·timeout이 Order row lock에서 경쟁하고 정확히
acceptance deadline부터 시간 기반 timeout이 이기도록 정했다. 그러나 deadline이 지난
`PAID` 취소는 자동 거절을 materialize하지 않고 `409 ORDER_STATE_CONFLICT`만
반환한다. 주기 worker가 실행되기 전까지 Order가 잠시 `PAID`로 조회될 수 있다.

고객 취소 endpoint가 store-timeout 거절 전체를 직접 실행하면 요청 책임, actor,
reason과 환불·보상 event가 고객 취소와 섞인다. 반대로 주기 worker만 기다리면 고객이
이미 기한 경계를 발견했는데도 처리가 scan 주기에 종속된다.

## Decision

### Cancellation deadline branch

- 고객 취소가 Order를 잠근 뒤 상태가 `PAID`이고
  `now >= acceptanceDeadlineAt`이면 고객 취소를 실행하지 않는다.
- 같은 짧은 transaction에서 `AcceptanceTimeoutWork`와 target AuditRecord를 내구
  저장하고 commit한 뒤 `409 ORDER_STATE_CONFLICT`를 반환한다.
- 이 transaction은 Order 상태, 취소 field, cancellation IdempotencyRecord,
  OrderCompensationCase, Refund, NotificationDelivery와 termination event를 만들지
  않는다.
- work source는
  `order:{orderId}:acceptance-timeout:{acceptanceDeadlineAt}`이고 Order당 해당
  deadline UNIQUE다. 같은 또는 다른 cancellation key와 주기 scanner가 같은 work를
  요청해도 한 row로 수렴한다.
- work 생성 Audit는 SYSTEM actor, action `ACCEPTANCE_TIMEOUT_WORK_REQUESTED`,
  target work ID, reason `ACCEPTANCE_DEADLINE_REACHED`를 사용하고 원 고객 요청
  correlation을 보존한다. 고객 cancellation detail과 key는 기록하지 않는다.
- work 또는 Audit 저장이 실패하면 transaction을 rollback하고
  `503 DEPENDENCY_UNAVAILABLE`를 반환한다. timeout 처리를 요청하지 못했는데 409
  winner가 내구 기록됐다고 말하지 않는다.
- 이 409는 고객 취소의 terminal 멱등 response로 저장하지 않는다. 같은 key 재요청은
  Order 상태를 다시 판정하고, work unique insert는 새 side effect를 만들지 않는다.

### Timeout worker

- commit 뒤 Ordering timeout worker를 즉시 깨우되 in-memory wakeup은 latency
  optimization일 뿐이다. durable work table과 기존 periodic scan이 재시작 복구의
  근거다.
- `AcceptanceTimeoutWork` 상태는 `PENDING`, `CLAIMED`, `COMPLETED`,
  `MANUAL_REVIEW`다. claim lease와 bounded retry를 사용한다.
- worker는 work를 claim한 뒤 기존 store-timeout rejection Application Service를
  호출한다. 서비스는 Order를 다시 잠그고 상태·deadline을 재검증한 뒤
  `OrderRejectedV1`, Refund·보상 Case·owner publication과 timeout Audit를 기존
  transaction 경계로 만든다.
- Order가 이미 같은 timeout source로 `REJECTED`면 work는 `COMPLETED`로 멱등
  수렴한다.
- 다른 정상 winner가 이미 terminal 상태를 만들었으면 실제 source를 검증한다.
  deadline 전에 `ACCEPTED`된 정상 상태라면 work를 `COMPLETED_NOT_APPLICABLE` 의미로
  완료한다. source·시각이 모순이면 성공으로 덮지 않고 `MANUAL_REVIEW`다.
- periodic due-order scanner와 즉시 work worker가 경쟁하면 Order lock, guarded
  transition, Case/refund/source Unique Constraint가 하나의 timeout rejection만
  허용한다.
- work retry 소진은 `ACCEPTANCE_TIMEOUT_WORK` ReprocessingCase와
  `MANUAL_REVIEW`를 남긴다. Order를 고객 취소로 바꾸거나 성공 거절로 위장하지 않는다.

### Retention and observability

- terminal work는 완료 시점부터 90일 보존하고 bounded keyset worker로 정리한다.
  `PENDING`, `CLAIMED`, `MANUAL_REVIEW` work는 자동 삭제하지 않는다.
- metric/log에는 state, outcome, age와 lag만 두고 Order/customer/key를 tag나 field로
  넣지 않는다.

## Alternatives Considered

### 고객 취소 transaction에서 timeout 거절 즉시 확정

- 응답 직후 Order가 `REJECTED`다.
- 고객 endpoint가 store-timeout actor/reason과 긴 환불·보상 commit gate를 대신
  실행한다.

### 기존 periodic worker만 대기

- 새 work table과 worker wakeup이 없다.
- 409 직후 `PAID` 조회 지연이 scan 주기에 종속되고 이미 감지한 due fact를 버린다.

## Rationale

짧은 durable work는 고객 취소와 store-timeout의 business 책임을 분리하면서도
관측된 deadline winner를 재시작 후 실행할 근거로 남긴다. 기존 timeout service를
그대로 호출하므로 보상 정책을 두 벌로 만들지 않는다.

## Consequences

- Ordering에 timeout work table, claim worker, wakeup과 retention이 추가된다.
- deadline 이후 취소 409와 실제 `REJECTED` 조회 사이에는 짧은 비동기 구간이 계속
  존재하지만 주기 scan만 사용할 때보다 줄어든다.
- work 저장 장애는 기존 409 대신 503으로 노출된다.

## Failure Scenarios

- work를 in-memory queue에만 넣으면 응답 직후 process 종료 시 timeout 처리가
  유실된다.
- work insert 실패를 409로 반환하면 worker 지연을 줄인다는 보장이 없다.
- cancellation endpoint가 직접 `OrderRejectedV1`을 만들면 actor/source 분류가
  고객 취소와 섞인다.
- work와 periodic scanner가 서로 다른 source를 쓰면 Refund·보상이 중복될 수 있다.
- terminal work를 즉시 삭제하면 409과 timeout 실행 사이 조사 근거가 사라진다.

## Verification

- deadline 전 취소 성공과 정확히 deadline부터 work+409
- work/Audit commit 실패의 503과 Order 무변경
- 같은/different cancellation key의 work 한 건
- periodic scanner와 work worker 경합의 단일 rejection
- process restart 뒤 PENDING work 처리
- timeout actor/reason/event의 고객 취소와 분리

## Required Tests

- `deadline - 1ns`, `deadline`, `deadline + 1ns`
- work insert·Audit insert failure injection
- cancellation retry와 work unique arbitration
- wakeup 직전 process crash와 periodic recovery
- 기존 timeout scanner 동시 실행
- ACCEPTED/REJECTED/CANCELLED 예상·모순 source 분기
- claim lease 만료, bounded retry와 manual review
- terminal 90일 retention과 nonterminal 보존
- log/metric의 customer/order/key 부재

## Metrics

- `beanflow.order.acceptance_timeout.work.count{state,outcome}`
- `beanflow.order.acceptance_timeout.work.lag`
- `beanflow.order.acceptance_timeout.work.manual_review.count`

Order와 Customer 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** 기존 periodic scan 대비 실제 상태 지연 감소

## Revisit Conditions

timeout worker SLA가 측정돼 별도 work 없이도 충분하거나 synchronous timeout
materialization 비용이 허용될 때

## Related Decisions

- BR-06, BR-14, BR-25, BR-30
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-022](ADR-022-audit-record.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-032](ADR-032-customer-cancellation-idempotency.md)
