# ADR-117: Store 고객 표시 profile과 주문 가능성 분리

- **Status:** Accepted
- **Date:** 2026-08-25
- **Implementation owner:** [고객·점주 화면 재현을 위한 계약 완성](../exec-plans/completed/customer-merchant-screen-contract-completion.md)

## Context

고객 매장 탐색 응답의 기존 `open`은 영업시간이 아니라 `acceptingOrders && pickupEnabled`인 주문
수락 flag다. UI가 이를 `영업 중`으로 표시하면 공개 운영시간과 주문 정책을 혼동한다. 현재 고객
응답에는 공개 주소, 길찾기 안내, 주간 운영시간, 실제 다음 픽업 window와 메뉴 표시 카테고리·설명도
없다.

Merchant에는 Support 목적의 `merchant_store_support_profile.public_description`과
`pickup_instructions`가 있지만, 이는 목적 제한 변경 workflow의 데이터다. 고객 공개 표시를 위해
재사용하면 Support 권한·Audit 의미와 customer content ownership이 섞인다.

이미지 저장과 optional customer read는 ADR-115로 완료됐다. 새 표시 profile은 이미지 pointer나
AIStor 실패 의미를 바꾸지 않아야 한다.

## Decision

### 1. Merchant가 별도 customer display profile을 소유한다

`merchant_store_customer_display_profile`은 Store와 one-to-one이며 다음을 가진다.

- `store_id` primary/foreign key
- nullable `address_line` 1..300
- nullable `directions_hint` 1..200
- `version`, `created_at`, `updated_at`

nullable text는 없거나 trim된 non-empty 값이어야 하고 control character를 허용하지 않는다.
`merchant_store_operating_hours`는 `(store_id, day_of_week)`를 key로 `closed`, `opens_at`,
`closes_at`을 가진다. schedule이 존재하면 일곱 요일이 정확히 한 번씩 있어야 한다.

- closed day: start/end 모두 null
- open day: start/end 모두 존재하고 `opensAt < closesAt`
- timezone: `Asia/Seoul` 고정
- same-day interval: 요일별 최대 하나

자정 넘김, 24시간 표현, 휴일·임시휴무 예외, 날짜별 override와 Store별 timezone은 지원하지 않는다.
기존 Store에 profile이나 운영시간을 backfill하지 않고 production default를 만들지 않는다.

### 2. full replacement command와 하나의 profile version을 사용한다

`GET /api/v1/stores/{storeId}/customer-display`는 점주 편집 전용 current representation으로
address, directions, optional complete seven-day schedule과 `version`을 반환한다. ACTIVE same-store
`OWNER`만 읽을 수 있으며, 이 version은 고객 공개 Store 응답에 노출하지 않는다. profile이 아직
없으면 nullable content와 schedule이 없는 `version=0` representation을 반환해 최초 PUT의
`expectedVersion=0`을 명시한다.

`PUT /api/v1/stores/{storeId}/customer-display`는 address, directions, optional complete seven-day
schedule과 `expectedVersion`을 받는다. schedule 생략은 운영시간 전체 미설정이며 기존 일곱 row를
모두 제거한다. 일부 요일만 보내는 patch는 허용하지 않는다.

ACTIVE same-store `OWNER`만 실행할 수 있다. Application Service는 membership과 Store를 확인하고
profile owner row를 잠근 뒤 expected version, 모든 text/hour 불변식, 전체 schedule을 검증한다.
profile·hours·append-only Audit를 하나의 local transaction으로 commit하거나 전부 rollback한다.
stale version은 409이며 partial schedule이나 Audit-only 성공을 남기지 않는다.

정규화 뒤 기존 값과 완전히 같은 replacement는 current representation을 반환하되 version,
`updatedAt`과 Audit를 바꾸지 않는 no-op이다. profile의 단일 version이 address, directions와 schedule
전체의 optimistic concurrency boundary다.

### 3. Menu 표시 metadata는 기존 Menu가 소유한다

`merchant_menu`에 nullable `display_category` 1..50과 `public_description` 1..500을 둔다.
`GET /api/v1/stores/{storeId}/menus/{menuId}/display-content`는 ACTIVE same-store `OWNER | STAFF`에게
현재 nullable display content와 기존 Menu `version`을 반환한다. 이 version은 customer menu catalog에
노출하지 않는다.

