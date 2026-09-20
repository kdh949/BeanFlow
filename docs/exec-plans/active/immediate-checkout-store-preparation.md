# 슬롯 없는 즉시 결제와 매장 준비시간 구현

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객이 장바구니에서 픽업 슬롯과 5분 예약 단계를 거치지 않고 기존 Toss V2 Standard Payment Window로
바로 진입하게 한다. 주문 제출 성공은 서버가 승인·실제 혜택 사용·정산 입력·Order `PAID`를 commit한 뒤에만
표시한다. 매장은 수락 시 준비시간을 한 번 정하며 기존 제조·준비·픽업 완료와 정산 흐름은 유지한다.

## Current State

- 작업 기준선은 2026-09-16 fetch한 `origin/main` `a8821fd3ec6764f30fdef7320f9938a5c070f110`이다.
- 첨부 조사 기준 `503d15831cc3b775bb073c8bcefb534ad89f36a0`은 이 기준선의 조상이며 reset 대상이 아니다.
- 원 checkout `feature/performance-grafana-diagnosis`에는 기존 수정과 untracked V87/V88가 있어 건드리지 않고
  `/private/tmp/beanflow-immediate-checkout-20260916`의 격리 worktree에서 작업한다.
- 기준선 마지막 Flyway는 V86, 마지막 등록 ADR은 ADR-130이다. 열린 PR #189는 V87, #190은 V88와
  ADR-132를 가진다. 번호 충돌을 피하려 이 stack은 V89와 ADR-133~135를 사용한다. 두 PR을 포함·병합하지
  않으며 이 stack은 migration-writer release gate가 해제될 때까지 Draft다.
- 첨부 code evidence 50개 경로 중 현재 기준선에서 달라진 것은 `OrderCreationTransaction`,
  `OrderCreationWorkflow`, `AcceptanceTimeoutWorkWorker`, 고객 commerce/transaction 화면이다. 슬롯 영향 경로는
  `OrderQuoteCoordinator`, `StoreOrderTransitionService`도 달라졌다. 차이는 성능 계측과 고객 탐색 동선이
  중심이며 즉시 주문 계약은 아직 없다.
- M2/M3 작업 head에서는 신규 quote/create/reorder가 슬롯 없이 IMMEDIATE 초안을 만들고, 승인 후 transaction에서
  Coupon/Point 실제 사용·최종 settlement input·Payment 승인·Order PAID를 함께 확정한다. 기존
  LEGACY_RESERVED 생성·confirm·복구 경로는 그대로 병존한다.
- M4 작업 head에서는 실제 customer/store/support route가 nullable pickup, server-owned payment cutoff,
  preparation ETA와 cart revision guard를 소비한다. OpenAPI 원본에서 즉시 주문 예시의 슬롯·5분 lease를 제거하고
  runtime schema를 다시 생성했다. 공개 quote/create/reorder request는 legacy `pickupSlotId`를 더 이상 받지 않으며,
  내부 legacy 생성 command만 과거 fixture·조회·복구 검증을 위해 유지한다.
- 고객 상세·검색·인근·즐겨찾기·최근매장의 `pickupAvailable`은 Store owner state와 현재 Asia/Seoul 영업시간
  `OPEN`을 함께 만족할 때만 true다. 신규 탐색은 Fulfillment 슬롯을 읽지 않고 `nextPickupWindow`를 생략하며,
  quote와 Tx A/C가 실제 주문 가능성을 다시 검증한다.

## Definitions

- `IMMEDIATE`: 슬롯과 결제 대기 자원을 예약하지 않는 신규 checkout mode.
- `LEGACY_RESERVED`: 기존 슬롯·5분 lease 계약으로 생성된 과거 Order mode.
- Tx A: PG 호출 전에 주문·금액·거래조건과 복구 식별자만 저장하는 짧은 transaction.
- Tx C: 승인 후 실제 혜택·정산 입력·Payment 승인·PAID를 원자 확정하는 transaction.
- Tx D: Tx C의 확정 업무 실패 뒤 승인 사실과 void/refund recovery를 저장하는 별도 transaction.
- `orderingWindowClosesAt`: 초안이 속한 서울 영업 구간의 원래 또는 단축된 닫힌 경계.

## Scope

### In Scope

영업시간 owner API와 cutoff, 추가형 schema, 슬롯 없는 quote/create/reorder, 승인 후 direct benefit use와
복구, 0원 주문, 미수락/마감 worker, 준비시간 수락, 고객·매장·지원 query/API/UI, OpenAPI·생성 타입,
PostgreSQL/Storybook/E2E와 legacy 회귀 검증을 구현한다.

### Non-goals

새 PG, Billing Key/BrandPay/Payment Widget, Redis/Kafka/Saga 엔진, 신규 재고, 야간·24시간·브레이크타임·
휴일 calendar, 수락 후 ETA 편집/자동 완료, accepted 주문의 마감 일괄취소, reservation 전면 rename, 배포·
공유 DB 변경·실제 결제·공유 부하는 포함하지 않는다.

