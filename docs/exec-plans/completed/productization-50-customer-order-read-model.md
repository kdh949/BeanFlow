# 고객이 자기 주문을 목록으로 본다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/productization-10-public-order-reference.md`, `docs/exec-plans/completed/productization-30-customer-account-and-login.md`, `docs/exec-plans/completed/productization-40-merchant-account-and-initial-password.md`
> **Completed-At:** `2026-08-14`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

UUID 입력창을 없앤다. 로그인한 고객의 활성 주문과 과거 주문을 서버가 알아서 보여준다.

이 plan은 동시에 두 가지 기술 계약을 세운다.

- Aggregate를 로딩하지 않는 DTO Projection 기반 목록 조회
- 프론트엔드가 상태 머신을 재구현하지 않게 하는 `allowedActions`

## Current State

- `productization-10`이 `publicReference`, `pickupNumber`, 매장명·픽업 시간 스냅샷을 제공한다.
- `productization-30`이 고객 계정과 Session을 제공한다. `CustomerActor`로 고객 ID를 얻는다.
- 주문 조회는 `GET /orders/{orderId}` 단건이고 Aggregate를 로딩한다.
- `GetOrderService`가 조회 시 만료된 예약을 물질화한다(부수효과가 있는 조회).
- `SignedCursorCodec`, `HmacSignedCursorCodec`가 이미 존재한다
  ([ADR-070](../../adr/ADR-070-signed-cursor-and-pagination-contract.md)).
- `PointAccountQueryRepository`가 keyset pagination Projection의 선례를 제공한다.

## Definitions

- **Query Projection:** Aggregate를 로딩하지 않고 필요한 컬럼만 DTO로 조회하는 읽기 코드다.
- **Active order:** 고객이 아직 픽업하지 않았고 종료되지 않은 주문이다. 분류는 서버가 소유한다.
- **allowedActions:** 현재 상태에서 수행 가능한 행동의 닫힌 집합이다. OpenAPI enum으로 고정한다.
- **Item summary:** "아이스 아메리카노 외 1건" 형태의 표시 문자열이다. 스냅샷 라인에서 파생한다.

## Scope

### In Scope

- `CustomerOrderQueryRepository`와 DTO Projection
- `GET /me/orders` 목록(활성·지난 주문 필터, 기간 필터, Cursor Pagination)
- `GET /me/orders/{orderReference}` 상세
- `allowedActions` 계산과 OpenAPI enum 고정
- `(customer_id, created_at DESC, id DESC)` 인덱스와 실행계획 검증
- 활성·종료 상태 분류 규칙
- 기간 필터 기본값(최근 30일)과 cursor 서명 결합
- 프론트엔드 고객 주문 화면의 UUID 입력 제거와 기간 선택 UI

### Non-goals

- 주문번호·메뉴명 부분 일치 검색
- 주문 상세의 결제·환불 원장 전체 노출
- 즐겨찾기·최근 주문 매장(이 plan 범위 밖, P0 Plan 70 소유)
- 재주문 화면(기존 API 존재, 별도 화면 작업)
- 점주·운영자 목록

## Business Rules and Invariants

1. 목록은 Session actor의 주문만 반환한다. 고객 ID를 요청에서 받지 않는다.
2. cursor에 customer scope와 필터(활성 여부, 기간)를 함께 서명하고 매 요청 인가를 다시 수행한다.
3. Projection 자체는 Aggregate를 로딩하지 않는다. 단, BR-03의 만료 물질화는 기존 Order 만료
   명령 경계를 사용한다.
4. 반환 후보인 만료 `PENDING_PAYMENT`는 worker를 기다리지 않고 먼저 Order 만료와 네 자원
   해제를 확정한다. 실패하면 stale 결과 대신 503이다.
5. 표시 값은 스냅샷을 사용한다. 매장·슬롯 테이블을 조인하지 않는다.
6. `allowedActions`는 서버가 계산한다. 프론트엔드가 상태로 분기하지 않는다.
7. 응답에 결제 식별자, provider reference, 내부 실패 코드, 고객 자유 서술 취소 사유를 포함하지 않는다.
8. 환불 진행은 축약 투영만 노출한다(`REQUESTED`, `PROCESSING`, `SUCCEEDED`, `REFUND_DELAYED`).

