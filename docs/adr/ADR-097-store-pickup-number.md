# ADR-097: 매장·영업일 단위 픽업번호와 동시 발급

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Public order reference](../exec-plans/completed/productization-10-public-order-reference.md)

## Context

디자인의 고객 주문 추적(`고객 1d`)과 점주 주문보드(`점주 1b`)는 `A-142` 형태의 픽업번호를
사용한다. 매장은 이 번호로 고객을 호명하고, 고객은 이 번호로 자기 주문을 확인한다.

[ADR-096](ADR-096-public-order-reference.md)의 공개 주문번호는 전역 유일하고 추측 저항성이 있지만
길어 매장에서 소리 내어 부르기 어렵다. 공개 주문번호는 조회에, 짧은 픽업번호는 고객 호명에 사용한다.

픽업번호는 매장·영업일 안에서만 유일하면 되고, 다음 날 재사용된다. 문제는 동시성이다.
`현재 최대값 + 1`을 조회한 뒤 저장하면, 동시 요청이 같은 최대값을 읽고 같은 번호를 발급하려 해 유일 제약 위반이 발생할 수 있다.

## Decision

주문에 `pickupBusinessDate`와 `pickupSequence`를 추가하고, 표시 값 `pickupNumber`는 이 둘에서
파생한다.

```text
pickupBusinessDate  2026-08-11   (Asia/Seoul 영업일, BR-01)
pickupSequence      142
pickupNumber        A-142        (`A-` + 10진수 sequence, zero padding 없음, 저장하지 않는다)
```

### 제약

```sql
ALTER TABLE ordering_order
    ADD COLUMN pickup_business_date date NOT NULL,
    ADD COLUMN pickup_sequence bigint NOT NULL CHECK (pickup_sequence > 0);

CREATE UNIQUE INDEX ux_ordering_order_pickup_sequence
    ON ordering_order (store_id, pickup_business_date, pickup_sequence);
```

### 순번 발급

매장·영업일 단위 카운터를 SQL 한 문장으로 원자적으로 증가시킨다. 애플리케이션에서 `MAX + 1`을
먼저 조회한 뒤 저장하는 방식은 사용하지 않는다.

```sql
INSERT INTO ordering_pickup_counter (store_id, business_date, last_sequence)
VALUES (:storeId, :businessDate, 1)
ON CONFLICT (store_id, business_date)
DO UPDATE SET last_sequence = ordering_pickup_counter.last_sequence + 1
RETURNING last_sequence;
```

- 정상 발급은 기존 카운터의 원자적 증가이며 애플리케이션에서 값을 먼저 읽지 않는다.
- 주문 생성 트랜잭션 안에서 실행한다. 같은 PostgreSQL transaction의 주문 insert가 rollback되면
  counter 증가도 rollback되어 그 시도만으로 결번이 생기지 않는다.
- 커밋된 주문이 이후 취소·거절·만료되더라도 번호를 반납하거나 재사용하지 않는다. 활성 주문 화면에서
  보이는 결번은 허용한다. 순번은 연속성 보장이 아니라 호명 식별자다.
- 영업일은 [BR-01](../product/business-policy-decisions.md)의 `Asia/Seoul` 기준으로 계산한다.
  픽업 시각이 자정을 넘는 경우의 영업일 귀속은 픽업 슬롯 시작 시각을 기준으로 한다.
- `pickupBusinessDate`는 주문 생성 시 snapshot하고 이후 슬롯·영업시간 변경으로 다시 계산하지 않는다.
  매장별 cutoff나 주문 생성일은 사용하지 않는다. 슬롯 시작 시각이 없거나 변환할 수 없으면 현재
  날짜·주문 생성일로 대체하지 않고 주문 생성 또는 migration을 실패시킨다.
- 접두사(`A-`)는 표시 규칙이며 저장하지 않는다. 매장별 접두사 정책이 필요해지면 별도 결정으로 다룬다.
- P0 표시값은 모든 매장에 `A-`를 고정하고 양수 sequence의 10진수 문자열을 zero padding 없이 붙인다.
  locale 숫자나 매장별 접두사를 사용하지 않는다.