`PUT /api/v1/stores/{storeId}/menus/{menuId}/display-content`는 두 값을 full replacement하고 기존
Menu version을 `expectedVersion`으로 사용한다. ACTIVE same-store `OWNER | STAFF`만 실행하며,
cross-store Menu는 존재를 누설하지 않는 기존 객체 인가를 따른다.

표시 metadata는 가격, `available`, option, search grammar, 주문 line name/price snapshot, benefit
계산과 refund allocation을 바꾸지 않는다. null category는 고객 UI의 전체/미분류 표현으로만
소비한다. 동일 replacement는 version/Audit를 바꾸지 않는 no-op이다.

### 4. 주문 가능성과 공개 운영상태를 별도 계산한다

customer Store summary/detail/search는 ambiguous `open`을 제거하고 다음을 원자적으로 제공한다.

```text
orderingAvailable: boolean
pickupAvailable: boolean
nextPickupWindow?: { startsAt, endsAt }
customerDisplay:
  addressLine?
  directionsHint?
  operatingStatus: OPEN | CLOSED | UNSPECIFIED
  operatingHours?:
    timezone: Asia/Seoul
    days[{ dayOfWeek, closed, opensAt?, closesAt? }]
image?: { url, expiresAt }
```

- `orderingAvailable = acceptingOrders && pickupEnabled`다. 영업시간이나 slot 존재 여부를 섞지 않는다.
- complete schedule이 없으면 `operatingStatus=UNSPECIFIED`다.
- schedule이 있으면 `Asia/Seoul`의 오늘과 local time을 사용해
  `opensAt <= now < closesAt`일 때만 `OPEN`, 그 외와 closed day는 `CLOSED`다.
- `OPEN`은 주문 가능성을 뜻하지 않고 `orderingAvailable`도 영업시간을 주장하지 않는다.
- `nextPickupWindow`는 ADR-076의 예약 가능 경계를 만족하는 가장 이른 실제 slot이다. Store policy,
  slot 시작·7일 horizon·capacity를 만족하는 slot이 없으면 생략한다. 준비시간을 추정하지 않는다.

`nextPickupWindow`와 pickup availability는 Fulfillment batch/read extension으로 조회해 목록의
Store 수만큼 query를 늘리지 않는다. 검색 query parameter `openOnly`는 이 slice에서 transport
호환성을 위해 유지하지만 문서와 response는 `orderingAvailable` 의미를 사용한다. 고객 UI는 이를
`주문 가능`/ `주문 불가`로 표시하며 `영업 중`으로 번역하지 않는다.

### 5. profile 미설정과 의존성 실패를 구분한다

profile이 실제로 없으면 address/directions/hours를 생략하고 `UNSPECIFIED`를 정상 반환한다. profile
query 또는 schedule invariant가 실패하면 503이며 미설정, 빈 profile, CLOSED 또는 주문 불가로
대체하지 않는다. customer response에 일곱 요일 중 일부만 반환하지 않는다.

기존 ADR-115 `image { url, expiresAt }` optional 계약, URL expiry와 AIStor failure semantics는 그대로
유지한다. Support-purpose profile을 fallback으로 읽거나 이미지 placeholder/stale URL을 만들지 않는다.

### 6. 결제·세무·개인정보 범위를 늘리지 않는다

고객 표시 profile은 공개 Store 정보만 소유한다. 운영 연락처, 대표자·정산계좌, customer PII,
Provider/card 식별자, saved-card UI, VAT·세무 정보는 저장하거나 응답하지 않는다. address와 directions
원문은 metric tag나 구조화 log에 넣지 않는다.

## Alternatives Considered

### 기존 `open`을 유지하고 설명만 변경

오래된 UI가 영업 상태로 계속 오해할 수 있고 두 의미가 같은 boolean에 남는다. 배포된 client가 없는
현재 atomic rename이 더 명확하다.

### Support profile을 customer 공개 source로 재사용

목적 제한 권한과 customer content authoring이 결합되고 Support 변경이 공개 화면을 뜻밖에 바꾼다.
별도 Merchant-owned profile을 사용한다.