## Architecture and Transaction Boundaries

```text
GET /me/orders
  ArgumentResolver → CustomerActor
  Tx R1(readOnly): customer scope + filter + cursor로 limit + 1 candidate ID와 scan boundary 조회
  Tx W1: candidate 중 기한이 지난 PENDING_PAYMENT를 ID 순서로 lock
         Order 만료 + 슬롯·재고·쿠폰·포인트 예약 해제를 한 transaction에서 처리
         하나라도 실패하면 전체 rollback과 503
  Tx R2(readOnly): 고정한 candidate ID의 CustomerOrderSummary Projection 조회
                   allowedActions 계산 (순수 함수, DB 접근 없음)

GET /me/orders/{orderReference}
  customer ownership 확인 → 기존 만료 materialization 명령 → 상세 Projection
```

- candidate scan과 Projection은 각각 bounded `readOnly` transaction이다. 라인 요약은 같은 쿼리에서
  집계하거나, candidate ID로 **한 번** 추가 조회한다. 주문 건수에 비례하는 쿼리를 만들지 않는다.
- 물질화 범위는 현재 candidate window로 제한한다. 활성 필터에서 만료 Order가 빠지면 응답이 page
  size보다 짧거나 비어도 다음 window까지 쓰지 않는다. `nextCursor`는 반환 row가 아니라 candidate
  scan boundary를 기준으로 만들어 누락을 막는다.
- 상태 변경 명령만 Aggregate를 로딩한다.

## Alternatives Considered

### 1. Order Aggregate 로딩으로 목록 구성

- 장점: 기존 코드를 재사용한다.
- 단점: N+1, 불필요한 로딩, 변경 감지 비용. 주문이 늘수록 나빠진다.

### 2. 별도 CQRS Read DB

- 장점: 읽기 확장성이 높다.
- 단점: 복제 지연·재구축·일관성 표현이 새 실패 모드다. 현재 규모에 과하다.

### 3. Offset Pagination

- 장점: 구현이 단순하고 총 개수를 알 수 있다.
- 단점: 깊은 페이지에서 성능이 나빠지고, 목록이 변하면 중복·누락이 생긴다. 저장소에 이미 signed
  cursor 계약이 있다.

### 4. `allowedActions`를 프론트엔드가 계산

- 장점: 서버 응답이 작다.
- 단점: 상태 머신이 두 곳에 존재한다. 취소 가능 시각·결제 상태·보상 진행을 클라이언트가 알아야
  하는데, 그 정보를 주는 순간 응답이 더 커진다.

## Failure Semantics

- 만료·변조 cursor: 400. 빈 목록으로 대체하지 않는다.
- `from > to`, 형식 오류, 페이지 도중 필터 변경: 400. 서버가 기간을 임의로 보정하지 않는다.
- 다른 고객의 `orderReference` 상세 조회: 403. 존재하지 않으면 404.
- 조회 실패: 503. 빈 목록이나 stale 데이터로 대체하지 않는다.
- 목록·상세 만료 물질화 중 네 자원 중 하나라도 해제 실패: 전체 transaction rollback 후 503.
  이미 조회한 stale Projection이나 부분 만료 결과를 반환하지 않는다.
- 라인 요약 조회 실패: 주문 목록 전체 실패. 요약을 빈 문자열로 채우지 않는다.
- `allowedActions` 계산에 필요한 값이 누락된 주문: 명시적 실패. 빈 집합으로 조용히 넘기지 않는다.

## Data and Migration

```sql
CREATE INDEX ix_ordering_order_customer_recent
    ON ordering_order (customer_id, created_at DESC, id DESC);
```

- 활성 주문 필터가 인덱스를 쓰지 못하면 부분 인덱스 또는 `state` 포함 인덱스를 추가한다.
  선택은 `EXPLAIN ANALYZE` 결과로 결정하고 그 결과를 문서에 남긴다.
- 데이터 backfill은 없다.

## API and Event Contracts

