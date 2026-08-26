# Design to Capability Map

> **문서 목적:** 원본 디자인 48화면과 제품 완결성 때문에 추가한 운영 화면 1개가 어떤 Backend
> Capability를 요구하는지, 그중 무엇이 이미 구현됐고 무엇이 없는지, 어떤 우선순위로 만들지를
> 화면 단위로 확정한다.
> **적용 범위:** BeanFlow 제품화(Productization) 프로그램 P0~P1
> **결정 상태:** `Accepted` (2026-08-12).

## 원천과 판독 규칙

화면 인벤토리의 원천은 다음 세 디자인 문서의 `data-screen-label`이다.

| 콘솔 | 화면 수 | 화면 ID 예시 |
|---|---:|---|
| 고객 앱 | 22 | `고객 1a 홈` |
| 점주 콘솔 | 13 | `점주 1b POS 주문보드` |
| 운영자 콘솔 | 13 | `운영자 1a 실패 추적` |
| 합계 | 48 | — |

원본에는 점주 계정을 실제 발급할 화면이 없다. [BR-46](business-policy-decisions.md)에 따라 P0 운영
콘솔에 `신규 점주 계정 발급` 화면 1개를 추가하므로 구현 인벤토리는 총 49화면이다.

### 첨부 ZIP 대조 증거

화면 계약 검증 자료로 첨부된 `BeanFlow_디자인.zip`(SHA-256
`a546e3f4253f35f9c0c405d0c8f1b57e3d94e98f5a7eeb32c5ce72dc3b1f612e`)을 대조했다. 압축파일에는
`BeanFlow 고객 앱.dc.html`, `BeanFlow 점주 콘솔.dc.html`, `BeanFlow 운영자 콘솔.dc.html` 원문과
렌더링 screenshot이 들어 있다. 세 HTML의 `data-screen-label`을 직접 계수한 결과 고객 22개,
점주 13개, 운영자 13개로 정확히 48개이며 위 인벤토리와 일치한다.

원문과 screenshot은 정적 화면의 문구·상태를 판독하는 증거로 사용했다. 특히 고객 `5a/5b`의
전화번호 OTP, 고객 `2b`의 자동 환불·지갑 환급, 고객 `4d`의 취소 전 환급 분해, 점주 `4d`의
PIN·3단 권한, 점주 `4e`의 발주 실행, 운영자 `4a`의 자동 PG 전환, 운영자 `4b/4c`의 KYC·실지급을
직접 확인했다. 이 동작들은 그대로 구현하지 않고 [Design and Contract Conflicts](design-contract-conflicts.md)의
C-1~C-18 판정과 화면 수정 지시를 적용한다.

판독 규칙은 다음과 같다.

1. 디자인 화면은 요구의 원천이지 계약이 아니다. 화면과 Accepted ADR·Business Policy가 충돌하면
   화면을 수정한다. 충돌 목록과 해소 결정은 [Design Contract Conflicts](design-contract-conflicts.md)에 있다.
2. `필요 API`의 경로는 `/api/v1` prefix를 생략한다. 이미 존재하는 operation은
   [Runtime OpenAPI](../../openapi/beanflow-v1-runtime.yaml)와 대조한 것이고, `신규`는 아직
   Controller mapping이 없다는 뜻이다.
3. `기존 구현 재사용`은 Backend capability 기준이다. 화면이 없다는 것과 capability가 없다는 것을
   구분한다.
4. 우선순위는 아래 정의를 따른다.

| 우선순위 | 정의 |
|---|---|
| `P0` | 고객이 로그인해 주문하고, 점주가 처리하고, 운영자가 실패를 복구하는 최소 제품에 필요하다. |
| `P1` | 제품 가치는 있으나 P0 흐름 없이는 검증할 수 없다. P0 완료 뒤 개별 승인으로 시작한다. |
| `Non-goal` | 이번 범위에서 구현하지 않는다. 화면은 sandbox·참고 표기로 남기거나 제거한다. |

5. `필요 상태`는 프론트엔드가 반드시 구분해 렌더링해야 하는 상태다. 모든 화면은 아래 공통
   상태 계약을 기본으로 갖는다. 표에는 화면 고유의 추가 상태만 적는다.

```text
loading / empty / success / validation-error / conflict
offline / retryable-failure / terminal-failure / unauthorized / forbidden
```

6. 프론트엔드는 주문 상태 전이 규칙을 자체 구현하지 않는다. 수행 가능한 행동은 Backend가
   `allowedActions`로 계산해 반환한다.
