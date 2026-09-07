# ADR-123: 주문 견적의 거래 조건과 공유 자원 잔여량 검증 분리

- **Status:** Accepted
- **Date:** 2026-09-07
- **Supersedes:** [ADR-116](ADR-116-non-reserving-order-quote.md)
- **Implementation owner:** [공유 자원 변동에 안정적인 주문 견적](../exec-plans/completed/order-quote-shared-resource-stability.md)

## Context

ADR-116의 v2 fingerprint에는 재고 available/reserved/confirmed quantity와 version,
픽업 슬롯 reserved/confirmed count와 version이 포함된다. 같은 자원을 쓰는 다른 고객의 주문이
그 사이 예약하면 가격·구성·혜택·픽업 시간과 실제 잔여량이 충분해도 `ORDER_QUOTE_STALE`이 된다.
비예약 견적의 조건 확인이 공유 자원의 모든 갱신을 거절하는 조건으로 확장된 것이다.

[2026-09-07 실측](../quality/performance-load-rca-2026-09-07.md)에서 1 workflow/s의 stale는
2/90, 5 workflow/s에서는 48/450이었다. 별도 두 견적→첫 주문→두 번째 주문 재현에서도
동일 표시 내용의 두 번째 주문이 거절됐다. 이 수치 자체가 처리 용량 한계나 재고·슬롯 각각의
단독 원인을 증명하지는 않는다. 후속 회귀 테스트는 두 owner를 독립적으로 검증한다.

## Decision

### 1. 비예약 견적과 거래 조건의 전체 비교를 유지한다

ADR-116의 customer Session/CSRF, editable input과 응답 shape, `guarantee=NONE`, quote의
무부수효과·무예약·Provider 미호출 경계를 그대로 계승한다. quote는 재고나 가격 보장이 아니다.
정규화 입력, 메뉴·옵션 구성/표시/가격, 재고 식별자·필요 수량, Store 주문 정책·표시명,
쿠폰 조건·비용 귀속, 포인트 lot/issuer provenance, 정산·적립 정책과 금액 배분 전체를 비교한다.
[ADR-118](ADR-118-merchant-transactional-catalog-lifecycle.md)의 Store root shared/exclusive lock과
Merchant 거래 의미 비교를 유지한다. 금액만 비교하거나 최신 견적을 자동 수락하지 않는다.

### 2. 사용량은 fingerprint가 아니라 잠금 아래의 가용성 검사로 보호한다

`order-quote-fingerprint/v3`는 다음 필드를 canonical material에서 제외한다.

- Inventory: `availableQuantity`, `reservedQuantity`, `confirmedQuantity`, `version`
- Fulfillment: `reservedCount`, `confirmedCount`, `version`

재고의 Store·sellable unit 식별자와 필요 수량, 픽업 슬롯의 식별자·Store·시작/종료·정원은 유지한다.
정원 변경은 운영 정책 변경이므로 재확인 대상이다. 고객별 쿠폰·포인트 version 비교는 유지한다.
다른 주문의 예약·확정·해제나 재고 보충만으로 유효한 거래 조건의 견적을 stale로 만들지 않는다.

최종 `OrderCreationTransaction`은 기존 순서대로 owner row를 잠근 뒤 `StockQuoteOperations`와
`PickupQuoteOperations`에서 **현재** 잔여량·시간·소유 Store를 검사한다. 자원이 충분하고 v3가
일치할 때만 같은 transaction에서 자원을 예약하고 immutable Order snapshot을 저장한다.
재고 부족은 `STOCK_NOT_AVAILABLE`, 슬롯 소진은 `PICKUP_SLOT_FULL`, 시작된 슬롯은 기존
`ORDER_STATE_CONFLICT`다. 잠금·검사·예약을 분리하거나 초과 예약을 허용하지 않는다.

### 3. 실패와 멱등성 의미를 계승한다