```http
GET /api/v1/me/orders?status=ACTIVE
GET /api/v1/me/orders?status=PAST
GET /api/v1/me/orders?from=2026-01-01&to=2026-08-12&cursor=...&limit=20
GET /api/v1/me/orders/{orderReference}
```

- `from`/`to`를 지정하지 않으면 최근 30일이 기본이다. 과거 범위에 상한을 두지 않는다.
- 두 값 모두 `Asia/Seoul` 날짜이고 `to`는 그날의 끝까지 포함한다([BR-01](../../product/business-policy-decisions.md)).

```text
CustomerOrderSummary
  orderReference
  pickupNumber
  storeName
  status
  orderedAt
  pickupWindowStart
  pickupWindowEnd
  totalAmount
  itemSummary
  allowedActions   [ "CANCEL", "REORDER", "VIEW_REFUND" ]
```

- 기본 페이지 크기 20, 최대 100. cursor 계약은
  [ADR-070](../../adr/ADR-070-signed-cursor-and-pagination-contract.md)을 따른다.
- 정렬 튜플 `(created_at DESC, id DESC)`를 ADR-070에 기록한다.
- 기존 `GET /orders/{orderId}`는 유지한다. 프론트엔드 전환 후 제거 여부를 별도로 결정한다.
- 이벤트 계약 변경 없음.

## Milestones

1. migration writer lease 획득, 인덱스 migration.
2. `CustomerOrderQueryRepository`와 Projection DTO 구현.
3. 활성·종료 분류 규칙과 `allowedActions` 계산 구현.
4. 목록·상세 endpoint와 cursor 계약 구현.
5. `EXPLAIN ANALYZE` 전후 비교 측정과 증거 문서 작성.
6. 프론트엔드 고객 주문 화면 전환(UUID 입력 제거).
7. runtime OpenAPI와 계약 테스트 갱신.

## Required Tests

- 주문 100건 이상에서 목록 조회 SQL 수가 주문 수와 무관하게 고정인지 검증한다.
- `EXPLAIN ANALYZE`로 인덱스 사용을 확인하고 추가 전후를 같은 조건에서 비교한다.
- Cursor 다음 페이지가 누락·중복 없이 이어지는지 검증한다.
- 만료·변조 cursor가 400인지, 다른 고객 scope의 cursor가 거부되는지 검증한다.
- `from`/`to` 미지정 시 최근 30일만 반환되고, 지정 시 그 범위 전체가 반환되는지 검증한다.
- 페이지 도중 필터를 바꾼 cursor가 400인지 검증한다.
- `from > to`와 잘못된 날짜 형식이 400인지 검증한다.
- 30일보다 오래된 주문이 기간 지정 시 상한 없이 조회되는지 검증한다.
- 기간 필터 조회가 `(customer_id, created_at DESC, id DESC)` 인덱스를 사용하는지
  `EXPLAIN ANALYZE`로 확인한다.
- 다른 고객의 주문이 목록에 나타나지 않는지 검증한다.
- 다른 고객의 `orderReference` 상세가 403, 없는 주문이 404인지 검증한다.
- 매장명 변경 후 과거 주문 표시가 바뀌지 않는지 검증한다.
- 목록 응답에 결제 식별자·내부 실패 코드·취소 자유 서술이 없는지 계약 테스트로 검증한다.
- 각 주문 상태에서 `allowedActions`가 실제 명령 성공 여부와 일치하는지 검증한다.
- 목록과 상세가 반환 후보의 기한 지난 `PENDING_PAYMENT`를 먼저 `EXPIRED`로 물질화하는지 검증한다.
- 목록 candidate에 만료 주문이 여러 건 있을 때 하나의 자원 해제 실패가 전체 rollback과 503을
  만드는지 PostgreSQL 통합 테스트로 검증한다.
- 활성 필터에서 만료 주문이 제거돼 빈 페이지와 `nextCursor`가 함께 반환되어도 다음 페이지에
  누락·중복이 없는지 검증한다.
- 환불 진행 상태가 축약 투영만 노출하는지 검증한다.

## Validation Commands