7. **P0 완료는 아래 24화면 전체의 Backend capability, 프론트엔드 상태 계약과 검증이 완료된
   시점이다.** 인증·고객 주문 목록·점주 주문보드까지의 첫 수직 흐름은 중간 통합 지점이며 P0
   완료로 간주하지 않는다.

---

# A. 고객 앱 (22화면)

## A-1 계약

| 화면 | 사용자 | 목적 | 소유 Context | 필요 API | 우선순위 | 기존 구현 재사용 |
|---|---|---|---|---|---|---|
| `5a 첫 진입` | 미인증 방문자 | 가입·로그인 진입 | Identity | `POST /auth/customer/registrations`, `POST /auth/customer/sessions` (신규) | P0 | 없음. 계정·자격증명 테이블 자체가 없다. |
| `5b 인증번호` | 미인증 방문자 | 휴대전화 OTP 확인 | Identity | `POST /auth/customer/phone-verifications` (신규) | P1 | 없음. P0는 ID/PW로 대체한다(충돌 C-1). |
| `5c 위치 권한` | 고객 | 위치 거부 시 대체 탐색 | Discovery | `GET /stores/search?query=` (신규), `GET /me/recent-stores` (신규) | P1 | `GET /stores/nearby` 좌표 경로만 존재. |
| `5d 빈 상태 세트` | — | 빈 상태 문구·톤 비교 시트 | — | 없음 | 화면 아님 | 각 화면의 `empty` 상태 계약으로 흡수한다. |
| `5e 오프라인` | 고객 | 결제 중 단절의 결과 확인 | Payment | `GET /payments/{paymentId}` | P0 | 있음. 재승인 없이 기존 attempt를 조회한다(ADR-007). |
| `1a 홈` | 고객 | 활성 주문·재주문·주변 매장 | Ordering, Discovery | `GET /me/orders?status=ACTIVE`, `GET /me/store-recommendations`, `GET /stores/nearby` | P0 | 실제 주문·추천·주문 가능/운영시간·다음 픽업 projection과 실패/빈 상태 UI가 연결됐다. |
| `1b 매장 찾기` | 고객 | 반경·필터·정렬 탐색 | Discovery | `GET /stores/nearby`, `GET /stores/search` | P0 | 이름·메뉴 검색, 이미지, 주문 가능/운영시간·다음 픽업 projection과 위치 거부/빈/실패 상태가 연결됐다. |
| `1c 매장 상세` | 고객 | 메뉴·재고·슬롯 확인 | Merchant, Fulfillment | `GET /stores/{storeId}`, `GET /stores/{storeId}/menus`, `GET /stores/{storeId}/pickup-slots` | P0 | Store 표시 profile, menu 표시 metadata와 실제 earliest pickup을 제공한다(ADR-076/117). |
| `1d 주문 추적` | 고객 | 상태·픽업번호·실제 진행 시각 추적 | Ordering | `GET /me/orders/{orderReference}` | P0 | public reference, pickup number, immutable pricing과 실제 lifecycle projection/UI가 연결됐다(ADR-099). |
| `4a 장바구니` | 고객 | 서버 견적의 상품·혜택·결제 금액 확인 | Ordering | `POST /me/order-quotes`, `POST /orders` | P0 | 서버 Cart Aggregate는 만들지 않는다. 비예약 quote fingerprint를 최종 잠금 재검증하고 stale은 새 key 재확인을 요구한다(ADR-116). |
| `2a 결제` | 고객 | 결제 요청 | Ordering, Payment | `POST /orders`, `POST /orders/{orderId}/payment-attempts`, `GET /payment-config` | P0 | 있음(ADR-080). `결제수단 선택`은 제거한다(충돌 C-2). |
| `2b 결제 예외` | 고객 | 중복 감지·재고 변경 안내 | Payment | `GET /payments/{paymentId}` | P1 | 멱등 승인은 있음. 화면 문구를 멱등 계약에 맞춘다(충돌 C-3). |
| `2c 결제수단 관리` | 고객 | 저장 결제수단 lifecycle | Payment | `GET/POST /payment-methods`, `PUT /{id}/default`, `DELETE /{id}` | P1 | 있음. 단 Checkout 승인 원천이 아니다(ADR-101). |
| `2d 부분 환불 상세` | 고객 | 환불 내역·포인트 복원 확인 | Ordering, Payment | `GET /me/orders/{orderReference}` (신규) | P1 | 환불 원장·복원 있음. 고객 조회 투영 없음. |
| `4b 쿠폰·프로모션` | 고객 | 보유 쿠폰 조회·선택, 한정 발급 | Promotion | `GET /me/coupons?storeId=&cursor=&limit=`, `POST /campaigns/{campaignId}/coupon-issuances` (신규) | P1 (발급); Goal core-journey Stage 05 (조회·선택) | wallet query와 매장 범위 선택 UI는 구현·live Storybook 검증됐다. 발급 한도 컬럼과 고객 발급 endpoint는 없다(ADR-107). |
| `4c 주문 내역` | 고객 | 과거 주문 목록 | Ordering | `GET /me/orders?from=&to=&cursor=` | P0 | actor-scoped cursor 목록과 기간 검증, active/past/empty UI가 연결됐다(ADR-070/099). |
| `4d 주문 취소` | 고객 | 수락 전 전체 취소 | Ordering | `GET /me/orders/{orderReference}`의 `cancellationPreview`, `POST /me/orders/{orderReference}/cancellations` (신규 경로, 기존 유스케이스) | P0 | 있음(ADR-029~032). 서버가 예상 환급을 계산하고 명령 시 재검증하며, 경로는 주문번호 기반으로 바꾼다. |
| `4e 알림` | 고객 | 알림함·수신 설정 | Notification | `GET /me/notification-summary`, `GET /me/notifications`, `PATCH /me/notifications/{id}`, `GET/PUT /me/notification-preferences` | P1 | InboxItem/Delivery 분리, unread bell, strict read와 마케팅 기본 opt-out UI가 연결됐다(ADR-104). |
| `4f 마이` | 고객 | 계정 허브·로그아웃 | Identity | `GET /me`, `DELETE /auth/customer/sessions/current` (신규) | P0 | 없음. |
| `3a 포인트` | 고객 | 잔액·만료·원장 | Loyalty | `GET /me/points`, `GET /me/point-transactions` (신규 facade) | P0 | Account 조회는 있음. `accountId`를 Session actor로 해석하고 가입과 0원 계정을 원자 생성한다(ADR-109). |
| `3b 지갑` | 고객 | 선불 지갑 | — | 없음 | Non-goal | 이미 [Non-goals](non-goals.md)다. 화면은 참고 표기로 유지한다. |
| `3c 재주문 재검증` | 고객 | 재검증된 재주문 | Ordering | `POST /me/orders/{orderReference}/reorders` | P0 | Session 소유권·주문번호 facade와 변경/슬롯 재선택/삭제 품목 UI가 연결됐다(ADR-077). |