### 자유 형식 영업시간 문자열

표시는 쉽지만 현재 시각의 OPEN/CLOSED를 신뢰성 있게 계산할 수 없고 timezone·자정 경계가 모호하다.
초기 범위를 단일 same-day interval로 제한한다.

### schedule로 orderingAvailable을 계산

영업시간 안에도 매장이 주문을 일시 중지하거나 pickup을 끌 수 있다. 두 정책을 독립 필드로 유지한다.

### 준비시간을 저장해 next pickup을 추정

현재 owner model에 준비시간이 없고 실제 slot capacity를 무시한다. 예약 가능한 실제 slot만 사용한다.

## Rationale

customer display를 Merchant Store에 두면 공개 content의 권한·Audit·version이 명확하다. 운영시간과
주문 수락 정책을 별도 필드로 노출하면 UI가 추정하지 않고 두 상태를 정확히 설명할 수 있다. 제한된
주간 schedule과 실제 Fulfillment slot은 구현 가능한 초기 계약을 제공하면서 존재하지 않는 lead time을
발명하지 않는다.

## Consequences

- customer response의 `open`이 `orderingAvailable`로 breaking rename된다. 배포된 client가 없다는
  전제 아래 deprecated alias를 두지 않고 OpenAPI/generated client/UI를 같은 PR에서 교체해야 한다.
- 점주 편집 UI는 인증된 GET current representation으로 version을 얻는다. 고객 공개 응답이나
  Support profile에는 optimistic concurrency version을 복제하지 않는다.
- Menu 편집 UI도 인증된 display-content GET으로 기존 Menu version을 얻으며 customer catalog에는
  이를 추가하지 않는다.
- 주간 예외·자정 영업·24시간 매장은 이 계약으로 표현할 수 없으며 profile을 미설정으로 두거나 후속
  결정이 필요하다.
- profile command는 새 table과 Audit write를, menu command는 기존 row version 변경을 만든다.
- search/list read에 profile과 earliest slot projection이 추가되므로 batch query와 대표 데이터
  EXPLAIN evidence가 필요하다.
- 주소·운영시간이 없어도 Store 자체와 텍스트 catalog는 정상 조회할 수 있다.

## Verification

- DB constraint로 profile text trim/control/length와 각 closed/open tuple·요일 범위를, Application과
  PostgreSQL transaction test로 정확한 seven-day full replacement를 검증한다.
- OWNER profile GET/PUT 성공, STAFF profile read/write 거절, OWNER/STAFF menu GET/PUT 성공,
  cross-store/revoked membership과 stale version을 PostgreSQL/HTTP 계약 테스트로 검증한다.
- profile·hours·Audit 원자성, identical no-op의 version/Audit 불변, partial schedule 부재를 검증한다.
- 고정 Clock으로 `opensAt`, `closesAt`, closed day, 미설정 schedule과 Asia/Seoul 날짜 경계를 검증한다.
- `orderingAvailable`의 Store flag 조합과 operatingStatus 독립성을 전수 검증한다.
- earliest reservable slot, full/started/disabled/no-slot 및 목록 query 수 고정을 검증한다.
- target/runtime OpenAPI와 generated client에서 `open`이 없고 `orderingAvailable`만 존재하는지 검증한다.
- profile 조회 장애가 UNSPECIFIED/empty 200이 아니라 503인지, image 동작이 ADR-115와 같은지 검증한다.

## Revisit Conditions

자정 넘김·24시간·휴일·임시휴무, Store별 timezone, 요일별 복수 interval, address 구조화·지도 Provider,
menu category의 검색/관리 Aggregate화 또는 실제 측정된 display/slot batch 병목이 제품 요구가 될 때
schedule/category 모델을 별도 결정으로 확장한다.

## Related Decisions

- BR-47, BR-48, BR-50
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-022](ADR-022-audit-record.md)
- [ADR-027](ADR-027-store-membership-authorization.md)
- [ADR-076](ADR-076-store-catalog-read-contract.md)
- [ADR-087](ADR-087-field-risk-and-purpose-specific-profile-change.md)
- [ADR-103](ADR-103-store-search-strategy.md)
- [ADR-115](ADR-115-store-and-menu-image-storage.md)
