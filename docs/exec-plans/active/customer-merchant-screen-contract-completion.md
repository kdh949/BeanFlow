# 고객·점주 화면 재현을 위한 계약 완성

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 고객 홈·매장 검색·메뉴·장바구니·결제·주문 상세와 점주 주문 보드·부분 환불 화면을
첨부 시안의 정보 밀도와 상태 표현으로 구현하기 위한 계약 완성 계획이다. `.agent/PLANS.md`,
[ADR-076](../../adr/ADR-076-store-catalog-read-contract.md),
[ADR-099](../../adr/ADR-099-customer-order-read-model.md),
[ADR-100](../../adr/ADR-100-store-order-board-read-model.md),
[ADR-104](../../adr/ADR-104-notification-inbox.md),
[ADR-108](../../adr/ADR-108-merchant-partial-refund-preview.md),
[ADR-115](../../adr/ADR-115-store-and-menu-image-storage.md),
[ADR-116](../../adr/ADR-116-non-reserving-order-quote.md),
[ADR-117](../../adr/ADR-117-store-customer-display-profile.md)를 따른다.

Milestone 0은 2026-08-25 승인된 결정을 Business Policy와 ADR-116/117 및 관련 ADR amendment에
기록했다. 이 문서 변경 자체는 production source, OpenAPI, migration, generated client, frontend와
Storybook source를 바꾸지 않는다. quote stale은 BR-25 terminal 멱등 응답으로 저장·재생하고 고객
재확인은 새 fingerprint와 새 `Idempotency-Key`를 사용한다. 결정 gate를 충족했으므로 metadata는
`Implementation-Ready: true`다.

## Purpose / Big Picture

시안의 화면은 단순한 card 재배치가 아니다. 고객은 현재 주문 가능한 매장과 실제 가장 이른 픽업 시간,
상품·할인·포인트가 반영된 주문 전 금액, 주문의 실제 진행 시각과 알림함을 보아야 한다. 점주는 보드에서
각 주문이 어느 단계에 언제 들어왔는지, 부분 환불에서 어떤 주문의 어떤 금액을 검토하는지 보아야 한다.
모든 값은 frontend가 추정하거나 fixture로 보완하지 않고, 각 Context가 소유한 현재 또는 immutable
snapshot에서 읽는다.

완료 후 다음 흐름이 성립한다.

```text
매장 탐색/상세
  → 공개 주소·운영시간·현재 주문 가능 여부·가장 이른 실제 픽업 슬롯
  → 장바구니의 비예약 quote
  → 기존 POST /orders의 최종 예약·주문 생성
  → 기존 Toss 일회성 결제
  → 고객 주문 상세의 가격 요약·실제 lifecycle 시각·알림함

점주 주문 보드
  → 기존 ETag/overflow/polling 계약을 유지한 lifecycle 시각 표시
  → 기존 부분 환불 preview에 안전한 주문 context를 함께 표시
  → 기존 previewVersion + Idempotency-Key 실행 경계에서 Provider 환불
```

### 화면과 계약의 대응

| 화면 | 재사용하는 현재 계약 | 이번 plan에서 완성할 계약 |
| --- | --- | --- |
| 고객 홈·매장 검색 | 검색/nearby/recent/recommendation, 거리, `pickupAvailable`, optional 이미지 | 공개 주소·운영시간·운영상태, 명확한 `orderingAvailable`, 가장 이른 픽업 window, 알림 unread 상태 |
| 매장 메뉴 | 메뉴·옵션·픽업 슬롯, optional 메뉴 이미지 | 메뉴 표시 카테고리·설명, 매장 공개 표시 정보 |
| 장바구니 | client cart, coupon 목록, 최종 `POST /orders` | 비예약 주문 quote와 authoritative 가격 요약 |
| 결제 | order lease, payment attempt, Toss Standard Payment Window | 새 결제수단 계약 없음. 기존 금액과 lease를 그대로 사용 |
| 고객 주문 상세 | customer order detail, public order reference, allowed actions | immutable 가격 breakdown과 실제 lifecycle timestamps, 화면 내 거래 요약 |
| 점주 주문 보드 | lane, `allowedActions`, 3초 conditional polling, overflow, ETag | 보드 item의 실제 lifecycle timestamps와 ETag canonicalization |
| 점주 부분 환불 | preview, `previewVersion`, execute, Provider/reconciliation | preview의 안전한 주문 context·결제/가격 요약 |
| 모든 고객 화면의 bell | existing delivery worker와 templates | inbox/preference/retention + `hasUnread` summary |

## Current State

1. **이미지는 이미 구현됐다.** `V65__add_store_image.sql`, `V66__add_menu_image.sql`,
   `StorefrontImageView { url, expiresAt }`, Merchant upload/delete endpoint, Discovery/Catalog projection,
   customer image rendering과 Storybook examples가 현재 `main`에 있다. 이번 plan은 이미지 저장소,
   upload, URL signing 또는 새 image API를 만들지 않고 이 optional field를 그대로 소비한다.
2. **저장 카드/BrandPay는 이 범위에 없다.** 현재 checkout은 Toss 일회성 결제이며, 이 plan은
   `PaymentMethod`, billing key, 등록 카드 선택 또는 결제수단 UI를 호출하거나 변경하지 않는다.
3. **VAT/세무 표시도 이 범위에 없다.** 부분 환불은 기존 Payment Provider 환불과 Order/Refund 원장을
   사용한다. VAT 계산·10% 표시·세금계산서·PG 응답 세무 모델을 새로 만들지 않는다.
4. `CustomerOrderDetail`은 현재 `subtotalKrw`, `payableKrw`, `paidAt`을 내부 projection으로 읽지만,
   응답은 `totalAmountKrw`만 내보낸다. `acceptedAt`, `preparingAt`, `readyAt`, `completedAt`도
   customer response에 없다.
5. `StoreOrderBoardItem`과 board SQL은 acceptance deadline과 pickup window만 읽는다. 보드의 lane,
   50건 상한, overflow cursor, weak ETag, 3초 polling과 `expectedStatus` 전이는 이미 구현돼 있다.
6. `POST /stores/{storeId}/orders/{orderReference}/refund-previews`는 line/quantity별 authoritative
   refund allocation과 `previewVersion`을 반환하지만, 시안의 주문 일시·원 주문 금액·결제 유형을
   표시할 non-PII context는 반환하지 않는다.
7. coupon 목록은 주문 quote나 reservation이 아니다. `POST /orders`만 현재 menu/coupon/point/slot을
   transaction 안에서 quote하고 reserve한다. Cart의 표시 합계는 client 계산값이다.
