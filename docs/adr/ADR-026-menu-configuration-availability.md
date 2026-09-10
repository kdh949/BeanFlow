# ADR-026: 메뉴 구성과 판매 상태 검증

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

고객은 메뉴와 옵션을 선택해 주문한다. 점주는 메뉴, 옵션과 판매 구성의 판매 가능 여부를 직접 관리한다.
동일한 옵션 집합은 요청 순서와 무관하게 같은 판매 구성을 의미해야 한다.

## Decision

- Merchant가 MenuConfiguration과 판매 가능 여부를 소유한다.
- Configuration은 menuId, 정규화한 option ID 집합과 available 상태를 가진다.
- option ID 집합은 ID 오름차순으로 정규화하며 같은 Menu에서 유일하다.
- OrderLine 순서는 BR-12 금액 배분 의미를 가지므로 유지한다.
- 메뉴와 옵션의 소속 및 구성 존재 여부를 검증한다. 잘못된 옵션 조합은 `400 INVALID_REQUEST`다.
- 존재하는 구성이 판매 불가하면 `409 MENU_CONFIGURATION_NOT_AVAILABLE`이다.
- 최종 주문 생성은 Store shared lock 아래 현재 판매 상태와 가격을 검증한다.
- 점주 변경은 같은 Store의 exclusive lock을 사용한다. 먼저 확정된 변경이 다음 주문에 반영된다.
- 주문에는 메뉴·옵션 이름, 가격과 선택 옵션의 불변 스냅샷을 저장한다.

### 고객 구성 선택 조회 (2026-09-11)

고객은 `GET /stores/{storeId}/menus/{menuId}/configurations`로 ACTIVE 메뉴의 현재 ACTIVE 구성을
조회한다. 응답은 configurationId, 정규화된 optionIds와 owner available flag다. 고객 화면은 메뉴·옵션·
구성의 판매 상태를 모두 확인하고 서버가 반환한 한 구성을 선택한다. 누락된 구성은 기본 구성으로 추측하지 않는다.
메뉴를 펼칠 때 조회하므로 매장 전체 목록에 구성마다 쿼리를 추가하지 않는다. 메뉴별 500개 상한은 ADR-118의
기존 authoring 상한이며, 501개인 손상 데이터는 partial success가 아닌 503이다. 다른 매장·보관 메뉴는 404다.
이 조회는 가격·예약 보장이 아니며 최종 주문의 기존 lock/quote 검증과 주문 snapshot은 유지한다.

## Alternatives Considered

옵션마다 독립적인 판매 여부만 확인하면 허용하지 않은 조합까지 주문할 수 있다.
정규화한 Configuration을 명시해 판매할 조합을 Merchant가 결정한다.

## Rationale

판매 상태의 소유권을 Merchant에 모으면 점주 변경과 고객의 최종 주문 사이에 일관된 검증 경계를 둘 수 있다.

## Consequences

- 점주가 품절과 판매 재개를 설정한다.
- 새 주문은 현재 판매 상태를 따르며 기존 주문의 가격·옵션 스냅샷은 변경되지 않는다.
- Configuration 없는 비판매 초안은 저장할 수 있다.

## Verification

- 옵션 순서가 달라도 같은 Configuration 조회
- 중복 옵션 및 다른 메뉴 옵션 거부
- 옵션 없는 기본 Configuration 주문
- 품절 설정 후 신규 주문 거절 및 판매 재개 후 주문 허용
- 점주 변경과 최종 주문의 Store lock 경합
- 카탈로그 변경 후 기존 주문 스냅샷 불변

## Metrics

- **Not measured:** Configuration 조회 지연

## Revisit Conditions

옵션 조합의 가격 또는 판매 규칙이 달라질 때

## Related Decisions

- BR-04
- [ADR-002](ADR-002-bounded-context-boundaries.md)
- [ADR-004](ADR-004-order-price-snapshot.md)
- [ADR-118](ADR-118-merchant-transactional-catalog-lifecycle.md)
