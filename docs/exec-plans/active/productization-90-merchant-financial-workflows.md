# 점주가 UUID 없이 부분 환불·정산·이의제기를 처리한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/productization-40-merchant-account-and-initial-password.md`, `docs/exec-plans/completed/productization-60-store-order-board.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

점주·직원이 주문 카드에서 품목과 수량을 선택해 서버 계산 환불액을 확인한 뒤 실행하고, 점주는 자신
매장의 정산 명세를 보고 이의제기를 접수·추적하게 한다. 결제·OrderLine UUID 입력을 제거하면서 기존
환불 원장, 포인트 복원, 정산 조정과 이의제기 Aggregate를 그대로 재사용한다.

## Current State

- `POST /payments/{paymentId}/refunds`는 OWNER·STAFF·PLATFORM_OPERATOR와 내부 UUID를 받는다.
- `PartialRefundService`는 Payment/Order lock, 결정적 unit 배분, Provider transaction 분리,
  포인트 복원·정산 tie-out과 멱등 응답을 이미 구현한다.
- `GET /stores/{storeId}/settlements`와 batch item 목록은 구현돼 있다.
- 이의제기 접수는 `POST /settlement-items/{itemId}/disputes`로 구현됐지만 store-scoped 목록 조회가 없다.
- 점주 frontend는 매장 선택·금융 화면이 연결돼 있지 않고 기존 환불 UI는 payment/orderLine UUID를
  직접 받는다.

## Definitions

- **Refund preview:** 금전 변경 없이 현재 환불 가능 unit과 배분액을 계산한 Projection이다.
- **Preview version:** ADR-108의 canonical refund state hash다. 권한 token이 아니다.
- **Financial owner action:** 정산 조회·이의제기처럼 `ACTIVE OWNER` membership만 가능한 action이다.
- **Settlement item:** 확정 정산 Batch 안의 주문별 명세이며 이의제기 대상이다.
- **Dispute summary:** 점주에게 필요한 상태·held 금액·접수 시각만 담고 내부 worker 오류를 제외한 DTO다.

## Scope

### In Scope

- ADR-108의 부분 환불 preview와 주문번호 기반 실행 facade
- OWNER·STAFF 환불 권한, reason·Idempotency-Key와 실행 Audit
- 기존 settlement batch/item 조회를 MerchantActor로 연결
- `GET /stores/{storeId}/disputes` store-scoped Cursor Projection
- 기존 settlement item 이의제기 접수를 Session OWNER 경로에 연결
- 점주 frontend의 환불·정산·이의제기 feature와 UUID 입력 제거
- dispute 목록 인덱스·실행계획과 금융 상태 matrix 검증

### Non-goals

- STAFF의 정산·이의제기 접근
- 이의제기 운영자 배정·수동 판정 API(P1)
- 정산 batch 재실행 preview(P1)
- 실제 은행 지급·KYC
- STAFF 환불 금액 상한, PIN step-up 또는 2인 승인
- 환불 계산·원장·Provider adapter 재작성

## Business Rules and Invariants

1. 부분 환불은 BR-38과 ADR-108을 적용한다. OWNER·STAFF 모두 현재 ACTIVE membership 매장에만
   실행할 수 있다.
2. 정산 batch/item 조회와 이의제기 접수·목록은 현재 `ACTIVE OWNER`만 가능하다. STAFF에는 403이다.
3. 환불 request는 orderReference와 lineSequence·quantity만 받는다. 금액과 내부 UUID를 받지 않는다.
4. preview 금액은 실행 승인 값이 아니다. 실행 transaction이 lock 아래 다시 계산한다.
5. 미확정 Refund가 있으면 새 환불을 만들지 않고 기존 reconciliation을 기다린다.
6. 확정 SettlementItem은 수정하지 않는다. 이의제기 held 금액과 accepted 결과는 기존
   SettlementAdjustment 원장으로 표현한다.