8. 검색·매장 상세·메뉴 API에는 이미지 외에 공개 주소, 운영시간, 메뉴 표시 카테고리/설명이 없다.
   현재 search의 `open`은 `acceptingOrders && pickupEnabled`라는 **주문 수락 플래그**이지 영업시간
   기반 `영업 중`이 아니다.
9. [ADR-104](../../adr/ADR-104-notification-inbox.md)는 inbox/preference/90일 보존을 이미 결정했지만,
   해당 schema, customer endpoint, read indicator와 customer UI는 아직 없다.

## Definitions

- **orderingAvailable:** `acceptingOrders && pickupEnabled`인 Store의 현재 주문 수락 가능 여부다.
  영업시간 기반 상태가 아니며, 고객 UI에서는 `주문 가능`/`주문 불가`로만 번역한다.
- **operating status:** Store가 공개한 주간 운영시간을 `Asia/Seoul`의 현재 시각에 대입한
  `OPEN | CLOSED | UNSPECIFIED`다. `OPEN`이라고 해서 주문이 가능한 것은 아니며,
  `orderingAvailable`을 별도로 함께 표시한다.
- **next pickup window:** [ADR-076](../../adr/ADR-076-store-catalog-read-contract.md)의 `startsAt > now`
  조건을 만족하는 가장 이른 실제 슬롯의 `(startsAt, endsAt)`이다. 준비 시간이나 "10분"을 임의로
  저장·추정한 값이 아니다.
- **non-reserving order quote:** customer가 제출할 Order 입력을 현재 owner state로 계산한 read 결과다.
  Order, slot/coupon/point reservation, Payment, idempotency response, Audit 또는 event를 만들지 않으며
  최종 가격·재고·슬롯을 보장하지 않는다.
- **quote fingerprint:** server가 normalized quote input, line/option 구성, price/benefit/slot 결과와
  관련 owner version을 canonicalize해 만든 opaque optimistic-concurrency precondition이다. 금액,
  reservation ID, 권한 또는 결제 Provider input이 아니며, browser가 바꿔 보내도 server가 final transaction
  안에서 다시 계산한 결과와 일치할 때만 Order 생성에 사용한다.
- **pricing summary:** immutable Order snapshot의 `subtotalKrw`, `couponDiscountKrw`,
  `pointsAppliedKrw`, `payableKrw`, `currency`다. `subtotal = couponDiscount + pointsApplied + payable`
  불변식을 가진다.
- **lifecycle timestamps:** Order에 실제로 기록된 `paidAt`, `acceptedAt`, `preparingAt`, `readyAt`,
  `completedAt`이다. pickup window, `updatedAt`, client 현재 시각 또는 상태 전이 예상 시각으로
  대신 만들지 않는다.
- **transaction summary:** customer order detail의 pricing·주문번호·주문시각을 보여 주는 화면 내
  정보다. PG 영수증, 세무 영수증, 카드전표, VAT 문서 또는 provider receipt API가 아니다.
- **refund context:** preview 계산에 쓰는 기존 immutable/현재 상태를 customer PII·provider 식별자 없이
  점주 화면에 재투영한 read-only 보조 정보다. execute authority, `previewVersion`, 금액 계산 source는
  바꾸지 않는다.

## Scope

### In scope

- 고객 알림함, 수신 설정, 90일 보존 worker, unread bell summary
- Store가 소유하는 공개 주소·안내 문구·주간 운영시간과 메뉴의 표시 카테고리·설명, 그 customer read와
  owner-authoring command
- customer Cart가 호출하는 비예약 quote endpoint와 그 authoritative price summary
- customer order detail의 pricing/lifecycle projection
- merchant board item의 lifecycle projection 및 ETag/fixture 갱신
- merchant partial-refund preview의 non-PII order context
- target/runtime OpenAPI, generated frontend schema, backend API contract tests, affected frontend routes,
  Storybook states, accessibility/interaction verification

### Non-goals

- 새 결제수단, billing key, saved card, Toss BrandPay, Payment Widget, 다중 PG 또는 checkout provider 변경
- VAT·세금·부가세 환급, tax breakdown, 카드전표/PDF 영수증, PG receipt endpoint
- 새 이미지 저장·업로드·삭제·CDN·placeholder URL 계약. [ADR-115](../../adr/ADR-115-store-and-menu-image-storage.md)의
  image contract만 재사용한다.
- 픽업 slot의 예약 창, capacity, lease, Order 생성/결제 확정의 최종 authority 변경
- customer address/precise coordinate를 서버에 저장하거나 location privacy 정책을 완화하는 일
- 주문보드 polling 주기, overflow queue, lane 분류, transition command/state machine의 재설계
- 부분 환불의 money allocation, point/coupon restoration, Provider 호출, settlement adjustment 또는
  필수 1..500자 환불 사유 규칙 변경
- holiday exception, overnight/24-hour operating hours, per-store timezone, full menu/catalog CRUD,
  map/geocoding, customer favorite command, notification push channel 확장

## Business Rules and Invariants

1. **이미지는 optional current data다.** `image`가 없는 경우에만 frontend가 제품 placeholder(매장)나
   image 영역 생략(메뉴)을 선택한다. AIStor/provider 장애를 missing image, stale URL 또는 placeholder
   URL로 바꾸지 않는다. `expiresAt`이 지난 URL을 client가 정상 이미지로 재사용하지 않는다.
2. **주문 가능과 영업 중을 합치지 않는다.** Schedule이 없어 `operatingStatus=UNSPECIFIED`이면 UI는
   `영업 중`이라고 단정하지 않는다. `OPEN`인데 주문이 닫혀 있거나 `CLOSED`인데 주문을 열어 둔 경우도
   두 상태를 각각 표시한다. 최종 order creation은 계속 Store policy와 slot reservation을 재검증한다.
3. **운영시간은 공개 안내 정보다.** `Asia/Seoul`, 요일별 하나의 same-day interval만 지원한다.
   `closed=true`이면 start/end가 없고, 열면 `opensAt < closesAt`이어야 한다. 자정 넘김, 휴일 예외,
   24시간 영업은 이 plan의 범위 밖이며 API가 그럴듯한 값으로 정규화하지 않는다.
4. **menu display metadata는 주문 snapshot을 바꾸지 않는다.** category/description은 catalog 표현용
   nullable metadata다. `available`, 가격, option, 주문 line의 name/price snapshot, search grammar와
   refund allocation을 바꾸지 않는다. category가 null인 메뉴는 UI의 `전체`/미분류 섹션에만 들어간다.