유효한 현재 거래 조건이 이전 견적과 다르면 `409 ORDER_QUOTE_STALE`과 `currentQuote`를 반환한다.
모든 거래 write는 rollback하고 BR-25의 terminal 실패 status/body를 별도 원장에 저장한다.
같은 key·payload는 최초 응답을 그대로 재생한다. 새로운 조건을 명시적으로 확인한 요청은 새
fingerprint와 새 `Idempotency-Key`를 사용한다. 같은 key에 변경한 fingerprint는
`IDEMPOTENCY_KEY_REUSED`다. malformed fingerprint는 400, 의존성/terminal 실패 저장 오류는
기존 typed 5xx이며 fallback 성공이나 자동 quote 재시도는 추가하지 않는다.

Toss 일회성 결제, reservation lease/확정 시점, UNKNOWN/reconciliation, API 인가와 고객별
idempotency scope도 그대로다. fingerprint·고객/주문 ID·금액을 telemetry label에 기록하지 않는다.

### 4. 배포와 롤백

canonical 필드가 바뀌므로 prefix를 v3로 올리고 quote와 주문 생성 함수를 같은 API 이미지로 배포한다.
API shape와 DB schema는 바뀌지 않는다. 배포 전에 받은 v2 견적은 stale 응답 후 재조회·명시적
재확인과 새 key가 필요하다. 이미 terminal인 주문 응답은 다시 계산하지 않고 계속 재생한다.
이전 이미지로 롤백할 때도 반대 방향으로 기존 미제출 v3 견적을 재조회해야 한다. 여러 버전의
API를 동시에 서비스하지 않고 이 단일 API 배포의 health/이미지 digest를 확인한다.

## Alternatives Considered

- 기존 전체 사용량 fingerprint 유지: 재고가 충분한 경쟁 주문을 불필요하게 거절하므로 제외한다.
- client의 stale 자동 재견적/재시도: 실제 가격·혜택 변경까지 자동 수락할 수 있어 제외한다.
- 금액만 비교: 동일 금액의 구성·비용 귀속 변경을 놓치므로 제외한다.
- 견적 시 자원 hold: 별도 만료·해제·중복 처리 lifecycle이 필요하고 BR-49 비예약 요구와 다르다.
- owner 잠금 제거: 초과 판매 위험이 있으므로 제외한다.

## Consequences

충분한 자원의 사용량 경합은 고객 재확인 사유가 되지 않는다. 고부하 시 owner row 잠금은 여전히
직렬화 지점이므로 이 변경만으로 처리 용량 증가를 주장하지 않는다. 슬롯 정원 등 실제 정책 변경,
재고 부족, 외부 실패는 여전히 명시적으로 실패한다. 새 dependency나 migration은 없다.

## Verification

- Inventory만 갱신하는 경우와 Fulfillment만 갱신하는 경우를 각각 재현해 사전 견적 성공을 검사한다.
- owner 예약·확정·해제 전이 뒤 충분한 자원의 견적 안정성을 검증한다.
- 동시에 미리 발급한 두 고객의 견적으로 충분한 경우 둘 다 성공하고, 마지막 재고/슬롯에서는
  정확히 하나만 성공하며 카운터·reservation·Order 수가 일치하는지 PostgreSQL에서 확인한다.
- 기존 가격·옵션·혜택·정원 변경 stale, 무예약 quote, writer 직렬화와 terminal replay를 회귀 검증한다.
- 동일 k6 스크립트/부하/관측 설정의 배포 전후 결과에서 stale·workflow 성공·지연·DB wait를 비교한다.
  누적 DB 상태와 background worker 차이는 명시하고 고부하 용량과 개선율을 추정하지 않는다.

## Revisit Conditions

예약 보장 quote, owner 저장소 분리, 실제 정책과 사용량을 함께 가진 추가 version 또는 shared row
경합이 실측 병목이 될 때 별도 정책과 serialization 설계를 검토한다.

## Related Decisions

- BR-05, BR-25, BR-49
- ADR-005, ADR-009, ADR-025, ADR-057, ADR-118, ADR-121
