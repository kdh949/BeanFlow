# 코어 사용자 여정 계약

> **상태:** `CURRENT_GOAL_INTEGRATION`
> **소스 기준:** `433ed1990fdded3551d8bc1070200607904a4ac7` 위 Goal Stage 05 integration (2026-08-18)
> **목적:** 고객이 탐색·주문·결제를 완료하고, 점주가 매장에서 처리·환불·정산·이의제기를 수행하는
> 현재 구현 여정과 아직 계약되지 않은 공백을 하나의 source-backed 표로 고정한다.

이 문서는 제품 동작의 새 원천이 아니다. 제품 규칙은
[Business Policy Decisions](business-policy-decisions.md), 실패 처리는
[Failure Semantics](../architecture/failure-semantics.md), HTTP 계약은 target OpenAPI와 runtime OpenAPI가
각각 소유한다. 여기서는 그 증거를 여정 단위로 연결하고 다음 slice의 owner를 명확히 한다.

## 판독 규칙과 계약 경계

| 표기 | 뜻 |
|---|---|
| `Implemented` | Controller/Application/프론트 route가 현재 소스와 runtime OpenAPI에 함께 존재한다. 이 표기만으로 새 환경에서 실행했다는 뜻은 아니다. |
| `Integrated` | 구현에 더해 해당 완료 ExecPlan에 통합 검증 증거가 남아 있다. 이번 Stage 02가 그 검증을 재실행한 것은 아니다. |
| `Contract-only` | target OpenAPI에는 있으나 runtime 구현이 아직 없는 계약이다. |
| `Missing` | target/runtime OpenAPI와 현재 frontend route 모두에 아직 없다. |
| `Blocked` | 필요한 선행 slice 또는 release 조건이 없어서 진행할 수 없다. |
| `Unknown` | 소스만으로 결론을 낼 수 없으며 실행 증거가 필요하다. |

- target `openapi/beanflow-v1.yaml`에는 162 path, runtime
  `openapi/beanflow-v1-runtime.yaml`에는 151 path가 있다. runtime은 target의 배포 가능한 구현 부분을
  명시적으로 참조·인라인한 **의도적인 부분집합**이며, 두 파일의 path 수가 같아야 한다는 계약은 아니다.
- `RuntimeOpenApiParityTest`가 이 대응을 검증하는 현재 테스트 경계다. Stage 05 backend/API slice에서
  이 테스트는 통과했지만, 별도 browser user-journey release gate는
  [release gate](../quality/core-journey-release-gate.md)에 `Not run`으로 남긴다.
- 다음 DAG는 구현 순서 계약이다. 이 문서가 Stage 03 이상의 작업을 시작시키지 않는다.

```text
01 → 02 → {03,04,07}; {03,04} → 05 → 06; 07 → 08; {06,08} → 09
```

## 정식 여정 DAG

```text
고객
  가입·로그인 → 매장 탐색 → 매장·메뉴·픽업 확인 → [쿠폰 지갑] → 주문 생성
       → 결제 시도·확인·UNKNOWN 복구 → 내 주문/추적 → 취소 또는 재주문

점주
  로그인·최초 비밀번호 변경 → 접근 매장 선택 → 주문 수락·제조·완료
       → 부분 환불 → 정산 항목 확인 → 이의제기
```

대괄호의 쿠폰 지갑은 backend/API contract가 구현됐지만 고객 선택 UI는 아직 Storybook MCP 차단 상태다.
쿠폰을 사용한 주문의 금액·소유권·상태 검증은 이미 주문 생성 transaction의 `CouponReservationService`가
최종 권한을 가진다. 조회 API나 이후 UI가 그 검증을 대체해서는 안 된다.

## 현재 source-backed journey inventory

`Seed fixture`는 현재 재현에 쓰이는 관련 seed 또는 fixture의 위치이며, 모든 행에 화면 데이터가
충분하다는 주장이 아니다. 특히 검색 결과 데이터와 쿠폰 지갑은 별도 release 조건을 가진다.