### 조회

- 픽업번호는 매장 화면의 표시·정렬용이며 **조회 키가 아니다**. 점주 주문 조회는
  `publicReference`를 사용한다. 픽업번호로 주문을 특정하려면 매장·영업일이 함께 필요하기 때문이다.
- 고객 화면은 자기 주문의 픽업번호를 표시만 한다.

### 마이그레이션

- 기존 주문은 `pickup_business_date`를 픽업 슬롯 시작 시각에서 계산하고, `pickup_sequence`는
  매장·영업일 안에서 `created_at`, `id` 순으로 부여한다. 슬롯 또는 시작 시각이 누락된 행은
  migration preflight에서 식별하고 contract migration을 실패시킨다.
- backfill 후 카운터 테이블을 각 매장·영업일의 최대값으로 초기화한다.

## Alternatives Considered

### 1. `SELECT MAX(pickup_sequence) + 1` 후 삽입

- 장점: 테이블이 늘지 않는다.
- 단점: 동시 요청에서 같은 값을 읽어 Unique 위반이 발생한다. 재시도로 덮으면 피크 시간에 재시도가
  집중되고 지연이 튄다.

### 2. PostgreSQL Sequence를 매장별로 생성

- 장점: 원자성이 보장된다.
- 단점: 매장·영업일 조합마다 DDL이 필요하다. 일 단위 리셋을 DDL로 처리해야 한다. 운영 부담이 크다.

### 3. Advisory Lock으로 직렬화

- 장점: 기존 테이블만으로 가능하다.
- 단점: 락 키 관리가 필요하고 트랜잭션 지속 시간이 길어지면 대기가 누적된다. 원자적 UPSERT보다 이득이 없다.

### 4. 픽업번호를 주문번호로 겸용

- 장점: 식별자가 하나 줄어든다.
- 단점: 전역 유일하지 않고 추측 가능하다. 다른 매장·다른 날짜의 같은 번호가 존재하므로 조회 키로
  쓸 수 없다.

## Rationale

`INSERT ... ON CONFLICT DO UPDATE ... RETURNING`은 읽기·증가·쓰기를 한 문장으로 처리한다.
DB의 row lock이 같은 카운터의 동시 변경을 순서대로 실행하므로, 애플리케이션에서 번호 충돌을 재시도하는 로직이 필요 없다.

커밋된 주문이 종료돼도 번호는 보존한다. 반납한 번호를 다른 주문에 부여하면 같은 날 두 고객에게
같은 번호가 보여 호명과 문의 과정에서 주문을 구분하기 어려워진다.

## Consequences

- 새 테이블 `ordering_pickup_counter` 하나와 컬럼 2개, Unique Index 1개가 추가된다.
- 주문 생성 트랜잭션에 쓰기가 한 번 늘어난다. 같은 매장·영업일의 주문은 이 행에서 직렬화되므로
  단일 매장의 동시 주문 처리량 상한이 이 행의 잠금 대기로 결정된다. 측정 대상이다.
- 취소·실패 주문의 번호는 재사용되지 않는다.
- 영업일 경계 처리 규칙을 픽업 슬롯 정의와 일치시켜야 한다.

## Verification

- 같은 매장·영업일에 동시 주문 N건을 생성해 중복 번호가 없고 결번을 제외한 순번이 단조 증가하는지 검증한다.
- 다른 매장과 다른 영업일의 순번이 서로 독립적으로 1부터 시작하는지 검증한다.
- 주문 생성 rollback이 counter 증가도 rollback하고 다음 성공 주문이 그 번호를 받는지 검증한다.
- 커밋 후 취소·거절·만료된 주문의 번호가 같은 날 재사용되지 않는지 검증한다.
- 자정 경계 픽업 슬롯의 영업일 귀속을 고정 `Clock`으로 검증한다.
- 주문 생성일과 픽업 슬롯 시작일이 다른 예약 주문이 픽업 시작일 카운터를 사용하는지 검증한다.
- 슬롯 시작 시각이 누락된 기존 행을 현재 날짜나 주문 생성일로 backfill하지 않고 migration이
  실패하는지 검증한다.