5. **quote는 read-only이며 reservation token이 아니다.** client는 line price, coupon discount, point
   amount, payable amount 또는 reservation ID를 `POST /orders`에 제출하지 않는다. 대신 quote response의
   `quoteFingerprint`만 `expectedQuoteFingerprint`로 보낸다. Order 생성 transaction은 현재 menu/coupon/
   point/slot을 기존 순서로 lock·re-quote하고, normalized input과 price breakdown, benefit source, pickup
   window/availability를 포함한 full fingerprint를 다시 계산한다. fingerprint가 같을 때만 reserve하고 그 server
   calculation으로 immutable Order snapshot을 저장한다. 하나라도 다르면 Order·reservation·Payment·
   Audit·event를 만들지 않고 `409 ORDER_QUOTE_STALE`과 current quote를 반환한다. BR-25에 따라
   최초 409 status/body는 terminal 주문 생성 idempotency response로 저장·재생하며, 고객 재확인은
   새 fingerprint와 새 `Idempotency-Key`를 사용한다. `payableKrw`만 같은지는 충분하지 않다.
6. **customer order detail은 immutable snapshot을 표시한다.** detail pricing은 주문 시 저장된 값이고,
   현재 menu/coupon/point 가격을 다시 계산하지 않는다. lifecycle은 실제 event 시각만 표시하고
   아직 일어나지 않은 단계는 field와 UI timestamp를 생략한다.
7. **merchant board는 existing live-work boundary를 보존한다.** 새 timestamps는 canonical board payload와
   ETag에 포함한다. client elapsed time은 server가 준 event timestamp와 client clock으로만 계산하고,
   `304` 상태에서 임의의 server time을 상상하지 않는다. board/overflow의 50건 상한과 cursor semantics는
   유지한다.
8. **refund preview는 금융 authority를 늘리지 않는다.** preview `orderContext`는 informational이고
   execute body에 context/금액을 다시 받지 않는다. `previewVersion`, membership recheck, order/payment
   lock 순서, unresolved conflict, `Idempotency-Key`, mandatory reason, Provider reconciliation을 그대로
   적용한다. tax/VAT, raw payment ID, card number, customer name/phone, provider transaction reference,
   settlement diagnostic은 노출하지 않는다.
9. **알림함과 채널 전달은 독립이다.** transactional event 처리 transaction에서 InboxItem, Delivery,
   persistent publication을 함께 저장한다. Provider 호출은 commit 뒤 worker에서 한다. push 실패가
   inbox 부재를 뜻하지 않고 inbox 저장이 성공했다고 provider delivery 성공을 뜻하지 않는다.
10. **알림 unread indicator는 boolean이다.** bell에는 `hasUnread`만 제공하고 notification 본문이나
    unread count를 모든 route response에 복제하지 않는다. 다른 customer의 item/summary는 존재를
    추론할 수 없으며, 표시 실패를 `false`로 대체하지 않는다.
11. **공개 계약은 한 번에 교체한다.** 아직 배포된 client가 없으므로 `open`은 customer-facing
    `orderingAvailable`로 rename하고, detail의 단일 `totalAmountKrw`는 `pricing`으로 교체한다.
    target/runtime OpenAPI, generated client, fixtures, frontend와 tests를 같은 slice에서 갱신하며
    compatibility alias, dual response field 또는 silent fallback을 남기지 않는다.

## Architecture and Transaction Boundaries

### Context ownership

| Capability | Owner | Read consumer | Write boundary |
| --- | --- | --- | --- |
| Store public display profile / menu display metadata | Merchant | Discovery, Customer Web | Store `OWNER`; menu metadata는 same-store `OWNER | STAFF` |
| next pickup window / capacity | Fulfillment | Discovery | existing slot management/reservation only |
| quote / final Order price | Ordering coordinated with Merchant, Promotion, Loyalty, Fulfillment | Customer Web | quote is read-only; final `POST /orders` is existing authoritative write |
| order lifecycle / immutable pricing | Ordering | Customer Web, Merchant Console | existing Order aggregate transitions only |
| payment/refund result | Payment / Ordering | Merchant Console | existing refund preparation → Provider → result/reconciliation |
| inbox / preference / delivery | Notification | Customer Web | Notification event listener / customer read command |

### Transaction map

```text
Customer POST /me/order-quotes
  → customer ownership + current menu/coupon/point/slot reads
  → pure calculation result
  → no DB write, no reservation, no Payment/provider call, no Audit/event

Customer POST /orders (existing)
  → expectedQuoteFingerprint + short Order/reservation transaction
  → lock current owner state and final re-quote
  → compare full server fingerprint
  → match: reserve resources and commit Order + idempotent response
  → mismatch: rollback transaction writes, persist terminal BR-25 failure response separately,
              return/replay ORDER_QUOTE_STALE with the first current quote
  → existing one-time payment path starts afterwards

Store display/menu metadata PUT
  → Merchant actor + Store/Menu ownership + expected version
  → locked Store-owned row / Menu row + Audit in one local transaction
  → commit or rollback together; no Discovery-owned replica write

Order lifecycle event
  → existing Order transition commits actual timestamp
  → Notification listener transaction creates InboxItem + Delivery + publication together
  → commit
  → delivery worker calls Provider outside transaction and records retry/unknown state

Merchant refund preview
  → same membership-scoped read that builds lines/totals/version
  → append safe orderContext to response
  → no Refund, Audit, Loyalty write or Provider call

Merchant refund execute (existing)
  → lock Order/Payment in documented order, recheck membership + previewVersion + reason
  → prepare Refund/Audit/idempotency atomically
  → Provider call after commit; UNKNOWN/RECONCILING uses existing recovery
```

### Concurrency and idempotency

- Store display/menu metadata commands use an explicit expected version. The server locks the owner row,
  returns `409` on a stale version and creates no partial display update or Audit-only success.
- A quote deliberately does **not** lock or reserve. `quoteFingerprint` is a customer-confirmation precondition,
  not a price/reservation/authorization token. Final Order creation is the one serialization point: it recomputes
  the full fingerprint under existing locks, creates the Order only on an exact match, and otherwise rolls back
  with `ORDER_QUOTE_STALE`. The UI reloads quote after editable cart input changes and renders the supplied current
  quote before a user explicitly retries with a new fingerprint and new `Idempotency-Key`; it never mutates
  local totals to match a guess. Same-key replay returns the original terminal stale body, not a newly calculated quote.
- Customer read projections and refund preview are DTO/query projections; they do not extend write Aggregate
  object graphs or introduce cross-context JPA associations.
- Board timestamps change its weak ETag canonical payload. Conditional polling therefore cannot return a 304
  for a changed lifecycle timestamp.
