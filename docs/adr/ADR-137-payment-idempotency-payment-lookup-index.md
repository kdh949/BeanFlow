# ADR-137: 결제 멱등성 payment 조회 인덱스

- **Status:** Accepted
- **Date:** 2026-09-20
- **Implementation owner:** Payment schema and performance validation

## Context

결제 승인 결과 적용은 `PaymentIdempotencyJpaRepository.findByPaymentId`로
`payment_idempotency_record.payment_id`를 조회한다. 기존 schema에는 non-terminal 상태용 partial index와
`(actor_id, operation, idempotency_key)` unique index만 있고 `payment_id` access path가 없다. 제어된
12 workflow/s 측정에서 이 조회는 720회, 누적 4.680초로 관측됐다. 원문 SQL, 결제·주문·사용자 식별자는
metric label에 추가하지 않는다.

## Decision

- `payment_idempotency_record(payment_id)` 단일-column B-tree index를 추가한다.
- Flyway migration은 transaction-local `lock_timeout=5s`, `statement_timeout=60s`로 lock 획득과 index
  build를 제한한다. 제한을 넘으면 release를 실패시키고 더 긴 운영 창을 명시적으로 선택한다.
- 동일 이름 index가 이미 있으면 `pg_index`의 valid/ready 상태와 `pg_get_indexdef`의 정확한 table·column
  정의를 검증한다. 스테이징 A/B에서 선적용한 동일 index만 허용하고 name collision이나 invalid index는
  migration을 실패시킨다.
- 애플리케이션 조회, transaction 경계, 멱등성 상태와 복구 의미는 변경하지 않는다.
- 고정 fixture에서 migration의 index 정의와 planner의 named index 선택을 함께 검증한다.
- 스테이징 A/B는 같은 DB, API image, driver generation, fixture, rate, VU와 duration을 유지하고 index만
  변경한다. 적용 전후 DB 불변식과 recovery를 확인한다.

## Alternatives Considered

- Payment Aggregate에 멱등성 record를 합치거나 캐시한다: 소유권과 실패 복구 의미를 바꾸므로 제외한다.
- 승인 결과 적용에서 조회를 생략한다: provider 성공 후 DB 실패와 replay의 멱등성 증거를 잃으므로 제외한다.
- 복합/covering index를 추가한다: 현재 predicate는 `payment_id` equality 하나이고 projection은 entity 전체라
  쓰기 증폭 대비 이점이 입증되지 않았다.

## Consequences

- 해당 조회는 전체 table scan 대신 payment_id index scan 후보가 된다.
- idempotency record insert/update에 index 유지 비용과 디스크 사용량이 추가된다.
- 일반 `CREATE INDEX`는 적용 중 write를 막을 수 있다. 5초 안에 lock을 얻지 못하거나 build가 60초를
  넘으면 전체 transactional migration이 rollback된다. 운영 배포 전에 실제 table size와 허용 window를
  다시 확인한다.
- PR #189의 V87은 `main`에 병합됐다. PR #190도 별도 V92를 보유하므로 #198은 Draft에서 V92 lane을
  유지하고 먼저 병합한다. 이후 #190은 최신 `main`을 통합하고 미적용 demo migration을 V93으로 옮긴다.

## Verification

- `PaymentIdempotencyQueryMigrationTest`: fresh PostgreSQL Flyway 적용, 올바른 선적용 index 허용, 같은 이름의
  잘못된 index 거부, index definition/valid 상태, 25,000-row 고정 fixture의 index 전후
  `EXPLAIN (ANALYZE, BUFFERS)`.
- Payment 승인·UNKNOWN/reconciliation 회귀와 전체 `check`, 문서 검증.
- 같은 스테이징 DB의 동일 조건 부하에서 SQL seconds/workflow, API/PostgreSQL CPU/workflow, HTTP/workflow
  tail, Hikari, dropped, 불변식과 recovery 전후 비교.

## Revisit Conditions

- 조회가 `payment_id` 외 조건을 요구하도록 repository contract가 바뀔 때.
- write amplification이 read 절감보다 커지거나 table/index bloat가 운영 한도를 넘을 때.
- planner가 실제 분포에서 named index를 선택하지 않을 때.

## Related Decisions

- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
- [ADR-121](ADR-121-performance-observability-and-trace-profile-correlation.md)
