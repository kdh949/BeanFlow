# Customer Order Cancellation Release-Gate Evidence

## Evidence identity

- **Recorded at:** 2026-07-31T23:40:15+09:00
- **Evidence source:** product-owner operational-state attestation
- **Attestor role:** product owner
- **Scope:** BeanFlow의 모든 non-local deployment, shared/production database,
  persistent event publication, external consumer와 rollback artifact
- **Collection method:** 대상 외부 환경과 artifact가 존재하지 않는다는 소유자 확인
- **Related decision:** [ADR-059](../adr/ADR-059-pre-release-compensation-clean-cutover.md)

원본 대화는 저장하지 않는다. 이 문서는 확인된 운영 사실, gate 계산과 재검증 조건만
기록한다.

## Environment inventory

2026-07-31 확인 시점에 local/test 밖의 shared, staging 또는 production 환경이 없다.
따라서 조회할 외부 database, publication registry, 독립 배포 consumer 또는 rollback
artifact repository도 없다. 저장소의 migration과 test fixture는 외부 운영 상태로
계산하지 않는다.

## Gate facts

| Required fact | Confirmed result | Evidence interpretation |
|---|---:|---|
| shared/production deployment environment | 0 | non-local 배포 환경 없음 |
| production/shared compensation schema, table와 row | 0 | 대상 database 자체가 없음 |
| completed `OrderRejectedV1`/`OrderCancelledV1` publication | 0 | 외부 publication registry 없음 |
| incomplete `OrderRejectedV1`/`OrderCancelledV1` publication | 0 | 외부 publication registry 없음 |
| external 또는 independently deployed consumer | 0 | 독립 consumer 배포 없음 |
| rollback 대상 production binary/data | 0 | production 배포·data 없음 |
| production/shared 환경에 적용된 migration | 0 | 적용 대상 환경 없음 |

모든 ADR-059 gate 항목이 unknown이 아니라 명시적 0으로 확인됐다.

```text
CLEAN_CUTOVER_GATE = PASSED
```

## Authorized path

- ADR-059의 pre-release clean-cutover 경로를 사용할 수 있다.
- producer, consumer와 fixture를 같은 변경에서 최종 계약으로 전환한다.
- legacy migration, publication drain, compatibility layer와 version 이중 발행을 만들지
  않는다.
- local/test database는 최종 migration history로 재생성하며 checksum repair로 구·신
  schema를 혼합하지 않는다.

## Validity and invalidation

이 증거는 위 기록 시점의 point-in-time 확인이다. compensation schema 변경 또는 최초
production/shared 배포 직전에 같은 inventory를 다시 확인해야 한다. 다음 중 하나가
생기거나 존재 여부가 unknown이 되면 이 PASS는 즉시 무효다.

- shared/staging/production 환경 또는 database
- 보존해야 할 compensation row나 완료·미완료 V1 publication
- external/independent consumer
- rollback 대상 binary 또는 data

무효가 되면 clean cutover를 중단하고 실제 상태를 입력으로 forward migration,
publication drain, compatibility와 rollback ADR/ExecPlan을 먼저 확정한다.

이 PASS는 migration/event 전략만 허용한다. 부분 환불 allocation, Settlement, 공통
compensation foundation과 고객 취소 command 구현 완료를 의미하지 않는다.

## Plan 10 issuer provenance execution evidence

- **Recorded at:** 2026-08-01
- **Execution inventory:** this workspace had no `BEANFLOW_DB_URL`,
  `BEANFLOW_DB_USERNAME`, or `BEANFLOW_DB_PASSWORD`; no configured runtime database was
  available to reclassify. Repository schema, entities, repositories, and fixtures also
  contained no legacy PointLot issuer source.
- **Interpretation:** this is not evidence that a non-empty future database is mappable.
  V14 accepts the clean empty path, while any non-empty V1–V13 database must present the
  exact one-to-one external `loyalty_point_lot_issuer_precheck` relation with valid issuer
  type/reference, non-blank source reference, and verification timestamp. A missing,
  partial, extra, blank, invalid, or changing mapping fails the migration and prevents
  application activation; V14 never supplies a `PLATFORM` default.
- **Test evidence:** PostgreSQL Testcontainers covered empty final constraints, verified
  exact backfill, missing and invalid mappings, immutable issuer snapshots, DTO projection,
  compensation issuer inheritance, and Spring startup failure.

## Plan 15 settlement-input execution evidence

- **Recorded at:** 2026-08-02
- **Execution inventory:** this workspace had no configured non-local runtime database or
  deployment environment. No Merchant terms, active Campaign, CouponReservation or Order row
  outside repository Testcontainers could be inspected or reclassified. This preserves the earlier
  external-environment inventory of explicit zero; it is not evidence that an unknown future legacy
  database is safely mappable.
- **Migration interpretation:** V18 adds immutable versioned Merchant terms without inventing a
  fee for existing Stores. V19 stops when an active legacy Campaign or any legacy CouponReservation
  lacks verified burden lineage. V20 stops when any legacy Order exists because terms/coupon/point
  source cannot be reconstructed from price totals. Application activation therefore accepts the
  clean path and fails closed for unverified financial history; checksum repair or guessed backfill
  is not an allowed release action.
- **Runtime interpretation:** a Store without exactly one applicable terms version, an incomplete
  coupon burden snapshot, mismatched PointLot issuer allocation or monetary/hash tie-out failure
  returns `SETTLEMENT_INPUT_UNAVAILABLE` and rolls back Order plus all reservations. No local,
  in-memory, current-value or zero-cost fallback is active.
- **Test evidence:** PostgreSQL Testcontainers covered V18–V20 constraints and legacy gates,
  applicable/overlapping/concurrent terms, all coupon burden modes and integer remainder, mixed
  issuer allocation, source/hash/formula tie-outs, exactly-one replay and forced persistence rollback.
  `OrderCompletedV2` contract tests covered exact fixture mapping and Payment mismatch without adding
  a producer/outbox or Settlement consumer.