## A-2 상태 계약

| 화면 | 화면 고유 상태 | 실패 상태 |
|---|---|---|
| `5a 첫 진입` | `submitting`, `locked` | 자격증명 불일치, 계정 잠금, 시도 제한 초과. 계정 존재 여부를 응답으로 구분하지 않는다. |
| `5e 오프라인` | `checking`, `approved`, `pending`, `failed` | `UNKNOWN`은 실패가 아니다. 재시도 버튼은 `terminal-failure`에서만 노출한다. |
| `1a 홈` | `no-active-order`, `has-active-order` | 활성 주문 조회 실패를 "주문 없음"으로 표시하지 않는다. |
| `1b 매장 찾기` | `location-denied`, `no-result-in-radius` | 위치 실패와 결과 없음을 구분한다. |
| `1c 매장 상세` | `sold-out-item`, `slot-closed` | 품절 메뉴는 숨기지 않고 비활성으로 노출한다. |
| `1d 주문 추적` | `PENDING_PAYMENT`, `PAID`, `ACCEPTED`, `PREPARING`, `READY`, `COMPLETED`, `CANCELLED` | 알림 실패는 준비 완료 상태를 바꾸지 않는다(ADR-019). |
| `4a 장바구니` | `price-changed`, `stock-changed` | 담기 시점 금액을 결제 금액으로 신뢰하지 않는다. |
| `2a 결제` | `preparing`, `window-open`, `confirming` | 준비 실패, 창 이탈, 승인 거절, 슬롯 lease 만료를 분리한다. |
| `2b 결제 예외` | `duplicate-detected`, `stock-adjusted` | 자동 환불 진행 중을 성공으로 표시하지 않는다. |
| `4c 주문 내역` | `first-page`, `has-more`, `cursor-invalid`, `range-filtered` | 만료·변조 cursor와 잘못된 기간(`from > to`)은 목록 없음이 아니라 400이다(ADR-070). |
| `4b 쿠폰·프로모션` | `loading`, `empty`, `applicable`, `STORE_NOT_APPLICABLE`; issuance의 `issuable`, `already-issued`, `exhausted`, `not-in-period`은 P1 | wallet query 실패를 빈 목록으로 바꾸지 않는다. `minimumOrderKrw`는 정보이며 checkout이 실제 주문금액을 다시 검증한다. 발급 상태는 ADR-107 범위를 유지한다. |
| `4e 알림` | `unread`, `read`, `marketing-opt-out` | 채널 전달 실패는 알림함 항목을 없애지 않는다. 전달 상태를 고객에게 노출하지 않는다(ADR-104). |
| `4d 주문 취소` | `cancellable`, `deadline-passed`, `already-accepted` | 취소 접수(202)와 환불 완료를 같은 상태로 표시하지 않는다. |
| `2d 부분 환불 상세` | `REQUESTED`, `PROCESSING`, `SUCCEEDED`, `REFUND_DELAYED` | `MANUAL_REVIEW`와 내부 오류 코드는 고객에게 노출하지 않는다. |
| `3a 포인트` | `expiring-soon`, `zero-balance` | 조회 실패를 잔액 0으로 표시하지 않는다. |
| `3c 재주문 재검증` | `price-changed`, `item-removed`, `coupon-replaced`, `slot-required` | 변경 없음과 재검증 실패를 구분한다. |

