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
| [ADR-073](ADR-073-order-point-accrual-snapshot.md) | Accepted | 주문 시점 일반 적립 snapshot과 frozen V1 trigger boundary |
| [ADR-074](ADR-074-global-and-store-scoped-point-accrual-policy.md) | Accepted | 전역 기본값과 매장별 일반 적립 정책 우선순위 |
| [ADR-075](ADR-075-single-operator-cancellation-refund-reconciliation.md) | Accepted | 고객 취소 terminal Refund의 단일 운영자 LOOKUP 재개 |
| [ADR-076](ADR-076-store-catalog-read-contract.md) | Accepted | 매장 카탈로그 조회 계약과 픽업 슬롯 예약 창 |
| [ADR-077](ADR-077-fast-reorder-order-creation-api-identity.md) | Accepted | 빠른 재주문의 새 Order 생성과 API identity |
| [ADR-078](ADR-078-toss-payments-sandbox-gateway-adapter.md) | Superseded | 토스페이먼츠 sandbox PaymentGateway adapter |
| [ADR-079](ADR-079-payment-method-token-management.md) | Accepted | 결제수단 등록·조회·폐기 lifecycle과 Provider Port |
| [ADR-080](ADR-080-toss-v2-one-time-payment-window.md) | Accepted | Toss V2 일회성 결제창과 Payment 시도 경계 |
| [ADR-081](ADR-081-support-context-case-and-query-boundary.md) | Accepted | Support Context, Case 중심 privileged action과 query boundary |
| [ADR-082](ADR-082-masked-purpose-bound-support-access.md) | Accepted | 기본 마스킹, staged verification과 purpose-bound PII access |
| [ADR-083](ADR-083-personal-data-encryption-and-blind-index.md) | Accepted | Vault Transit 개인데이터 암호화와 keyed blind index |
| [ADR-084](ADR-084-support-action-authorization-and-separation.md) | Accepted | risk-based Support action, exact approval binding과 Operations handoff |
| [ADR-085](ADR-085-lifecycle-aware-support-order-resolution.md) | Accepted | 주문 생명주기별 Support 변경과 post-acceptance resolution |
| [ADR-086](ADR-086-versioned-goodwill-compensation.md) | Accepted | versioned risk compensation과 goodwill source 분리 |
| [ADR-087](ADR-087-field-risk-and-purpose-specific-profile-change.md) | Accepted | R0-R4 field risk와 목적별 profile change |
| [ADR-088](ADR-088-canonical-delivery-provider-boundary.md) | Accepted | canonical DeliveryFulfillment, Provider ACL과 reconciliation |
| [ADR-089](ADR-089-purpose-based-retention-legal-hold-and-deletion.md) | Accepted | 목적별 retention, expiring LegalHold와 deletion replay |
| [ADR-090](ADR-090-support-console-frontend-and-sensitive-cache.md) | Proposed | Support Console frontend/trust boundary와 non-persistent sensitive state |
| [ADR-091](ADR-091-support-migration-queue-metadata.md) | Rejected | queue priority를 direct ExecPlan dependency로 표현하는 metadata 제안 |
| [ADR-092](ADR-092-hybrid-authentication.md) | Accepted | 고객·점주 Session과 운영자 Keycloak의 Hybrid 인증 |
| [ADR-093](ADR-093-merchant-credential-lifecycle.md) | Accepted | 점주 계정 자격증명 lifecycle과 최초 비밀번호 강제 변경 |
| [ADR-094](ADR-094-browser-session-security.md) | Accepted | 브라우저 Session 보안과 저장소 |
| [ADR-095](ADR-095-unified-current-actor.md) | Accepted | 인증 구현을 Application 계층에서 분리하는 CurrentActor |
| [ADR-096](ADR-096-public-order-reference.md) | Accepted | 내부 UUID와 분리한 공개 주문번호 |
| [ADR-097](ADR-097-store-pickup-number.md) | Accepted | 매장·영업일 단위 픽업번호와 동시 발급 |
| [ADR-098](ADR-098-order-display-snapshots.md) | Accepted | 주문 표시용 매장명·픽업 시간 스냅샷 |
| [ADR-099](ADR-099-customer-order-read-model.md) | Accepted | 고객 주문 목록의 Aggregate와 Read Model 분리 |
| [ADR-100](ADR-100-store-order-board-read-model.md) | Accepted | 점주 주문보드의 상태·픽업 시간 중심 Query |
| [ADR-101](ADR-101-payment-method-checkout-scope.md) | Accepted | 일회성 결제창과 저장 결제수단의 범위 분리 |
| [ADR-102](ADR-102-polling-before-sse.md) | Accepted | 주문보드 갱신을 조건부 Polling으로 시작 |
| [ADR-103](ADR-103-store-search-strategy.md) | Accepted | 매장 검색 전략과 추천 Baseline |
| [ADR-104](ADR-104-notification-inbox.md) | Accepted | 고객 알림함과 거래·마케팅 수신 설정 |
| [ADR-105](ADR-105-sandbox-settlement-payout.md) | Accepted | 실제 정산 지급을 Non-goal로 두고 sandbox 범위 명시 |
| [ADR-106](ADR-106-support-verification-and-data-access-grant.md) | Accepted | opaque challenge verification과 audit-gated DataAccessGrant reveal |
| [ADR-107](ADR-107-limited-coupon-issuance.md) | Accepted | 한정 쿠폰의 원자적 발급과 잔여 수량 표현 |
| [ADR-108](ADR-108-merchant-partial-refund-preview.md) | Accepted | 점주 부분 환불 preview와 공개 품목 식별 계약 |
| [ADR-109](ADR-109-customer-point-account-provisioning.md) | Accepted | 고객 가입과 PointAccount 원자 provisioning |
| [ADR-110](ADR-110-federated-operations-failure-queues.md) | Accepted | 소유 Context 기반 운영 실패 큐 연합 조회 |
| [ADR-111](ADR-111-productization-stack-a-draft-release.md) | Accepted | 제품화 Plan 00~60의 검증형 Draft PR 체인과 combined release gate |
| [ADR-112](ADR-112-store-brand-and-administrative-region.md) | Accepted | 매장 브랜드 Aggregate와 행정구역 어휘 |