## Business Rules and Invariants

1. quote, Tx A와 Toss 인증 대기는 Pickup/Coupon/Point를 선점하지 않는다.
2. IMMEDIATE `reservationExpiresAt`는 null이며 5분만으로 만료되지 않는다.
3. complete schedule이 없거나 `[open, close)` 밖이면 신규 confirm을 보내지 않는다.
4. preflight와 외부 승인 사이 경합은 Tx C 재검증과 Tx D 복구로 수렴한다.
5. cutoff는 줄일 수만 있고 재개점·연장·다음 날 callback으로 옛 거래를 살리지 않는다.
6. 승인·benefit use·필수 snapshot·Payment approved·PAID는 전부 commit 또는 rollback이다.
7. 같은 coupon/잔액 경합은 한 경제적 승자만 남고 나머지 승인은 void/refund로 수렴한다.
8. 0원도 동일 gate와 snapshot을 통과한다.
9. `acceptanceDeadlineAt=min(paidAt+3m, orderingWindowClosesAt)`이며 동률은 STORE_CLOSED다.
10. deadline 이후 수락할 수 없고 수락 후 마감은 주문을 취소하지 않는다.
11. 최초 accept가 preparation 1~120과 ETA를 고정하고 ETA는 상태 전이가 아니다.
12. UNKNOWN은 새 승인이나 확정 실패로 대체하지 않는다.
13. 성공 확인 전 cart를 비우지 않고 시작/current revision이 같을 때만 비운다.
14. 실제 생성된 자원만 복구하며 PICKUP 없는 신규 주문은 NOT_REQUIRED다.
15. 공개 번호, 과거 슬롯·snapshot·event·원장·정산 사실을 보존한다.
16. 슬롯이 0개여도 현재 영업 중인 매장은 탐색 가능해야 하며, 탐색 응답에서 준비 ETA를 지어내지 않는다.

## Architecture and Transaction Boundaries

- 매장 관리 쓰기는 기존 Identity membership shared lock → Merchant Store exclusive lock 순서를 보존한다.
  고객 Tx A는 Merchant quote owner가 Store shared lock을 먼저 얻고 availability Port가 같은 Store 행을
  재사용한 뒤 point-accrual/pickup/coupon/point owner lock으로 진행한다. Ordering은 Merchant repository를
  직접 사용하지 않는다.
- schedule replacement는 Store exclusive lock 아래 같은 transaction에서 현재 cutoff를 단조 감소시킨다.
  Ordering listener 실패를 숨기고 profile만 commit하지 않는다.
- Tx A는 Store shared lock과 owner quote를 읽어 typed input snapshot과 Order를 저장한다. 혜택 owner write와
  final settlement snapshot은 없다.
- Payment prepare/claim은 기존 idempotency와 reconciliation을 재사용한다. Provider confirm은 DB transaction과
  lock 밖이다.
- Tx C는 replay/current winner 확인 후 Store→Order→Payment claim→Coupon issuance→PointAccount→PointLot
  순서를 현재 restore/refund 경로와 대조해 적용한다.
- 혜택 부족·마감 같은 확정 업무 실패와 Tx C의 DB/timeout/deadlock/snapshot 무결성 실패는 서로 다른 code로
  유지한다. 승인 사실을 이미 얻은 경우 모두 rollback 뒤 Tx D에서 현재 Order/Payment 승자를 다시 잠그고,
  승자가 아니면 `PAYMENT_COMMITMENT_FAILED`와 late void/refund recovery를 저장한다. Tx D 자체가 DB 장애로
  실패하면 성공으로 위장하지 않고 503과 기존 approval lookup claim을 남겨 재조정한다.
- `LEGACY_RESERVED` approval lookup도 DB 경합 뒤 Tx D가 현재 Order를 다시 잠근다. 이미 EXPIRED/CANCELLED/
  REJECTED인 경우에만 실제 승인 사실을 late void/refund recovery로 저장하고, 아직 PENDING_PAYMENT이면 임의
  취소하지 않은 채 `DEPENDENCY_UNAVAILABLE`로 재시도를 남긴다.

## Alternatives Considered

자동 슬롯, 기한 없는 benefit reservation, 결제 전 차감, PG 전 DB 기록 없음, 새 Checkout Aggregate, 새 마감
queue를 제외했다. 기존 Order/Payment/owner ledger/reconciliation/acceptance worker를 확장하는 방식을 선택했다.

## Failure Semantics

- `STORE_HOURS_NOT_CONFIGURED`, `STORE_CLOSED`, `STORE_NOT_ACCEPTING_ORDERS`는 확정 업무 차단이다.
- 원본 read/DB/config 오류는 `DEPENDENCY_UNAVAILABLE`이고 open/closed 또는 benefit 부족으로 위장하지 않는다.
- Provider confirm 이후 timeout/응답 유실/commit 실패는 동일 Payment lookup과 기존 recovery로 수렴한다.
- 승인 후 주문 미성립은 주문 성공이 아니며 돈 반환 완료와도 다르다. 고객은 처리 중/지연, 운영자는 실제
  UNKNOWN/RECONCILING/MANUAL_REVIEW와 원인을 본다.