| Journey step | Actor goal | Backend capability | Runtime API | Frontend route | Seed fixture | Automated evidence | Owner ExecPlan | Current state | Blocker |
|---|---|---|---|---|---|---|---|---|---|
| 고객 가입·로그인·세션 | 계정 생성 후 자신의 세션으로 시작 | Customer account, login, session rotation, CSRF | `POST /auth/customer/registrations`, `POST /auth/customer/sessions`, `GET /me` | `/app/signup`, `/app/login`, `/app` | `scripts/demo/seed.sh` | `frontend/src/features/auth/customer/customerSession.test.tsx`; Plan 80 validation | productization-30, -80 (completed) | Integrated | 이번 slice에서 browser 재실행 안 함 |
| 매장 탐색 | 검색·근처·추천에서 매장 선택 | Discovery query projection | `GET /stores/search`, `/stores/nearby`, `/me/store-recommendations` | `/app`, `/app/stores` | `scripts/demo/seed.sh` | `frontend/src/features/discovery/Discovery.test.tsx`; productization-70, -80 | productization-70, -80 (completed) | Integrated | demo seed의 검색 색인 한계로 결과 화면 fresh proof 없음 |
| 매장·메뉴·픽업 확인 | 메뉴·옵션·가능한 픽업 슬롯 확인 | public store/catalog read | `GET /stores/{storeId}`, `/stores/{storeId}/menus`, `/stores/{storeId}/pickup-slots` | `/app/stores/:storeId`, `/app/cart` | `scripts/demo/seed.sh` | `frontend/src/features/ordering/Ordering.test.tsx`; productization-80 | productization-80 (completed), ADR-076 | Integrated | 이번 slice에서 runtime 실행 안 함 |
| 보유 쿠폰 지갑·선택 | 이미 발급된 쿠폰 중 매장에 적용 가능한 것을 확인 | Customer-scoped Promotion DTO projection | `GET /me/coupons?storeId=&cursor=&limit=` | 없음 (UI blocked) | `CustomerCouponWalletIntegrationTest` Testcontainers fixture | `CustomerCouponWalletIntegrationTest`, OpenAPI contract, parity, auth registry, CouponReservation regression | **Stage 05 core-journey slice** | Backend/API Integrated | Storybook MCP가 없어 selector/loading/empty/unavailable/selection browser proof가 Not run |
| 포인트 확인 | 사용 가능 잔액·만료 예정 확인 | actor-scoped point facade | `GET /me/points`, `/me/point-transactions` | `/app/points` | `scripts/demo/seed.sh` | `frontend/src/features/loyalty/Points.test.tsx`; productization-80 | productization-80 (completed) | Integrated | fresh E2E not run |
| 주문 생성 | 장바구니를 서버 가격으로 주문화 | order validation, reservation, coupon/point calculation | `POST /orders` | `/app/cart`, `/app/checkout/:orderId` | `scripts/demo/seed.sh` | `frontend/src/features/ordering/Ordering.test.tsx`; productization-80 | ordering core, productization-80 | Integrated | coupon wallet UI absent; order command remains authoritative |
| 결제 시도·확인·복구 | 결제창 뒤 확정 상태 또는 UNKNOWN 복구를 본다 | payment attempt, confirmation, reconciliation | `GET /payment-config`, `POST /orders/{orderId}/payment-attempts`, `POST /payments/{paymentId}/confirmations`, `GET /payments/{paymentId}` | `/app/checkout/:orderId`, `/app/payments/:paymentId/success`, `/app/payments/:paymentId/fail` | `scripts/demo/seed.sh` | `frontend/src/features/payment/PaymentCallbackPages.test.tsx`; productization-80 smoke historical evidence | payment core, productization-80 | Integrated | Toss sandbox is optional and not current-run proof |
| 내 주문·추적·취소·재주문 | 자신의 주문을 보고 후속 행동 수행 | customer order projection, cancellation/reorder facade | `GET /me/orders`, `/me/orders/{orderReference}`, `POST /me/orders/{orderReference}/cancellations`, `/reorders` | `/app/orders`, `/app/orders/:orderReference` | `scripts/demo/seed.sh` | `frontend/src/features/ordering/OrderPages.test.tsx`; productization-50, -80 | productization-50, -80 (completed) | Integrated | fresh journey E2E not run |
| 점주 로그인·최초 비밀번호 변경 | 권한 있는 점주 세션 수립 | merchant login, session, password-change | `POST /auth/merchant/sessions`, `POST /auth/merchant/password-changes`, `GET /merchant/me` | `/store/login`, `/store/password` | `scripts/demo/seed.sh` | `frontend/src/features/auth/merchant/merchantSession.test.tsx`; productization-40 | productization-40 (completed) | Integrated | browser rerun not performed |
| 접근 매장 선택·주문보드 | 자신의 매장에서 주문 수락·제조·완료 | membership scoped store list and board transition | `GET /merchant/me/stores`, `GET /stores/{storeId}/orders`, `POST /stores/{storeId}/orders/{orderReference}/transitions` | `/store` | `scripts/demo/seed.sh` | `frontend/src/pages/console/StoreOrderBoard.test.tsx`; productization-60 | productization-60 (completed) | Integrated | combined customer→merchant browser E2E not run |
| 부분 환불 | line/quantity로 환불을 미리 보고 실행 | preview, partial refund, point recovery states | `POST /stores/{storeId}/orders/{orderReference}/refund-previews`, `/refunds` | `/store/refunds/:storeId/:orderReference` | `scripts/demo/seed.sh` | `frontend/src/features/merchant/MerchantFinancialSurface.test.tsx`; productization-90 | productization-90 (completed) | Integrated | fresh recovery verification not run |
| 정산·조정 결과 | 매장별 정산 batch/item을 본다 | settlement and adjustment projection | `GET /stores/{storeId}/settlements`, `/stores/{storeId}/settlements/{settlementBatchId}/items` | `/store/settlements` | `scripts/demo/seed.sh` | `frontend/src/features/merchant/MerchantFinancialSurface.test.tsx`; productization-90 | productization-90 (completed) | Integrated | OWNER/STAFF fresh verification not run |
| 이의제기 | 정산 item에 이의제기하고 목록 확인 | dispute command and merchant list | `POST /settlement-items/{itemId}/disputes`, `GET /stores/{storeId}/disputes` | `/store/disputes` | `scripts/demo/seed.sh` | `frontend/src/features/merchant/StoreDisputesPage.test.tsx`; productization-90 | productization-90 (completed) | Integrated | fresh role verification not run |

