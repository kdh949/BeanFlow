# ADR-120: 운영형 선착순 쿠폰 캠페인과 이벤트 배너 lifecycle

- **Status:** Accepted
- **Date:** 2026-09-02
- **Supersedes:** [ADR-107](ADR-107-limited-coupon-issuance.md)
- **Implementation owner:** [Limited coupon events ExecPlan](../exec-plans/completed/limited-coupon-events.md)

## Context

ADR-107은 고객 발급 원자성만 결정하고 운영 캠페인 생성·기간 설정·배너·발행 이후 lifecycle은 별도
결정으로 제외했다. 제품은 이제 운영팀이 전체 조건을 설정하고 로그인 고객이 이벤트 페이지에서 쿠폰을
다운로드하는 하나의 capability를 요구한다. 기존 `promotion_campaign`과 `CouponIssuance` 계약은 주문
예약·정산 snapshot에서 이미 사용되므로 일반 캠페인을 깨뜨리지 않는 확장이 필요하다.

## Decision

### Ownership and lifecycle

Promotion은 기존 Campaign과 1:1인 `LimitedCouponCampaign`을 소유한다. 기존 Campaign은 할인·매장·메뉴·
비용 조건을, 확장은 `DRAFT | PUBLISHED | STOPPED`, 노출 문구·배너 포인터·다운로드 기간·쿠폰 만료일을
소유한다. 일반 Campaign은 확장 행이 없으며 동작이 바뀌지 않는다.

초안은 변경할 수 있지만 발행 뒤에는 immutable이다. 발행은 필수 배너와 모든 조건을 검증하고 기존
Campaign을 active로 전환한다. STOP은 신규 claim만 막고 Campaign active와 이미 발급된 쿠폰은 유지한다.
고객 화면의 `SCHEDULED | OPEN | SOLD_OUT | ENDED`는 저장 상태가 아니라 시간·카운터에서 계산한다.

### Atomic claim order

고객 claim과 STOP은 같은 LimitedCouponCampaign row를 먼저 pessimistic write lock한다. claim은 고객별
unique claim을 선점한 뒤 `issued_count < total_quota` 조건부 UPDATE, AVAILABLE CouponIssuance, terminal
idempotency response를 하나의 local transaction으로 commit한다. 하나라도 실패하면 모두 rollback한다.
별도 queue와 request-arrival timestamp는 두지 않으며 성공 transaction 순서만 보장한다. `issued_count`는
어떤 복원 경로에서도 감소하지 않는다.

### Operations authorization and commands

Operations는 persistent `PROMOTION_CAMPAIGN_READ | PROMOTION_CAMPAIGN_WRITE` grant를 사용한다. mutation은
1..200자 reason, 8..128자 Idempotency-Key, 필요한 expectedVersion과 Audit를 요구한다. 발행 뒤 edit,
quota increase, expiry extension, banner replacement, delete와 duplicate command는 제공하지 않는다.

### Event media

기존 AIStor adapter와 normalization을 범용 Media API로 추출한다. source는 JPEG/PNG 최대 5 MiB이며 방향을
정규화하고 metadata를 제거한 뒤 1200x450 JPEG로 저장한다. PUT은 DB transaction 밖에서 수행하고 immutable
key를 한 번 HEAD해 불명 결과를 수렴한 다음 Campaign pointer를 별도 transaction으로 publication한다.
presign 또는 필수 pointer integrity 실패는 고객 이벤트 목록 전체를 503으로 실패시키며 placeholder나 빈
이미지로 바꾸지 않는다.

### Public contracts

- Operations: draft create/list/detail/update, banner PUT, publication POST, stoppage POST와 매장·메뉴 picker.
- Customer: signed-in event campaign list와 synchronous coupon issuance POST.
- 고객 목록은 `(claimEndsAt, campaignId)` keyset cursor를 사용하고 매진·중단·기간 밖 Campaign을 숨긴다.
- claim은 201 또는 `CAMPAIGN_QUOTA_EXHAUSTED`, `COUPON_ALREADY_ISSUED`,
  `CAMPAIGN_NOT_ISSUABLE`, `IDEMPOTENCY_KEY_REUSED`, dependency 503을 반환한다.

## Alternatives Considered

- 엄격한 FIFO queue: 인프라·순번 persistence·복구 모델이 추가되고 사용자가 선택한 DB 접근 순서보다 복잡하다.
- 발행 후 append-only revision: 향후 증액에는 유용하지만 이번 immutable Campaign 요구에는 불필요하다.
- 배너를 공개 URL 문자열로 입력: 저장·검증·삭제·접근 제어를 운영자에게 전가하고 ADR-115와 충돌한다.
- `promotion_campaign`에 nullable 컬럼을 모두 추가: 일반 Campaign과 lifecycle 의미가 섞이므로 1:1 확장보다 약하다.

## Rationale

정확성이 필요한 한 곳을 Campaign root lock과 조건부 counter update에 집중한다. 운영 lifecycle과 고객
발급이 같은 root를 잠그면 STOP과 마지막 claim도 하나의 순서로 설명할 수 있다. 기존 CouponIssuance를
재사용하면 쿠폰함·주문·복원·정산 계약을 복제하지 않는다.

## Consequences

- 인기 Campaign 처리량은 한 counter/root lock의 대기에 제한된다.
- 발급됐지만 사용되지 않은 쿠폰도 총 예산을 계속 점유한다.
- 배너 저장 성공 뒤 DB pointer publication 실패 시 orphan cleanup이 필요하다.
- 운영 권한 bootstrap과 AIStor private prefix가 준비되기 전에는 capability를 노출할 수 없다.

## Verification

- PostgreSQL Testcontainers로 quota/duplicate/STOP concurrency와 full rollback을 검증한다.
- target/runtime OpenAPI parity와 actor·grant·reason·idempotency·expectedVersion을 계약 테스트한다.
- 기존 Coupon wallet/reservation/restoration과 Store/Menu media regression을 실행한다.
- Storybook MCP로 고객·운영 화면의 loading/empty/error/permission/race 상태와 a11y를 검증한다.

## Metrics

- `beanflow.promotion.limited_coupon.claim{outcome}`
- `beanflow.promotion.limited_coupon.lock_wait`
- `beanflow.promotion.campaign.command{operation,outcome}`
- `beanflow.media.operation{target=campaign_banner,operation,outcome}`

식별자, 고객 ID, Campaign ID와 Idempotency-Key는 metric tag로 사용하지 않는다.

## Revisit Conditions

- 측정된 lock wait가 승인된 처리량 목표를 만족하지 못할 때
- 운영팀의 증액·기간 연장·revision 요구가 감사·고객 공정성 정책과 함께 승인될 때
- 캠페인별 1인 2장 이상 또는 예약형/추첨형 배포가 필요할 때

## Related Decisions

- [ADR-024](ADR-024-coupon-calculation-model.md)
- [ADR-064](ADR-064-risk-based-idempotency-model-selection.md)
- [ADR-069](ADR-069-operator-permission-grants-and-audited-policy-read.md)
- [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)
- [ADR-115](ADR-115-store-and-menu-image-storage.md)