---

### Goal core-journey coupon wallet scope (2026-08-18)

Goal Stage 05는 **이미 발급된** 고객 쿠폰을 매장 선택 맥락에서 조회·선택하는 좁은 read surface만
소유한다. normal issuance는 active Campaign과 issuance expiry를, restored compensation issuance는 immutable
snapshot과 issuance expiry를 사용한다. store에 적용되지 않는 항목은 숨기지 않고
`STORE_NOT_APPLICABLE`로 표시한다. 주문 생성은 coupon ownership, state, expiry, store scope, minimum order,
one-coupon rule과 concurrent consumption을 다시 검증하는 최종 권한이다
([BR-09](business-policy-decisions.md)의 2026-08-18 amendment).

이 보완은 이 표의 P1 Campaign limited issuance·management·history 또는 `3b` 선불 wallet non-goal을
재분류하지 않는다. Stage 05 backend/API contract는 target OpenAPI, runtime API, generated client와 tests로
구현됐지만, selection UI와 Storybook/browser evidence는 Storybook MCP가 복구될 때까지 구현·검증하지 않는다.

---

# B. 점주 콘솔 (13화면)

## B-1 계약

| 화면 | 사용자 | 목적 | 소유 Context | 필요 API | 우선순위 | 기존 구현 재사용 |
|---|---|---|---|---|---|---|
| `1b POS 주문보드` | 점주·직원 | 모든 날짜의 실행 주문 처리 | Ordering | `GET /stores/{storeId}/orders`, `GET /stores/{storeId}/orders/overflow`, `POST /stores/{storeId}/orders/{orderReference}/transitions` | P0 | lane/50건/overflow/signed cursor/ETag와 실제 lifecycle 경과 UI가 연결됐다. 미래 픽업 `PAID`도 즉시 노출한다(BR-06, ADR-100). |
| `4a 매장 비교` | 점주 | 매장 전환과 비교 | Identity, Analytics | `GET /merchant/me/stores` | P0(전환만) / P1(비교 지표) | 접근 가능한 매장 전환은 구현됐다. 매장 간 비교 지표는 여전히 P1이다. |
| `4c 품목 부분 환불` | 점주·직원 | 품목 단위 환불 | Ordering, Payment | `POST /stores/{storeId}/orders/{orderReference}/refund-previews`, `.../refunds` | P0 | 서버 계산 금액과 safe orderContext를 표시하며 stale/unresolved/멱등 실행 경계를 유지한다(BR-38, ADR-108). |
| `2a 정산 내역` | 점주 | 정산 명세 조회 | Settlement | `GET /stores/{storeId}/settlements`, `/{batchId}/items` | P0 | 있음. |
| `2b 이의제기 상세` | 점주 | 이의제기 접수·근거 확인 | Dispute | `POST /settlement-items/{itemId}/disputes`, `GET /stores/{storeId}/disputes` | P0(접수) / P1(상세·재실행 미리보기) | 접수·판정 서비스와 점주 store-scoped 목록 있음(Plan 90). |
| `1a 대시보드` | 점주 | KPI 요약 | Analytics | `GET /stores/{storeId}/summary` (신규) | P1 | 없음. Analytics projection 계획은 별도 ExecPlan이다. |
| `1c 재고 관리` | 점주·직원 | 품절·수량 조정 | Inventory | `GET/PATCH /stores/{storeId}/stocks` (신규) | P1 | 예약·확정·복원은 있음. 운영자용 수동 조정 API 없음. |
| `4b 메뉴·가격` | 점주 | 메뉴·옵션·가격 관리 | Merchant | `GET /stores/{storeId}/menus`, `GET/PUT /stores/{storeId}/menus/{menuId}/display-content` | P1 | 이름·설명·분류 표시 metadata의 versioned read/write는 구현됐다. 가격·옵션 authoring은 여전히 없다. |
| `3b 영업시간·슬롯 설정` | 점주 | 슬롯 정원·휴무 | Merchant, Fulfillment | `GET/PUT /stores/{storeId}/customer-display`, `GET/PUT /stores/{storeId}/pickup-slot-policies` (후자는 신규) | P1 | 고객 표시용 7일 운영시간 full replacement는 구현됐다. 슬롯 정원·휴무 정책 쓰기는 여전히 없다. |
| `3c 포인트·쿠폰 정책` | 점주 | 매장 적립·쿠폰 규칙 | Operations, Promotion | `GET/PATCH /operations/policies/ordinary-point-accrual/stores/{storeId}` | P1 | 있음. 단 현재는 운영자 permission 전용이다(충돌 C-5). |
| `3a 매출 분석` | 점주 | 순매출·환불률·객단가 | Analytics | `GET /stores/{storeId}/analytics` (신규) | P1 (Analytics ExecPlan 완료 후) | 없음. 지표는 BR-31의 두 지표만 소비하고 새로 정의하지 않는다(MD-2026-012). |
| `4d 직원·권한` | 점주 | 직원 초대·역할·PIN | Identity | `GET/POST/DELETE /stores/{storeId}/members` (신규) | P1 | `StoreMembership` 모델 있음. 관리 API·PIN 없음(충돌 C-6). |
| `4e AI 인사이트` | 점주 | 추천과 근거 | — | 없음 | Non-goal | LLM의 자율 가격·정산 변경은 이미 Non-goal이다. |

