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
- 현재는 quote/create/reorder가 `pickupSlotId`를 필수로 받고 Order 생성에서 Pickup/Coupon/Point를 예약한다.
  settlement input은 Order 생성 때 materialize되며 Payment 결과에서 세 예약을 confirm한 뒤 PAID가 된다.

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

## Architecture and Transaction Boundaries

- Identity의 membership shared lock을 먼저 얻고 Merchant Store commerce root shared/exclusive lock 규칙을
  보존한다. Ordering은 Merchant repository를 직접 사용하지 않고 public availability Port를 소비한다.
- schedule replacement는 Store exclusive lock 아래 같은 transaction에서 현재 cutoff를 단조 감소시킨다.
  Ordering listener 실패를 숨기고 profile만 commit하지 않는다.
- Tx A는 Store shared lock과 owner quote를 읽어 typed input snapshot과 Order를 저장한다. 혜택 owner write와
  final settlement snapshot은 없다.
- Payment prepare/claim은 기존 idempotency와 reconciliation을 재사용한다. Provider confirm은 DB transaction과
  lock 밖이다.
- Tx C는 replay/current winner 확인 후 Store→Order→Payment claim→Coupon issuance→PointAccount→PointLot
  순서를 현재 restore/refund 경로와 대조해 적용한다.
- 확정 업무 실패만 Tx D로 보내고 DB/timeout/deadlock/snapshot corruption은 UNKNOWN/RECONCILING으로 둔다.

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

V89는 additive/constraint replacement migration으로 작성한다.

- `ordering_order.checkout_mode`, nullable pickup/time/lease, `ordering_window_closes_at`, typed
  `checkout_input_snapshot`, `preparation_minutes`, `estimated_ready_at`.
- IMMEDIATE/LEGACY별 CHECK, conditional warning/deadline, accept/ETA pair와 정확한 산식.
- Coupon/Point reservation expiry는 direct USED에만 null을 허용하고 RESERVED에는 필수다.
- Support cancel/history의 신규 slot 없는 경로만 nullable로 만들고 legacy FK/history는 보존한다.
- 현재 due query와 보드 sort에 필요한 실제 query 기반 index만 추가한다.
- fresh install, V86 fixture와 최종 stack baseline upgrade를 PostgreSQL Testcontainers로 검증한다.

실환경 운영시간 미설정 Store, active legacy payment, incomplete publication, support reschedule은 read-only release
gate이며 이번 로컬 작업에서 DB를 변경하지 않는다.

## API and Event Contracts

- quote/create/reorder 신규 request에서 pickup input을 제거하고 quote fingerprint를 v7로 올린다.
- draft `201`, PAID confirm `200`, UNKNOWN/reconciling/recovery `202`, closed/stale `409`, dependency `503`을
  구분한다. 기존 Payment 조회·refund/reconciliation은 마감 후에도 허용한다.
- `ACCEPT`에는 preparationMinutes가 필수이고 다른 action에 보내면 400이다. UUID 호환 endpoint도 같다.
- customer history/detail, board/cursor/ETag, discovery availability, Support nullable history를 함께 전환한다.
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

세부 case와 실제 test method/명령/결과는 구현하면서 아래 Progress와 Outcomes에 갱신한다. T17/T18은 loser
승인의 환불 수렴, T20/T21은 모든 경제 write rollback까지 확인해야 통과다.

M1에서 T01~T05의 같은 날 `[open, close)`, 미설정, 수동 OFF, 단축/연장 판정을
`StoreOrderAvailabilityPolicyTest` 5개 case에 연결했다. T52의 V86→V89와 fresh install, slotless/lease 금지,
cutoff/snapshot 불변성은 `ImmediateCheckoutMigrationTest`와 `FlywayMigrationSmokeTest` 4개 case에 연결했다.
마감 단축과 실제 승인/수락의 PostgreSQL 경합(T31~T35)은 M2/M3 transaction 구현과 함께 남아 있다.

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
- [ ] M2 transaction/benefit/recovery 구현과 경합·장애 검증.
- [ ] M3 lifecycle/preparation/compensation 구현과 검증.
- [ ] M4 query/API/UI/Storybook 전환과 검증.
- [ ] M5 전체 검증, stacked PR 생성과 release gate 기록.

## Surprises & Discoveries

- origin/main은 첨부 기준 이후 Order/quote/transition에 성능 계측과 고객 탐색 개선을 포함하므로 첨부 함수
  본문을 덮어쓰면 안 된다.
- 원 checkout의 V87/V88은 각각 열린 PR #189/#190과 연관된다. origin/main은 V86이므로 이 stack은 V89를
  사용하되 repository-wide migration release 순서를 별도 gate로 유지한다.
- ADR registry가 파일 ADR-125~130을 누락하고 있어 신규 ADR 등록과 함께 현재 파일 목록을 보완했다.
- V16의 deferred point-accrual completeness trigger 때문에 schema fixture의 bare Order insert는 USER trigger를
  명시적으로 비활성화한 격리 fixture로만 만들었다. 제품 경로는 기존 snapshot 생성을 우회하지 않는다.

## Decision Log

| Date | Decision | Reason |
|---|---|---|
| 2026-09-16 | origin/main a8821fd 기준의 격리 worktree 사용 | dirty checkout과 사용자 변경 보존 |
| 2026-09-16 | V89, ADR-133~135 사용 | 열린 V87/V88 및 ADR-132와 번호 충돌 방지 |
| 2026-09-16 | IMMEDIATE/LEGACY_RESERVED 병존 | 과거 주문·event·정산·recovery 보존 |

## Outcomes

- M0 문서 검증: `scripts/verify-docs.sh` Passed (18 tests, 57 policies, 133 ADRs, 377 Markdown, 108 ExecPlans).
- M1 경계/스키마: availability 5 tests, V89/전체 Flyway 4 tests, Order domain/entity 19 tests Passed.
- M1 구조/통합: `MerchantDisplayContentAuditRollbackIntegrationTest`, `StoreOrderLifecycleIntegrationTest`,
  `ModularityTests`, `SupportArchitectureTest` Passed.
- 공유 DB inventory, 배포, 실제 PG와 공유 부하는 범위 밖이며 release gate로 Pending이다.
| 2026-09-16 | Tx A/C/D와 기존 Payment recovery 재사용 | 선예약 제거와 승인 복구를 함께 만족 |
| 2026-09-16 | schedule을 신규 결제 gate로 사용 | 사용자 확정 C04와 원래 영업 구간 보존 |

## Outcomes & Retrospective

M0 계약 문서화가 완료됐다. 로컬 구현/검증, PR과 출시 준비 결과는 이후 milestone에서 갱신한다.

## Revision Notes

- 2026-09-16: 현재 origin/main, 열린 migration PR, 최신 ADR-123과 실제 Storybook catalog를 기준으로 최초 작성.