## Stage 05 coupon wallet boundary

Stage 05 owns only the following read-and-select capability. It does not reopen Campaign administration,
coupon issuance, wallet balance, or coupon history.

1. The current customer may query only their own coupon issuances in `AVAILABLE` or `RESTORED` state. A normal
   issuance, including an original issuance restored after reservation, requires `Campaign.active=true` and an
   unexpired issuance. A restored compensation issuance requires its complete issuance-owned immutable terms
   snapshot and an unexpired issuance, not a live Campaign check; missing or incomplete snapshot data fails with
   a typed 5xx and is never completed from Campaign data. Campaign expiry or title is not invented for this query.
2. `storeId` is required. The current slice decides only current store applicability. An applicable item has
   `applicable=true`; a non-applicable item has `applicable=false` and `reasonCode=STORE_NOT_APPLICABLE`.
   The item remains visible instead of being silently omitted. Brand-scope matching and brand hierarchy are deferred.
3. `minimumOrderKrw` is informative. Since this query has no order amount, it does not emit an invented
   `MINIMUM_ORDER_NOT_MET` result. Order creation recalculates all conditions against the actual cart.
4. `RESERVED`, `USED`, and `EXPIRED` records are not history items for this endpoint. Campaign management,
   limited issuance, and history screens remain outside this Stage 05 slice.
5. A failed query is a typed failure (normally 503), not an empty list. The UI must distinguish loading,
   empty, and unavailable. No cache, fake, or stale coupon list may be substituted as success.
6. The response is a read projection only. `POST /orders` remains the final authority for ownership, state,
   expiry, store scope, minimum order amount, the one-coupon rule, and concurrent consumption.

`GET /me/coupons`와 response fields는 target/runtime OpenAPI 및 generated client에 구현됐다. 이 backend/API
slice는 order authority를 바꾸지 않으며 UI selection completion을 주장하지 않는다. Representative
`EXPLAIN (ANALYZE, BUFFERS)`는 새 index 필요성을 보이지 않았으므로 migration-writer lease와 index는
추가하지 않았다.

## Release use

The [Core Journey Release Gate](../quality/core-journey-release-gate.md) is the only status page for the
cross-journey release decision. This inventory records current source evidence, historical evidence references,
and open contract owners; it does not assert a local, remote, or production release.
