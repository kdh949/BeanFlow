# Quality Evidence Map

| System risk | Design mechanism | Verification | Durable evidence |
|---|---|---|---|
| 중복 결제 | Payment IdempotencyRecord insert-first arbitration, Order당 Payment/Provider reference unique, UNKNOWN 조회 | `PaymentConfirmationIntegrationTest`의 동일 key 동시 요청·다른 payload·현재 상태 replay | ADR-007, PostgreSQL Testcontainers 결과 |
| 0원 주문의 외부 결제 우회·부분 확정 | BENEFIT_ONLY Payment, Provider 없는 전용 service, 동일 Tx owner confirm | `BenefitOnlyOrderCreationTest`의 0/1원 분기·동시 key·confirmation fault | BR-11, ADR-016, PostgreSQL Testcontainers 결과 |
| PG 성공 후 내부 기록 실패 | Tx1/Provider/Tx2 분리, Tx1에서 due reconciliation 생성 | owner fault rollback, claim lease 재시작, Hikari pool 1 테스트 | `PaymentConfirmationIntegrationTest`, `PaymentConnectionBoundaryTest` |
| 만료 후 뒤늦은 결제 승인 | Order lock과 guarded expiry, late-approval void/refund | 5분 경계 동시 실행, Order 비복구, void/refund 단일 실행 | BR-03, ADR-013, `PaymentConfirmationIntegrationTest` |
| 환불 결과 불명 은폐 | Refund UNKNOWN/RECONCILING | Provider timeout·ACK 유실 | 상태·운영 case |
| 고객 취소의 부분 성공·허위 202 | Tx C0/C1/CT별 Order와 owner/Refund/Delivery/Audit/publication commit gate | `CustomerCancellationCommandIntegrationTest`의 저장 지점별 fault, replay와 수락·만료 race | ADR-035/044/058, V23 PostgreSQL constraints |
| 고객 취소 Refund 재요청·복구 예산 혼합 | REQUEST allowlist 3회와 UNKNOWN 이후 LOOKUP 5회 분리, claim lease recovery | `RefundStateTest`, `RejectionRefundRepositoryTest`, customer cancellation payment tests | ADR-037/038, V15/V24 constraints와 worker metric |
| terminal 고객 취소 Refund의 근거 없는 수기 성공·중복 REQUEST | 전용 grant, 완전 원천 재검증, 단일 operator marker와 기존 key LOOKUP-only 명령 | `PaymentSetupRepairIntegrationTest`의 grant/replay/concurrency/unknown/success/Audit rollback과 Provider call count | ADR-075, V27 command store·Refund CHECK, cancellation runbook |
| 고객 취소 setup 손상 은폐·위험한 재구성 | inline+batch detector, source-unique Case/Audit, 완전 snapshot+Refund 누락만 2인 LOOKUP-only repair | `PaymentSetupRepairIntegrationTest`의 self/stale/expiry/concurrent/fault/DTO, setup scanner tests | ADR-050~053, V25/V26 constraints, cancellation runbook |
| 금융 outbox가 복구 worker 대상에서 누락 | 최초 target row를 `FAILED`/attempt 0으로 저장해 bounded recovery가 실제 consumer를 선택 | 운영자 delayed→success 통합 테스트의 recovery worker→Notification/Settlement 완료 | ADR-068, `FinancialEventPublicationService`, event-publication metric/runbook |
| 환불 terminal 알림 유실·기본 step 재개방 | result transaction의 전용 persistent publication, version logical source Delivery | `RejectionRefundRepositoryTest`, `NotificationDeliveryRepositoryTest`의 replay/conflict와 delayed→success | ADR-045/046, event catalog |
| 고객 취소 clean-cutover 오판 | 외부 DB/publication/consumer/rollback fact gate | 항목별 0 운영 상태 attestation, 구현·배포 전 재확인, unknown/nonzero 차단 | ADR-059, customer cancellation release evidence와 readiness report |
| 부분 환불 뒤 이중 환불·포인트 복원 또는 쿠폰 복원 누락 | line-level cash/point restoration, coupon attribution과 source unique | 선행 부분 환불 후 취소 tie-out·원 쿠폰 단일 복원 | ADR-036, allocation foundation ExecPlan |
| 미완료 고객 취소의 허위 정산 | 실제 Order/Refund evidence, SettlementItem 부재와 source-unique NOT_APPLICABLE Audit | `CustomerCancellationRefundExclusionIntegrationTest`의 cause/reason/source/version/amount/time, replay, 기존 Item, Audit rollback과 publication completion | ADR-048, completed Settlement foundation ExecPlan |
| 완료 event 중복·지연으로 정산 Item 유실/중복 | V2 immutable payload, store/date OPEN Batch insert-or-read, order/source unique Item, closed-Batch 명시적 case | `SettlementItemCreationIntegrationTest`, `SettlementFoundationMigrationTest`, `SettlementItemQueryIntegrationTest` | ADR-017/062/067/068, V21 PostgreSQL constraints |
| Batch 집계 중복·불완전 summary·음수 이월 재적용 | Batch row lock, 500건 keyset snapshot 합산, 이전 confirmed gate, calculation-time Adjustment cursor와 carry source | `SettlementBatchLifecycleIntegrationTest`의 concurrent/restart/multi-store/서울 경계/tie-out/carry와 고정 measurement fixture | ADR-008/017/067, V28/V29 CHECK·transition trigger |
| 확정 후 Refund/Dispute가 과거 원장을 변경하거나 중복 Adjustment 생성 | confirmed target public view, append-only Adjustment, source/reason unique, Audit/outbox commit gate | `SettlementRefundAdjustmentIntegrationTest`, `SettlementDisputeIntegrationTest`, Plan 20 exclusion regression | ADR-008/017/018/068, V28 immutable trigger |
| 이의기한·권한·재이의 우회 또는 accepted handoff 부분 성공 은폐 | OWNER membership, 서울 half-open window, Item/actor-key advisory lock, active partial unique, 실제 새 evidence 1회, Adjustment 선커밋+Case | fixed Clock/API/concurrency/refile/Audit-publication fault와 exact retry 통합 테스트 | BR-22~24, ADR-018, V28/V30, settlement lifecycle runbook |
| 슬롯·재고 초과 | reservation, owner row lock, DB constraint | `PickupReservationRepositoryTest`, `StockReservationRepositoryTest`, `ReservationExpiryTest` | PostgreSQL Testcontainers 결과 |
| 쿠폰 이중 사용 | issuance state, unique constraint | two-order contention | Testcontainers test |
| 포인트 만료·복원 오류 | PointLot, ledger, Clock | 경계·환불 테스트 | ADR, tie-out |
| 확정 정산 변경 | immutable item/batch, adjustment | post-confirm refund/dispute와 DB mutation 거부 | 원장·금액 tie-out |
| 중복 이벤트 | consumer idempotency | duplicate delivery | module/integration test |
| 알림 실패 은폐 | persistent delivery state | timeout·manual review | metric/runbook |
| N+1 | use-case fetch plan | SQL count, plan | Before/After report |
| 권한 우회 | role + ownership | cross-store access test | authorization matrix |
| 암묵적 fallback | fail-fast and explicit states | startup/dependency failure | failure semantics ADR |
| 감사 누락·민감정보 저장 | target별 동일 transaction append, 민감 key 거부, retention worker | `AuditRecordTest`, 주문 생성·만료 audit assertion | BR-30, ADR-022, Testcontainers 결과 |
| 문서 drift | contract checks and decision protocol | required file, BR, ADR, link, OpenAPI ref 검사 | ADR/Policy history, verify-docs output |