- 보상 publication 실패는 terminal Order를 되돌리지 않고 step별 retry/manual review에 남긴다.

## Data and Migration

V89는 availability와 mode/cutoff/input/준비시간의 additive migration이고, 이미 push된 migration을 수정하지
않기 위해 M2의 승인 후 혜택·복구 제약 보완은 V90으로 작성한다.

- `ordering_order.checkout_mode`, nullable pickup/time/lease, `ordering_window_closes_at`, typed
  `checkout_input_snapshot`, `preparation_minutes`, `estimated_ready_at`.
- IMMEDIATE/LEGACY별 CHECK, conditional warning/deadline, accept/ETA pair와 정확한 산식.
- Coupon/Point reservation expiry는 direct USED에만 null을 허용하고 RESERVED에는 필수다.
- PENDING/EXPIRED/CANCELLED IMMEDIATE 초안은 final settlement input이 없어야 하고, PAID는 정확히 하나의 유효한
  final snapshot을 가져야 한다.
- Support cancel/history의 신규 slot 없는 경로만 nullable로 만들고 legacy FK/history는 보존한다.
- 현재 due query와 보드 sort에 필요한 실제 query 기반 index만 추가한다.
- fresh install, V86 fixture와 최종 stack baseline upgrade를 PostgreSQL Testcontainers로 검증한다.

실환경 운영시간 미설정 Store, active legacy payment, incomplete publication, support reschedule은 read-only release
gate이며 이번 로컬 작업에서 DB를 변경하지 않는다.

## API and Event Contracts

- quote/create/reorder 신규 request에서 pickup input을 제거하고 quote fingerprint를 v7로 올린다.
- 제거된 pickup input을 알 수 없는 JSON 필드로 보낸 신규 공개 요청은 400으로 거절한다. nullable 내부 command는
  과거 주문 materialization과 legacy 회귀에만 남기고 public controller가 전달하지 않는다.
- draft `201`, PAID confirm `200`, UNKNOWN/reconciling/recovery `202`, closed/stale `409`, dependency `503`을
  구분한다. 기존 Payment 조회·refund/reconciliation은 마감 후에도 허용한다.
- `ACCEPT`에는 preparationMinutes가 필수이고 다른 action에 보내면 400이다. UUID 호환 endpoint도 같다.
- customer history/detail, board/cursor/ETag, discovery availability, Support nullable history를 함께 전환한다.
- discovery의 즉시 주문 가용성은 `orderingAvailable && operatingStatus == OPEN`이다. legacy slot batch는 신규
  상세·검색·인근·즐겨찾기·최근매장 경로에서 호출하지 않고 `nextPickupWindow`를 만들지 않는다.
- 기존 OrderRejected/OrderCancelled/OrderReady/OrderCompleted event version을 유지한다. 신규 reason을 소비자와
  audit parser에서 검증하며 legacy publication target을 삭제하지 않는다.

## Milestones

- M0: 기준선·정책·ADR·계약·T01~T56 추적 고정.
- M1: availability, V89, mode/cutoff/input/ETA와 schedule 단축.
- M2: slotless quote/create/reorder, Tx A/C/D, direct use, 0원과 recovery.
- M3: 마감/미수락 worker, NOT_REQUIRED, preparation acceptance.
- M4: query/OpenAPI/frontend/Storybook 실제 route와 callback 전환.
- M5: 전체 migration/경합/장애/보안/정산/Storybook/E2E/demo/load 계약 검증.

Stacked Draft PR topology는 `M0 docs → M1 schema/availability → M2/M3 transaction/lifecycle → M4/M5 UI/contracts/verification`이다.
각 child는 직전 verified head만 parent로 사용한다. merge와 deploy는 이번 범위가 아니다.

## Required Tests

| 범위 | 테스트 ID | 실제 연결 예정 |
|---|---|---|
| 영업시간/단축 | T01~T05, T30~T35, T56 | availability domain/API, Store display PostgreSQL race, acceptance worker |
| 무예약 초안/견적 | T06~T16 | quote/create/reorder transaction, owner write count |
| 혜택/승인/복구 | T17~T29, T39 | Coupon/Point direct-use race, Payment confirmation/reconciliation |
| 수락/보상 | T30~T43, T51, T56 | lifecycle, UUID contract, compensation/publication replay |
| query/UI | T44~T50 | board SQL/cursor/ETag, cart/callback/history Storybook/E2E |
| migration/구조/정산 | T52~T55 | V86/current upgrade, direct SQL CHECK, Modulith/ArchUnit, settlement regression |

아래 명령 키는 이 문서의 Validation Commands와 Outcomes에 전체 명령을 보존한다. `EDGE`는 표에 적힌 정확한
test method pattern으로 실행한 targeted Gradle command다. `PARTIAL`은 구현 증거가 있어도 시나리오의 모든
경계/장애를 아직 실행하지 않았다는 뜻이다.