- backfill 후 카운터 초기값과 실제 최대값이 일치하는지 검증한다.
- 카운터 행 경합의 대기 시간을 부하 조건에서 측정한다.

## Metrics

- 매장·영업일별 순번 발급 수
- 카운터 행 잠금 대기 시간 p95
- 주문 생성 지연에서 순번 발급이 차지하는 비중
- 커밋 후 종료되어 활성 보드에서 보이지 않는 번호 수

## Revisit Conditions

- 단일 매장의 동시 주문에서 카운터 잠금 대기가 실제로 병목이 될 때
- 매장별 접두사 또는 채널별 번호 체계가 필요할 때
- 24시간 영업 매장이 생겨 영업일 경계 정의를 바꿔야 할 때

## Implementation Outcome (2026-08-12)

- `ordering_pickup_counter` UPSERT와 주문 insert는 같은 transaction에서 실행되며 rollback 시 함께
  되돌아간다. 커밋된 할당은 종료 상태와 무관하게 반납하지 않는다.
- V50는 기존 매장·영업일별 주문 수를 선점하고, bounded backfill은 `(created_at, id)` rank를 기록한 뒤
  V51이 실제 최대값으로 카운터를 재동기화한다.
- `Asia/Seoul` 자정 경계, 매장/일자 독립성, 동시 20건 유일성, rollback과 커밋 후 비재사용을
  PostgreSQL Testcontainers로 검증했다.
- 순번 UPSERT와 행 잠금 대기를 `beanflow.order.pickup_sequence.allocation.duration` p95 timer로 계측한다.

### 2026-09-08 정상 발급과 초기화 경로 분리

- 기존 카운터에는 `UPDATE ... SET last_sequence = last_sequence + 1 RETURNING last_sequence`를
  실행한다. 정상 주문마다 과거 주문과 슬롯을 집계하지 않는다.
- UPDATE 결과가 없는 최초 발급에만 기존 주문의 `GREATEST(count(*), max(pickup_sequence)) + 1`
  기준선을 구하고 UPSERT한다. 동시 최초 발급은 `GREATEST(counter + 1, EXCLUDED.last_sequence)`로
  직렬화한다. V50 당시 누락된 슬롯을 복구한 뒤 새 카운터를 만드는 경로도 유지한다.
- V50/V51과 backfill로 초기화·동기화한 카운터를 이후 번호 발급의 기준값으로 사용한다.
  운영 중 카운터를 임의 삭제하거나 낮춘 경우에는 runbook에 따른 별도 복구가 필요하다.
  기존 writer 실행 차단, backfill·contract preflight와 카운터 보존 조건은 유지한다.
- 두 경로 모두 주문 저장과 같은 트랜잭션이다. 초기화가 실패하면 주문도 실패하고, 롤백 시 번호 증가도
  취소된다. 최초 발급에서는 카운터 존재 확인용 UPDATE를 한 번 더 실행하지만, 이후 발급부터는
  과거 주문 수에 비례하는 집계 비용이 사라진다.
- 검증과 배포 전후 측정은 [잠금 구간 SQL 개선 계획](../exec-plans/completed/pickup-counter-pr-extraction.md)에 기록한다.
  환불·감사 후속 처리까지 포함한 통합 측정은 [잠금 구간 SQL 재측정](../quality/performance-lock-critical-section-retest-2026-09-08.md)을 참조한다.

## Related Decisions

- [ADR-096](ADR-096-public-order-reference.md)
- [ADR-098](ADR-098-order-display-snapshots.md)
- [ADR-076](ADR-076-store-catalog-read-contract.md)
- [BR-01 시스템 기준 시간대](../product/business-policy-decisions.md)
