# 공개 주문번호와 매장 픽업번호로 UUID를 내부에 숨긴다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/productization-00-design-capability-contract.md`
> **Completed-At:** `2026-08-12`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

고객은 주문 UUID를 알 수도 기억할 수도 없고, 점주는 UUID로 픽업을 호명할 수 없다. 이 plan은
내부 UUID를 그대로 둔 채 **사람이 쓰는 식별자 두 개**를 추가한다.

- `publicReference`: 전역 유일한 외부 주문번호. 고객·고객센터·점주 조회 키
- `pickupNumber`: 매장·영업일 순번의 표시값. 호명과 정렬용

동시에 과거 주문의 표시가 소급 변경되지 않도록 매장명·픽업 시간 스냅샷을 추가한다.

## Current State

- `ordering_order`의 PK는 UUID이고 공개 식별자가 없다.
- `GET /orders/{orderId}`, `GET /store-orders/{orderId}`, `PATCH /store-orders/{orderId}/status`가
  UUID를 경로로 받는다.
- 주문은 `store_id`, `pickup_slot_id`만 갖고 매장명·픽업 시각 스냅샷이 없다.
- 프론트엔드 `CustomerPages.tsx`, `ConsolePages.tsx`가 UUID 입력창을 노출한다.
- 현재 checkout의 Flyway 최신 migration은 `V42`다. 이 plan은 lease 획득 후 최신 main에서 다음 번호를
  다시 확인하며 `V43`을 미리 예약하지 않는다.

## Definitions

- **Public order reference:** `BF-XXXX-XXXX` 형식의 전역 유일 외부 주문번호다. 문자 집합은
  `23456789ABCDEFGHJKMNPQRSTUVWXYZ`이고 권한 증명이 아니다.
- **Pickup business date:** 픽업 슬롯 시작 시각의 `Asia/Seoul` 영업일이다.
- **Pickup sequence:** 매장·영업일 안에서 1부터 증가하는 정수다. 결번을 허용한다.
- **Pickup number:** `A-`와 양수 `pickupSequence`의 zero-padding 없는 10진수 표현을 합친 표시값
  (`A-142`)이며 저장하지 않는다.
- **Display snapshot:** 주문 생성 시점의 매장명과 픽업 시간대다. 이후 변경하지 않는다.

## Scope

### In Scope

- `ordering_order`에 `public_reference`, `pickup_business_date`, `pickup_sequence`,
  `store_name_snapshot`, `pickup_window_start_snapshot`, `pickup_window_end_snapshot` 추가
- `ordering_public_reference_registry`의 transaction-safe 충돌 예약
- `ordering_pickup_counter` 테이블과 원자적 순번 발급
- Merchant `StoreDisplaySnapshotOperations`와 확장된 Fulfillment `PickupReservationGrant` 계약
- 공개 주문번호 생성기와 충돌 재시도 상한
- 기존 주문 backfill migration
- 주문 생성·조회 응답에 두 식별자 추가
- 주문번호 기반 조회 경로 추가(기존 UUID 경로는 유지)

### Non-goals

- UUID를 PK에서 제거
- 매장별 픽업번호 접두사 정책
- 주문 검색(주문번호 부분 일치)
- 프론트엔드 화면 전환(각 화면 plan이 수행한다)
- 이벤트 payload에 스냅샷 추가

## Business Rules and Invariants

1. `public_reference`는 전역 유일하며 DB Unique Constraint가 보장한다.
2. `(store_id, pickup_business_date, pickup_sequence)`는 유일하다.
3. 주문번호를 안다고 다른 고객의 주문을 조회할 수 없다. 소유권·매장 소속을 함께 검증한다.
4. 표시 스냅샷은 주문 생성 시 한 번 기록하고 이후 변경하지 않는다.
5. 픽업 순번은 결번을 허용한다. 연속성을 보장하지 않는다.
6. 주문번호는 순번이나 시각을 인코딩하지 않는다.
7. 내부 UUID는 PK, FK, 이벤트 Aggregate ID로 계속 사용한다.
8. 공개번호 예약은 최초 포함 5회만 시도하며 모두 충돌하면 주문 전체를 503으로 실패시킨다.
9. 새 고객·점주 API 응답에는 내부 `orderId`를 포함하지 않는다.

