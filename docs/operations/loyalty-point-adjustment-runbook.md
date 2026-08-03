# Audited Loyalty Point Adjustment Runbook

## Scope

이 절차는 확인된 PointAccount·PointLot·원장 불일치를 감사형
`ADJUSTMENT` command로 바로잡는다. 일반 적립, 환불 복원·회수,
PointRecoveryPending 상계와 SettlementAdjustment를 대신하지 않는다. 직접 SQL로
Account/Lot/transaction을 수정하는 것은 허용된 대체 절차가 아니다.

## Activation prerequisites

- Flyway V14 issuer provenance, V17 recovery ledger와 V31 adjustment migration이 모두
  성공해야 한다. V31의 issuer precheck가 실패하면 endpoint를 활성화하지 않고 원본
  source evidence를 확정한다. `PLATFORM` 추정 backfill은 금지한다.
- 대상 actor는 유효한 `PLATFORM_OPERATOR` JWT role과 Operations의 active
  `POINT_ADJUSTMENT` grant를 모두 가져야 한다. grant는
  [offline bootstrap runbook](operator-permission-bootstrap-runbook.md)으로만 변경한다.
- 대상 PointAccount ID, signed nonzero 금액, 승인된 reason, 하나 이상의 immutable
  evidence reference와 새 `Idempotency-Key`를 준비한다.
- CREDIT이면 비용 주체 `PLATFORM|BRAND|STORE`, non-blank issuer reference와 현재보다
  엄격히 미래인 expiry를 명시한다. DEBIT에는 issuer와 expiry를 보내지 않는다.

## Command examples

양수 조정은 새 PointLot 하나를 만든다.

```http
POST /api/v1/operations/point-accounts/{accountId}/adjustments
Authorization: Bearer <operator JWT>
Idempotency-Key: <8..128 character key>
Content-Type: application/json

{
  "amountKrw": 125,
  "issuer": {
    "issuerType": "STORE",
    "issuerReference": "store:<approved cost owner>"
  },
  "expiresAt": "2030-01-01T00:00:00Z",
  "reason": "<approved correction reason>",
  "evidenceReferences": ["ticket:<immutable reference>"]
}
```

음수 조정은 만료되지 않은 available Lot만 `(expiresAt, pointLotId)` 순서로 차감한다.

```json
{
  "amountKrw": -125,
  "reason": "<approved correction reason>",
  "evidenceReferences": ["ticket:<immutable reference>"]
}
```

성공과 같은 key·같은 canonical payload replay는 같은 `201` body를 반환한다. timeout 뒤
결과가 불명확하면 payload를 바꾸거나 새 key로 다시 실행하지 않고 먼저 같은 key로
재조회한다. response에 replay 표시는 없다.

## Failure handling

| HTTP / code | Meaning | Required action |
|---|---|---|
| 400 `INVALID_REQUEST` | zero amount, conditional issuer/expiry, reason/evidence 또는 key 형식 오류 | 입력과 승인 증적을 수정하고 새 검토를 거친다 |
| 403 `ACCESS_DENIED` | role 또는 active grant 부재/revoke | JWT와 audited grant lifecycle을 확인한다. role fallback을 만들지 않는다 |
| 404 `RESOURCE_NOT_FOUND` | PointAccount 부재 | account 식별자를 다시 확인한다 |
| 409 `POINT_ADJUSTMENT_INSUFFICIENT_AVAILABLE` | unexpired available Lot 합 부족 | 부분 debit이나 pending으로 대체하지 않고 원장 불일치를 다시 조사한다 |
| 409 `IDEMPOTENCY_KEY_REUSED` | 같은 actor/key가 다른 account 또는 payload에 이미 사용됨 | 기존 command evidence를 확인하고 의도적으로 별도 command면 새 key를 발급한다 |
| 503 `DEPENDENCY_UNAVAILABLE` | grant lock, DB, Audit 또는 outbox commit 실패 | 성공으로 간주하지 않는다. 의존성을 복구한 뒤 같은 key·payload로 재시도한다 |

실패한 command에서 Account, Lot, transaction, idempotency, Audit 또는 outbox 일부만
남아 있으면 정상 상태가 아니다. 해당 source에 추가 SQL 보정을 적용하지 않고 release를
중단해 원자성 위반을 조사한다.

## Read-only verification

raw key, evidence body와 issuer reference를 log나 metric에 복제하지 않는다. 승인된 운영
세션에서 target account와 반환된 opaque source로만 결합을 확인한다.

```sql
SELECT t.id, t.point_account_id, t.point_lot_id, t.amount_krw,
       t.balance_effect, t.source_reference, t.occurred_at
FROM loyalty_point_transaction t
WHERE t.type = 'ADJUSTMENT'
  AND t.point_account_id = :account_id
ORDER BY t.occurred_at, t.id;
```

```sql
SELECT a.action, a.target_type, a.target_id, a.occurred_at,
       a.correlation_id, a.source_reference
FROM operations_audit_record a
WHERE a.action = 'POINT_ADJUSTMENT_APPLIED'
  AND a.target_id = :account_id
ORDER BY a.occurred_at, a.id;
```

```sql
SELECT listener_id, status, completion_attempts, publication_date
FROM event_publication
WHERE event_type = 'io.github.kdh949.beanflow.eventing.api.PointsAdjustedV1'
ORDER BY publication_date, id;
```

Analytics target의 미완료 publication은 adjustment를 되돌리는 근거가 아니다. Loyalty
command는 이미 commit됐으며 Analytics owner의 retry/receipt 절차로 수렴시킨다.

## Retention and observability

- terminal command response는 `created_at + 90일`까지 Loyalty table에 보존된다.
  worker는 기본 1시간마다 due row를 최대 100개 삭제한다. 실패 시 row를 남기고 다음
  tick에 재시도한다. 수동 조기 삭제 API는 없다.
- 감시 metric은
  `beanflow.loyalty.point_adjustment.command.count{direction,outcome}`,
  `beanflow.loyalty.point_adjustment.amount_krw{direction}`,
  `beanflow.loyalty.point_adjustment.idempotency_retention.count{outcome}`와 Plan 10의
  `beanflow.loyalty.issuer_precheck.count{outcome}`이다.
- actor, account, Lot, raw key, evidence와 issuer reference는 metric tag나 일반 log field로
  사용하지 않는다.

## Migration and rollback

V31은 forward-only다. 적용된 migration을 수정하거나 checksum repair, column/table drop,
기존 transaction 방향 재추정을 수행하지 않는다. 배포 전 non-empty database에서 current
transaction type의 `balance_effect` mapping과 PointLot issuer precheck를 검증한다. activation
실패 또는 application rollback이 필요하면 V31 schema는 유지한 채 endpoint traffic을 멈추고
호환되는 이전/보정 artifact 계획을 별도 승인한다.