7. 이의제기 expectedAdjustmentKrw는 점주의 주장 금액이며 서버는 confirmed item·window·상한을 기존
   Filing Service에서 검증한다. 화면 계산값으로 source of truth를 바꾸지 않는다.
8. UI role gate는 편의 표시일 뿐이다. 모든 endpoint가 membership을 다시 검증한다.

## Architecture and Transaction Boundaries

```text
POST .../refund-previews
  MerchantActor + store/orderReference lookup
  read-only snapshot: Payment + Refund + OrderLine + point policy
  lineSequence allocation → previewVersion + amounts
  no Provider / no write / no Audit

POST .../refunds
  Tx1: membership + Order/Payment lock + previewVersion 재계산
       sequence → internal line ID
       existing PartialRefundPreparationTransaction
  commit Tx1
  Provider call
  Tx2: Provider outcome + point/settlement result

GET /stores/{storeId}/disputes
  MerchantActor OWNER membership
  QueryRepository: WHERE store_id = :storeId
  ORDER BY filed_at DESC, id DESC
```

- 환불 facade가 기존 `PartialRefundService` 계산을 별도 구현하지 않는다. preview에 필요한 pure allocation
  calculator를 추출하고 preview/prepare가 같은 함수를 사용한다.
- store/orderReference→payment resolution은 Query/port가 수행하며 Controller가 Repository를 직접
  호출하지 않는다.
- Settlement/Dispute Controller의 `Jwt` 파라미터는 Plan 20의 MerchantActor를 사용한다. actor ID나 role을
  body에서 받지 않는다.
- merchant unsafe request는 `BEANFLOW_MERCHANT_XSRF` cookie와 `X-BEANFLOW-CSRF` header를 사용한다.

## Alternatives Considered

- 기존 UUID refund endpoint만 UI에서 호출: 기술 식별자 입력과 교차 주문 실수가 남아 기각한다.
- preview 금액을 execute body로 신뢰: 변조·TOCTOU 때문에 기각한다.
- preview DB reservation: 만료·정리·취소 상태가 새 운영 대상이 되어 기각한다.
- STAFF에게 정산 조회도 허용: 기존 authorization matrix와 정산 owner 정책을 바꾸므로 P0에서 기각한다.
- Dispute Aggregate를 settlement response에 eager join: 목록 N+1과 쓰기 모델 결합 때문에 별도 Query를 쓴다.

## Failure Semantics

- 다른 매장·revoked membership·role mismatch: 403. 리소스 없음으로 감추지 않는다.
- refund target/dispute target 없음: 404. 다른 store 소유임이 확인되면 403이다.
- previewVersion 변화: 409 `REFUND_PREVIEW_STALE`; UI는 preview를 다시 요청한다.
- 미확정 환불: 409 `REFUND_OUTCOME_UNRESOLVED`; 새 Provider call은 0회다.
- line quantity가 remaining을 초과: 422. 금액을 0으로 줄여 실행하지 않는다.
- Provider timeout·응답 유실: 202/UNKNOWN 또는 reconciliation. 성공·실패로 단정하지 않는다.
- settlement/dispute query 장애: 503. 빈 명세·이의제기 없음으로 대체하지 않는다.
- Audit 저장 실패는 환불·이의제기 command transaction의 성공 응답을 막는다.

## Data and Migration

새 migration은 없다. 부분 환불은 기존 원장과 line sequence를 사용하고, 이의제기 목록은 V28의
`idx_settlement_dispute_store_filed (store_id, filed_at, id)`를 역방향 scan해
`filed_at DESC, id DESC` 정렬을 지원한다.

- 실제 PostgreSQL fixture에서 이 index의 backward scan과 store predicate를 `EXPLAIN (ANALYZE,
  BUFFERS)`로 검증한다.
- 측정 결과로 기존 index가 부족하더라도 이 plan의 선언 없이 중복 DESC index를 즉시 만들지 않는다.
  migration이 필요하면 `Writes-Migration`, ADR-072 writer lease와 program 순서를 먼저 갱신한다.
- 기존 이의제기 row backfill은 없다.

## API and Event Contracts