| ID | 실제 test file / case | 명령 | 현재 결과 |
|---|---|---|---|
| T01 | `StoreOrderAvailabilityPolicyTest#same-day Seoul window is open-inclusive and close-exclusive` | M1 | PASSED |
| T02 | `StoreOrderAvailabilityPolicyTest#missing hours fail closed without inventing a window` | M1 | PARTIAL — invalid source 직접 주입은 M5 |
| T03 | 주소/다음 요일만 변경하는 Merchant 통합 case | M5 | NOT RUN |
| T04 | Store exclusive 변경과 Tx C shared lock 경합 | M5 | NOT RUN |
| T05 | `ImmediateCheckoutMigrationTest#V89 permits cutoff shortening...`, `OneTimeCheckoutIntegrationTest#approved immediate callback replays...` | M1, EDGE | PARTIAL — 다음 날 callback은 M5 |
| T06 | `OrderQuoteIntegrationTest#quote returns...without reserving`, `OneTimeCheckoutIntegrationTest#immediate checkout keeps resources unreserved...` | M2 | PASSED |
| T07 | `OneTimeCheckoutIntegrationTest#immediate checkout keeps resources unreserved...` | M2 | PASSED |
| T08 | 같은 case의 6분 후 승인 | M2 | PASSED |
| T09 | `OrderQuoteIntegrationTest`의 owner state/price/option/coupon stale cases | M2 | PARTIAL — IMMEDIATE 판매중지 경합은 M5 |
| T10 | `CreateOrderConcurrencyTest#concurrent identical key executes one order transaction` | M5 | PARTIAL — 공통 PostgreSQL idempotency는 PASSED, IMMEDIATE 전용 병렬 fixture는 없음 |
| T11 | `CreateOrderServiceTest#same key with different payload...` | M5 | PASSED |
| T12 | `OrderControllerContractTest#payment confirmation enforces order ownership...` | M2 | PASSED |
| T13 | `OneTimeCheckoutIntegrationTest#tampered amount and order binding fail before Provider confirmation` | M2 | PASSED |
| T14 | `OneTimeCheckoutIntegrationTest#first immediate callback at store close is rejected before Provider confirmation` | EDGE | PASSED |
| T15 | `OneTimeCheckoutIntegrationTest#approved immediate callback replays after store close...` | EDGE | PASSED |
| T16 | `OneTimeCheckoutIntegrationTest#two approved immediate payments competing for one coupon...`의 승인 전 reservation 0 | EDGE | PASSED |
| T17 | 같은 coupon case의 병렬 callback, loser `LATE_VOID=SUCCEEDED`, winner PAID | EDGE | PASSED |
| T18 | `OneTimeCheckoutIntegrationTest#two approved immediate payments competing for one point balance...` | EDGE | PASSED |
| T19 | `OneTimeCheckoutIntegrationTest#approval replaces an expired quoted point lot...` | EDGE | PASSED |
| T20 | `OneTimeCheckoutIntegrationTest#point shortage after immediate coupon use rolls every benefit back...` | EDGE | PASSED |
| T21 | `OneTimeCheckoutIntegrationTest#settlement snapshot failure rolls back point use...` | EDGE | PASSED |
| T22 | `OneTimeCheckoutIntegrationTest#approved immediate callback replays...` | EDGE | PARTIAL — 혜택 USE 중복 직접 count는 M5 |
| T23 | T17/T18의 동시 Tx D loser 선택과 winner PAID 재확인 | EDGE | PASSED |
| T24 | T21의 Tx C rollback→Tx D commit | EDGE | PARTIAL — process kill/restart는 M5 |
| T25 | `OneTimeCheckoutIntegrationTest#unknown confirmation is recovered...` | M2 | PARTIAL — 5분 이상 고정 clock은 M5 |
| T26 | IMMEDIATE UNKNOWN의 마감 후 승인 lookup | M5 | NOT RUN |
| T27 | `OneTimeCheckoutIntegrationTest#zero payable immediate order commits...without Provider` | M2 | PASSED |
| T28 | 영업시간 밖 0원 주문 Provider 0회 | M5 | NOT RUN |
| T29 | 0원 혜택 경합/rollback | M5 | NOT RUN |
| T30 | `OrderEntityLifecycleTest#acceptance fails at exact...`, 즉시 마감 동률/조기마감 lifecycle cases | M3, EDGE | PASSED |
| T31 | `StoreOrderLifecycleIntegrationTest#early close removes an impossible warning...` | EDGE | PASSED |
| T32 | `StoreOrderLifecycleIntegrationTest#acceptance and timeout race produces exactly one...` | M3 | PASSED |
| T33 | 같은 race case | M3 | PARTIAL — lock 대기 중 clock 전진 전용 case는 M5 |
| T34 | 수락 뒤 마감 변경에도 상태 보존 | M5 | NOT RUN |
| T35 | 조기마감 반복 listener의 cutoff 비연장 | EDGE | PARTIAL — 기존 timeout work source replay는 M5 |
| T36 | `CustomerCancellationCommandIntegrationTest#customer cancellation and store acceptance race...` | M3 | PARTIAL — 마감과 상담 취소 3-way는 M5 |
| T37 | `StoreOrderLifecycleIntegrationTest#immediate paid timeout...needs no pickup restoration` | M3 | PASSED |
| T38 | `OrderTerminationResourceListenerIntegrationTest`, `EventPublicationRecoveryIntegrationTest` | M3 | PASSED |
| T39 | T17/T18 loser void 실행 | EDGE | PARTIAL — timeout/duplicate recovery callback은 M5 |
| T40 | `OrderEntityLifecycleTest#acceptance preparation...`, `StoreOrderLifecycleIntegrationTest#public and UUID acceptance APIs require...` | M3, EDGE | PASSED |
| T41 | `StoreOrderLifecycleIntegrationTest#store transition replays same command...`의 10분 replay/15분 key mismatch | EDGE | PASSED |
| T42 | `StoreOrderLifecycleIntegrationTest#public and UUID acceptance APIs require...` | EDGE | PASSED |
| T43 | `OrderEntityLifecycleTest#paid order follows...`, 명시적 상태 전이와 고정 ETA assertion | M3/M5 | PASSED |
| T44 | backend board 정렬/cursor/ETag와 `storeOrderBoardModel.test.ts` IMMEDIATE ETA 정렬 | M3/M4 | PASSED |
| T45 | no-slot discovery detail/search/nearby/favorite/recent PostgreSQL tests, customer/store/checkout/board Story와 backend create→accept→complete tests | M4/M5 | PARTIAL — 단일 실제 browser+backend E2E는 NOT RUN |
| T46 | `CustomerOrderQueryIntegrationTest`, order detail/payment recovery Storybook 상태 | M3/M4 | PASSED |
| T47 | `PaymentCallbackPages.test.tsx#queries server status...`의 fail callback cart/attempt 보존 | M4 | PASSED |
| T48 | `PaymentCallbackPages.test.tsx#uses status GET...`, `#never sends a second confirmation...` | M4 | PASSED |
| T49 | `Ordering.test.tsx#clears only...`, payment callback changed-cart/changed-coupon tests | M4 | PASSED |
| T50 | fail callback status-only, UNKNOWN polling/no-new-payment tests와 backend lookup recovery | M2/M4 | PASSED |
| T51 | `CustomerCancellationCommandIntegrationTest#customer and support cancellation preserve slotless immediate history...` | EDGE | PASSED |
| T52 | `ImmediateCheckoutMigrationTest#V89 preserves V86...`, `FlywayMigrationSmokeTest#fresh database...` | M1/M2 | PASSED |
| T53 | `ImmediateCheckoutMigrationTest`의 IMMEDIATE nullable/lease/snapshot/cutoff direct SQL cases | M2 | PASSED |
| T54 | `ModularityTests`, `SupportArchitectureTest`, `AuthenticationArchUnitTest` | M3/M5 | PASSED |
| T55 | `StoreOrderLifecycleIntegrationTest` 완료/부분환불/정산 snapshot 회귀 | M3 | PARTIAL — IMMEDIATE 전체 완료 E2E는 M5 |
| T56 | `OneTimeCheckoutIntegrationTest#concurrent startup overdue scans expire one immediate draft exactly once` | EDGE | PASSED |
| T57 | `PaymentConfirmationIntegrationTest#legacy approval recovery waits...`, `#approval lookup racing...` | EDGE | PASSED |