## Architecture and Transaction Boundaries

주문 생성 트랜잭션(`OrderCreationTransaction`) 안에서 다음 순서로 수행한다.

```text
Tx1 (기존 주문 생성 트랜잭션)
  1. Merchant 표시 snapshot 조회
       StoreDisplaySnapshotOperations.require(storeId) → owner-verified storeName
  2. 기존 픽업 예약
       PickupReservationGrant → reservationId, expiresAt, startsAt, endsAt
  3. 기존 재고·쿠폰·포인트 예약과 가격 계산
  4. 픽업 순번 발급
       startsAt의 영업일 → INSERT ... ON CONFLICT DO UPDATE ... RETURNING
  5. 주문번호 예약
       난수 → registry INSERT ... ON CONFLICT DO NOTHING RETURNING (최대 5회)
  6. 주문 INSERT
       storeName, startsAt, endsAt, publicReference, pickup date/sequence snapshot
```

- 순번 발급과 주문 삽입은 같은 PostgreSQL transaction이다. rollback이면 counter 증가와 public
  reference registry 예약도 함께 rollback된다. 커밋 후 취소·거절·만료된 번호는 재사용하지 않는다.
- `PickupReservationGrant`는 Fulfillment가 slot row lock 아래 읽은 `startsAt`·`endsAt`을 반환한다.
  Ordering이 slot을 다시 조회하지 않는다. Merchant 표시명 누락과 grant 시각 불일치는 fallback 없이
  주문 전체를 실패시킨다.
- 외부 호출은 이 트랜잭션에 들어가지 않는다.
- 조회 경로는 `public_reference` 인덱스를 사용하고 소유권 predicate를 SQL에 포함한다.

## Alternatives Considered

각 대안의 상세는 [ADR-096](../../adr/ADR-096-public-order-reference.md),
[ADR-097](../../adr/ADR-097-store-pickup-number.md),
[ADR-098](../../adr/ADR-098-order-display-snapshots.md)에 있다. 요약은 다음과 같다.

- UUID 앞 8자리 표시: 충돌·추측·정책 부재로 기각
- UUID를 짧은 문자열로 완전 교체: 내부·외부 식별 혼재와 마이그레이션 비용으로 기각
- `MAX + 1` 순번: 동시 요청 충돌로 기각
- 매장별 PostgreSQL Sequence: 일 단위 리셋의 DDL 부담으로 기각
- 조회 시 매장·슬롯 조인: 과거 주문의 소급 변경으로 기각

## Failure Semantics

- 주문번호 registry 충돌은 statement를 abort시키지 않는 `ON CONFLICT DO NOTHING RETURNING`으로
  최초 포함 5회만 재생성한다. 상한 초과는 `503 ORDER_REFERENCE_EXHAUSTED`이며 metric을 남긴다.
- 순번 발급 실패는 주문 생성 실패다. 순번 없이 주문을 만들지 않는다.
- 존재하지 않는 주문번호 조회는 404, 다른 고객·다른 매장의 주문번호는 403이다. 기존
  [ADR-030](../../adr/ADR-030-customer-cancellation-authorization.md) 규칙을 그대로 따른다.
- backfill 중단 시 부분 적용 상태에서 재개 가능해야 한다. `NOT NULL` 적용은 backfill 완료 후다.

## Data and Migration