- Notification `logical_source` keeps inbox creation idempotent. Replayed source event cannot create duplicate
  inbox/delivery records; a repeated `{ read: true }` remains 204.

## Alternatives Considered

### Let the frontend calculate every visual value

This would be quick for the screenshot but makes cart discounts, pickup minutes, board elapsed time and refund
headers drift from the server. It also turns a provider/persistence failure into plausible UI values. Reject.

### Make quote reserve a slot/coupon/points

It would make the cart total look firmer, but creates expiry, release, abandoned-cart and concurrent resource
semantics before the user commits to Order creation. Reject. Existing `POST /orders` remains the only reservation
and payment-precondition boundary.

### Reuse search `open` as the displayed business-hours status

`open` currently means only `acceptingOrders && pickupEnabled`; showing it as `영업 중` is false when a store
manually closes ordering or its published hours differ. Reject. Rename it to `orderingAvailable` and add a
separately computed operating status.

### Store a client-friendly fixed pickup lead time

A static "10 minutes" does not survive slot changes or store-specific operations. Reject. Use the earliest
reservable slot from Fulfillment and let UI format its distance from the injected/current clock.

### Add a standalone tax/VAT refund projection

The requested screen does not need it, current payment/refund flows already hand monetary refund execution to the
Provider, and adding a tax model would create accounting ownership without a product requirement. Reject.

### Treat transaction summary as a PG receipt

It would imply a fiscal/provider document, provider identifier exposure and provider receipt retrieval semantics.
Reject. Render a customer-facing order transaction summary from immutable Order data only.

### Add a separate refund-order endpoint

The existing preview already proves membership and calculates all refundable lines. A second read would add a
TOCTOU surface and double the access path. Reject. Add safe context to the same preview response.

### Expose NotificationDelivery as the bell/inbox source

Delivery is provider operational state and can fail/retry independently of customer visibility. Reject; use the
ADR-104 InboxItem projection and a narrow `hasUnread` query.

## Failure Semantics

- Merchant/Discovery/Fulfillment/Promotion/Loyalty quote dependencies failing return their typed 5xx (normally
  `503`) or existing validation conflict. Do not return client total, zero discount, an empty slot list, cached
  coupon applicability or a guessed pickup time as success.
- `expectedQuoteFingerprint` mismatch, including a changed benefit source that happens to leave `payableKrw`
  unchanged, returns `409 ORDER_QUOTE_STALE` with a current `OrderQuote` and creates no Order, reservation,
  Payment, Audit or event. BR-25 stores the first 409 status/body as a terminal `FAILED` idempotency response
  and replays it for the same key·payload; it does not recalculate `currentQuote` during replay. A reviewed retry
  uses a new fingerprint and new key. A malformed fingerprint is `400`; it is never interpreted as a request to
  accept the newest price automatically.
- A missing optional display profile yields omitted address/schedule and `operatingStatus=UNSPECIFIED`; it is not
  a dependency failure. A failed profile read is a 503 and is not the same as an unconfigured profile.
- Invalid operating-hours tuple, stale display version, cross-store menu or insufficient role fails before any
  write. A successful HTTP response cannot contain half of a seven-day schedule.
- Missing or out-of-order lifecycle data is an explicit projection/invariant failure (503), not a client-computed
  timestamp. Normal not-yet-reached stages are omitted only when the Order state makes their absence valid.
- Notification preference lookup/InboxItem/Delivery/publication persistence failure rolls back the listener
  transaction and is retried through its durable source path. Provider timeout/response loss remains delivery
  retry/unknown state and never flips inbox read state or response to delivered.
- `GET /me/notification-summary` failure is visible as a failed bell query; UI does not quietly render
  `hasUnread=false`. An empty inbox with a successful summary is a legitimate `hasUnread=false`.
- Refund preview context query failure has the same explicit failure outcome as preview calculation. It cannot
  return lines/totals with fake header information. Existing `REFUND_PREVIEW_STALE` and
  `REFUND_OUTCOME_UNRESOLVED` remain unchanged.
- Image URL expiry, AIStor outage and image absence continue to follow ADR-115. This plan does not add stale URL,
  local image or public-bucket fallback behavior.

## Data and Migration

`Writes-Migration: true` because the notification inbox/preference, public Store display profile and menu display
metadata require persistent data. Before creating migration files, the implementer must acquire the repository's
migration-writer lane and re-inventory the latest applied/committed Flyway version. `V66` is only this plan's
planning baseline; no version number is reserved here.

**Execution lease (2026-08-25):** PR #108 head `6575356`에서 child
`feature/store-menu-display-profile`을 만들고 repository task, open PR, live worktree와 모든 local/remote
migration inventory를 재검증했다. 실행 중인 다른 schema writer가 없고 combined committed inventory의
마지막 번호가 V66이므로 이 ExecPlan stack이 sole migration-writer lease를 획득하고 Store/menu slice에
`V67`을 선택했다. Notification child는 같은 linear stack과 shared lease에서 다음 combined 번호를
사용한다. Lease는 final migration-writing child의 stack handoff/merge 전까지 unrelated writer에게
양보하지 않으며, 원격 migration 충돌이 생기면 새 번호를 추측하지 않고 중단한다.

### New/changed data

1. **Notification (ADR-104)**
   - Create `notification_inbox_item` and `notification_customer_preference` exactly with ADR-104's ownership,
     `logical_source` uniqueness, target check, retention timestamp and `(customer_id, created_at DESC, id DESC)`
     cursor index.
   - Add a partial unread lookup index on `notification_inbox_item(customer_id)` where `read_at IS NULL` for the
     narrow summary query. It is not a notification counter cache.
   - Add a bounded cleanup worker/claim query keyed by `(retention_expires_at, id)`; no background job may delete
     Order, Refund or Delivery records by inferring Inbox retention.
2. **Merchant Store display profile**
   - Create a Store-owned one-to-one `merchant_store_customer_display_profile` with `store_id` primary/foreign key,
     nullable trimmed `address_line` (1..300), nullable trimmed `directions_hint` (1..200), `version`,
     `created_at`, `updated_at`; reject controls and untrimmed persisted values.
   - Create `merchant_store_operating_hours` with `(store_id, day_of_week)` primary key, `closed`, `opens_at`,
     `closes_at`, and a check that a closed day has no times and an open day has both times with
     `opens_at < closes_at`. No default hours are invented for existing Store rows.
   - Keep `merchant_store_support_profile.public_description` and `pickup_instructions` in their existing
     support-purpose workflow. This plan does not duplicate or silently rehome them.
3. **Menu display metadata**
   - Add nullable trimmed/control-character-free `display_category` (1..50) and `public_description` (1..500)
     to `merchant_menu`. Values are not search terms in this plan and do not change menu availability or price.
