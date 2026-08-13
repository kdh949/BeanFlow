# 점주가 상태별 주문보드로 매장을 운영한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/productization-10-public-order-reference.md`, `docs/exec-plans/completed/productization-40-merchant-account-and-initial-password.md`, `docs/exec-plans/completed/productization-50-customer-order-read-model.md`
> **Completed-At:** `2026-08-14`

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
  request  { action, expectedStatus, reason? }
  response 200 또는 REJECT의 202 StoreOrderBoardItem
  충돌     409 { code: "ORDER_STATE_CONFLICT" }
  비허용   422 { code: "ORDER_ACTION_NOT_ALLOWED" }
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

- 2026-08-14: exact Plan 50 completion `fcd5a2319a9e44ac2c7eb242b5db789319b82e0a`에서
  `feature/productization-60-store-order-board`를 만들고 Plan 60 범위와 디자인 ZIP의 POS 3열 흐름을
  다시 확인했다. A안에 따라 `expectedStatus` command precondition과 409/422 분리 계약을 먼저 기록했다.
- 2026-08-14: V56에 store/state/pickup 정렬 index와 PAID acceptance-deadline partial index를 추가하고,
  매장 predicate를 SQL에 고정한 JDBC Projection을 구현했다. Order header 한 문장과 line batch 한 문장으로
  활성 주문 1건과 50건이 모두 두 SQL statement를 사용한다.
- 2026-08-14: 목록·상세·전이 API, canonical SHA-256 ETag와 304, 매 요청 membership 확인, 403/404
  store scope 구분, 실패 시 503, 서버 계산 `allowedActions`, `expectedStatus` 경쟁 검출과 별도
  `STORE_ORDER_BOARD_ACTION_V1` 멱등 namespace를 구현했다. 기존 UUID lifecycle endpoint는 바꾸지 않았다.
- 2026-08-14: 첨부 디자인 ZIP의 `kit/PosScreen.jsx`를 화면 계약으로 사용해 접수 대기/제조 중/준비 완료
  3열 보드를 구현했다. Domain `ACCEPTED`와 `PREPARING`은 디자인의 제조 중 한 열에 함께 표시하며,
  ACTIVE membership 단일 매장은 즉시 열고 다점포 계정만 선택기를 노출한다. UUID 입력은 제거했다.
- 2026-08-14: PostgreSQL 17 Testcontainers에서 cross-store·REVOKED·privacy·미래 픽업·date grouping,
  1/50건 SQL 수, ETag 시간 경계·hash 장애, exact replay·409/422, 모든 action과 2요청 동시 전이를 검증했다.
  `*StoreOrder*`, `*OrderBoard*`, Spotless와 최종 full build 1,091 tests(0 failures, 0 errors, 1 skipped)가
  통과했다. 첫 full build에서는 runtime 계약의 이전 inline schema 기대와 Support timeline fixture의
  비결정적 timestamp가 각각 한 건 실패했으며, 실제 runtime `$ref`와 DB check를 보존하는 결정적 fixture로
  고친 뒤 집중 테스트와 전체 build를 다시 통과시켰다.
- 2026-08-14: 프론트엔드 Vitest는 6 files/35 tests가 통과했다. 브라우저에서 1440×1000 3열 배치,
  375px 수평 보드, ETag conditional polling, visibility pause/resume, 다점포 전환과 console error 0건을
  확인했다. 추가 `npm run build`는 Plan 80/90 소유의 기존 customer/merchant mutation 세 곳이
  `X-BEANFLOW-CSRF`를 아직 보내지 않아 종료 코드 2로 실패했다. Plan 60 주문보드 mutation은 CSRF를
  전송하며 이 범위 밖의 세 경로를 수정하지 않았다.
- 2026-08-14: `PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh`와 `git diff --check`가 통과했다.
  문서 완료 이동과 successor path 갱신 뒤 같은 검증을 다시 실행한다.
