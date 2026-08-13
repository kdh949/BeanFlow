# ADR-099: 고객 주문 목록의 Aggregate와 Read Model 분리

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Customer order read model](../exec-plans/completed/productization-50-customer-order-read-model.md)

## Context

현재 주문 조회는 `GET /orders/{orderId}` 단건뿐이고, 구현은 Order Aggregate를 로딩한다.
Aggregate는 주문 라인, 예약, 스냅샷, 보상 상태를 함께 가진 큰 객체 그래프다.

고객 홈(`고객 1a`)과 주문 내역(`고객 4c`)은 목록이다. 목록을 Aggregate 로딩으로 만들면 다음이 발생한다.

- 주문 N건마다 라인·스냅샷 조회가 따라붙는 N+1
- 화면이 쓰지 않는 필드까지 로딩
- 영속성 컨텍스트의 변경 감지 비용
- 목록 요구가 바뀔 때마다 쓰기 모델의 연관관계를 확장하려는 압력

`AGENTS.md`는 "쓰기 모델의 객체 그래프를 조회 편의를 위해 확장하지 않는다", "목록·집계 조회는
DTO Projection, Query Repository 또는 별도 Read Model을 검토한다"고 규정한다.

## Decision

같은 PostgreSQL 안에서 **쓰기 모델과 조회 코드를 분리**한다. 물리적 CQRS 인프라를 도입하지 않는다.

```text
OrderRepository                    Aggregate 저장과 상태 전이
CustomerOrderQueryRepository       고객 주문 목록·상세 Projection
MaterializeOrderExpiryUseCase      반환 대상의 만료 확정과 예약 자원 해제
```

### 규칙

- `CustomerOrderQueryRepository`는 DTO Projection만 반환한다. JPA Entity를 반환하지 않는다.
- Query Repository의 candidate scan과 Projection 조회는 `@Transactional(readOnly = true)`로
  실행한다. 단, BR-03에 따라 반환 후보인 만료 `PENDING_PAYMENT`를 먼저 물질화하는 명령 경계는
  쓰기 transaction이다.
- 목록은 Cursor Pagination을 사용하고 계약은 [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)의
  HMAC signed cursor를 재사용한다. cursor에는 customer scope를 서명하고 매 요청 인가를 다시 수행한다.
- 정렬 키는 `(created_at DESC, id DESC)`이고 인덱스 후보는 다음이다. 실제 실행계획으로 검증한다.

```sql
CREATE INDEX ix_ordering_order_customer_recent
    ON ordering_order (customer_id, created_at DESC, id DESC);
```

- 활성 주문과 종료 주문의 분류는 서버가 소유한다. 클라이언트가 상태 목록을 하드코딩하지 않는다.
  목록 `status`는 생략 시 기간 내 전체, `ACTIVE`면 진행 주문, `PAST`면 종료 주문이다. `PAST`는
  디자인 아카이브의 지난 주문 탭을 클라이언트 상태 추론 없이 구현하기 위한 additive 값이다.
- 기간 필터는 `from`/`to`를 받는다. 지정하지 않으면 **최근 30일**이 기본이다. 조회 가능한 과거
  범위에 상한을 두지 않는다. keyset pagination이므로 깊은 과거 조회에 성능상 근거가 없고,
  상한 값을 정하려면 그 숫자를 정당화할 별도 근거가 필요하다.
- 기간 필터와 활성 필터는 cursor에 함께 서명한다. 페이지 도중 필터가 바뀌면 cursor는 무효다
  ([ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)).
- `from > to`이거나 형식이 잘못되면 400이다. 빈 목록으로 대체하지 않는다.
- 응답 Summary DTO는 다음을 포함한다.

```text
orderReference      publicReference (ADR-096)
pickupNumber        표시용 (ADR-097)
storeName           스냅샷 (ADR-098)
status
orderedAt
pickupWindowStart   스냅샷
pickupWindowEnd     스냅샷
totalAmount
itemSummary         "아이스 아메리카노 외 1건"
allowedActions      서버가 계산한 수행 가능 행동
```