4. **Existing Order data**
   - No Order/Payment/Refund migration is needed for pricing/lifecycle/refund context: the Order snapshot and
     lifecycle columns already exist. The projection must select them directly and validate their relationship to
     state.

Update every database cleaner/truncate fixture for the new tables, migration verification tests, seed data and
test factories. Seeded visual stories may supply a complete seven-day schedule; no migration or runtime fallback
may manufacture production hours.

## API and Event Contracts

All changes occur atomically in target OpenAPI, runtime OpenAPI delegation, generated TypeScript schema and
backend response/controller tests. Because no client has been deployed, use one clean response shape instead of a
deprecated compatibility alias.

### 1. Customer notification API

Keep ADR-104's endpoints and add a narrow bell endpoint through an ADR-104 amendment:

```http
GET   /api/v1/me/notification-summary
GET   /api/v1/me/notifications?cursor=&limit=
PATCH /api/v1/me/notifications/{notificationId}  { "read": true }
GET   /api/v1/me/notification-preferences
PUT   /api/v1/me/notification-preferences         { "marketingOptIn": boolean }
```

```json
{ "hasUnread": true }
```

- Summary is customer-scoped and returns only a boolean. `GET` requires the customer session; the unsafe
  `PATCH`/`PUT` endpoints retain the existing CSRF requirement. It does not disclose Delivery outcome, count,
  title/body or target.
- Inbox pages preserve ADR-104 cursor tuple/order and expose only title, body, createdAt, readAt,
  classification and safe target (`ORDER` public reference or `NONE`).
- Existing transactional templates become InboxItem sources at the same event handling point as Delivery. New
  marketing creation follows ADR-104 opt-in behavior; none is silently created for opt-out customers.

### 2. Store and menu customer display contract

Add a shared `customerDisplay` object to customer Store summary/detail/search surfaces and change the
ambiguous `open` field name to `orderingAvailable`:

```text
CustomerStore / StoreSearchItem
  orderingAvailable: boolean
  pickupAvailable: boolean
  nextPickupWindow?: { startsAt, endsAt }
  customerDisplay: {
    addressLine?: string
    directionsHint?: string
    operatingStatus: OPEN | CLOSED | UNSPECIFIED
    operatingHours?: {
      timezone: "Asia/Seoul"
      days: [{ dayOfWeek, closed, opensAt?, closesAt? }]
    }
  }
  image?: { url, expiresAt }                 # existing ADR-115 contract

StoreMenuItem
  displayCategory?: string
  description?: string
  image?: { url, expiresAt }                 # existing ADR-115 contract
```

- `nextPickupWindow` comes from one Fulfillment batch/read extension so list pages do not add per-store queries.
  It is omitted if no reservable slot exists; it is not based on schedule or a hard-coded lead time.
- Search's `openOnly` query parameter may keep its transport spelling for this slice, but its documentation and
  response must use `orderingAvailable`; a later query-parameter rename requires a separate compatibility
  decision. Customer UI does not call the result `영업 중`.
- Add Store-owned authoring endpoints outside the customer surface:

```http
GET /api/v1/stores/{storeId}/customer-display
PUT /api/v1/stores/{storeId}/customer-display
PUT /api/v1/stores/{storeId}/menus/{menuId}/display-content
```

  The authenticated GET returns the full authoring representation and current `version` to same-store
  `OWNER`; an absent profile is empty content/schedule at `version=0`. The version is not added to customer
  public Store responses. Each command carries its full replacement payload and `expectedVersion`; Store
  `OWNER` can change display profile, same-store `OWNER | STAFF` can change menu display content. Both lock,
  validate, write Audit and return 409 on stale version. They do not change price, availability or current
  Order snapshots.

### 3. Non-reserving customer order quote

```http
POST /api/v1/me/order-quotes
```

The request reuses the editable inputs of `POST /orders`:

```text
storeId, pickupSlotId, lines[{ menuId, quantity, option selections }],
couponIssuanceId?, pointsToUseKrw
```

The response is deliberately not an authority token:

```text
OrderQuote
  quotedAt
  quoteFingerprint
  store { storeId, name }
  pickupWindow { startsAt, endsAt }
  lines[{ menuId, menuName, quantity, option names, lineTotalKrw }]
  pricing { subtotalKrw, couponDiscountKrw, pointsAppliedKrw, payableKrw, currency }
  guarantee: NONE
```

- `quoteFingerprint` is an opaque server-generated optimistic-concurrency precondition, not a `quoteId`, a
  reservation ID, a Provider input or a client-supplied money field. It covers the normalized request and full
  authoritative result, not `payableKrw` alone. `quotedAt` is informational only.
- It uses Session/CSRF because its complex request body is customer-scoped, even though it creates no persistent
  command result. No `Idempotency-Key` is needed because the operation is a read calculation with no side effect.
- `POST /orders` repeats the normalized editable input and adds `expectedQuoteFingerprint`; it never receives
  quote money. Inside the existing Order/reservation transaction, the server locks/re-quotes and compares the
  full fingerprint, then reserves only on a match and creates the Order from that server calculation. A mismatch returns
  `409 ORDER_QUOTE_STALE` with `currentQuote: OrderQuote` and no new Order; the user must explicitly review and
  submit the returned fingerprint with a new `Idempotency-Key`. BR-25 stores and byte-equivalently replays the
  first terminal stale response for the original key·payload, so replay does not claim its `currentQuote` is
  newly calculated. Existing validation/conflict responses remain distinct.

### 4. Customer order detail and in-screen transaction summary

Replace detail-level `totalAmountKrw` with the canonical pricing object and add an optional lifecycle object:

```text
CustomerOrderDetail
  ...existing public fields...
  pricing { subtotalKrw, couponDiscountKrw, pointsAppliedKrw, payableKrw, currency }
  lifecycle?: { paidAt?, acceptedAt?, preparingAt?, readyAt?, completedAt? }
```

- List summary `totalAmountKrw` may remain as the compact list value; only the detail response needs a complete
  payment breakdown.
- The server emits only timestamps that actually occurred. State/present timestamp relationships are contract
  tested; an invalid persisted combination is a dependency/invariant failure, not a display approximation.
- Customer UI builds `주문 내역` / `거래 요약` from this response. It must not label it a PG receipt or fiscal
  document and must not request a new Provider receipt endpoint.

### 5. Merchant order board lifecycle projection

Extend `StoreOrderBoardItem` with:

```text
lifecycle: { paidAt?, acceptedAt?, preparingAt?, readyAt? }
```

- The field is in normal board, overflow page and per-order board-detail response. It is part of weak ETag
  canonicalization.