## Validation Commands

```bash
bash scripts/verify-docs.sh
./gradlew spotlessCheck
./gradlew test --tests '*ModularityTests' --tests '*SupportArchitectureTest'
./gradlew test --tests '*OrderCreation*' --tests '*OrderQuote*' --tests '*OneTimeCheckout*' \
  --tests '*PaymentConfirmation*' --tests '*SettlementInput*' --tests '*StoreOrderLifecycle*' \
  --tests '*CustomerCancellation*' --tests '*StoreOrderBoard*'
./gradlew test
./gradlew build

cd frontend
npm run generate:api
npm run typecheck
npm test
npm run check:design
npm run test:storybook:docs
npm run build-storybook
npm run build
npm run test:sites
```

M2/M3에서 실제 실행한 묶음 명령은 다음과 같다.

```bash
# M2
./gradlew test --tests '*OneTimeCheckoutIntegrationTest' --tests '*ImmediateCheckoutMigrationTest' \
  --tests '*OrderQuoteIntegrationTest' --tests '*FastReorderServiceTest' \
  --tests '*PaymentConfirmationIntegrationTest' --tests '*OrderSettlementInputSnapshotIntegrationTest' \
  --tests '*BenefitOnlyOrderCreationTest' --tests '*OrderControllerContractTest'

# M3
./gradlew test --tests '*StoreOrderLifecycleIntegrationTest' --tests '*StoreOrderBoardIntegrationTest' \
  --tests '*OrderEntityLifecycleTest' --tests '*CustomerOrderQueryIntegrationTest' \
  --tests '*CustomerCancellationCommandIntegrationTest' --tests '*OrderTerminationResourceListenerIntegrationTest' \
  --tests '*EventPublicationRecoveryIntegrationTest' --tests '*SupportArchitectureTest' \
  --tests '*AuthenticationArchUnitTest'

# EDGE는 표의 test method를 아래 형식으로 개별 실행했다.
./gradlew spotlessApply test --tests '*<test method pattern>*'
```