## B-2 상태 계약

| 화면 | 화면 고유 상태 | 실패 상태 |
|---|---|---|
| `1b POS 주문보드` | `PENDING_ACCEPTANCE`(Domain `PAID`), `ACCEPTED`, `PREPARING`, `READY`, `deadline-warning`, `pickup-business-date` | 다른 직원이 먼저 전이시킨 경우 409를 그대로 표시하고 목록을 재조회한다. |
| `4a 매장 비교` | `single-store`, `multi-store` | membership 없는 매장 요청은 403이고, 목록에서 조용히 감추지 않는다. |
| `4c 품목 부분 환불` | `preview-ready`, `partially-refunded`, `not-refundable`, `preview-stale`, `outcome-unresolved` | 누적 가능 수량 초과는 422, previewVersion 변화와 미확정 Refund는 서로 다른 409다. 미확정 결과에서 새 Provider 승인을 호출하지 않는다. |
| `2b 이의제기 상세` | `접수`, `심사 중`, `판정`, `재이의` | held 금액은 확정 정산을 덮어쓰지 않는다(ADR-008, ADR-018). |
| `4b 메뉴·가격` | `draft`, `saved`, `version-conflict` | 가격 변경은 기존 주문 스냅샷을 바꾸지 않는다(ADR-004). |
| `1c 재고 관리` | `reserved`, `available`, `sold-out` | 수동 조정은 사유 없이 실행할 수 없다. |
| `3b 영업시간·슬롯 설정` | `has-reservation`, `applies-next-slot` | 이미 예약된 슬롯의 정원을 소급 축소하지 않는다. |

---

# C. 운영자 콘솔 (원본 13화면 + P0 신규 1화면)

## C-1 계약

