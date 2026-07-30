# Architecture Decision Records

| ADR | Status | Title |
|---|---|---|
| [ADR-001](ADR-001-modular-monolith.md) | Accepted | Modular Monolith로 시작 |
| [ADR-002](ADR-002-bounded-context-boundaries.md) | Accepted | Bounded Context 후보와 모듈 경계 |
| [ADR-003](ADR-003-aggregate-reference-by-id.md) | Accepted | Aggregate 간 ID 참조 |
| [ADR-004](ADR-004-order-price-snapshot.md) | Accepted | 주문 가격·메뉴·옵션 스냅샷 |
| [ADR-005](ADR-005-reservation-transaction-strategy.md) | Accepted | 초기 예약의 로컬 트랜잭션 |
| [ADR-006](ADR-006-external-payment-transaction-boundary.md) | Accepted | 외부 PG 호출과 DB 트랜잭션 분리 |
| [ADR-007](ADR-007-payment-idempotency-reconciliation.md) | Accepted | 결제 멱등성과 reconciliation |
| [ADR-008](ADR-008-settlement-adjustment-ledger.md) | Accepted | 확정 정산 조정 원장 |
| [ADR-009](ADR-009-explicit-failure-semantics.md) | Accepted | 실패를 숨기지 않는 의미론 |
| [ADR-010](ADR-010-initial-event-publication.md) | Accepted | 초기 이벤트 발행 방식 |
| [ADR-011](ADR-011-point-lot-ledger.md) | Accepted | PointLot과 포인트 원장 |
| [ADR-012](ADR-012-decision-capture-protocol.md) | Accepted | 질문 기반 결정 기록 절차 |
| [ADR-013](ADR-013-payment-unknown-reservation-expiry.md) | Accepted | 결제 결과 불명과 예약 만료의 경계 |
| [ADR-014](ADR-014-money-allocation-and-partial-refund.md) | Accepted | 정수 KRW 배분과 품목 부분 환불 |
| [ADR-015](ADR-015-store-acceptance-timeout-compensation.md) | Accepted | 매장 수락 timeout과 보상 흐름 |
| [ADR-016](ADR-016-benefit-only-payment.md) | Accepted | 0원 혜택 전용 결제 |
| [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md) | Accepted | 완료일 정산과 혜택 비용 배분 |
| [ADR-018](ADR-018-settlement-dispute-hold-and-refile.md) | Accepted | 정산 이의제기 held 금액과 재이의 |
| [ADR-019](ADR-019-notification-retry-and-manual-recovery.md) | Accepted | 알림 재시도와 수동 복구 |
| [ADR-020](ADR-020-nearby-location-privacy.md) | Accepted | 가까운 매장 검색과 정밀 위치 최소 보존 |
| [ADR-021](ADR-021-payment-method-tokenization.md) | Accepted | 결제수단 tokenization과 저장 금지 데이터 |
| [ADR-022](ADR-022-audit-record.md) | Accepted | 중요 변경의 append-only AuditRecord |
| [ADR-023](ADR-023-analytics-refund-and-late-events.md) | Accepted | 환불 지표와 late event 재집계 |
| [ADR-024](ADR-024-coupon-calculation-model.md) | Accepted | 대상 품목 합계 기반 쿠폰 계산 모델 |
| [ADR-025](ADR-025-order-creation-idempotency-transaction.md) | Accepted | 주문 생성 멱등 레코드의 선행 등록과 최초 응답 재생 |
| [ADR-026](ADR-026-menu-configuration-sellable-unit-mapping.md) | Accepted | MenuConfiguration의 sellable unit 요구량 번역 |
| [ADR-027](ADR-027-store-membership-authorization.md) | Accepted | 매장 membership 기반 객체 수준 인가 |
| [ADR-028](ADR-028-expired-benefit-restoration-policy.md) | Accepted | 버전형 만료 혜택 복원 정책 |