Storybook은 live MCP의 `list-all-documentation → get-documentation → story instructions → preview →
get-changed-stories → run-story-tests(a11y=true)` 순서로 검증한다. package script는 현재 `package.json`과
대조하며 존재하지 않는 명령을 만들지 않는다.

## Observability

closed 판정 뒤 confirm 호출, 승인 후 미성립 원인/복구, Tx A reservation write, benefit 중복/원장 불일치,
UNKNOWN 최고 경과시간, deadline→reject와 reject→refund 지연, Store/Order/benefit lock wait, pool pending,
board query plan을 기존 metric/log/case로 관측한다. raw paymentKey·카드·secret·PII는 기록하지 않는다.
성능은 동일 조건 전후 측정 없이는 개선을 주장하지 않는다.

## Documentation Updates

BR-01/03/05/06/11/14/18/19/20/33/49/50/52, ADR-133~135, failure semantics, transaction/aggregate/event
catalog, OpenAPI 원본, error catalog, owner/operations runbook과 이 ExecPlan을 코드와 함께 갱신한다.

## Progress

- [x] 2026-09-16 origin fetch, root/branch/HEAD/dirty/worktree와 첨부 기준 조상 관계 확인.
- [x] AGENTS, frontend 지침, policy, failure semantics, decision rules, DoD, PLANS와 관련 ADR 확인.
- [x] 첨부 confirmed→README→plan→task→decision→verification과 evidence/manifest 대조.
- [x] Storybook MCP live catalog의 BeanFlow identity 확인.
- [x] BR amendment와 ADR-133~135 등록.
- [x] 2026-09-16 M1 Merchant availability port, membership→Store lock 순서, V89 additive schema,
  checkout/cutoff/input/준비시간 매핑, current-window 단축 listener 구현.
- [x] 2026-09-16 M2 slotless quote/create/reorder, 승인 후 direct benefit use·final settlement·Payment/PAID
  원자 확정, 0원 BENEFIT_ONLY, Tx D late void/refund recovery와 approval lookup 재조정 구현.
- [x] 2026-09-16 M3 unpaid store-close expiry, paid acceptance deadline/조건부 경고/기동 scan,
  `STORE_CLOSED`, source-aware `PICKUP NOT_REQUIRED`, 1~120분 단회 수락과 nullable query/support 계약 구현.
- [x] 2026-09-16 Stacked PR CI artifact에서 legacy approval lookup과 만료의 DB 경합이 즉시주문 전용 Tx D로
  잘못 진입하는 호환성 결함을 확인하고, 종료 주문 late-approval 수렴과 진행 주문 fail-closed 재시도를 구현.
- [x] 2026-09-16 M4 nullable query/OpenAPI 생성 타입, 슬롯 없는 cart/create/reorder, server `PAID` 뒤
  revision-guarded cart·coupon 선택 정리, payment callback 복구, 준비시간 board와 영업시간/이미지 관리 화면 전환.
- [x] 2026-09-16 public quote/create/reorder의 legacy pickup writer 차단, frozen V1 종료 event 보존,
  Support nullable overview, slotless demo·load 계약 전환과 현재 HEAD targeted 회귀 검증.
- [x] 2026-09-16 slot-zero 매장이 현재 영업 중이면 상세·검색·인근·즐겨찾기·최근매장에서 즉시 주문 가능으로
  노출되도록 discovery의 Fulfillment slot batch 의존 제거, OpenAPI/fixture/query-count 계약 전환.
- [x] M5 로컬 전체 검증. PostgreSQL migration, 전체 backend suite, 최종 backend build, frontend
  unit/type/design/build, focused MCP와 전체 Storybook browser suite는 Passed.
- [ ] release gate: migration writer 순서, 공유 DB inventory, 실제 Toss 계정, 배포 후 canary와 공유 환경 부하.
  이번 요청 범위의 Stacked PR은 Draft로 유지한다.

## Surprises & Discoveries

- origin/main은 첨부 기준 이후 Order/quote/transition에 성능 계측과 고객 탐색 개선을 포함하므로 첨부 함수
  본문을 덮어쓰면 안 된다.
- 원 checkout의 V87/V88은 각각 열린 PR #189/#190과 연관된다. origin/main은 V86이므로 이 stack은 V89를
  사용하되 repository-wide migration release 순서를 별도 gate로 유지한다.
- ADR registry가 파일 ADR-125~130을 누락하고 있어 신규 ADR 등록과 함께 현재 파일 목록을 보완했다.
- V16의 deferred point-accrual completeness trigger 때문에 schema fixture의 bare Order insert는 USER trigger를
  명시적으로 비활성화한 격리 fixture로만 만들었다. 제품 경로는 기존 snapshot 생성을 우회하지 않는다.