```http
GET  /api/v1/auth/merchant/csrf
POST /api/v1/stores/{storeId}/orders/{orderReference}/refund-previews
POST /api/v1/stores/{storeId}/orders/{orderReference}/refunds
GET  /api/v1/stores/{storeId}/settlements?cursor=&limit=
GET  /api/v1/stores/{storeId}/settlements/{settlementBatchId}/items?cursor=&limit=
POST /api/v1/settlement-items/{itemId}/disputes
GET  /api/v1/stores/{storeId}/disputes?state=&cursor=&limit=
```

- refund schema와 previewVersion은 ADR-108을 따른다. 신규 merchant 응답은 paymentId/orderLineId를
  포함하지 않는다.
- settlement item ID와 dispute ID는 목록에서 받은 opaque resource link로만 사용하며 입력창을 만들지
  않는다. body의 actor/store ID는 금지한다.
- dispute list 기본 20, 최대 100. cursor는 storeId·state filter와 `(filedAt,id)`를 서명한다.
- dispute summary는 `disputeId`, `settlementItemId`, state, expected/held amount, filedAt, decidedAt만
  반환한다. 내부 reprocessing case, worker 오류와 actor credential은 제외한다.
- 기존 도메인 이벤트와 Refund/Settlement/Dispute 이벤트 payload는 변경하지 않는다.

## P0 Merchant Screen Coverage

| 화면 | owner capability | 이 plan의 완료 증거 |
|---|---|---|
| `1b POS 주문보드` | Plan 60 | board route와 환불 상세 진입 |
| `4a 매장 전환` | Plan 40 | membership role별 menu/route gate |
| `4c 품목 부분 환불` | ADR-108 | preview·stale·UNKNOWN·Audit 상태 |
| `2a 정산 내역` | 기존 Settlement | OWNER batch/item 목록과 empty/error 상태 |
| `2b 이의제기 상세` | 기존 Dispute + 신규 Query | item 접수·store 목록·state 상태 |

## Milestones

1. 기존 dispute index의 backward scan 실행계획 확인.
2. pure refund allocation/preview service와 previewVersion 구현.
3. orderReference+lineSequence merchant refund facade와 기존 service 연결.
4. Settlement Controller MerchantActor 전환과 OWNER authorization 회귀 검증.
5. store-scoped Dispute QueryRepository·cursor·endpoint 구현.
6. merchant Session/CSRF API client와 financial route role gate 구현.
7. 환불·정산·이의제기 화면 연결, UUID 입력·기존 operator refund form 제거.
8. runtime OpenAPI, Error Catalog, 실행계획·브라우저 evidence 갱신.

## Required Tests

- ADR-108 preview/execute, 같은 unit 동시 경쟁, stale와 unresolved 결과.
- OWNER·STAFF 환불 허용, OWNER만 정산/이의제기 허용과 모든 다른-store/revoked 조합 403.
- 부분 환불 Provider call이 준비 transaction 밖이고 timeout에서 중복 호출이 없는지 검증.
- 환불 cash/points/coupon/settlement 합계와 기존 테스트 회귀.
- dispute 목록의 store predicate, state/cursor 서명, 누락·중복 없는 keyset.
- 이의제기 reason/evidence/window/refile·idempotency와 Audit/event 원자성 회귀.
- query 장애가 empty UI가 아닌 retryable failure인지 검증.
- role별 navigation, 409 preview refresh, 202/UNKNOWN과 terminal result 표시.
- DOM/request에 paymentId/orderLineId UUID 입력 field와 client-supplied refund amount가 없는지 검증.

## Validation Commands

```bash
./gradlew test --tests '*PartialRefund*' --tests '*SettlementBatch*' --tests '*SettlementItem*' --tests '*SettlementDispute*'
cd frontend && npm test
cd frontend && npm run typecheck
cd frontend && npm run build
./gradlew spotlessCheck
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
```

## Observability