| 화면 | 사용자 | 목적 | 소유 Context | 필요 API | 우선순위 | 기존 구현 재사용 |
|---|---|---|---|---|---|---|
| `1a 실패 추적` | 운영자 | 결제·알림·정산 실패 큐 | Operations + 각 source Context | `GET /operations/failure-queues/summary`, `GET /operations/failure-queues/{queueType}` (신규) | P0 | 원본 실패 상태와 `ReprocessingCase`는 있음. typed source-owned Projection endpoint가 없다(ADR-110). |
| `1b 이벤트 상세` | 운영자 | 상관 ID 기반 조회·재처리 | Operations + 각 source Context | `GET /operations/failure-queues/{queueType}/{workReference}`, `GET /operations/failure-search?correlationId=` (신규), 기존 repair/reconciliation command | P0 | 재처리·복구 command는 있다. 목록이 반환한 opaque reference·`allowedActions`로 연결한다. |
| `2a 정산 대사` | 정산 운영자 | 차이 확인·재실행 | Settlement | `GET /operations/settlement-batches`, `/{batchId}/items`, `/{batchId}/reconciliation` (신규) | P0 | immutable item·batch 합계·조정 원장은 있다. 운영 전역 대사 Projection이 없다(BR-45). |
| `3c 감사 로그` | 운영자 | 민감 조치 이력 확인 | Operations | `GET /operations/audit-records`, `/{auditRecordId}` (신규) | P0 | `AuditRecord` append-only는 있다. 조회는 별도 grant·사유·접근 Audit와 30/90일 계약이 필요하다(BR-44). |
| `4e 고객 계정` | 고객센터 | 마스킹 고객 조회 | Support, Identity | `POST /support/searches`, `POST /support/cases` | P0 | 있음(ADR-082, ADR-083). |
| `P0 신규 점주 계정 발급` | 운영자 | 계정·최초 매장 권한 발급, 임시 비밀번호 초기화 | Operations, Identity, Merchant | `GET/POST /operations/merchant-accounts`, `POST /operations/merchant-accounts/{merchantAccountId}/temporary-password-resets`, `.../lock-releases` (신규) | P0 | 계정 모델과 API가 없다. account+최초 membership 원자 생성, explicit permission과 임시 비밀번호 1회 표시가 필요하다(BR-46). |
| `4d 환불 승인` | 운영자 | 환불 검토 큐 | Payment, Operations | `GET /operations/refund-reviews` (신규) | P1 | 환불·수동검토 상태 있음. 승인 큐 없음. |
| `2b 이의제기 라우팅` | 정산 운영자 | 담당 배정·기한 | Dispute | `GET/PATCH /operations/disputes` (신규) | P1 | 판정 서비스 있음. 공개 운영 endpoint 없음. |
| `3b 이벤트 추적` | 운영자 | 도메인 레인 타임라인 | Operations, Eventing | `GET /operations/correlations/{correlationId}` (신규) | P1 | Correlation ID filter 있음. 조회 투영 없음. |
| `3a 쿠폰 발급 모니터` | 운영자 | 한정 발급 한도 감시 | Promotion | `GET /operations/campaigns/{campaignId}/issuance` (신규) | P1 | Campaign 예약 있음. 모니터 없음. |
| `4f 캠페인 편성` | 운영자 | 캠페인 생성·한도 설계 | Promotion | `POST /operations/campaigns` (신규) | P1 | 없음. |
| `4a 운영 개요` | 운영자 | 실시간 상태·킬스위치 | Operations | `GET /operations/overview` (신규) | P1 | 없음. 결제사 자동 전환은 제거한다(충돌 C-4). |
| `4b 가맹점 심사` | 운영자 | 입점 심사·계좌 검증 | Merchant | 없음 | Non-goal | 실제 KYC·계좌 실명 확인은 외부 계약이 필요하다. |
| `4c 지급 실행` | 정산 운영자 | 실제 이체 실행 | Settlement | `POST /operations/settlement-payout-files` (sandbox) | Non-goal(실제 지급) / P1(지급 파일 생성) | 실제 대금 지급은 이미 Non-goal이다(ADR-105). |

## C-2 상태 계약

| 화면 | 화면 고유 상태 | 실패 상태 |
|---|---|---|
| `1a 실패 추적` | `RETRY_SCHEDULED`, `FAILED`, `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW` | source 하나의 장애도 summary 0·부분 결과로 대체하지 않고 503이다(ADR-110). |
| `1b 이벤트 상세` | `action-available`, `read-only`, `attempt-unavailable` | read grant는 command 권한이 아니며 실제 attempt가 없으면 0으로 표시하지 않는다. |
| `2a 정산 대사` | `CONSISTENT`, `MISMATCH`, `INCOMPLETE` | 미완료 source를 0원 일치로 표시하거나 확정 배치를 조회 중 수정하지 않는다. |
| `3c 감사 로그` | `range-filtered`, `has-more`, `access-audited` | 기본 30일·최대 90일이며 접근 Audit 실패 시 body를 반환하지 않는다. |
| `4e 고객 계정` | `masked`, `revealed` | 원본 조회 자체가 감사 대상이며 목적 없이는 403이다. |
| `P0 신규 점주 계정 발급` | `not-found`, `created-secret-visible-once`, `secret-lost`, `reset-required`, `locked` | mutation을 자동 retry하지 않는다. 응답을 잃으면 exact ID 조회 뒤 새 reset으로 수렴하고 secret을 storage에서 복원하지 않는다(BR-46). |