- Hibernate/Jackson의 기본 JSON mapper는 `Instant`를 serialize하지 못했다. 애플리케이션의 configured
  `tools.jackson` ObjectMapper로 checkout input을 String JSON으로 저장/복원해 Tx A 재계산을 제거했다.
- PostgreSQL은 read-only transaction의 `SELECT ... FOR SHARE`를 거부했다. callback preflight를 짧은 쓰기 가능
  transaction으로 두고 shared lock 해제 후에만 Provider를 호출하도록 경계를 검증했다.
- 분할 CI에서 legacy approval lookup과 만료가 동시에 Order를 잠글 때 첫 승인 반영 transaction이 rollback되고,
  관측한 PG 승인이 즉시주문 전용 Tx D의 mode guard에 막혀 UNKNOWN에 남는 경합이 드러났다. Tx D는 legacy
  종료 상태만 late recovery로 수렴시키고 PENDING_PAYMENT는 변경하지 않는 회귀 계약(T57)을 추가했다.
- 기존 `OrderRejectedV1`/`OrderCancelledV1`에 pickup flag를 추가하면 frozen event 계약이 깨진다. V1 payload는
  보존하고 listener가 같은 transaction에서 생성된 compensation PICKUP step을 조회해 legacy `PROCESSING`과
  IMMEDIATE `NOT_REQUIRED`를 구분한다.
- public request의 optional legacy `pickupSlotId`는 "신규 실행 경로에서 무시"가 아니라 여전히 신규 writer를
  허용하는 계약이었다. 공개 DTO/OpenAPI에서 제거하고 unknown field로 거절하되 내부 command와 legacy fixture는
  보존해 신규 writer 차단과 과거 read/recovery를 분리했다.
- Storybook MCP의 broad run은 Vitest watch process와 63320 포트를 남기며 transport를 종료했다. 변경 핵심
  story는 MCP `run-story-tests(a11y=true)`로 검증했고 전체 118 files/755 stories는 같은 browser project의
  `npm run test:storybook:ci`로 별도 통과시켰다.
- M4 최종 검토에서 고객 탐색의 `pickupAvailable`이 여전히 Fulfillment reservable-slot batch로 계산돼 슬롯 0개
  매장을 checkout 전에 제외한다는 계약 누락을 발견했다. BR-47/50과 ADR-103/117을 먼저 amend한 뒤 Merchant
  owner state+현재 영업시간 projection으로 전환했고, 상세 endpoint의 SQL 수는 2에서 1로 줄었다. 동일 조건
  성능 측정은 하지 않았으므로 성능 개선으로 주장하지 않는다.

## Decision Log

| Date | Decision | Reason |
|---|---|---|
| 2026-09-16 | origin/main a8821fd 기준의 격리 worktree 사용 | dirty checkout과 사용자 변경 보존 |
| 2026-09-16 | V89, ADR-133~135 사용 | 열린 V87/V88 및 ADR-132와 번호 충돌 방지 |
| 2026-09-16 | IMMEDIATE/LEGACY_RESERVED 병존 | 과거 주문·event·정산·recovery 보존 |
| 2026-09-16 | Tx A/C/D와 기존 Payment recovery 재사용 | 선예약 제거와 승인 복구를 함께 만족 |
| 2026-09-16 | schedule을 신규 결제 gate로 사용 | 사용자 확정 영업시간 계약과 원래 영업 구간 보존 |
| 2026-09-16 | provider callback preflight와 Tx C availability 재검증 병행 | 마감/OFF 뒤 불필요한 confirm을 막고 동시 변경은 승인 반환으로 수렴 |
| 2026-09-16 | discovery 즉시 주문 가용성에서 legacy slot batch 제거 | 슬롯 0개 매장의 탐색→결제 진입을 허용하고 영업시간 미설정은 fail-closed로 유지 |

## Outcomes

- M0 문서 검증: `scripts/verify-docs.sh` Passed (18 tests, 57 policies, 133 ADRs, 377 Markdown, 108 ExecPlans).
- M1 경계/스키마: availability 5 tests, V89/전체 Flyway 4 tests, Order domain/entity 19 tests Passed.
- M1 구조/통합: `MerchantDisplayContentAuditRollbackIntegrationTest`, `StoreOrderLifecycleIntegrationTest`,
  `ModularityTests`, `SupportArchitectureTest` Passed.
- M2 묶음: 8개 결제/견적/migration/정산/API test class, `BUILD SUCCESSFUL in 2m 56s`.
- M3 묶음: 9개 lifecycle/query/cancellation/event/architecture test class, `BUILD SUCCESSFUL in 2m 1s`.
- M2/M3 통합 회귀: 결제, V86→V90 migration, quote/reorder, lifecycle, board/query, cancellation,
  publication recovery와 architecture 14개 test class, `BUILD SUCCESSFUL in 3m 29s`.
