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
| [ADR-029](ADR-029-customer-cancellation-scope.md) | Accepted | 고객 주문 취소 범위와 보상 경계 |
| [ADR-030](ADR-030-customer-cancellation-authorization.md) | Accepted | 고객 취소 인가와 보상 진행 조회 범위 |
| [ADR-031](ADR-031-customer-cancellation-api-contract.md) | Accepted | 고객 취소 API 계약과 동기·비동기 성공 표현 |
| [ADR-032](ADR-032-customer-cancellation-idempotency.md) | Accepted | 고객 취소 명령의 멱등성 모델과 멱등 레코드 수명 |
| [ADR-033](ADR-033-order-compensation-case-generalization.md) | Accepted | 주문 보상 Case 일반화와 환불 요약 파생 원천 |
| [ADR-034](ADR-034-customer-cancellation-event-contract.md) | Accepted | 고객 취소와 매장 거절의 이벤트 타입 분리 |
| [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md) | Accepted | 결제 후 고객 취소의 202 내구 저장 경계 |
| [ADR-036](ADR-036-cancellation-after-partial-refund.md) | Accepted | 선행 부분 환불 후 고객 취소의 잔액 환불 |
| [ADR-037](ADR-037-customer-cancellation-refund-reconciliation-budget.md) | Accepted | 고객 취소 환불의 요청·조회 예산 |
| [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md) | Accepted | 재시도 가능 환불 실패와 고객 상태 투영 |
| [ADR-039](ADR-039-benefit-only-cancellation-payment-step.md) | Accepted | 0원 결제 취소의 PAYMENT 보상 표현 |
| [ADR-040](ADR-040-order-termination-resource-release.md) | Accepted | 주문 종료 후 확정 픽업·재고 복원 상태 |
| [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md) | Accepted | 종료 원인·혜택별 만료 복원 정책 |
| [ADR-042](ADR-042-benefit-restoration-ledger-metadata.md) | Accepted | 혜택 복원 원장의 원인·정책 metadata |
| [ADR-043](ADR-043-compensation-coupon-terms-snapshot.md) | Accepted | 종료 Campaign과 독립적인 보상 쿠폰 조건 snapshot |
| [ADR-044](ADR-044-cancellation-accepted-notification-durability.md) | Accepted | 고객 취소 접수 알림의 내구 저장 경계 |
| [ADR-045](ADR-045-cancellation-refund-customer-notifications.md) | Accepted | 고객 취소 환불의 성공·지연 후속 알림 |
| [ADR-046](ADR-046-cancellation-refund-notification-events.md) | Accepted | 고객 취소 환불 후속 알림의 영속 결과 이벤트 경계 |
| [ADR-047](ADR-047-primary-notification-compensation-step.md) | Accepted | 주문 보상 Case의 기본 고객 알림 step 범위 |
| [ADR-048](ADR-048-preacceptance-cancellation-settlement-exclusion.md) | Accepted | 매장 수락 전 고객 취소 환불의 정산 제외 증적 |
| [ADR-049](ADR-049-compensation-coupon-cost-attribution.md) | Accepted | 보상 쿠폰의 원 Campaign 비용 부담 승계 |
| [ADR-050](ADR-050-setup-incomplete-customer-projection.md) | Accepted | 환불 준비 손상의 고객 지연 투영과 운영 노출 |
| [ADR-051](ADR-051-setup-integrity-detection.md) | Accepted | 고객 취소 환불 준비 손상의 즉시·주기 탐지 |
| [ADR-052](ADR-052-safe-setup-repair-scope.md) | Accepted | 고객 취소 Refund 누락의 제한적 안전 복구 |
| [ADR-053](ADR-053-two-person-setup-repair-approval.md) | Accepted | 누락 Refund 복구의 2인 승인 |
| [ADR-054](ADR-054-customer-cancellation-audit-granularity.md) | Accepted | 고객 취소의 target별 append-only 감사 |
| [ADR-055](ADR-055-order-cancelled-event-data-minimization.md) | Accepted | OrderCancelledV1의 미사용 고객·매장·사유 필드 제거 |
| [ADR-056](ADR-056-ordering-idempotency-retention-worker.md) | Accepted | Ordering 명령 멱등 레코드의 통합 보존 worker |
| [ADR-057](ADR-057-idempotent-response-replay-indicator.md) | Accepted | 멱등 명령 응답의 replay 표시 제거 |
| [ADR-058](ADR-058-paid-cancellation-deadline-timeout-work.md) | Accepted | 기한 후 PAID 고객 취소의 즉시 timeout work |
| [ADR-059](ADR-059-pre-release-compensation-clean-cutover.md) | Accepted | OrderCompensation의 pre-release clean cutover |
| [ADR-060](ADR-060-customer-cancellation-implementation-scope.md) | Accepted | 고객 취소 구현의 MVP 범위와 비목표 |
| [ADR-061](ADR-061-refund-requested-and-confirmed-amounts.md) | Accepted | Refund 요청 금액과 성공 확정 금액 분리 |
| [ADR-062](ADR-062-settlement-batch-item-discovery.md) | Accepted | 정산 Batch별 Item 조회와 이의제기 식별 경로 |
| [ADR-063](ADR-063-partial-refund-expired-point-restoration.md) | Accepted | 부분 환불의 만료 포인트 30일 보상 복원 |
| [ADR-064](ADR-064-risk-based-idempotency-model-selection.md) | Accepted | 위험 기반 멱등성 모델 선택 |
| [ADR-065](ADR-065-refund-earned-point-recovery-ledger.md) | Accepted | 환불 적립 포인트 회수 원장과 `RECOVERY` transaction |
| [ADR-066](ADR-066-audited-loyalty-point-adjustment.md) | Accepted | 감사형 Loyalty 포인트 조정 |
| [ADR-067](ADR-067-settlement-batch-creation-and-schema-ownership.md) | Accepted | Settlement Batch 최소 생성과 스키마 소유권 |
| [ADR-068](ADR-068-immutable-integration-event-snapshots.md) | Accepted | Immutable integration event snapshot 계약 |
| [ADR-069](ADR-069-operator-permission-grants-and-audited-policy-read.md) | Accepted | Operator permission grant와 감사형 정책 조회 |
| [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md) | Accepted | Versioned HMAC cursor와 pagination 상한 |
| [ADR-071](ADR-071-settlement-input-snapshot-foundation.md) | Accepted | 정산 입력 snapshot의 원천과 주문 시점 물질화 |
| [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md) | Accepted | 무인 ExecPlan 실행과 migration writer lane |