```bash
./gradlew test --tests 'io.github.kdh949.beanflow.ordering.*'
./gradlew test --tests '*CustomerOrderQuery*'
./gradlew spotlessCheck
./gradlew build --stacktrace
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

## Observability

- 주문 목록 조회 p50·p95·p99
- 목록 조회 SQL 수
- Cursor 다음 페이지 오류율
- 페이지 크기별 응답 크기
- 403/404 발생 수

## Documentation Updates

- ADR-099 구현 결과 반영
- [ADR-070](../../adr/ADR-070-signed-cursor-and-pagination-contract.md)에 고객 주문 목록 정렬 튜플 추가
- `docs/security/authorization-matrix.md`
- `openapi/beanflow-v1-runtime.yaml`
- 신규 `docs/quality/customer-order-list-performance-evidence.md`

## Progress

- 2026-08-13: Plan 40이 account-backed Merchant Session과 기본 전체 demo smoke를 required validation으로
  완료했다. Stack A exact predecessor가 completed되어 `Implementation-Ready=true`로 전환했으며 구현은
  아직 시작하지 않았다.
- 2026-08-13: Plan 40 completion/local/remote/PR head `2642a7e`과 Draft PR #66의 OPEN/Draft,
  head `feature/productization-40-merchant-account`, base `feature/productization-30-customer-account`를
  검증했다. 같은 head의 중복 PR은 없고 GitHub build check는 조회 시점 `pending`이다. exact head에서
  `feature/productization-50-customer-orders`를 만들었으며 제품 코드는 아직 변경하지 않았다.
- 2026-08-13: 첨부 ZIP을 다시 직접 열어 41개 파일의 디자인 아카이브이며
  `kit/OrderScreen.jsx`에 `진행 중`/`지난 주문` 탭, active 상세, snapshot 매장명·픽업번호·품목
  요약이 있음을 확인했다. target OpenAPI와 ADR-099를 대조해 `PAST`를 additive 상태 필터로
  기록했고 구현 전 계약 검토를 마쳤다.
- 2026-08-14: 상태 분류와 `allowedActions`, signed cursor의 customer/status/date binding, 기본 서울
  30일과 무상한 명시 기간, 공개 주문번호 owner 403/404 계약을 구현했다. V55는
  `(customer_id, created_at DESC, id DESC)` 인덱스를 추가하고 runtime/target OpenAPI와 generated
  frontend schema를 같은 계약으로 맞췄다.
- 2026-08-14: 목록 transaction을 R1 read-only candidate scan, candidate window 하나를 묶는 W1 만료
  materialization, R2 fixed-candidate projection으로 분리했다. 101건에서도 candidate/header/line 세 SQL,
  20건 만료 rollback, 빈 ACTIVE page와 scan-boundary cursor의 다음 page 연속성, 고객 취소 후 detail의
  축약 recovery와 내부 식별자 비노출을 PostgreSQL 통합 테스트로 고정했다.
- 2026-08-14: 고객 `/app/orders`를 진행 중/지난 주문 탭, 서울 날짜 필터, cursor 더 보기와 공개번호
  상세 화면으로 교체하고 UUID 입력 화면을 제거했다. frontend 29 tests가 통과했다. 390×844 실제
  browser에서 탭·날짜·401 error/empty shell·bottom navigation과 console 무오류를 확인했지만, Plan 80의
  Customer login facade가 아직 없어 실제 데이터 카드/detail은 component test로만 검증했다.
- 2026-08-14: PostgreSQL 17.5 Testcontainers 10,000행 동일 fixture에서 V55 전 `Seq Scan + Sort`
  3.924 ms, 후 named `Index Scan` 1.235 ms를 측정했다. 에뮬레이션 query-plan 근거이며 SLA나 처리량
  개선으로 해석하지 않는다.
- 2026-08-14: 첫 `spotlessCheck`는 두 신규 Kotlin 파일 formatting으로 실패해 `spotlessApply` 후 통과했다.
  첫 전체 build는 MVC slice의 신규 service 누락과 V54를 Flyway 최신 head로 가정한 기존 migration
  assertion을 드러냈고, 이를 bean mock과 V54 자체 성공 assertion으로 고쳤다. 두 번째 build는 자동
  scheduler 읽기와 fixture `TRUNCATE`의 PostgreSQL deadlock을 드러냈다. test profile에서 모든 scheduler
  initial delay를 1시간으로 고정하고 명시적 `runOnce()` 테스트를 유지한 뒤 재현 대상과 전체 build가
  통과했다.
- 2026-08-14: 최종 required validation은 ordering 251 tests, `*CustomerOrderQuery*`, Spotless, 전체
  build 1,078 tests(0 failures, 0 errors, opt-in benchmark 1 skipped)가 통과했다. 전체 build는 57개
  격리 Spring context를 포함해 11분 46초였다. frontend `typecheck`와 `build`는 Plan 80/90이 소유한
  mutation 세 곳의 `X-BEANFLOW-CSRF` header 미구현 때문에 실패했으며 새 주문 화면 오류는 없었다.
- 2026-08-14: completion 이동 직후 첫 문서 검증은 ADR-099와 docs index의 active Plan 50 링크 두 개를
  broken link로 보고해 실패했다. 두 링크를 completed 경로로 교정한 뒤 동일 검증이 46 policies,
  111 ADRs, 276 Markdown, 57 ExecPlans와 target/runtime OpenAPI 계약으로 통과했다.

## Surprises & Discoveries

- 기존 `GetOrderService`가 조회 중 예약 만료를 물질화하며 BR-03은 목록에도 같은 실패 의미를
  요구한다. 목록은 page candidate window로 쓰기 범위를 제한하고, 전체 물질화 실패 시 stale
  Projection 대신 503을 반환한다.
- 첨부 ZIP은 구현 저장소가 아니라 실제 화면 HTML, `kit/*.jsx`, 디자인 시스템과 스크린샷을 담은
  41-file 디자인 아카이브다. `kit/OrderScreen.jsx`의 품목 요약 예시는 음료 단위 `외 1잔`이지만
  ExecPlan의 공개 문자열 계약은 이종 메뉴에도 적용 가능한 `외 1건`이므로 후자를 유지한다.
- ZIP은 `진행 중`과 `지난 주문`을 별도 탭으로 요구하지만 target OpenAPI의 상태 enum은
  `ACTIVE`만 적혀 있었다. 서버가 활성·종료 분류를 소유한다는 ADR-099를 지키기 위해 기존 호출을
  깨지 않는 `PAST` 값을 추가했다.
- 처음의 목록 구현은 candidate 조회·만료·projection을 하나의 read/write transaction에 두어 이 Plan이
  명시한 R1/W1/R2 경계를 충족하지 않았다. proxy가 분리된 read와 expiry component로 바꿔 read-only
  candidate snapshot과 원자적 bounded write, 이후 projection을 실제 transaction 세 개로 고정했다.
- 전체 build의 두 번째 실패는 제품 lock 순서가 아니라 Testcontainers fixture 정리 중 background
  scheduler가 자동으로 읽기를 시작한 테스트 환경 deadlock이었다. 개별 재시도로 숨기지 않고 test
  application의 28개 initial-delay property와 Session cleanup cron을 비활성 시점으로 옮겼다. 제품
  scheduler 기본값과 worker의 명시적 단위·통합 테스트는 바꾸지 않았다.
- 전체 build는 deadlock 제거 뒤에도 57개 격리 Spring context의 Spring Modulith metadata 계산과 1GB
  test heap GC 때문에 11분 46초가 걸렸다. thread dump에서 Test worker가 runnable이고 scheduler가
  timed-waiting임을 확인해 정지로 오판하지 않았으며 최종 정상 종료를 기다렸다.
- runtime OpenAPI의 CSRF header 계약이 먼저 생성되면서 frontend 전체 typecheck/build는 아직 Plan 80
  customer mutation 두 곳과 Plan 90 operations refund 한 곳에서 실패한다. Plan 50의 read-only 화면은
  type error가 없고 component tests는 통과했지만, 후속 소유 범위를 앞당겨 CSRF client를 구현하지 않았다.
- 실제 browser는 Plan 80의 Customer Session facade 전이 전이라 `/me/orders`에서 명시적 401을 보였다.
  인증을 우회하거나 cookie를 주입하지 않고 error state와 layout만 검증했으며 실제 데이터 UI 상태는
  mock 기반 component test 증거로 한정했다.
- 브라우저 로컬 날짜로 기본 30일을 계산하면 한국 밖 timezone과 UTC 자정 경계에서 API의
  `Asia/Seoul` 날짜 계약과 하루가 어긋난다. `Intl.DateTimeFormat`의 서울 timezone으로 고정하고 경계
  테스트를 추가했다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-11 | 물리적 CQRS 없이 같은 DB의 Query Projection | [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |
| 2026-08-11 | `allowedActions`를 서버가 계산해 반환 | [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |
| 2026-08-12 | 기본 30일 + `from`/`to` 필터, 과거 조회 상한 없음 | [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |
| 2026-08-12 | 필터를 cursor에 함께 서명해 페이지 도중 변경을 400으로 거부 | [ADR-070](../../adr/ADR-070-signed-cursor-and-pagination-contract.md) |
| 2026-08-12 | 목록도 candidate window의 만료 주문을 먼저 물질화하고 실패 시 503 | [BR-03](../../product/business-policy-decisions.md), [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |
| 2026-08-13 | 상태 생략은 전체, `ACTIVE`는 진행 주문, `PAST`는 종료 주문으로 서버가 분류 | [ADR-099](../../adr/ADR-099-customer-order-read-model.md), [ADR-070](../../adr/ADR-070-signed-cursor-and-pagination-contract.md) |
| 2026-08-14 | 목록은 R1 candidate scan → bounded atomic W1 expiry → R2 fixed-candidate projection의 세 transaction으로 실행 | [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |
| 2026-08-14 | ACTIVE 만료로 응답 row가 비어도 `nextCursor`는 candidate scan boundary로 만들고 다음 window를 채우지 않음 | [ADR-070](../../adr/ADR-070-signed-cursor-and-pagination-contract.md), [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |
| 2026-08-14 | Plan 50 UI는 `allowedActions.CANCEL` 안내만 표시하고 CSRF mutation·실제 취소 버튼은 Plan 80까지 만들지 않음 | [productization-80](../active/productization-80-customer-web-p0-integration.md) |
| 2026-08-14 | 통합 테스트의 background scheduler는 test profile에서 1시간 뒤로 미루고 worker 검증은 명시적 `runOnce()`로 유지 | 이 plan |
| 2026-08-14 | 고객 주문 기본 날짜는 browser timezone이 아니라 `Asia/Seoul`로 계산 | [BR-01](../../product/business-policy-decisions.md), [ADR-099](../../adr/ADR-099-customer-order-read-model.md) |

## Outcomes & Retrospective

- V55와 DTO Projection 기반 고객 주문 목록·상세, signed cursor, 상태 분류, `allowedActions`, bounded
  만료 materialization을 구현했다. 고객은 UUID를 입력하지 않고 공개 주문번호, immutable 매장·픽업·
  메뉴 snapshot과 축약 환불 상태만 본다.
- required ordering/query/Spotless/full-build 검증이 모두 통과했다. 최종 full build는 1,078 tests 중
  failures/errors 0, 의도적으로 opt-in인 nearby benchmark 1건 skip이다. 동일 10,000행 plan capture는
  V55가 전체 scan+sort를 named keyset index scan으로 바꿈을 증명했다.
- frontend component 29 tests와 mobile browser의 layout/error-state QA가 통과했다. 실제 account-backed
  browser 데이터 흐름과 CSRF mutation client는 Plan 80 소유라 완료했다고 주장하지 않으며, 현재 전체
  frontend typecheck/build의 세 CSRF header 실패도 후속 dependency로 명시한다.
- 초기 transaction 경계 불일치, stale Flyway-head assertion, MVC slice 누락과 test scheduler deadlock을
  숨기지 않고 각각 proxy 경계 분리, version-local assertion, mock 보강과 test-only scheduler 격리로
  해소했다. Plan 60은 이 completed head의 공개 주문번호와 Merchant Session을 사용해 매장 주문보드를
  구현할 수 있다.

## Revision Notes

- 2026-08-11: 최초 작성.
- 2026-08-13: Plan 40 completion dependency와 Stack A readiness를 반영.
- 2026-08-14: 고객 주문 read model·화면·V55와 실제 검증, 실패·복구 및 후속 범위를 기록.
