# 점주가 상태별 주문보드로 매장을 운영한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/productization-10-public-order-reference.md`, `docs/exec-plans/completed/productization-40-merchant-account-and-initial-password.md`, `docs/exec-plans/active/productization-50-customer-order-read-model.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

UUID 입력 조회를 상태별 실행 주문 보드로 바꾼다. 점주는 매장을 고르고, 픽업 날짜와 무관하게
접수 대기·제조 중·준비 완료 주문을 보고 카드에서 바로 상태를 전이시킨다.

이 plan이 끝나면 고객 주문이 점주 화면에 나타나고 처리되는 전체 경로가 완성된다.

## Current State

- `productization-10`이 `publicReference`, `pickupNumber`, 픽업 시간 스냅샷을 제공한다.
- `productization-40`이 점주 계정, Session, `GET /merchant/me/stores`를 제공한다.
- 상태 전이는 `PATCH /store-orders/{orderId}/status`와 `StoreOrderTransitionService`가 이미 구현했다.
  수락·거절·제조·준비·완료 전이와 2분 경고·3분 timeout이 동작한다
  ([ADR-015](../../adr/ADR-015-store-acceptance-timeout-compensation.md)).
- 매장 주문 목록 조회가 없다.
- `ConsolePages.tsx`가 UUID 입력창을 노출한다.
- 기존 전이 서비스는 `findLockedById`의 `PESSIMISTIC_WRITE`로 주문을 직렬화한다. 새 endpoint도 이
  경계를 그대로 재사용한다.

## Definitions

- **Order board:** 매장의 활성 주문을 상태별 열로 보여주는 화면과 그 조회 계약이다.
- **Executable state:** 보드에 표시되는 `PAID`, `ACCEPTED`, `PREPARING`, `READY`다.
- **PENDING_ACCEPTANCE lane:** Domain `PAID`를 화면에서 표시하는 lane 이름이다. 새 Order 상태가 아니다.
- **Conditional polling:** `ETag`와 `If-None-Match`를 사용해 변경이 없으면 `304`를 받는 주기 조회다.
- **Transition conflict:** 두 요청이 같은 주문을 동시에 전이시켜 하나가 실패하는 상황이다.

## Scope

### In Scope

- `StoreOrderBoardQueryRepository`와 Projection DTO
- `GET /stores/{storeId}/orders` 상태별 활성 주문 목록
- `GET /stores/{storeId}/orders/{orderReference}` 상세
- `POST /stores/{storeId}/orders/{orderReference}/transitions` 상태 전이
- `GET /merchant/me/stores`를 사용하는 단일/다점포 매장 선택·전환 UI
- `allowedActions` 계산
- `ETag` / `If-None-Match` 조건부 응답
- `(store_id, state, pickup_window_start_snapshot, id)` 인덱스와 실행계획 검증
- 동시 전이 충돌 처리(조건부 UPDATE 또는 낙관적 잠금)
- 프론트엔드 점주 콘솔의 UUID 입력 제거와 Polling 생명주기

### Non-goals

- SSE·WebSocket
- 완료·취소 이력 조회(P1)
- 메뉴·재고·슬롯 관리(P1)
- 부분 환불 preview·실행(P0, 별도 Plan 90과 ADR-108 소유)
- 매장 비교 지표와 대시보드(P1)

## Business Rules and Invariants

1. 매 요청 `StoreMembership`을 확인한다. 역할만으로 통과하지 않는다.
2. 매장 범위를 SQL predicate에 포함한다. 조회 후 필터링하지 않는다.
3. `REVOKED` membership은 즉시 차단된다.
4. 보드는 픽업 날짜와 무관하게 모든 실행 상태 주문을 반환한다. `PENDING_PAYMENT`와 종료 주문은
   별도 경로다.
5. `PENDING_ACCEPTANCE`는 수락 deadline, 나머지 lane은 픽업 예정 시각 오름차순이다. 픽업 영업일과
   표시 시각은 스냅샷을 사용한다.
6. 동시 전이에서 하나만 성공한다. 마지막 쓰기가 이기게 두지 않는다.
7. `allowedActions`는 서버가 계산한다.
8. 보드 응답에 고객 개인정보와 결제 식별자를 포함하지 않는다.
9. 매장에는 축약 보상 요약만 노출한다. step 배열·시도 횟수·내부 오류 코드는 제외한다
   ([ADR-030](../../adr/ADR-030-customer-cancellation-authorization.md)).

## Architecture and Transaction Boundaries

```text
GET /stores/{storeId}/orders
  ArgumentResolver → MerchantActor
  StoreAccessOperations.requireStoreAccess(actorId, storeId, roles)
  @Transactional(readOnly = true)
    StoreOrderBoardQueryRepository.findExecutableBoard(storeId)
    PAID phase 계산: OPEN | WARNING | TIMEOUT_PENDING
    정렬된 canonical Projection의 SHA-256 ETag 계산
    If-None-Match 일치 → 304, response body 없음
    불일치 → 200 + Projection

POST /stores/{storeId}/orders/{orderReference}/transitions
  StoreAccessOperations.requireOrderManagementAccess(...)
  Tx1: orderReference로 주문 조회 + 매장 일치 확인
  Tx1: 기존 StoreOrderTransitionService로 전이 (조건부 UPDATE)
       영향 행이 0이면 409
  응답: 전이된 주문의 Projection (재조회 없이)
```