```sql
-- 1) 컬럼 추가 (nullable)
ALTER TABLE ordering_order
    ADD COLUMN public_reference varchar(12),
    ADD COLUMN pickup_business_date date,
    ADD COLUMN pickup_sequence bigint,
    ADD COLUMN store_name_snapshot varchar(200),
    ADD COLUMN pickup_window_start_snapshot timestamptz,
    ADD COLUMN pickup_window_end_snapshot timestamptz;

-- 2) 공개번호 registry와 카운터 테이블
CREATE TABLE ordering_public_reference_registry (
    public_reference varchar(12) PRIMARY KEY,
    allocated_at timestamptz NOT NULL,
    CONSTRAINT ck_ordering_public_reference_registry_format
      CHECK (public_reference ~ '^BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$')
);

CREATE TABLE ordering_pickup_counter (
    store_id      uuid    NOT NULL,
    business_date date    NOT NULL,
    last_sequence bigint NOT NULL CHECK (last_sequence > 0),
    PRIMARY KEY (store_id, business_date)
);

-- 3) backfill (별도 실행)
--    public_reference: registry에 행별 원자 예약, 최초 포함 최대 5회
--    pickup_business_date: 픽업 슬롯 시작 시각의 Asia/Seoul 날짜
--    pickup_sequence: 매장·영업일 안에서 (created_at, id) 순
--    스냅샷: 현재 merchant_store_discovery_profile.name과
--            fulfillment_pickup_slot.starts_at/ends_at (근사값)

-- 4) 제약과 인덱스
CREATE UNIQUE INDEX ux_ordering_order_public_reference
    ON ordering_order (public_reference);
ALTER TABLE ordering_order
    ADD CONSTRAINT fk_ordering_order_public_reference_registry
    FOREIGN KEY (public_reference) REFERENCES ordering_public_reference_registry(public_reference),
    ADD CONSTRAINT ck_ordering_order_pickup_sequence_positive CHECK (pickup_sequence > 0),
    ADD CONSTRAINT ck_ordering_order_display_snapshot_shape CHECK (
        store_name_snapshot = btrim(store_name_snapshot)
        AND length(store_name_snapshot) BETWEEN 1 AND 200
        AND pickup_window_end_snapshot > pickup_window_start_snapshot
    );
ALTER TABLE ordering_order
    ADD CONSTRAINT ck_ordering_order_public_reference_format
    CHECK (public_reference ~ '^BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$');
CREATE UNIQUE INDEX ux_ordering_order_pickup_sequence
    ON ordering_order (store_id, pickup_business_date, pickup_sequence);

-- 5) NOT NULL 적용
-- 6) 카운터 초기화: 매장·영업일별 MAX(pickup_sequence)
-- 7) six display/identifier fields의 UPDATE 변경을 거부하는 BEFORE UPDATE trigger
```

expand migration 뒤 새 주문부터 여섯 값을 dual-write하고, bounded backfill CLI가 `(created_at, id)`
checkpoint로 기존 주문을 처리한 뒤 contract migration이 null·registry·profile·slot coverage를 검증하고
`NOT NULL`을 적용한다. 누락 profile·slot을 placeholder로 채우지 않는다.

contract migration의 trigger는 기존 주문 상태·version·updatedAt 변경은 허용하되 `public_reference`,
`pickup_business_date`, `pickup_sequence`, `store_name_snapshot`, `pickup_window_start_snapshot`,
`pickup_window_end_snapshot` 중 하나가 `IS DISTINCT FROM`이면 SQLSTATE `23514`로 거부한다. expand/backfill
중에는 nullable 값을 채워야 하므로 trigger를 backfill 전에 만들지 않는다.

backfill의 스냅샷 값은 **주문 당시 값이 아닐 수 있다.** 이 한계를 migration 주석과 runbook에 남긴다.

## API and Event Contracts

추가 경로(기존 경로는 유지하고 프론트엔드 전환 후 제거한다).

```http
GET  /api/v1/me/orders/{orderReference}
POST /api/v1/me/orders/{orderReference}/cancellations
GET  /api/v1/stores/{storeId}/orders/{orderReference}
POST /api/v1/stores/{storeId}/orders/{orderReference}/transitions
```

응답에 추가되는 필드.

```text
publicReference    신규
pickupNumber       신규 (표시값)
storeName          스냅샷
pickupWindowStart  스냅샷
pickupWindowEnd    스냅샷
```