- 2026-08-14: 완료 commit `97412d59198cf2459cd9cb3ca7706e91e86ac452`를 push하고
  [Draft PR #68](https://github.com/kdh949/BeanFlow/pull/68)을 exact Plan 50 base로 생성했다. 생성 직후
  local/remote/PR head가 일치했고, Stack A #55/#57/#64/#65/#66/#67/#68의 정확한 open Draft topology와
  금지 branch/PR 부재를 검증한 뒤 shared migration-writer lease를 해제했다. merge·ready 전환은 하지 않았다.

## Surprises & Discoveries

- target 계약의 `StoreOrderBoardItem.lane`과 `acceptancePhase`가 필수였지만 `COMPLETE`·`REJECT` 응답은
  종료 상태라 활성 lane이 없다. 기존 ADR-015의 202 보상 응답을 보존하기 위해 두 필드는 실행 상태에서만
  제공하고, REJECT 응답에는 축약 `compensationRecovery`만 제공하도록 계약을 교정한다.
- 계획 본문의 "조건부 UPDATE" 설명과 실제 구현이 달랐다. 기존 전이 서비스는 Order row
  `PESSIMISTIC_WRITE`로 직렬화하므로 이를 재사용하고 `expectedStatus` 비교로 경쟁 패자를 식별한다.
- Plan 40의 `GET /merchant/me/stores` 구현과 테스트는 최상위 배열을 반환하지만 target OpenAPI의
  `MerchantStoreList`만 `items` wrapper로 남아 있었다. 기존 동작을 깨지 않고 실제 배열 계약으로
  target/runtime/generated type을 맞추고 계약 테스트를 추가했다.
- 다른 매장의 공개 주문번호가 존재하는지 판별하기 전에 membership을 확인하지 않으면 membership 없는
  actor가 404/403 차이를 관찰할 수 있었다. 전이 facade가 reference resolution 전에 membership을 확인하고,
  locked transition에서 다시 확인하도록 두 경계를 유지했다.
- `COMPLETE` action fixture가 결제·정산 입력 없이 Order state만 직접 바꾸면 기존 completion 경로가
  `DEPENDENCY_UNAVAILABLE`로 올바르게 실패했다. assertion을 약화하지 않고 실제 approved Payment를 만든 뒤
  모든 광고 action 성공을 검증했다.
- 첫 전체 build에서 Support timeline의 `unlinked_at=now()`가 매우 드물게 `linked_at`보다 1.382ms 빨라
  DB check를 위반했다. 정책이나 constraint를 낮추지 않고 `linked_at + interval '1 microsecond'`로
  시간 순서를 결정적으로 만들었다.
- 20,000-row 단일 측정에서 두 read plan은 Seq Scan+sort에서 Index Only Scan으로 바뀌었지만, 1,000-row
  insert와 `PAID→ACCEPTED` update sample은 각각 약 31.0%, 31.3% 느려졌다. 이 결과는 emulated local
  sample이며 성능 향상·SLA 주장이 아니다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-11 | 조건부 Polling으로 시작하고 SSE는 측정 후 재검토 | [ADR-102](../../adr/ADR-102-polling-before-sse.md) |
| 2026-08-11 | 보드 전용 Query Repository를 고객 목록과 분리 | [ADR-100](../../adr/ADR-100-store-order-board-read-model.md) |
| 2026-08-11 | 전이 로직을 새로 만들지 않고 기존 서비스를 재사용 | 이 plan |
| 2026-08-12 | 오늘로 제한하지 않고 모든 실행 주문을 날짜별로 반환 | [BR-06](../../product/business-policy-decisions.md), [ADR-100](../../adr/ADR-100-store-order-board-read-model.md) |
| 2026-08-14 | action에 client가 본 `expectedStatus`를 묶고, stale/경쟁 상태는 409, 불가능한 action/status 조합은 422로 구분 | [ADR-100](../../adr/ADR-100-store-order-board-read-model.md), [Error Catalog](../../api/error-catalog.md) |
| 2026-08-14 | 디자인의 세 열을 유지하면서 Domain `ACCEPTED`와 `PREPARING`을 화면의 제조 중 열로 합친다 | 첨부 ZIP `kit/PosScreen.jsx`, 이 plan |
| 2026-08-14 | Plan 40의 실제 top-level 매장 배열을 canonical `MerchantStoreList` 계약으로 유지한다 | target/runtime OpenAPI, `StoreOrderBoardOpenApiContractTest` |

## Outcomes & Retrospective

- 점주는 내부 주문 UUID 없이 ACTIVE membership 매장을 선택하고, 모든 픽업 영업일의 실행 주문을
  접수 대기/제조 중/준비 완료 열에서 확인해 공개 주문번호로 전이할 수 있다.
- 보드는 고객·결제 식별자를 노출하지 않고 매 요청 store scope를 확인한다. 조회·hash 장애는 빈 보드나
  stale 응답이 아닌 503이며, 권한 상실은 기존 보드를 즉시 제거한다.
- canonical projection ETag, hidden-tab polling 정지, 304 재사용과 상태 충돌 후 새로고침으로 polling의
  첫 운영 계약을 닫았다. SSE 전환 여부는 ADR-102 재검토 조건 전에는 열지 않는다.
- V56 read index 효과와 write amplification을 같은 PostgreSQL fixture에서 함께 기록했다. native mixed-load
  p95/p99는 측정하지 않았으며 production 성능으로 일반화하지 않는다.
- Plan 60 필수 검증은 모두 통과했다. 추가 frontend 전체 build의 기존 Plan 80/90 CSRF 세 오류는
  미해결로 명시하며, 이 완료가 해당 후속 화면의 build 완료를 뜻하지 않는다.
- Plan 60 Draft PR과 Stack A의 정확한 seven-PR topology까지 검증했다. Stack A 내부 PR은 모두 open
  Draft로 유지하며 이 결과는 merge 또는 deployment 완료를 뜻하지 않는다.

## Revision Notes

- 2026-08-11: 최초 작성.
- 2026-08-14: 구현 시작과 상태 전이 precondition·종료 응답 계약을 반영.
- 2026-08-14: 구현·성능·브라우저·전체 회귀 결과와 실패 이력을 기록하고 완료 처리.
- 2026-08-14: Draft PR #68, final Stack A topology 검증과 migration-writer lease 해제를 기록.