- Query Repository는 Domain `PAID`를 API `PENDING_ACCEPTANCE` lane으로 번역한다. 미래 픽업도
  반환하며 response를 `pickupBusinessDate`로 그룹화한다.
- ETag는 `MAX(updated_at)+COUNT`가 아니라 canonical Projection에서 계산한다. 첫 버전은 304에서도
  Projection 쿼리를 실행하며 네트워크 본문만 줄인다. 별도 변경 counter는 측정 전 도입하지 않는다.
- 상태 전이는 기존 서비스와 트랜잭션 경계를 재사용한다. 새 전이 로직을 만들지 않는다.
- 외부 호출(알림)은 기존 경로대로 트랜잭션 밖 또는 이벤트로 처리한다.

## Alternatives Considered

상세는 [ADR-100](../../adr/ADR-100-store-order-board-read-model.md),
[ADR-102](../../adr/ADR-102-polling-before-sse.md)에 있다. 요약은 다음과 같다.

- 고객 목록과 Query Repository 공유: 정렬·범위·인가 축이 달라 인덱스가 어느 쪽에도 맞지 않음
- Aggregate 목록 로딩: 초 단위 반복 조회에서 비용이 누적됨
- 보드 전용 비정규화 테이블: 동기화 실패라는 새 실패 모드, 현재 크기에서 정당화 불가
- WebSocket: 연결 복구·세션·순서·프록시가 모두 새 운영 대상
- SSE: 재연결 후 어차피 전체 조회가 필요함

## Failure Semantics

- membership 없음·`REVOKED`: 403.
- 다른 매장의 `orderReference`: 403. 존재하지 않으면 404.
- 동시 전이 충돌: 409. 클라이언트는 목록을 재조회한다.
- 허용되지 않는 상태 전이: 409 또는 422로 구분한다. 두 경우를 같은 코드로 합치지 않는다.
- 조회 실패: 503. 빈 보드나 stale 데이터로 대체하지 않는다.
- Projection 조회·canonical hash 계산 실패: 503. 조건부 처리를 건너뛰어 full response를 반환하지 않는다.
- 전이 후 알림 발송 실패는 전이 성공을 되돌리지 않는다. 알림 상태는 독립적으로 유지한다
  ([ADR-019](../../adr/ADR-019-notification-retry-and-manual-recovery.md)).

## Data and Migration

```sql
CREATE INDEX ix_ordering_order_store_board
    ON ordering_order (store_id, state, pickup_window_start_snapshot, id);
CREATE INDEX ix_ordering_order_store_acceptance_board
    ON ordering_order (store_id, state, acceptance_deadline_at, id)
    WHERE state = 'PAID';
```

- `state` 값이 자주 바뀌므로 인덱스 갱신 비용이 쓰기 경로에 더해진다. 인덱스 추가 전후의 주문
  생성·전이 지연을 같은 조건에서 측정한다.
- 활성 상태만 대상으로 하는 부분 인덱스가 더 나은지 `EXPLAIN ANALYZE`로 판단하고 결과를 남긴다.
- 데이터 backfill은 없다.

## API and Event Contracts

```http
GET  /api/v1/stores/{storeId}/orders
GET  /api/v1/stores/{storeId}/orders/{orderReference}
POST /api/v1/stores/{storeId}/orders/{orderReference}/transitions
```

```text
StoreOrderBoard
  groups[]
    pickupBusinessDate
    items[]
      orderReference
      pickupNumber
      pickupBusinessDate   group date와 동일
      lane                 PENDING_ACCEPTANCE | ACCEPTED | PREPARING | READY
      status
      pickupWindowStart
      pickupWindowEnd
      itemSummary
      acceptanceDeadlineAt
      acceptancePhase      OPEN | WARNING | TIMEOUT_PENDING
      allowedActions       [ "ACCEPT", "REJECT", "START_PREPARING", "MARK_READY", "COMPLETE" ]

POST .../transitions
  request  { action, reason? }
  response 200 StoreOrderBoardItem
  충돌     409 { code: "ORDER_STATE_CONFLICT" }
```

- 응답 헤더에 `ETag`를 포함한다. 요청의 `If-None-Match`가 일치하면 `304`다.
- 기존 `PATCH /store-orders/{orderId}/status`는 전환 기간 동안 유지하고, 프론트엔드 전환 후 제거한다.
- 이벤트 계약 변경 없음. 기존 전이 이벤트를 그대로 사용한다.

## Milestones