- preview success/stale/unresolved와 refund actor role별 결과
- Provider UNKNOWN·reconciliation 수와 해소 시간
- settlement/dispute 목록 p50·p95·p99와 query row 수
- dispute filing state·window/idempotency 결과
- store authorization 거부 수

## Documentation Updates

- [ADR-108](../../adr/ADR-108-merchant-partial-refund-preview.md)
- [BR-38](../../product/business-policy-decisions.md)
- [Authorization Matrix](../../security/authorization-matrix.md)
- `docs/api/api-conventions.md`, `docs/api/error-catalog.md`
- `openapi/beanflow-v1.yaml`, `openapi/beanflow-v1-runtime.yaml`
- 정산·부분 환불 운영 runbook과 실행계획 evidence

## Progress

2026-08-14: 선행 Plan 40과 Plan 60의 실제 Outcome·필수 검증이 모두 completed되어 dependency path와
`Implementation-Ready=true`를 갱신했다. 구현은 시작하지 않았고 기존 dispute index 실행계획 확인은
이 plan의 첫 milestone로 남는다. 이 plan 자체는 migration writer lease를 요구하지 않는다.

- 2026-08-15: runtime OpenAPI는 Merchant Chain의 legacy `POST /payments/{paymentId}/refunds`에
  `X-BEANFLOW-CSRF`를 요구하지만, `ConsolePages`의 UUID refund form은 `Idempotency-Key`만 보내
  frontend typecheck/build를 실패시킨다. 사용자 선택 A에 따라 이 보정은 완료된 Plan 60에 one-off header로
  넣지 않고, 이 plan의 Merchant financial Session/CSRF client 및 UUID form 교체 milestone에서 함께 처리한다.
  구현은 여전히 시작하지 않았다.

## Surprises & Discoveries

- OrderLine에는 이미 order-scoped immutable `lineSequence`가 있어 새 공개 line ID migration이 필요 없다.
- 이의제기는 접수·worker 판정은 있지만 점주가 새로고침 뒤 상태를 볼 store-scoped Query가 없다.
- `ConsolePages`의 이름 `OpsRefundPage`와 달리 중앙 path registry는 `/payments/{paymentId}/refunds`를
  Merchant Chain으로 배정한다. 이를 Operations bearer 요청으로 취급하거나 CSRF를 끄면 ADR-094를 위반한다.
  one-off header는 단기적으로 build를 통과시키지만 기존 UUID form과 token 흐름을 중복시키므로, Merchant
  financial client와 form 교체를 함께 하는 이 plan으로 보정을 남긴다. 그때까지 frontend 전체
  typecheck/build는 이 call과 Plan 80 소유 두 customer call로 실패한다.

## Decision Log

| 일자 | 결정 | 기록 위치 |
|---|---|---|
| 2026-08-12 | 부분 환불은 OWNER·STAFF 모두 실행 | [BR-38](../../product/business-policy-decisions.md) |
| 2026-08-12 | 공개 품목 식별자는 orderReference 범위의 lineSequence | [ADR-108](../../adr/ADR-108-merchant-partial-refund-preview.md) |
| 2026-08-12 | 정산·이의제기는 기존 ACTIVE OWNER 정책 유지 | [Authorization Matrix](../../security/authorization-matrix.md) |
| 2026-08-15 | legacy `/payments/{paymentId}/refunds`의 CSRF consumer와 UUID form 제거는 이 plan의 Merchant financial Session/CSRF client milestone에서 함께 전환하며, Plan 60에는 one-off header를 선반영하지 않는다 | [MD-2026-014](../../decisions/minor-decisions.md), [ADR-094](../../adr/ADR-094-browser-session-security.md) |

## Outcomes & Retrospective

아직 없다.

## Revision Notes

- 2026-08-12: 최초 작성.
- 2026-08-14: Plan 60 완료에 따라 dependency path와 readiness를 갱신. 구현은 시작하지 않음.
- 2026-08-15: 사용자 선택 A에 따라 Merchant CSRF consumer 보정의 소유 범위, trade-off와 frontend 전체 build의 알려진 실패를 기록. 구현은 시작하지 않음.
