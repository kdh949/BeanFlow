# ADR-100: 점주 주문보드의 상태·픽업 시간 중심 Query

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Store order board](../exec-plans/completed/productization-60-store-order-board.md)

## Context

현재 점주 주문 조회는 `GET /store-orders/{orderId}` 단건이고, 화면은 UUID 입력창이다. 실제 매장
업무는 "지금 접수해야 할 주문", "제조 중인 주문", "픽업 대기 주문"을 픽업 예정 시각 순으로 보는
것이다. 단건 조회로는 이 업무를 할 수 없다.

주문보드는 고객 주문 목록과 접근 패턴이 다르다.

| 축 | 고객 목록 | 점주 보드 |
|---|---|---|
| 범위 | 한 고객의 전체 이력 | 한 매장의 모든 실행 주문 |
| 정렬 | 최신순 | 수락 deadline 또는 픽업 예정 시각순 |
| 크기 | 시간이 갈수록 증가 | polling snapshot은 lane별 최대 50건, 초과분은 on-demand queue |
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
  보드 또는 overflow queue에서 접근 가능하게 한다. `PENDING_PAYMENT`와 종료 상태는 포함하지 않고
  완료·취소 이력은 별도 경로로 조회한다.
- API lane `PENDING_ACCEPTANCE`는 Domain `PAID`의 표현 이름이며 새 상태가 아니다. 이 lane은
  `(acceptance_deadline_at ASC, id ASC)`, 나머지 lane은
  `(pickup_window_start_snapshot ASC, id ASC)`로 정렬한다. 응답은 `pickupBusinessDate`로 그룹화한다.
- 3초 polling `GET /stores/{storeId}/orders`는 lane마다 앞선 50건만 canonical Projection·line batch·ETag에
  포함한다. 51번째가 있으면 `overflow[]`에 lane, 정확한 `overflowCount`, 50번째 row 뒤의 signed keyset
  cursor를 넣는다. `overflow=[]`는 모든 실행 주문이 보드 snapshot에 있음을 뜻한다.
- `GET /stores/{storeId}/orders/overflow?lane={lane}&cursor={cursor}`는 요청한 lane에서 cursor 뒤의 다음
  최대 50건을 반환한다. cursor는 [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)의 common HMAC
  codec으로 endpoint·storeId·lane·sort tuple을 결합하고 15분 뒤 만료한다. 매 요청 membership을 다시
  확인한다. cursor 만료·변조·다른 store/lane 재사용은 repository query 없이 `400 INVALID_REQUEST`이며,
  queue read/ETag 실패는 빈 목록 fallback이 아닌 `503 DEPENDENCY_UNAVAILABLE`이다. 브라우저는 cursor
  `400`을 받은 경우 local queue와 board validator를 버리고 unconditional board snapshot을 정확히 한 번
  새로 읽는다. 이 recovery는 새 cursor의 자동 queue 재시도가 아니며, 사용자가 다시 queue를 열어야 한다.
- overflow queue는 사용자가 명시적으로 열고 다음 page를 요청할 때만 읽는다. 기본 polling은 queue를
  재조회하지 않는다. UI는 overflowCount와 queue 진입을 항상 표시하므로, lane의 50건 상한이 주문 완료나
  누락으로 보이지 않는다.
- 보드 validator는 cursor issuance/expiry를 제외한 canonical 의미 Projection의 SHA-256 weak ETag다.
  따라서 `304`는 보드 내용이 같다는 뜻이지 overflow cursor의 TTL 또는 유효성을 연장했다는 뜻이 아니다.
  cursor의 TTL·scope·무결성은 ETag가 아니라 HMAC 검증과 매 요청 membership 확인으로 보호한다.
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

### 4. 모든 실행 주문을 매 3초 snapshot에 무제한 반환

- 장점: 처음 구현은 단순하고 별도 queue UI가 없다.
- 단점: 상태 전이 worker 장애, 미수령 READY 누적 또는 대형 매장 backlog에서 header·line `IN`·JSON
  serialization·SHA-256 ETag가 모두 행 수에 비례한다. 단순 `LIMIT`은 나머지 주문을 사라진 것처럼
  보이게 하므로 제품 규칙도 위반한다.

## Rationale

보드의 정상 크기는 작고 조회 빈도는 높지만, backlog는 정상 크기를 보장하지 않는다. 기본 snapshot을
lane별 50건으로 제한하면 line batch·response serialization·ETag hash의 작업량을 상한화한다. 정확한
overflowCount는 overflow lane에서만 indexed count로 읽고, 오래된 작업은 keyset queue로 보존한다.
비정규화는 크기가 아니라 조인 복잡도가 문제일 때 쓰는 도구인데, 여기서는 스냅샷 컬럼
([ADR-098](ADR-098-order-display-snapshots.md)) 덕분에 조인이 없다.

## Consequences

- 인덱스가 하나 추가된다. `state`가 자주 바뀌므로 인덱스 갱신 비용이 쓰기 경로에 더해진다.
  측정 대상이다.
- 상태 전이 API가 `orderReference` 경로로 바뀐다. 기존 `PATCH /store-orders/{orderId}/status`는
  전환 기간 동안 유지한다.
- 409 충돌이 정상 응답이 되므로 프론트엔드가 이를 오류 화면이 아니라 재조회로 처리해야 한다.
- command 계약에 `expectedStatus`가 추가되고, 불가능한 action/status 조합은
  `422 ORDER_ACTION_NOT_ALLOWED`로 분리된다.
- 기본 board response에 required `overflow`가 추가되고, overflow queue endpoint와 15분짜리 signed cursor가
  추가된다. 기존 `groups`와 item 필드는 제거·변경하지 않는다.