---

# D. 요약

| 구분 | P0 | P1 | Non-goal | 화면 아님 |
|---|---:|---:|---:|---:|
| 고객 앱 | 13 | 7 | 1 | 1 |
| 점주 콘솔 | 5 | 7 | 1 | 0 |
| 운영자 콘솔 | 6 | 6 | 2 | 0 |
| 합계 | 24 | 20 | 4 | 1 |

P0 24화면은 화면 번호가 아니라 [계약 → 공개 식별자 → 인증·계정 → 조회 Projection →
actor-scoped facade와 UI] 순서로 구현했다. 이 표의 우선순위 산술은 유지하되, 아래 P0 구현 증거와
각 후속 ExecPlan outcome을 현재 구현 상태의 근거로 사용한다. Provider sandbox, 배포와 production
효과는 로컬 계약·Storybook 검증에서 추론하지 않는다.

## P0 구현 소유권

한 화면이 여러 backend 선행 capability를 소비하더라도 최종 화면 상태·브라우저 검증 owner는 하나로
고정한다.

| 영역 | P0 화면 | Backend 선행 owner | 최종 화면·상태 검증 owner |
|---|---|---|---|
| 고객 | `5a 첫 진입` | Plan 30 | Plan 80 |
| 고객 | `5e 오프라인` | 기존 Payment/ADR-080 | Plan 80 |
| 고객 | `1a 홈` | Plans 50, 70 | Plan 80 |
| 고객 | `1b 매장 찾기` | Plan 70 | Plan 80 |
| 고객 | `1c 매장 상세` | 기존 ADR-076 | Plan 80 |
| 고객 | `1d 주문 추적` | Plans 10, 50 | Plan 80 |
| 고객 | `4a 장바구니` | client cart + 기존 Order 생성 | Plan 80 |
| 고객 | `2a 결제` | 기존 ADR-080 | Plan 80 |
| 고객 | `4c 주문 내역` | Plan 50 | Plan 80 |
| 고객 | `4d 주문 취소` | 기존 취소 유스케이스 + Plan 50 | Plan 80 |
| 고객 | `4f 마이` | Plan 30 | Plan 80 |
| 고객 | `3a 포인트` | Plan 30/ADR-109 + Loyalty | Plan 80 |
| 고객 | `3c 재주문 재검증` | 기존 ADR-077 | Plan 80 |
| 점주 | `1b POS 주문보드` | Plans 10, 40, 60 | Plan 60 |
| 점주 | `4a 매장 비교`의 전환 | Plan 40 | Plan 60 |
| 점주 | `4c 품목 부분 환불` | Plan 90/ADR-108 | Plan 90 |
| 점주 | `2a 정산 내역` | 기존 Settlement | Plan 90 |
| 점주 | `2b 이의제기 상세`의 P0 범위 | 기존 Dispute + Plan 90 query | Plan 90 |
| 운영 | `1a 실패 추적` | Plan 100/ADR-110 | Plan 100 |
| 운영 | `1b 이벤트 상세` | Plan 100 + 기존 command | Plan 100 |
| 운영 | `2a 정산 대사` | Plan 100 + Settlement | Plan 100 |
| 운영 | `3c 감사 로그` | Plan 100 + ADR-022/069 | Plan 100 |
| 운영 | `4e 고객 계정` | Support S40 | Plan 100 |
| 운영 | `P0 신규 점주 계정 발급` | Plans 20, 40/BR-46 | Plan 100 |

## 고객 P0 화면 구현 증거

Plan 80이 고객 13화면을 Session과 실제 거래 API에 연결했다. 각 행의 증거는 실제로 실행한 자동
검증이며, 브라우저 수동 확인이나 배포 결과가 아니다.