- 기존 UUID endpoint는 전환 동안 기존 응답을 유지한다. 위 신규 고객·점주 endpoint는 내부 `orderId`를
  반환하지 않는다.
- 이벤트 payload는 변경하지 않는다([ADR-068](../../adr/ADR-068-immutable-integration-event-snapshots.md)).
- runtime OpenAPI와 계약 테스트를 같은 변경에서 갱신한다.

## Milestones

1. migration writer lease 획득, 최신 main에서 다음 Flyway 번호 확인.
2. 컬럼·카운터 테이블 migration과 원자적 순번 발급 구현.
3. 주문번호 생성기와 충돌 재시도 구현.
4. 주문 생성 경로에 스냅샷 기록 추가.
5. backfill migration과 카운터 초기화.
6. Unique Constraint와 `NOT NULL` 적용.
7. 주문번호 기반 조회·취소·전이 경로 추가.
8. runtime OpenAPI, 계약 테스트, runbook 갱신.

## Required Tests

- 공개 주문번호 충돌 주입 후 재생성이 동작하고, 상한 초과가 명시적 실패인지 검증한다.
- 같은 매장·영업일 동시 주문 N건에서 중복 순번이 없는지 PostgreSQL Testcontainers로 검증한다.
- 다른 매장·다른 영업일의 순번이 독립적으로 1부터 시작하는지 검증한다.
- 주문 생성 rollback 후 counter와 registry 예약이 함께 rollback되고 다음 성공 주문이 같은 순번을
  받는지 검증한다.
- 커밋 후 취소·거절·만료된 픽업번호가 같은 날 재사용되지 않는지 검증한다.
- 다른 고객의 `orderReference` 조회가 403, 존재하지 않으면 404인지 검증한다.
- 다른 매장의 `orderReference` 조회·전이가 403인지 검증한다.
- 대소문자 혼합 주문번호가 같은 주문으로 해석되는지 검증한다.
- lookup이 입력을 uppercase canonical form으로 바꾼 뒤 형식을 검증하고 잘못된 문자를 400으로
  거부하는지 검증한다.
- 매장명 변경 후 기존 주문 표시가 바뀌지 않는지 검증한다.
- 슬롯 정책 변경 후 기존 주문의 픽업 시각 표시가 바뀌지 않는지 검증한다.
- 직접 SQL/JPA update로 여섯 snapshot/identifier 필드 중 하나를 바꾸면 DB trigger가 거부하고 일반
  주문 상태 전이는 계속 성공하는지 검증한다.
- Store display profile 누락과 Pickup grant 시각 누락이 주문 전체 rollback인지 검증한다.
- backfill migration 후 기존 주문이 UUID와 주문번호 양쪽으로 조회되는지 검증한다.
- 자정 경계 픽업 슬롯의 영업일 귀속을 고정 `Clock`으로 검증한다.
- 기존 주문 생성·취소·환불·정산 테스트가 회귀 없이 통과하는지 확인한다.

## Validation Commands