- 최신 HEAD 핵심 회귀: `spotlessCheck`와 결제, V90 migration, lifecycle, cancellation, Modulith/ArchUnit
  7개 test class, `BUILD SUCCESSFUL in 1m 57s`.
- Stacked PR CI 보정: M1 migration 2개 class Passed (`BUILD SUCCESSFUL in 1m 18s`), frozen event/board/slotless
  보상 4개 case Passed (`BUILD SUCCESSFUL in 1m 21s`), legacy approval Tx D의 진행/종료 경계와 만료 경합
  2개 case 및 `spotlessCheck` Passed (`BUILD SUCCESSFUL in 29s`).
- EDGE: 마감 전 PG 차단/승인 replay, 수동 OFF, coupon·point 병렬 승인 경합과 loser void, 실제 PointLot
  issuer 정산, 부분 혜택·snapshot 실패 rollback, 준비시간 API/멱등성, 조기마감 경고, slotless 고객·상담 취소,
  동시 startup overdue scan을 개별 실행해 Passed. T19의 첫 실행은 존재하지 않는 checkout JSON Lot 상세를
  기대해 Failed 후 해당 잘못된 assertion을 제거했고 실제 allocation/정산 assertion은 Passed했다.
- M4 frontend: 생성 타입/typecheck, 37 unit files/256 tests, presentation boundary 10, product copy 11,
  design adherence, Vite production build와 Sites 4 tests Passed. callback cart 보존 보강 뒤 해당 22 tests도
  다시 Passed.
- Storybook: 핵심 cart/checkout/order/store/board/schedule/payment/support 상태는 live MCP
  `run-story-tests(a11y=true)` Passed. 전체 browser project는 118 files/755 stories Passed, 정적 build와
  Docs smoke는 122 docs entries/15 stateful docs/47 surfaces Passed. broad MCP 단일 호출은 runner가 watch
  process/63320을 남겨 Blocked였고 동일 전체 project 결과로 대체했다고 주장하지 않는다.
- 최신 OpenAPI: 문서 18 tests, 57 policies, 133 ADRs, 377 Markdown, 108 ExecPlans와 258/292 target,
  248/282 runtime path/operation semantic verification Passed. runtime schema를 원본에서 재생성했고 Support
  nullable pickup 소비자까지 typecheck했다.
- M5 migration/OpenAPI: PostgreSQL 17 fresh V1→V90, V86→V90, V90 direct CHECK/trigger와 runtime parity,
  `spotlessCheck` Passed (`BUILD SUCCESSFUL in 1m 24s`).
- M5 current-HEAD targeted 회귀: 공개 create/quote/reorder, 고객 인증, payment setup repair, slotless 고객 취소,
  V51 legacy 불변성→최신 nullable upgrade, board query plan/index, frozen event·publication recovery,
  runtime OpenAPI parity와 demo guard 13개 test class Passed (`BUILD SUCCESSFUL in 5m 6s`).
- M5 demo/load 계약: `bash -n scripts/demo/smoke.sh`, load contract 5 tests와 실제 k6 9개 정상·오류·UNKNOWN
  시나리오 Passed. 실제 공유 환경 부하를 실행한 결과는 아니다.
- M5 frontend current-HEAD: typecheck/API 재생성, 37 unit files/257 tests, presentation 10, product copy 11,
  design adherence, Vite/Sites build와 Sites 4 tests, Storybook browser 118 files/755 tests Passed. 최종 정적
  Storybook build와 Docs 122 entries/15 stateful docs/47 state surfaces smoke도 Passed.
- M5 slot-zero discovery: 상세/검색/인근/즐겨찾기/최근매장과 query-count를 포함한 핵심 76 tests Passed,
  이어서 Discovery 패키지 전체와 runtime OpenAPI parity를 단독 실행해 `BUILD SUCCESSFUL in 3m 20s`.
- M5 전체 backend suite: 1,784 tests 중 1,782 Passed, failures/errors 0. 명시적 opt-in 대용량 nearby
  benchmark와 AIStor Free 외부 환경 통합 2건은 Skipped. `./gradlew test`는 `BUILD SUCCESSFUL in 58m 29s`,
  동일 소스의 `./gradlew build -x test`는 `BUILD SUCCESSFUL in 2s`.
- 공유 DB inventory, 배포, 실제 PG와 공유 부하는 범위 밖이며 release gate로 Pending이다.

## Outcomes & Retrospective

M0~M5의 로컬 정책·schema·backend·frontend/OpenAPI 전환과 PostgreSQL/Storybook/전체 backend 검증을
완료했다. 단일 실제 browser+backend E2E, opt-in 110k nearby benchmark, AIStor Free 외부 통합은 실행하지
않았고 공유 DB inventory, 실제 Toss 계정, 배포와 공유 부하는 별도 release gate다. migration writer/release
ordering이 해제되기 전 stack은 Draft로 유지한다.

## Revision Notes

- 2026-09-16: 현재 origin/main, 열린 migration PR, 최신 ADR-123과 실제 Storybook catalog를 기준으로 최초 작성.
