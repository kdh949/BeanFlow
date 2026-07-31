# ADR-056: Ordering 명령 멱등 레코드의 통합 보존 worker

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

BR-26은 terminal 멱등 레코드를 90일 보존하고 bounded chunk로 정리하도록 정한다.
ADR-032는 신규 고객 취소 테이블에 `retention_expires_at`과 cleanup worker를
설계했지만 기존 `ordering_store_command_idempotency`에는 정리 컬럼·index·worker가
없음을 미해결 상태로 남겼다.

BR-25가 매장 전이 operation을 `STORE_ORDER_TRANSITION_V2`로 승격해 구 레코드를 더
이상 조회하지 않더라도 보존 기간 전에 조기 삭제할 근거는 아니다. 새 취소 테이블만
정리하면 같은 Ordering Context의 terminal command record에 서로 다른 보존 정책이
적용된다.

## Decision

- 하나의 `OrderingIdempotencyRetentionWorker`가 다음 두 table cleanup job을
  orchestration한다.
  - `ordering_cancellation_command_idempotency`
  - `ordering_store_command_idempotency`
- schedule은 기본 fixed delay 1시간이다. 각 tick에서 table별 최대 100건 한 chunk만
  처리해 OLTP 부하를 제한한다.
- 두 table job은 각각 독립 transaction이다. 한 table의 query/delete가 실패해도
  다른 table job은 실행하며, 실패한 table은 성공 또는 0건으로 기록하지 않고 다음
  tick에 다시 시도한다.
- 두 table 모두 `retention_expires_at = created_at + 90일`을 row 생성 시
  materialize하고 `(retention_expires_at, id)` index를 사용한다.
- store table forward migration은 기존 모든 row의 `retention_expires_at`을
  `created_at + 90일`로 backfill하고 검증한 뒤 NOT NULL과 index를 추가한다.
- store와 cancellation command record는 저장 시점에 최초 200/202 response를 가진
  terminal row다. 향후 non-terminal state가 추가되면 due time만으로 삭제하지 않고
  terminal state predicate와 partial index를 별도 migration/ADR로 추가한다.
- 삭제 query는 `retention_expires_at <= now`인 row를
  `(retention_expires_at, id)` keyset 순서로 제한한다. offset pagination과
  unbounded delete를 사용하지 않는다.
- `STORE_ORDER_TRANSITION` 구 operation도 V2 승격 시점에 즉시 삭제하지 않고 각 row
  자신의 90일 만료 시각까지 보존한다.
- 일반 Application API와 운영자 API는 idempotency row 삭제 기능을 제공하지 않는다.
- cleanup structured log와 metric에는 table, outcome, deleted count와 oldest due
  age만 담는다. actor, Order, raw Idempotency-Key와 response body를 넣지 않는다.
- cleanup은 business AuditRecord 대상이 아니다. 정책에 따른 retention purge 자체를
  각 command Audit로 증폭하지 않는다.

## Alternatives Considered

### table별 scheduler

- 장애와 배포 단위가 명확하다.
- 같은 Context 안에서 schedule, metric, 설정과 runbook이 중복된다.

### 고객 취소 table만 정리

- 신규 Feature 범위가 작다.
- 기존 store record가 BR-26을 계속 위반하고 무기한 증가한다.

### V2 승격 시 구 store record 즉시 삭제

- 더 이상 조회하지 않는 data를 빨리 줄인다.
- 최초 응답과 문제 조사 증적의 90일 보존 정책을 위반한다.

## Rationale

worker lifecycle과 운영 관측은 공유하되 table별 transaction을 분리하면 정책은
통일하고 장애 전파는 제한할 수 있다. materialized expiry와 keyset chunk는 중단·재실행
시 같은 due predicate로 안전하게 수렴한다.

## Consequences

- store idempotency table에 forward migration과 index가 추가된다.
- cleanup worker의 table별 metric·alert와 runbook이 필요하다.
- 최악의 cleanup 처리량은 기본 table당 시간당 100건이므로 backlog가 이를 넘으면
  측정 후 batch/schedule을 조정해야 한다.

## Failure Scenarios

- 두 table을 한 transaction으로 지우면 store failure가 cancellation cleanup도
  rollback한다.
- backfill을 현재 시각 기준으로 하면 오래된 row가 추가 90일 남아 정책보다 오래
  보존된다.
- V2에서 조회하지 않는다는 이유로 구 record를 즉시 지우면 90일 조사·재생 증적이
  사라진다.
- raw key나 response body를 cleanup log에 넣으면 민감 요청 정보가 로그로 확산된다.
- future non-terminal row를 due time만으로 삭제하면 진행 중 명령이 재실행될 수 있다.

## Verification

- 기존 store row의 createdAt+90일 backfill
- 두 table의 due 이전/이후 경계
- table별 batch 100과 독립 rollback
- worker 중단·재실행의 누락·중복 side effect 부재
- 구/V2 store operation의 동일 보존
- metric/log 금지 필드 부재

## Required Tests

- 정확히 90일 경계와 clock 고정
- store backfill 재실행 안전성, NOT NULL과 index
- cancellation failure 중 store 성공과 반대 조합
- 각 table 101건에서 첫 tick 100건, 다음 tick 1건
- due row 추가 중 keyset 처리
- Application/Operations delete endpoint 부재
- future non-terminal fixture가 도입될 경우 deletion guard contract

## Metrics

- `beanflow.ordering.idempotency.retention.deleted{table}`
- `beanflow.ordering.idempotency.retention.failure{table}`
- `beanflow.ordering.idempotency.retention.oldest_due_age.seconds{table}`
- `beanflow.ordering.idempotency.retention.backlog{table}`

Actor, Order와 raw Idempotency-Key는 metric tag로 사용하지 않는다.

- **Not measured:** production delete latency와 dead tuple volume

## Revisit Conditions

실제 backlog가 기본 처리량을 지속적으로 넘거나 table partitioning이 도입될 때

## Related Decisions

- BR-25, BR-26
- [ADR-025](ADR-025-order-creation-idempotency-transaction.md)
- [ADR-032](ADR-032-customer-cancellation-idempotency.md)