- `allowedActions`는 주문 상태, 취소 가능 시각, 결제 상태를 근거로 **서버가 계산**한다.
  프론트엔드가 상태 머신을 다시 구현하지 않는다. 값은 닫힌 집합이며 OpenAPI에 enum으로 고정한다.
- 목록 응답은 개인정보와 결제 식별자를 포함하지 않는다. 카드 정보, provider reference, 내부
  실패 코드는 제외한다.
- 상세 조회도 같은 Query Repository를 사용한다. 상태를 바꾸는 명령만 Aggregate를 로딩한다.
- 상세는 기존 `MaterializeOrderExpiryUseCase`를 먼저 실행한 뒤 Projection을 읽는다.
- 목록은 customer scope, 필터와 cursor로 `limit + 1` candidate ID와 scan boundary를 고정한다.
  candidate 중 기한이 지난 `PENDING_PAYMENT`가 있으면 ID 정렬 순서로 잠그고, Order 만료와 네
  예약 자원 해제를 **하나의 transaction**에서 처리한다. 하나라도 실패하면 전체 rollback 후 `503`이다.
  이후 고정한 candidate ID만 다시 Projection한다. 활성 필터에서 만료 Order가 빠져 짧거나 빈
  페이지가 되더라도 다음 window를 채우기 위한 추가 materialization은 하지 않으며, `nextCursor`는
  반환 row가 아니라 candidate scan boundary를 서명한다.
- `GET /me/orders` cursor binding은 ADR-070의 `customer-orders` endpoint, 고객 ID,
  `ALL|ACTIVE|PAST`, 기본값이 적용된 서울 날짜 `from`/`to`와 `(createdAt DESC, orderId DESC)`를
  사용한다.

## Alternatives Considered

### 1. Order Aggregate 로딩으로 목록 구성

- 장점: 초기 구현이 빠르다.
- 단점: N+1, 불필요한 로딩, 변경 감지 비용. 주문이 늘수록 선형 이상으로 나빠진다.

### 2. 별도 CQRS 인프라(이벤트 기반 Read DB 복제)

- 장점: 읽기 확장성이 높고 쓰기와 완전히 분리된다.
- 단점: 현재 규모에 비해 과하다. 복제 지연, 재구축, 일관성 표현이 새 실패 모드로 추가된다.
  `AGENTS.md`가 요구하는 "필요성과 장애 정책 문서화" 기준을 아직 만족하지 못한다.

### 3. Entity Graph·Fetch Join으로 Aggregate 로딩 최적화

- 장점: 코드 구조가 그대로다.
- 단점: 화면 요구가 늘 때마다 쓰기 모델의 연관관계가 늘어난다. Aggregate 경계가 조회 요구에
  의해 침식된다.

## Rationale

읽기와 쓰기의 요구가 다르다는 사실은 인정하되, 그 분리를 **코드 수준**에서 하면 대부분의 이득을
얻고 분산 시스템의 비용은 피할 수 있다. 같은 DB를 쓰므로 일관성 표현이 필요 없고, Read Model이
필요해지면 이 Query Repository가 그대로 이전 지점이 된다.

## Consequences

- Query DTO와 SQL이 늘어난다. 화면별로 필요한 필드만 조회하므로 중복이 생긴다. 이는 의도된 비용이다.
- `allowedActions` 계산 로직이 서버에 추가된다. 상태 머신과 이 계산이 어긋나면 화면이 잘못된
  버튼을 보여주므로, 상태 전이 테스트가 `allowedActions`도 함께 검증해야 한다.
- 인덱스가 하나 추가된다. 쓰기 비용이 소폭 증가한다.
- 목록 API 계약이 늘어나므로 OpenAPI와 계약 테스트를 같은 변경에서 갱신해야 한다.
- 목록은 보통 read-only지만 반환 후보에 기한이 지난 결제 전 주문이 있으면 bounded write가 선행한다.
  활성 필터의 한 페이지가 짧거나 비어도 `nextCursor`가 존재할 수 있으므로 이 계약을 UI와 OpenAPI에
  명시해야 한다.