- `completedAt` is not returned because the board only holds live executable lanes; completed history remains a
  separate surface. No board lane/state/action changes are introduced.

### 6. Merchant partial refund preview context

Add `orderContext` to `MerchantRefundPreview`:

```text
orderContext
  orderedAt
  pickupWindow { startsAt, endsAt }
  status
  pricing { subtotalKrw, couponDiscountKrw, pointsAppliedKrw, payableKrw, currency }
  paymentKind: ONE_TIME_EXTERNAL | BENEFIT_ONLY
```

- `paymentKind` is a small display vocabulary, not saved-card metadata or provider/card detail. Current
  `EXTERNAL` Payment maps to `ONE_TIME_EXTERNAL` for this customer product path.
- `previewVersion` remains the execution staleness authority. `orderContext`, client money and payment identifiers
  are not accepted by `POST .../refunds`.
- The execute `reason` remains mandatory and 1..500 trimmed characters under BR-38; visual optional wording does
  not loosen the command contract.

## Milestones

### Milestone 0 — Record the recommended product/API decisions

1. Create two new ADRs using the next free ADR numbers at execution time:
   - non-reserving customer order quote, full quote fingerprint precondition, stale response and no-hold/authority
     boundary;
   - Store customer display profile, `orderingAvailable` versus operating status, weekly-hours limitations and
     display metadata ownership.
2. Amend ADR-099 (customer detail pricing/lifecycle), ADR-100 (board lifecycle/ETag), ADR-104 (unread summary),
   ADR-108 (refund preview context), ADR-076/ADR-103 (catalog/search display fields) as applicable.
3. Amend the Business Policy for quote authority, public Store display ownership and terminology, while preserving
   BR-05, BR-33, BR-37, BR-38 and BR-48. Record that no VAT/saved-card change is being made.
4. Record the user-selected quote-fingerprint decision, obtain approval for the remaining recommendations, set
   `Implementation-Ready: true`, and only then allocate a migration version and begin source changes.

**Exit criteria:** Accepted policy/ADR text has no conflict with existing Order, refund, notification, image or
privacy decisions. No source/API implementation starts before this checkpoint.

### Milestone 1 — Schema, aggregates and data hygiene

1. Acquire the migration-writer lane; inventory migrations again; add forward-only migrations for Inbox,
   customer notification preference/index, Store display profile/hours and menu display metadata.
2. Implement Merchant-owned value/entity persistence and DB constraints. Add migration tests for both valid
   seven-day schedules and invalid/null/trim/control-character boundaries.
3. Add test data/fixtures with complete display schedules where a visual state needs them. Do not use a global
   default schedule to pass coverage.
4. Update all test cleanup/truncate paths and document the new data owners.

**Exit criteria:** PostgreSQL migration tests prove no partial schedule/profile state, new tables are cleaned in
every relevant suite, and no existing Store is falsely shown as operating merely because data is missing.

### Milestone 2 — Notification inbox vertical slice

1. Implement `NotificationInboxItem`, preference projection, customer ownership query, cursor pagination,
   read command, 90-day bounded cleanup and `hasUnread` summary.
2. At each existing customer transactional notification source, create InboxItem + Delivery + persistent
   publication atomically and prove replay deduplication via `logical_source`.
3. Implement customer notification client/query hooks and global bell behavior. On read, invalidate summary/list;
   do not display Delivery failure diagnostics.
4. Add Storybook states for no unread, unread badge, inbox page, empty inbox, loading and explicit dependency
   failure; run a11y/interaction verification when Storybook MCP is available.

**Exit criteria:** a Provider delivery failure still leaves exactly one inbox item; Inbox persistence failure leaves
neither Inbox nor Delivery/published work committed; cross-customer access is 404; retention works in bounded
keyset batches.

### Milestone 3 — Store/menu display profile vertical slice

1. Implement Store owner current representation GET and profile/menu content authoring services/controllers
   without Controller→Repository access; enforce actor/membership/store/menu binding, expected version, Audit
   and no-op behavior.
2. Extend Merchant public query DTOs, Discovery hydrators/search projections and Fulfillment availability batch
   contract to return display profile, operating status and earliest reservable pickup window.
3. Replace customer response `open` with `orderingAvailable` in target/runtime OpenAPI and generated schema;
   update search filter documentation and all test fixtures atomically.
4. Extend catalog menu query/projection with optional display category/description; keep image resolution through
   existing `StorefrontImageViewResolver`.
5. Update Home, Search, Store Detail and Cart presentation to use the new fields. `UNSPECIFIED` schedule and no
   next pickup have explicit non-deceptive UI states.

**Exit criteria:** reads remain bounded/no N+1 per Store; `OPEN` and `orderingAvailable` are independently tested;
existing optional images render correctly without a new media contract.

### Milestone 4 — Non-reserving quote and cart/payment boundary

1. Extract/reuse a pure, read-only quote orchestration path from the existing Order creation inputs. It may call
   owner read ports but must not call `CouponReservationService`, point/slot reservation writes, Payment or Audit.
   Define one canonical full-fingerprint function shared by quote and final Order creation; it must include the
   normalized input and all authoritative values whose change would alter Order snapshot, benefit attribution or
   pickup eligibility.
2. Implement `POST /me/order-quotes`, `quoteFingerprint`, `POST /orders.expectedQuoteFingerprint`,
   `ORDER_QUOTE_STALE.currentQuote`, target/runtime schema, generated client and state-specific response/error
   tests. Test that quote results create no Order/reservation/idempotency/Payment rows/events. A stale final-create
   rejection creates no transaction resource rows/events but does create exactly one terminal BR-25
   idempotency response, replays it for the same key·payload and requires a new key for a reviewed retry.
3. Replace Cart's client-calculated displayed totals with the server quote. Debounce/reload on cart, coupon,
   point or slot changes; render an explicit error/retry state rather than an old total.
4. Keep `POST /orders` and current Checkout/Toss flow authoritative. Its success response drives existing lease
   timer and payable payment button; no stored-card selector is added.

**Exit criteria:** an exact quote creates an Order whose server-side immutable snapshot matches the quote;
concurrent price, option, coupon, point or slot/benefit-source changes return `ORDER_QUOTE_STALE` with a fresh
quote and no persisted Order. The original key replays the first stale body and a reviewed retry uses a new key.
No test observes a quote-as-reservation, client-submitted money amount or silent acceptance of a changed price.

### Milestone 5 — Order detail, board and refund presentation contracts

1. Extend customer order projection SQL/service/response with immutable pricing and lifecycle fields; update
   openapi/generated clients and normal/cancellation/reorder regressions.
