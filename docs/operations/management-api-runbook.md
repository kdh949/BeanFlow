# 관리 API 운영 절차

API 추가가 운영 grant 발급이나 배포를 수행하지는 않는다. 기존 운영 권한 발급 절차에서 목적별 grant를
명시적으로 발급해야 한다. 원장 replay도 현재 grant 또는 ACTIVE membership을 다시 확인한다.

## 정산 이의제기

1. `GET /api/v1/operations/settlement-disputes?storeId={storeId}`로 매장별 이의를 조회한다.
   `SETTLEMENT_DISPUTE_READ`가 필요하며 state, signed cursor, limit(1~100)을 사용할 수 있다.
2. 상세 `GET /api/v1/operations/settlement-disputes/{disputeId}`에서 state, version, 근거와 pendingDecision을 확인한다.
3. FILED이면 `POST .../{disputeId}/reviews`에 `Idempotency-Key`와 `{expectedVersion, reason}`을 보내 검토를 시작한다.
   자동 review worker가 이미 전환했다면 최신 version을 다시 읽는다.
4. `POST .../{disputeId}/decisions`에 `{outcome: ACCEPTED|REJECTED, expectedVersion, reason}`을 보낸다.
   검토·판정에는 `SETTLEMENT_DISPUTE_DECIDE`가 필요하다. 조정액은 접수된 원본 금액을 사용한다.
5. 503 또는 응답 유실이면 **같은 키와 같은 payload**로 재시도한다. 의도 저장 후 실패하면 상세는
   UNDER_REVIEW와 pendingDecision을 반환한다. 다른 판정·철회는 409이며 DB에서 intent를 지우지 않는다.
   다른 권한 있는 운영자도 최신 version으로 같은 pending 판정을 접수해 재개할 수 있다.
   실제 판정 Audit는 최초 intent actor/reason/time을 보존하고 새 재개 요청 Audit는 재개 actor를 기록한다.
6. 점주는 `GET /api/v1/stores/{storeId}/disputes/{disputeId}`로 상세를 확인하고 UNDER_REVIEW에서만
   `POST .../{disputeId}/withdrawals`를 요청한다. OWNER membership, Merchant Session, CSRF,
   Idempotency-Key와 `{expectedVersion, reason}`이 필요하다.

승인은 동일 `dispute:{id}:accepted` source의 SettlementAdjustment를 재사용한다. accepted intent가 먼저
commit되므로 Adjustment 이후 event/Audit 실패가 반대 판정으로 바뀌지 않는다. 성공은 response 원장과
판정·Audit·event publication 저장까지 commit된 상태다. listener의 후속 성공은 별도 event 복구 상태로 확인한다.

완료 command response는 90일 후 최대 100행씩 삭제하며 pending command는 삭제하지 않는다.
판정 intent와 감사 이력은 command cleanup과 함께 삭제하지 않는다. 원래 이의 접수·재이의·보류액 정책은 유지한다.
