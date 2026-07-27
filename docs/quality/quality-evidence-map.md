# Quality Evidence Map

| System risk | Design mechanism | Verification | Durable evidence |
|---|---|---|---|
| 중복 결제 | Idempotency record, unique constraint, UNKNOWN | 동시 재요청·응답 유실 테스트 | ADR, 통합 테스트 |
| PG 성공 후 내부 기록 실패 | transaction split, reconciliation | fault injection | 장애 보고서 |
| 슬롯·재고 초과 | reservation, conditional update/lock | concurrency test | SQL·lock 결과 |
| 쿠폰 이중 사용 | issuance state, unique constraint | two-order contention | Testcontainers test |
| 포인트 만료·복원 오류 | PointLot, ledger, Clock | 경계·환불 테스트 | ADR, tie-out |
| 확정 정산 변경 | immutable item/batch, adjustment | post-confirm refund | 원장·금액 tie-out |
| 중복 이벤트 | consumer idempotency | duplicate delivery | module/integration test |
| 알림 실패 은폐 | persistent delivery state | timeout·manual review | metric/runbook |
| N+1 | use-case fetch plan | SQL count, plan | Before/After report |
| 권한 우회 | role + ownership | cross-store access test | authorization matrix |
| 암묵적 fallback | fail-fast and explicit states | startup/dependency failure | failure semantics ADR |
| 문서 drift | contract tests and decision protocol | PR checklist | ADR/Policy history |