2. Extend board SQL, projector, response, canonical ETag and overflow projection with lifecycle fields without
   changing lane/ordering/cursor behavior. Update fixed-clock board stories and tests.
3. Extend refund preview composition with `orderContext`, sourced alongside existing authoritative preview data;
   exclude PII, raw payment/provider fields, VAT and any client-supplied financial input.
4. Update Customer Order Detail transaction summary and Merchant Refund UI to render only the new safe response
   fields. Preserve existing stale/unresolved/retry UX and mandatory execution reason.

**Exit criteria:** a changed timestamp changes ETag, preview remains write-free, a stale preview still cannot
execute, and neither screen makes provider/tax claims.

### Milestone 6 — Visual integration and Storybook proof

1. Apply the visual system to the scoped customer routes (home, search, store detail, cart, checkout, order detail,
   inbox) and merchant routes (board, partial refund), reusing existing design-system primitives/icons and actual
   HTML text rather than screenshot crops.
2. Preserve route/auth boundaries and existing fallback/error semantics. New route states must use MSW/server
   fixtures that match generated contracts, not handwritten compatibility objects.
3. Register stories for normal, empty, unavailable, stale, deadline/elapsed, image-absent and accessibility
   states. Use fixed clocks for countdown/timeline assertions.
4. Run Storybook MCP route/state/a11y checks and browser interaction tests. If the MCP is unavailable, record UI
   implementation/verification as **Blocked** rather than treating a static build as equivalent proof.

**Exit criteria:** every visual field is backed by the contract above; no route silently invents a discount,
pickup time, lifecycle timestamp, notification unread state, refund context or image URL.

### Milestone 7 — Integrated validation and handoff

1. Re-run migration, focused modules, API parity/auth, frontend type/unit, Storybook/browser and documentation
   gates after the combined change.
2. Review the final diff for unintended compatibility fields, source-generated contract mismatch, new PII in logs
   or metric tags, fake fallback behavior and missing cleaner updates.
3. Record Passed/Failed/Blocked/Not run evidence in this plan. Do not declare the visual recreation complete from
   mock-only or static screenshot evidence.

## Required Tests

### Backend, migration and API contracts

- Notification: transactional template → exactly one InboxItem/Delivery/publication, delivery provider failure,
  source replay, preference opt-in/out, customer ownership 404, `{read:true}` replay, invalid PATCH body,
  cursor no-gap/no-duplicate, `hasUnread`, 90-day boundary and bounded cleanup retry.
- Store display: Store OWNER/STAFF authorization, same-store menu binding, stale expected version, full weekly
  replacement, invalid hours constraints, no-op update/Audit behavior, optional profile rendering, schedule
  `OPEN/CLOSED/UNSPECIFIED`, `orderingAvailable` independence and `Asia/Seoul` fixed-clock boundary.
- Discovery/catalog: no N+1 next-slot query, earliest reservable slot only, unavailable Store/no slot distinction,
  no invented lead time, optional category/description/image serialization, runtime/target OpenAPI parity and
  search `open` removal.
- Quote: all current menu/option/slot/coupon/point validations, zero/nonzero payment cases, no persistent write,
  no provider call, exact quote→final Order immutable snapshot equality, tampered/malformed fingerprint,
  price/option/coupon/point/slot change, same-payable-but-different-benefit-source change,
  `ORDER_QUOTE_STALE.currentQuote`, stale transaction write rollback, terminal BR-25 response 저장·동일
  key replay·새 key retry, client price injection rejection and same Order-creation authority regression.
- Customer orders: immutable price algebra, lifecycle/state consistency, owner isolation, public reference only,
  no internal payment/provider identifiers and output schema replacement.
- Board: normal/overflow/detail lifecycle timestamps, ETag changes for timestamp-only mutation, 304 correctness,
  lane/order/cursor regression, no PII.
- Refund: orderContext belongs to same Store/order/preview, preview remains write-free, safe vocabulary only,
  stale/unresolved handling, mandatory reason, Idempotency-Key replay and existing money/points/coupon/settlement
  tie-out regressions.

### Frontend and visual tests

- Home/search/detail show ordering availability separately from schedule status; absent schedule, slot and image
  have deliberate UI states.
- Cart uses quote pricing and fingerprint only, invalidates/refetches on edited inputs, renders
  `ORDER_QUOTE_STALE.currentQuote` for explicit re-confirmation, keeps final create conflict recoverable and never
  displays a saved-card control.
- Checkout retains existing one-time payment and lease countdown semantics.
- Customer order detail shows only occurred lifecycle steps and a transaction summary, not a fiscal/PG receipt.
- Bell/inbox read and preference interaction updates `hasUnread`; delivery operational failure never leaks into
  customer copy.
- Merchant board uses fixed clocks for elapsed time and preserves 304/overflow behavior; refund page displays safe
  preview context and preserves stale/unresolved workflows.
- Every affected new component/story passes accessibility checks, keyboard flow and semantic name queries.

## Validation Commands

The documentation-only change that introduces this plan runs only the first three commands. Implementation must
run the relevant later groups and report each result precisely.

```bash
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
git diff --check
git diff --cached --check

./gradlew test \
  --tests '*Notification*' \
  --tests '*CustomerOrder*' \
  --tests '*StoreOrderBoard*' \
  --tests '*MerchantRefund*' \
  --tests '*StoreCatalog*' \
  --tests '*StoreSearch*' \
  --tests '*RuntimeOpenApiParityTest' \
  --tests '*AuthenticationPathRegistryTest'
./gradlew spotlessCheck

npm run typecheck
npm run test:unit
npm run build-storybook
npm run test:storybook:docs
```

Run the applicable PostgreSQL/Testcontainers migration suites, OpenAPI generation/parity, module/architecture
tests and Storybook MCP/browser/a11y coverage in addition to these commands. Provider sandbox and full release
gate are **Not run** until explicitly scheduled; they must not be inferred from unit or story tests.

## Observability

- Add quote outcome/latency metrics with closed `outcome` vocabulary only. Never tag customer ID, Store ID,
  order reference, coupon issuance, menu ID, cart contents or money amount.
- Keep ADR-104 delivery/retry metrics and add inbox create/read/summary/cleanup outcomes; compare inbox creation
  to delivery attempts without treating their difference as a delivery success rate.
- Record Store display command outcomes, stale writes and schedule configuration state using closed vocabularies;
  do not log public address/directions text as metric tags.
- Preserve board query/ETag/overflow metrics and refund preview/stale/unresolved metrics. Lifecycle additions do
  not add internal IDs, Provider data, customer PII, reason text or money as tags.