1. migration writer lease 획득, 보드 인덱스 migration.
2. `StoreOrderBoardQueryRepository`와 Projection 구현.
3. `allowedActions` 계산과 활성 상태 집합 정의.
4. 목록·상세 endpoint와 `ETag` 조건부 응답 구현.
5. 주문번호 기반 전이 endpoint 구현(기존 서비스 재사용).
6. 동시 전이 충돌 테스트와 409 계약 확정.
7. `EXPLAIN ANALYZE` 전후 비교와 Polling 부하 측정.
8. 프론트엔드 점주 콘솔 전환(UUID 입력 제거, Polling 생명주기).
9. 단일 매장은 바로 보드를 열고 다점포 계정은 접근 가능한 매장만 전환하는 상태 검증.
10. runtime OpenAPI, 계약 테스트, runbook 갱신.

## Required Tests

- 다른 매장의 주문이 보드에 나타나지 않는지 검증한다.
- 단일 매장 membership은 별도 선택 없이 해당 보드를 열고, 다점포 membership은 매장 전환 뒤 선택한
  `storeId`로만 보드·전이 요청을 보내는지 프론트엔드 상태 테스트로 검증한다.
- `REVOKED` membership 매장은 전환 목록에서 제거되고 현재 선택 매장이 revoke되면 빈 목록이나 이전
  보드를 계속 표시하지 않고 403 상태로 전환하는지 검증한다.
- membership 없는 점주와 `REVOKED` membership이 403인지 검증한다.
- 다른 매장의 `orderReference` 조회·전이가 403인지 검증한다.
- 두 요청이 동시에 같은 주문을 전이시킬 때 하나만 성공하고 다른 하나가 409인지
  PostgreSQL Testcontainers로 검증한다.
- 허용되지 않는 전이가 409/422로 구분되는지 검증한다.
- 활성 주문 50건 기준으로 보드 조회 SQL 수가 고정인지 검증한다.
- 오늘 이후 픽업 `PAID`가 `PENDING_ACCEPTANCE` lane에 즉시 나타나고 deadline 순인지 검증한다.
- 목록이 `pickupBusinessDate`별 `groups[]`로 반환되고 각 item의 날짜가 group key와 일치하는지 검증한다.
- `PENDING_PAYMENT`와 종료 상태가 보드에서 제외되고, 나머지 lane이 픽업 시작 순인지 검증한다.
- `EXPLAIN ANALYZE`로 인덱스 사용을 확인하고 추가 전후를 같은 조건에서 비교한다.
- 변경이 없을 때 Projection 조회 후 `304`와 빈 response body가 반환되는지 검증한다.
- 주문 상태가 바뀌면 새 `ETag`와 `200`이 반환되는지 검증한다.
- DB 변경 없이 2분·3분 경계를 넘을 때 `acceptancePhase`와 `ETag`가 바뀌는지 검증한다.
- Projection 또는 hash 실패가 full response fallback이 아니라 503인지 검증한다.
- `allowedActions`가 실제 전이 성공 여부와 일치하는지 상태별로 검증한다.
- 보드 응답에 고객 개인정보·결제 식별자·보상 step 상세가 없는지 계약 테스트로 검증한다.
- 전이 후 알림 실패가 전이 상태를 되돌리지 않는지 검증한다.
- 기존 매장 주문 lifecycle과 timeout 보상 테스트가 회귀 없이 통과하는지 확인한다.

## Validation Commands

```bash
./gradlew test --tests '*StoreOrder*'
./gradlew test --tests '*OrderBoard*'
./gradlew spotlessCheck
./gradlew build --stacktrace
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

## Observability

- 보드 조회 p50·p95·p99
- Polling RPS와 `304` 비율
- 주문 생성부터 보드 노출까지의 시간
- 상태 전이 충돌(409) 비율과 잘못된 전이 요청 수
- 매장 권한 거부 수
- DB CPU와 HikariCP active·pending
- `ETag` 계산 실패 수

## Documentation Updates

- ADR-100, ADR-102 구현 결과 반영
- `docs/security/authorization-matrix.md`
- `docs/api/api-conventions.md`(조건부 요청 규칙)
- `openapi/beanflow-v1-runtime.yaml`
- `docs/operations/store-order-lifecycle-runbook.md`
- 신규 `docs/quality/store-order-board-performance-evidence.md`

## Progress

아직 시작하지 않았다.

## Surprises & Discoveries

아직 없다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-11 | 조건부 Polling으로 시작하고 SSE는 측정 후 재검토 | [ADR-102](../../adr/ADR-102-polling-before-sse.md) |
| 2026-08-11 | 보드 전용 Query Repository를 고객 목록과 분리 | [ADR-100](../../adr/ADR-100-store-order-board-read-model.md) |
| 2026-08-11 | 전이 로직을 새로 만들지 않고 기존 서비스를 재사용 | 이 plan |
| 2026-08-12 | 오늘로 제한하지 않고 모든 실행 주문을 날짜별로 반환 | [BR-06](../../product/business-policy-decisions.md), [ADR-100](../../adr/ADR-100-store-order-board-read-model.md) |

## Outcomes & Retrospective

아직 없다.

## Revision Notes

- 2026-08-11: 최초 작성.