- cursor 재발급 값을 제외한 weak ETag를 사용한다. strong response validator를 유지하면 같은 보드라도
  snapshot마다 새 cursor 때문에 conditional polling이 항상 cache miss가 된다. 그 대가로 304는 response
  byte 동등성이나 cursor lease를 증명하지 않으며, client는 cursor 400 뒤 새 snapshot을 한 번 읽어야 한다.
- 전체 lane의 exact count를 매 polling에 계산하지 않는다. 51번째가 확인된 overflow lane만 count하며,
  count query와 queue page의 rows/bytes/hash time을 별도 metric으로 관찰한다.
- 완료·취소 주문의 매장 조회 경로가 별도로 필요하다. 이 ADR 범위 밖이며 P1이다.

## Verification

- 다른 매장의 주문이 목록에 나타나지 않는지, membership 없는 점주가 403인지 검증한다.
- `REVOKED` membership이 즉시 차단되는지 검증한다.
- 두 요청이 동시에 같은 주문을 전이시킬 때 하나만 성공하고 다른 하나가 409인지 검증한다.
- 잘못된 상태 전이 요청이 409 또는 422로 명확히 구분되는지 검증한다.
- `expectedStatus`가 같은 두 동시 요청 중 하나만 성공하고 패자는 409인지, action/status 조합 자체가
  불가능하면 422인지 검증한다.
- lane별 50/51/대규모 mixed backlog에서 기본 snapshot이 lane당 50건을 넘지 않고, line query와 payload가
  최대 200개 주문에 묶이는지 검증한다.
- overflowCount가 정확하고, 같은 store/lane signed cursor가 누락·중복 없이 50건 page를 완주하며 다른
  store/lane·변조·만료 cursor는 400 및 repository 미호출인지 검증한다.
- cursor 재발급만으로 weak ETag가 바뀌지 않고, 304가 cursor TTL을 연장하지 않으며, cursor 400 뒤
  브라우저가 새 board snapshot을 정확히 한 번 읽고 queue를 자동 재시도하지 않는지 검증한다.
- `EXPLAIN ANALYZE`로 실제 production의 lane-bounded SQL과 overflow count/keyset SQL이 인덱스를 사용하는지
  확인하고, mixed state fixture에서 response byte size와 ETag hash 시간을 함께 기록한다.
- `allowedActions`가 실제 전이 성공 여부와 일치하는지 검증한다.

## Metrics

- 주문보드 조회 p50·p95·p99
- 조회 주기별 요청 수와 DB CPU
- 상태 전이 충돌(409) 비율
- 잘못된 전이 요청 수
- 매장 권한 거부 수
- 주문 생성부터 보드 노출까지의 시간
- lane별 overflow 응답 수·overflowCount distribution·queue page 수
- 기본 snapshot/queue page rows, response byte size, ETag canonicalization/hash duration

## Implementation Results

2026-08-14 Plan 60의 최초 JDBC Projection은 Order header와 batched line 두 문장으로 구현됐지만, 모든
실행 주문을 무제한 반환했고 성능 fixture도 production mixed-state SQL이 아닌 단일 `READY LIMIT 50`만
확인했다. review remediation은 이 ADR의 lane-bound/overflow queue 계약을 먼저 기록한 뒤 구현·실측한다.

V56은 결정한 두 인덱스를 그대로 설치한다. PostgreSQL 17.5 / PostGIS 3.5의 20,000행 고정 fixture에서
repository와 같은 four-lane `UNION ALL ... LIMIT 51` primary SQL은 PAID partial
`ix_acceptance_fixture`와 나머지 lane의 `ix_board_fixture` **Index Scan**을 사용했고, 단일
emulated final run은 17.451 ms → 9.148 ms였다. PAID keyset overflow page는 6.637 ms → 2.391 ms였지만,
overflow count는 800 heap fetch가 있는 Index Only Scan으로 15.006 ms → 14.751 ms였고, 직전 같은 환경
run은 7.758 ms → 18.139 ms여서 개선으로 주장하지 않는다. 1,000건 단일 실행 쓰기 표본은 두 인덱스가
있는 경우 final run에서 insert와 상태 전이 시간이 각각 225.1%, 245.3% 높았으며 직전 run은
90.4%, 82.1%였다. 이 결과는 SLA나 운영 성능 주장이 아니며, native concurrent multi-Store load와
p95/p99는 아직 측정하지 않았다. 상세 조건과 원시는
[성능 증거](../quality/store-order-board-performance-evidence.md)에 기록한다.

전이는 기존 `PESSIMISTIC_WRITE`와 Aggregate 메서드를 재사용하고, 새 operation
`STORE_ORDER_BOARD_ACTION_V1`에 action·expectedStatus를 포함한 canonical payload를 저장한다. exact
replay, 409 경쟁 패자, 422 불가능 조합, 각 advertised action의 실제 성공, 거절 알림 실패 후 독립
`RETRY_SCHEDULED`를 PostgreSQL 통합 테스트로 고정했다.

## Revisit Conditions

- overflow lane count 또는 queue page 수가 지속적으로 커져 indexed count/keyset query가 목표 지연을
  만족하지 못한다고 측정될 때
- 보드에 완료 이력과 통계를 함께 표시해야 할 때
- 상태 전이 충돌 비율이 실제 운영에서 문제가 될 때

## Related Decisions

- [ADR-099](ADR-099-customer-order-read-model.md)
- [ADR-098](ADR-098-order-display-snapshots.md)
- [ADR-102](ADR-102-polling-before-sse.md)
- [ADR-027](ADR-027-store-membership-authorization.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