## Verification

- 주문 100건 이상 상태에서 목록 조회의 SQL 수가 주문 수와 무관하게 고정인지 검증한다.
- `EXPLAIN ANALYZE`로 인덱스 사용을 확인하고 인덱스 추가 전후를 같은 조건에서 비교한다.
- Cursor 다음 페이지가 누락·중복 없이 이어지는지, 만료·변조 cursor가 400인지 검증한다.
- 다른 고객의 주문이 목록에 나타나지 않는지, cursor를 조작해도 scope를 벗어나지 못하는지 검증한다.
- `allowedActions`가 각 주문 상태에서 실제 명령 성공 여부와 일치하는지 검증한다.
- 목록 응답에 결제 식별자·개인정보가 포함되지 않는지 계약 테스트로 검증한다.
- candidate window의 만료를 반환 전에 물질화하고, 네 자원 중 하나의 해제 실패가 전체 rollback과
  `503`을 만드는지 PostgreSQL 통합 테스트로 검증한다.
- 활성 필터에서 빈 페이지와 `nextCursor`가 함께 반환되어도 다음 호출에 누락·중복이 없는지 검증한다.

## Implementation evidence (2026-08-14)

- `GET /me/orders`와 `GET /me/orders/{orderReference}`는 Session의 `CustomerActor`만 사용하며 요청
  customer ID를 받지 않는다. 존재하지 않는 공개 주문번호는 404, 다른 고객 소유 주문은 403이다.
- 목록은 R1 read-only candidate scan, candidate window 전체를 묶는 W1 expiry transaction, R2
  read-only fixed-candidate projection으로 분리했다. W1은 주문 ID 순으로 기존 만료 명령을 호출하며
  한 건의 Audit/예약 해제 실패도 모든 candidate 만료를 rollback하고 503으로 드러낸다.
- 한 건과 101건 목록 모두 candidate/header/line의 정확히 세 SQL을 사용했다. 20개 만료 candidate가
  모두 ACTIVE 결과에서 빠져 첫 page가 비어도 scan boundary cursor를 반환하고, 다음 page의 마지막
  candidate까지 누락·중복 없이 만료됨을 PostgreSQL 통합 테스트로 고정했다.
- V55가 `(customer_id, created_at DESC, id DESC)` 인덱스를 설치한다. 동일한 10,000행 fixture의
  `EXPLAIN (ANALYZE, BUFFERS)`는 `Seq Scan + Sort` 3.924 ms에서 named `Index Scan` 1.235 ms로
  바뀌었다. 에뮬레이션 환경의 plan evidence이며 운영 SLA 주장이 아니다. 상세 조건은
  [Customer order list query plan evidence](../quality/customer-order-list-performance-evidence.md)에 있다.
- 상태별 `allowedActions`, 30일 기본·무상한 명시 기간, 서명 cursor의 customer/status/date binding,
  변조·만료·형식 오류, immutable display snapshot, 축약 환불 recovery와 내부 식별자 비노출을
  단위·HTTP·PostgreSQL 계약 테스트로 검증했다.

## Metrics

- 주문 목록 조회 p50·p95·p99
- 목록 조회 SQL 수
- Cursor 다음 페이지 오류율
- 페이지 크기별 응답 크기

## Revisit Conditions

- 주문 수가 커져 단일 테이블 조회로 목표 지연을 만족하지 못한다고 측정될 때
- 검색·필터 요구가 늘어 정렬 키 조합이 인덱스로 감당되지 않을 때
- 여러 Context를 조인해야 하는 목록 요구가 생길 때

## Related Decisions

- [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)
- [ADR-096](ADR-096-public-order-reference.md)
- [ADR-098](ADR-098-order-display-snapshots.md)
- [ADR-100](ADR-100-store-order-board-read-model.md)
