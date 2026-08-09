# Order Reservation Lease Runbook

## Scope

이 문서는 `PENDING_PAYMENT` Order의 5분 reservation lease, expiry worker,
AuditRecord retention worker와 주문 생성 IdempotencyRecord를 운영할 때 사용하는
탐지·재시도 기준을 정리한다. 데이터베이스 값을 직접 변경해 성공으로 보이게 하거나
in-memory/local fallback을 활성화하지 않는다.

## Reservation expiry

정상 worker는 다음 조건의 Order ID를 deadline과 ID 순서로 제한된 chunk만 조회하고,
각 Order를 독립 transaction으로 만료한다.

```sql
SELECT id, reservation_expires_at
FROM ordering_order
WHERE state = 'PENDING_PAYMENT'
  AND reservation_expires_at <= now()
ORDER BY reservation_expires_at, id
LIMIT 100;
```

관측 항목:

- `beanflow.reservation.due.count`: 현재 worker 조회 시점의 due Order 수
- `beanflow.reservation.expiry{outcome=expired|failed|not_due|not_eligible}`:
  처리 결과
- `beanflow.reservation.expiry.lag.seconds`: 처리 시점과 deadline의 차이
- `beanflow.reservation.expiry.chunk.duration`: 한 chunk 처리 시간
- `reservation_expiry`와 `reservation_expiry_worker` structured log: order ID,
  outcome, deadline, correlation ID

due count 또는 expiry lag가 계속 증가하면 먼저 PostgreSQL 연결, row-lock 대기,
`reservation_expiry_worker ... outcome=FAILED` 로그와 owner reservation의 누락·terminal
상태를 확인한다. 실패한 Order는 다음 schedule 또는 소유 고객의 GET/향후 결제
명령에서 같은 idempotent expiry transaction으로 재시도된다. worker process를
재시작해도 수량 복원은 한 번만 적용된다.

DB row를 수동으로 `EXPIRED`로 바꾸거나 owner 수량을 보정하지 않는다. owner row 누락,
source mismatch 또는 수량 불일치는 자동 복구 대상이 아니며 incident로 격리하고
원인과 영향 범위를 확인한 뒤 별도 승인된 복구 절차를 사용한다.

## Audit retention

AuditRecord는 `occurredAt`을 Asia/Seoul 달력 시각으로 변환한 5주년까지 보존한다.
retention worker만 due record를 정렬된 chunk로 삭제하며 일반 애플리케이션 API는
수정·삭제 기능을 제공하지 않는다.

관측 항목:

- `beanflow.audit.retention.deleted`: 삭제 건수
- `beanflow.audit.retention.failure`: cleanup 실패 건수
- `beanflow.audit.retention.oldest_due_age.seconds`: 처리한 가장 오래된 due record의
  지연
- `audit_retention` structured log: outcome, 삭제 건수, oldest due 시각

cleanup 실패는 성공이나 0건으로 기록하지 않는다. 원인을 해결한 뒤 worker를
재실행하며, due 이전 record를 조기 삭제하지 않는다. legal hold나 별도 보존 요구가
생기면 worker 설정으로 우회하지 말고 BR-30과 ADR-022를 먼저 변경한다.

## Stuck idempotency records

다음 조회는 configured threshold를 넘겨 `PROCESSING`에 머문 주문 생성·빠른 재주문 요청을
찾기 위한 read-only 진단 예시다.

```sql
SELECT id, operation, intended_order_id, started_at
FROM ordering_idempotency_record
WHERE operation IN ('CREATE_ORDER', 'REORDER_ORDER_V1')
  AND status = 'PROCESSING'
ORDER BY started_at, id;
```

기본 5분 threshold를 넘은 row는 reconciliation worker가 intended Order 존재 여부를 확인하고
`MANUAL_REVIEW`로 격리한다. 자동 주문 실행이나 terminal response 재구성은 하지 않는다.
`MANUAL_REVIEW` same-key API는 `IDEMPOTENCY_MANUAL_REVIEW_REQUIRED`를 `Retry-After` 없이
반환한다. record를 삭제하거나 FAILED/COMPLETED로 직접 변경하지 않는다. 상세 metadata,
read-only 조사와 V36 배포 절차는
[Fast Reorder Idempotency and V36 Runbook](fast-reorder-runbook.md)을 따른다. 공식 운영자
mutation API는 아직 구성되지 않았다.

## Configuration assumptions

- reservation expiry chunk: 100
- reservation expiry fixed delay: 30 seconds
- AuditRecord retention chunk: 100
- AuditRecord retention fixed delay: 1 hour

이 값은 측정된 용량이 아니라 초기 운영 가정이다. 변경 전 backlog, lock wait,
transaction duration과 DB 부하를 같은 조건에서 측정한다.