```bash
./gradlew test --tests 'io.github.kdh949.beanflow.ordering.*'
./gradlew spotlessCheck
./gradlew build --stacktrace
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

## Observability

- 주문번호 충돌 재생성 횟수와 재시도 상한 초과 수
- 픽업 카운터 행 잠금 대기 시간 p95
- 주문 생성 지연에서 순번 발급이 차지하는 비중
- backfill 처리 건수와 소요 시간
- 주문번호 기반 조회의 403/404 수

## Documentation Updates

- ADR-096, ADR-097, ADR-098을 `Accepted`로 유지하고 구현 결과를 반영
- `openapi/beanflow-v1-runtime.yaml`
- `docs/api/api-conventions.md`(주문 식별자 규칙)
- `docs/security/authorization-matrix.md`(주문번호 기반 조회 행)
- 신규 `docs/operations/order-reference-backfill-runbook.md`

## Progress

- 2026-08-12: Checkpoint 1에서 exact predecessor
  `3c02752c114a271cec2458a1f9fcc00873d0ae1f`와 최신 migration V42를 확인하고
  `feature/productization-10-order-reference`를 그 commit에서 분기했다. Stack A migration-writer lease 아래
  V43/V44를 할당했다.
- 2026-08-12: 공개번호 CSPRNG 생성·registry 충돌 예약(최초 포함 5회), Seoul 영업일별 원자 pickup counter,
  주문 표시 snapshot과 `ORDER_REFERENCE_EXHAUSTED` 503 실패 계약을 구현했다.
- 2026-08-12: Merchant 표시명 port와 Fulfillment 예약 grant 시각 snapshot을 주문 생성 transaction에
  연결하고, 기존 UUID route를 유지한 채 고객 조회·취소와 매장 조회·전이의 공개번호 route를 추가했다.
  신규 응답은 내부 `orderId`를 제거했다.
- 2026-08-12: V43 expand, 재시작 가능한 bounded backfill CLI/runbook, V44 contract migration을 구현했다.
  V44는 6개 필드 `NOT NULL`·유일성·registry FK·형식·불변 trigger와 Fulfillment grant window 제약을
  적용한다.
- 2026-08-12: target/runtime OpenAPI, API 규칙, 인가 매트릭스, ADR-096~098과 회귀 fixture를 실제 동작에
  맞게 갱신했다.
- 2026-08-12: 필수 검증을 완료했다. Ordering 224 tests는 0 failure/0 skipped, 전체 build는 782 tests 중
  0 failure/1 skipped로 통과했다. Spotless와 문서/OpenAPI 검증도 통과했다.

## Surprises & Discoveries

- PostgreSQL의 같은 transaction에서 counter UPSERT와 주문 insert를 수행하면 주문 rollback 시 counter도
  rollback된다. 이전 문서의 "rollback 결번" 설명을 제거했다.
- 매장 표시명은 `merchant_store`가 아니라 `merchant_store_discovery_profile.name`이 소유한다.
- JPA insert의 unique 예외 뒤 같은 transaction 재시도는 안전하지 않아 별도 registry 예약으로 바꿨다.
- Fulfillment의 idempotent 예약 replay가 현재 slot row를 다시 읽으면 이후 slot 변경이 과거 주문 표시를
  바꾼다. V43에서 예약 자체에 시작·종료 snapshot을 추가하고 최초 grant와 replay가 같은 값을 반환하게
  했다.
- PostgreSQL의 non-null 개수 함수 이름은 `num_nonnull`이 아니라 `num_nonnulls`였다. backfill migration
  테스트가 첫 구현의 오타를 검출했다.
- 공개번호 충돌 상한 실패가 공통 오류 매핑에서 처음에는 409로 변환됐다. 실제 HTTP 통합 테스트가 이를
  검출했고 `ORDER_REFERENCE_EXHAUSTED`를 명시적으로 503에 고정했다.
- V44 `NOT NULL` 적용 뒤 기존 테스트의 여러 모듈이 `ordering_order`를 구형 column 집합으로 직접
  삽입했다. 첫 전체 build는 782 tests 중 78 failures였고, 유효한 registry 예약과 표시 snapshot을 만드는
  공통 fixture로 모두 교정했다. 한 Loyalty 테스트의 일시적 `INVALID_REQUEST`도 함께 관측됐지만 집중
  재실행과 최종 전체 build에서 코드 변경 없이 재발하지 않았다.
- 첫 fixture 교정 뒤 Kotlin 증분 cache가 `EOFException`으로 손상됐다. `./gradlew --stop`과 저장소
  `build/` clean 뒤 전체 test source를 재컴파일해 정상화했다.
- 완료 diff 검토에서 target OpenAPI path parameter가 구현의 case-insensitive 입력보다 좁고 두 GET
  경로에 400 응답이 빠진 것을 발견했다. 입력용 대소문자 정규식과 canonical 출력 정규식을 분리하고
  문서 검증기·계약 테스트를 함께 고정했다. 첫 문서 재검증은 이 불일치로 실패했고 교정 후 통과했다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-11 | 결번을 허용하고 순번 반납 큐를 만들지 않는다 | [ADR-097](../../adr/ADR-097-store-pickup-number.md) |
| 2026-08-11 | 픽업번호는 조회 키가 아니다. 조회는 주문번호를 쓴다 | [ADR-097](../../adr/ADR-097-store-pickup-number.md) |
| 2026-08-11 | backfill 스냅샷은 근사값이며 그 사실을 문서에 남긴다 | [ADR-098](../../adr/ADR-098-order-display-snapshots.md) |
| 2026-08-12 | rollback은 counter를 소비하지 않고 커밋 후 종료 번호만 재사용하지 않는다 | [ADR-097](../../adr/ADR-097-store-pickup-number.md) |
| 2026-08-12 | 공개번호는 registry `ON CONFLICT DO NOTHING`으로 최대 5회 예약 | [ADR-096](../../adr/ADR-096-public-order-reference.md) |
| 2026-08-12 | 매장명은 discovery profile, 픽업 시각은 lock 아래 grant에서 snapshot | [ADR-098](../../adr/ADR-098-order-display-snapshots.md) |
| 2026-08-12 | V43 expand에서 dual-write와 bounded CLI backfill을 수행하고, V44 contract에서 완전성·불변성을 강제 | [backfill runbook](../../operations/order-reference-backfill-runbook.md) |
| 2026-08-12 | 예약 replay의 시각도 최초 grant 값으로 고정하기 위해 Fulfillment reservation에 slot window snapshot을 저장 | [ADR-098](../../adr/ADR-098-order-display-snapshots.md) |
| 2026-08-12 | 신규 공개번호 route만 내부 UUID를 제거하고 기존 UUID route는 후속 화면 전환까지 유지 | [API conventions](../../api/api-conventions.md) |
| 2026-08-12 | OpenAPI 입력은 대소문자를 허용하고 응답·저장값은 대문자 canonical 형식만 허용 | [ADR-096](../../adr/ADR-096-public-order-reference.md) |

## Outcomes & Retrospective

- 주문 생성은 한 transaction에서 검증된 매장명, lock 아래 픽업 시간, Seoul 영업일 순번과 전역 공개번호를
  할당한다. rollback은 counter와 registry를 함께 되돌리고, 커밋된 종료 주문의 번호는 재사용하지 않는다.
- 고객·점주는 대소문자 입력을 canonicalize한 공개번호로 조회·명령할 수 있다. 소유권 또는 store scope가
  다르면 403, 존재하지 않으면 404이며 신규 응답에는 내부 `orderId`가 없다.
- 과거 주문은 V43에서 중단·재개 가능한 배치로 채우고 V44가 누락, 중복, registry 미등록, 잘못된
  snapshot과 이후 변경을 거부한다. 누락 profile·slot은 placeholder로 대체하지 않는다.
- 실행 검증 결과:
  - `./gradlew test --tests 'io.github.kdh949.beanflow.ordering.*'`: 성공, 최종 전체 리포트 기준 Ordering
    224 tests, 0 failures, 0 skipped.
  - `./gradlew spotlessCheck`: 성공.
  - `./gradlew build --stacktrace`: 성공(13m 53s), 782 tests, 0 failures, 1 skipped.
  - `PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh`: 성공. target 98 paths/104 operations,
    runtime 59 paths/63 operations, 213 schemas, 46 policies, 111 ADRs, 264 Markdown files, 50 ExecPlans.
- 측정 가능한 production traffic이 없어 latency 개선이나 실사용 충돌률은 주장하지 않는다. metric과
  runbook만 제공하며 실제 배포 시 관측해야 한다.

## Revision Notes

- 2026-08-11: 최초 작성.
- 2026-08-12: 구현, 회귀 fixture 교정, 필수 검증과 완료 결과를 기록.