| 화면 | 구현 | 상태 검증 증거 |
|---|---|---|
| `5a 첫 진입` | `features/auth/customer/AuthPages.tsx` | `customerSession.test.tsx` 로그인 401/429, 가입 409 |
| `5e 오프라인` | `features/payment/usePaymentResolution.ts` | `PaymentCallbackPages.test.tsx` confirm 유실 뒤 status GET만 반복, online 중복 이벤트 |
| `1a 홈` | `features/discovery/HomePage.tsx` | `Discovery.test.tsx` 진행 주문·추천, 조회 실패와 빈 목록 구분 |
| `1b 매장 찾기` | `features/discovery/StoreSearchPage.tsx` | `Discovery.test.tsx` 검색·빈 결과·실패·위치 권한 거부 |
| `1c 매장 상세` | `features/ordering/StoreDetailPage.tsx` | `Ordering.test.tsx` 품절 메뉴 비활성, 픽업 마감, URL 직접 진입 시 서버가 준 매장 이름 |
| `1d 주문 추적` | `features/ordering/OrderPages.tsx` | `OrderPages.test.tsx` publicReference·pickupNumber·allowedActions |
| `4a 장바구니` | `features/ordering/cart.ts`, `CartPage.tsx` | `Ordering.test.tsx` 한 매장 제약, 손상 cart, slot conflict, 저장된 이름보다 서버 이름 우선 |
| `2a 결제` | `features/payment/CheckoutPage.tsx`, `PaymentResultPages.tsx` | `PaymentCallbackPages.test.tsx` callback 검증·승인·복구 |
| `4c 주문 내역` | `features/ordering/OrderPages.tsx` | `OrderPages.test.tsx` 기간·탭·cursor·empty |
| `4d 주문 취소` | `features/ordering/CancelOrderPanel.tsx` | `OrderPages.test.tsx` 202 취소와 환불 진행 분리 |
| `4f 마이` | `features/auth/customer/MyPage.tsx` | `customerSession.test.tsx` 로그아웃 뒤 보호 route 차단, 운영자 token 보존 |
| `3a 포인트` | `features/loyalty/PointsPage.tsx` | `Points.test.tsx` 실제 0원, 무결성 503, 원장 실패 |
| `3c 재주문` | `features/ordering/ReorderPanel.tsx` | `OrderPages.test.tsx` 주문번호 재주문, `PublicReferenceReorderContractTest` 소유권·재검증 |

공통 경계 검증은 `api/client.test.ts`(Bearer 미부착, unsafe CSRF guard)와
`features/CustomerSurface.test.tsx`(토큰·UUID 입력 field 부재, 420px form label·focus·reduced motion)에
있다.

## 확정된 후속 결정

초안 단계에서 미해결이던 항목은 2026-08-12에 모두 결정됐다.

| 항목 | 결정 | 기록 위치 |
|---|---|---|
| 주문 내역 30일 초과 조회 | 기본 30일 + `from`/`to` 기간 필터. 과거 범위 상한 없음. 필터는 cursor에 서명한다 | [ADR-099](../adr/ADR-099-customer-order-read-model.md) |
| 쿠폰 잔여 수량 실시간성 | 발급은 원자적 UPDATE로 정확, 표시 잔여 수량은 조회 시점 근사. 카운터는 발급 기준으로 고정하고 취소·만료로 감소하지 않는다 | [ADR-107](../adr/ADR-107-limited-coupon-issuance.md) |
| 점주 매출 분석 지표 정의 | Analytics가 지표를 단독 소유하고 점주 화면은 BR-31의 두 지표만 소비한다. Analytics ExecPlan 완료 이후 P1 | [MD-2026-012](../decisions/minor-decisions.md) |
| 알림 거래·마케팅 분류 경계 | 고객 알림은 `orderId` 유무로 판정, 매장 알림은 예외 없이 거래성. 마케팅 기본값은 수신 거부 | [ADR-104](../adr/ADR-104-notification-inbox.md) |
| P0 프로그램 완료 범위 | 표의 P0 24화면 전체. productization-60까지는 중간 통합 지점이며 누락 capability의 후속 ExecPlan을 추가한다 | [Product Usability Program](../exec-plans/active/product-usability-program-orchestration.md) |
| P0 화면 수 산술 정합화 | 원본 분류의 고객 13·점주 5·운영자 5에 점주 계정 발급 운영 화면 1개를 더해 전체 24화면 | 이 문서, [BR-46](business-policy-decisions.md) |

## 남은 미해결 항목

다음은 해당 화면의 P1 시작 전에 결정한다.

1. 점주 PIN 재확인의 인증 계층 위치(세션 재인증 vs 명령 단위 step-up).
   [C-6](design-contract-conflicts.md)에서 P1로 이월했다.
2. 점주가 직접 바꿀 수 있는 포인트·쿠폰 정책의 정확한 필드 목록과 상한.
   [C-5](design-contract-conflicts.md)에서 Business Policy로 이월했다.