- No latency, conversion, error reduction or operational improvement claim is made until a same-condition
  measurement exists.

## Documentation Updates

- ADR-116/117 record the non-reserving quote fingerprint/stale semantics and Store customer display profile.
- ADR-076, ADR-099, ADR-100, ADR-103, ADR-104 and ADR-108 amendments record their public projection changes.
- BR-49/50 and the BR-25 amendment record Order quote authority, terminal stale replay, Store public display
  ownership and terminology while retaining BR-05, BR-33, BR-37, BR-38 and BR-48 semantics.
- Update `docs/product/core-user-journey.md`, capability/design contract tracking and relevant OpenAPI reference
  documentation when the slices are implemented; do not claim an integrated journey until the actual combined
  validation has passed.
- Update frontend route/story inventory only after live story registration and routed UI are verified.

## Progress

- 2026-08-25: Created this documentation-first ExecPlan. No production source, API, migration, generated client,
  frontend, Storybook, test fixture, commit, push or PR changed.
- 2026-08-25: Re-verified that Store/Menu image persistence, optional public read contract, customer rendering and
  Storybook examples already exist through ADR-115, V65/V66 and current source. Image storage work is excluded.
- 2026-08-25: Removed saved-card and VAT/tax work from scope. Existing one-time payment and Provider refund
  boundaries remain inputs, not implementation targets.
- 2026-08-25: Documentation validation and both staged/unstaged whitespace checks passed for this plan-only
  change. Backend, migration, frontend, Storybook MCP/browser and Provider validation are not run.
- 2026-08-25: Replaced the earlier no-token quote recommendation with a server-generated full
  `quoteFingerprint` precondition. Final Order creation re-quotes under existing locks, reserves only after an
  exact match and returns `ORDER_QUOTE_STALE` otherwise instead of silently accepting a changed quote.
- 2026-08-25: User approved recommendations 2–8: separate `orderingAvailable` and operating status, Merchant-owned
  display content, immutable in-app transaction summary, narrow `hasUnread`, safe refund context, unchanged
  image contract and atomic response replacement.
- 2026-08-25: Resolved the ExecPlan/BR-25 conflict by user decision. `ORDER_QUOTE_STALE` is a terminal
  idempotency response; reviewed retry uses a new fingerprint and new `Idempotency-Key`.
- 2026-08-25: Recorded BR-49/50, ADR-116/117 and ADR-076/099/100/103/104/108 amendments. Milestone 0 now meets
  its decision gate and the plan is `Implementation-Ready: true`; production source remains unchanged in this PR.
- 2026-08-25: PR #108 head에서 Store/menu child branch를 만들고 task/open-PR/worktree/local·remote
  migration inventory를 검증했다. 이 stack이 sole writer lease를 획득해 V67을 선택했으며 다른 dirty
  user worktree는 변경하지 않았다.
- 2026-08-25: Source inventory에서 expected-version authoring을 시작할 점주 current read가 없음을
  발견했다. 사용자 승인에 따라 same-store OWNER 전용 GET과 absent `version=0` 의미를 BR-50/ADR-117에
  기록했으며 customer public response에는 version을 추가하지 않는다.

## Surprises & Discoveries

- Existing Merchant APIs expose neither the customer-display authoring values nor their optimistic concurrency
  version. A dedicated authenticated owner read was required; using the customer response would have leaked an
  internal write boundary and still left actor-specific edit semantics ambiguous.
- The current customer detail repository already reads a subset of price/lifecycle data internally, but its public
  response intentionally omits the breakdown and later timestamps. This is a projection contract gap, not a new
  Order data-model requirement.
- The current board/overflow system is mature enough that adding fields must update its canonical ETag rather than
  introducing a parallel live-order feed.
- Image capability was previously a plausible gap, but current `main` already includes full optional media support;
  creating another image API would duplicate ownership and violate ADR-115.
- Existing support-purpose public profile text must not be copied casually into a new customer display record. The
  scoped display profile only owns the new address/directions/hours fields.

## Decision Log

The following decisions were approved by the user on 2026-08-25 and are recorded in BR-49/50,
ADR-116/117 and the related ADR amendments.

1. Use `POST /me/order-quotes` as a non-reserving calculation that returns `quoteFingerprint`; final
   `POST /orders` sends only `expectedQuoteFingerprint`, revalidates under server locks, reserves only after
   a full fingerprint match and then creates an Order. A mismatch returns `ORDER_QUOTE_STALE` with a current quote.
2. Rename customer response `open` to `orderingAvailable`, add separately computed
   `OPEN | CLOSED | UNSPECIFIED` operating status, and evaluate a simple weekly schedule in `Asia/Seoul` only.
3. Store public address/directions/hours in a Merchant-owned profile; keep existing support-purpose text fields
   out of that new record. Store OWNER manages profile; same-store OWNER/STAFF manages menu display metadata.
4. Treat customer order `거래 요약` as an in-app immutable Order summary, not a PG or tax receipt.
5. Add only `hasUnread` to the global bell contract; InboxItem remains separate from NotificationDelivery.
6. Add safe order/payment/pricing context to refund preview only; retain mandatory refund reason and do not expose
   VAT, provider/card identifiers or customer PII.
7. Reuse the existing image contract unchanged, including optional field and expiry/failure behavior.
8. Because there is no deployed client, make the public response shape change atomically rather than retaining
   deprecated aliases.
9. Treat `ORDER_QUOTE_STALE` as a terminal BR-25 order-creation response: store and replay the first status/body
   for the same key·payload, and require a new fingerprint plus new `Idempotency-Key` after customer review.
10. Add an authenticated same-store OWNER GET for the Store customer-display current representation and
    version; keep that concurrency version out of the customer public response.

## Outcomes & Retrospective

Milestone 0 decision recording is complete and `Implementation-Ready: true`. No production implementation
outcome exists yet. Completion still requires evidence from migration/API/frontend/Storybook validation, not a
successful document check alone.

## Revision Notes

- 2026-08-25: Preserved BR-25 terminal failure replay for `ORDER_QUOTE_STALE` and changed the stale tests from
  “no idempotency row” to “no transaction resource writes plus exactly one terminal idempotency response.”
- 2026-08-25: Accepted the remaining Store display, projection, inbox, refund, image and atomic compatibility
  decisions and completed the Milestone 0 policy/ADR gate.
- 2026-08-25: Changed the quote recommendation from stateless no-token re-quote to full fingerprint comparison,
  explicit stale response and customer re-confirmation. The fingerprint is not a reservation or money authority.
- 2026-08-25: Initial plan created from the supplied customer and merchant screen references, current contract
  audit and the user-supplied scope exclusions.
