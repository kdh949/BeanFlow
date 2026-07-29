# Quality Evidence Map

| System risk | Design mechanism | Verification | Durable evidence |
|---|---|---|---|
| 중복 결제 | Idempotency record, unique constraint, UNKNOWN | 동시 재요청·응답 유실 테스트 | ADR, 통합 테스트 |
| 0원 주문의 외부 결제 우회·부분 확정 | BENEFIT_ONLY Payment, Provider 없는 전용 service, 동일 Tx owner confirm | `BenefitOnlyOrderCreationTest`의 0/1원 분기·동시 key·confirmation fault | BR-11, ADR-016, PostgreSQL Testcontainers 결과 |
| PG 성공 후 내부 기록 실패 | transaction split, reconciliation | fault injection | 장애 보고서 |
| 만료 후 뒤늦은 결제 승인 | guarded expiry, late-approval void/refund | `ReservationExpiryTest`의 5분 경계·동시 만료·rollback | BR-03, ADR-013, Testcontainers 결과 |
| 환불 결과 불명 은폐 | Refund UNKNOWN/RECONCILING | Provider timeout·ACK 유실 | 상태·운영 case |
| 슬롯·재고 초과 | reservation, owner row lock, DB constraint | `PickupReservationRepositoryTest`, `StockReservationRepositoryTest`, `ReservationExpiryTest` | PostgreSQL Testcontainers 결과 |
| 쿠폰 이중 사용 | issuance state, unique constraint | two-order contention | Testcontainers test |
| 포인트 만료·복원 오류 | PointLot, ledger, Clock | 경계·환불 테스트 | ADR, tie-out |
| 확정 정산 변경 | immutable item/batch, adjustment | post-confirm refund | 원장·금액 tie-out |
| 중복 이벤트 | consumer idempotency | duplicate delivery | module/integration test |
| 알림 실패 은폐 | persistent delivery state | timeout·manual review | metric/runbook |
| N+1 | use-case fetch plan | SQL count, plan | Before/After report |
| 권한 우회 | role + ownership | cross-store access test | authorization matrix |
| 암묵적 fallback | fail-fast and explicit states | startup/dependency failure | failure semantics ADR |
| 감사 누락·민감정보 저장 | target별 동일 transaction append, 민감 key 거부, retention worker | `AuditRecordTest`, 주문 생성·만료 audit assertion | BR-30, ADR-022, Testcontainers 결과 |
| 문서 drift | contract checks and decision protocol | required file, BR, ADR, link, OpenAPI ref 검사 | ADR/Policy history, verify-docs output |
