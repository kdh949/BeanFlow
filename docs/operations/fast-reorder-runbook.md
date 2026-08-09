# Fast Reorder Idempotency and V36 Runbook

## Scope

이 runbook은 `CREATE_ORDER`와 `REORDER_ORDER_V1` 사전등록 record의 stale
`PROCESSING -> MANUAL_REVIEW` 전환, 빠른 재주문 owner failure 조사와
`V36__add_fast_reorder_snapshots_and_idempotency_retention.sql` 배포 전 검증을 다룬다.
재주문을 운영자가 대신 실행하거나 과거 가격·혜택·결제를 복원하는 절차가 아니다.

## State meaning and client contract

- `PROCESSING`은 최초 명령이 아직 처리 중이다. 같은 key·payload는
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`와 `Retry-After`를 받는다.
- `COMPLETED`와 `FAILED`는 최초 HTTP status/body가 저장된 terminal 상태다. 동일 key는
  exact replay된다.
- `MANUAL_REVIEW`는 stale threshold 뒤 자동 처리가 중단된 상태다. 동일 key는
  `409 IDEMPOTENCY_MANUAL_REVIEW_REQUIRED`를 받으며 `Retry-After`는 없다.
- reconciliation은 `manual_review_reason`, `manual_review_started_at`,
  `intended_order_exists`를 저장한다. terminal response를 재구성하거나 주문을 자동 실행하지 않는다.
- 현재 감사 가능한 운영자 해결 command는 없다. DB에서 status, response 또는 intended Order
  연결을 직접 수정하지 않는다. 이 제한이 바뀌려면 권한, Audit, source 검증과 exact replay를
  함께 정의한 Accepted ADR과 별도 use case가 필요하다.

## Alert and first response

다음 신호를 operation과 bounded outcome으로 확인한다. actor ID, source Order ID, raw
Idempotency-Key를 metric tag에 넣지 않는다.

- `beanflow.order.idempotency.reconciliation{outcome=MANUAL_REVIEW_NO_ORDER|MANUAL_REVIEW_ORDER_FOUND}`
- `beanflow.order.idempotency.events{outcome=manual_review_required}`
- `beanflow.order.reorder.attempts{outcome=DEPENDENCY_UNAVAILABLE}`
- oldest PROCESSING age, MANUAL_REVIEW count와 terminal retention backlog

먼저 동일 시간대 DB 오류, transaction timeout과 Merchant configuration 무결성 오류를 확인한다.
owner 의존성을 빈 값, stale catalogue 또는 local fallback으로 대체하지 않는다.

## Read-only investigation

승인된 제한 DB session에서 actor와 raw key를 일반 log에 남기지 않고 사건 범위로만 조회한다.

```sql
SELECT id, operation, status, intended_order_id, started_at,
       manual_review_reason, manual_review_started_at, intended_order_exists
  FROM ordering_idempotency_record
 WHERE id = :record_id;

SELECT EXISTS (
    SELECT 1 FROM ordering_order WHERE id = :intended_order_id
) AS intended_order_exists_now;
```

`intended_order_exists=false`는 Tx O rollback 가능성을 보여 주지만 저장된 owner 예약과 Audit을
함께 대조하기 전 terminal failure body를 추정할 근거가 아니다. `true`도 최초 HTTP body와 모든
snapshot tie-out을 증명하지 않으므로 `COMPLETED`로 직접 바꾸지 않는다.

고객에게는 동일 key polling을 안내하지 않는다. 원인과 거래 상태를 확인한 뒤 새 주문 시도 가능
여부는 별도 고객 지원 정책으로 판단한다. 새 key 사용은 기존 record를 해결하거나 exact replay로
바꾸지 않으며, intended Order가 존재할 가능성이 있으면 중복 주문 위험을 먼저 배제해야 한다.

## Merchant owner corruption

`merchant_menu_configuration.normalized_option_key`는 빈 문자열 또는 canonical UUID를 쉼표로
연결한 중복 없는 오름차순 값이어야 한다. V36 CHECK가 신규 손상을 거부하고 adapter도 방어적으로
`503 DEPENDENCY_UNAVAILABLE`로 변환한다. 제약을 삭제하거나 잘못된 값을 허용해 트래픽을 복구하지
않는다. owner source를 승인된 Merchant 정정 절차로 복구한 뒤 새 요청을 검토한다.

## V36 non-local deployment

V36은 기존 `ordering_order_line` 전체를 `LEGACY_UNAVAILABLE/null`로 backfill하고 즉시
`NOT NULL`/CHECK를 추가한다. 구버전 writer는 새 필수 provenance를 쓰지 못하므로 rolling 또는
무중단 migration으로 간주하지 않는다. 실제 row 수, table size, WAL과 lock wait를 측정하기 전
소요 시간이나 무중단을 주장하지 않는다.

현재 승인된 방식은 maintenance window다.

1. 주문 생성·빠른 재주문 writer를 중지하고 구버전 instance가 drain됐는지 확인한다.
2. terminal idempotency `completed_at` 누락과 Merchant normalized option key precheck를 실행한다.
3. DB backup/복구 절차와 forward-fix 담당자를 확인한다.
4. V36을 단독 migration writer로 적용한다.
5. malformed·duplicate·unsorted option 값이 거부되고 verified no-option `[]`가 허용되는지 smoke
   검증한다.
6. V36 writer를 포함한 새 application만 시작하고 주문 생성·빠른 재주문 contract test를 실행한다.
7. lock wait, owner 503, stuck PROCESSING과 MANUAL_REVIEW를 관찰한 뒤 maintenance window를 닫는다.

Flyway 적용 뒤 migration 파일을 되돌리거나 checksum을 바꾸지 않는다. 실패 시 구버전 writer를
혼합 재기동하지 않고 backup restore 또는 새 forward-only migration 중 승인된 복구 경로를 택한다.

## Escalation and revisit conditions

- `MANUAL_REVIEW_ORDER_FOUND`가 발생하거나 동일 intended Order와 owner reservation tie-out이 다름
- `MANUAL_REVIEW_NO_ORDER`가 반복되거나 Tx I2 저장 실패가 지속됨
- Merchant canonical CHECK 위반 precheck가 발견됨
- V36 lock wait가 maintenance budget을 넘거나 WAL/replication lag가 허용 범위를 넘음
- 고객 영향 때문에 terminal exact response로 수렴하는 운영자 command가 필요함

마지막 조건은 운영 runbook 변경만으로 처리하지 않는다. ADR, authorization matrix, Audit schema,
operator use case와 order-found/no-order/exact-replay 테스트가 함께 필요하다.
