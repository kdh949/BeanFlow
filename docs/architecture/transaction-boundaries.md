# Transaction Boundaries

## General rule

한 트랜잭션에서 강한 일관성이 반드시 필요한 Aggregate만 함께 변경한다. 외부 네트워크 호출은 트랜잭션 경계 밖에서 실행한다.

## Order creation

Initial decision:

- Tx I1에서 intended Order ID, scope, canonical payload hash와
  `IdempotencyRecord(PROCESSING)`을 짧게 먼저 커밋한다.
- Order, 슬롯·재고·쿠폰·포인트 예약은 같은 PostgreSQL 배포 단위의 로컬 트랜잭션에서 공개 Application API를 통해 조정한다.
- Ordering이 다른 모듈의 Repository를 직접 호출하지 않는다.
- 일부 실패 시 주문과 모든 예약을 롤백한다.
- Merchant는 정규화한 메뉴·옵션 구성을 sellable unit 요구량으로 번역하고,
  Ordering은 같은 unit 요구량을 주문 전체에서 합산하여 Inventory 공개 API에
  전달한다. Inventory는 메뉴·옵션을 역으로 조회하지 않는다.
- payable이 0이면 같은 주문 생성 트랜잭션에서
  `BENEFIT_ONLY Payment(APPROVED)`를 만들고 네 예약을 확정한 뒤 Order를 `PAID`로
  커밋한다. 중간 `PENDING_PAYMENT`와 `RESERVED` 상태는 외부에 커밋하지 않으며
  외부 Provider를 호출하지 않는다.
- 성공 Tx O는 Order, 예약, AuditRecord와 IdempotencyRecord의
  `COMPLETED + 최초 201 response`를 함께 커밋한다.
- 확정 실패로 Tx O가 rollback되면 별도 Tx I2가 `FAILED + 최초 error response`를
  저장한다. Tx I1 뒤 멈춘 PROCESSING은 intended Order와 owner source를
  reconciliation하며 새 주문을 자동 실행하지 않는다.

Revisit when:

- Lock Wait 또는 transaction duration이 측정된 병목이 됨
- 독립 서비스 분리가 요구됨
- 보상 Saga의 운영 비용을 감당할 필요가 생김

## Payment approval

```text
Tx 1: Payment READY + IdempotencyRecord 저장
commit
External PG approval
Tx 2: APPROVED | FAILED | UNKNOWN 저장 + 후속 사실 영속화
```

- DB connection을 Provider latency 동안 점유하지 않는다.
- timeout은 `UNKNOWN`일 수 있다.
- PG 성공 후 Tx 2 실패는 reconciliation으로 복구한다.
- `BENEFIT_ONLY`는 외부 호출 없이 같은 로컬 트랜잭션에서 Payment 승인 사실을
  확정하지만, 주문별 source reference와 IdempotencyRecord를 동일하게 보호한다.

### Lease expiry while payment is unknown

```text
Tx E1: 5분 만료 시 Order PENDING_PAYMENT -> EXPIRED + 예약 자원 해제
commit
Provider reconciliation reports approved
Tx E2: expired-order late approval 기록 + void/refund recovery 생성
commit
External Provider void/refund
Tx E3: SUCCEEDED | UNKNOWN | RECONCILING | MANUAL_REVIEW 기록
```

- 만료 worker와 승인 reconciliation은 Order의 guarded transition으로 경쟁한다.
- `PAID`가 먼저 확정되면 만료가 실패하고 예약을 확정한다.
- `EXPIRED`가 먼저 확정되면 뒤늦은 승인은 Order와 예약을 되살리지 않는다.
- 외부 void/refund는 E2 트랜잭션 밖에서 실행하고 그 결과를 별도 트랜잭션에 기록한다.
- 실패·timeout은 새 승인 요청이나 성공 환불로 바꾸지 않는다.

### Lease expiry materialization

- scheduled worker, Order 조회와 결제 명령은 같은 idempotent expiry Application
  Service를 호출한다.
- `now >= reservationExpiresAt`이고 Order가 `PENDING_PAYMENT`이면 Order row를
  guarded lock한 뒤 Order `EXPIRED`, 슬롯·재고 예약 `EXPIRED`, 쿠폰 release와
  PointReservation release를 한 로컬 transaction에서 실행한다.
- 모든 owner release와 AuditRecord가 commit된 뒤에만 조회는 `EXPIRED`를 반환하고
  결제 명령은 `RESERVATION_EXPIRED`를 반환한다.
- 한 owner라도 실패하면 전체 rollback한다. 조회·결제는 503을 반환하고 다음 worker
  또는 요청이 같은 source reference로 재시도한다.
- 아직 due가 아니거나 이미 terminal이면 자원 수량을 바꾸지 않는다.

## Cancellation and refund

- Order 취소/거절 전이는 owner Context의 한 트랜잭션에서 한 번만 확정한다.
- 결제 승인 후 필요한 void/refund는 Order 트랜잭션과 분리된 Payment 명령이다.
- Refund REQUESTED와 idempotency record를 먼저 커밋하고 외부 Provider를 호출한다.
- Provider timeout은 Refund `UNKNOWN`과 reconciliation case를 남긴다.
- 원 Order의 취소·거절 상태를 환불 성공으로 간주하지 않는다.
- 성공한 환불 fact를 Loyalty와 Settlement가 각자의 원장에 멱등 반영한다.

## Order completion

- Order를 `COMPLETED`로 전환하는 트랜잭션은 원본 사실을 확정한다.
- 포인트 적립, 정산 항목, 알림과 분석은 idempotent after-commit 처리다.
- 부수효과 실패로 완료 주문을 되돌리지 않는다.

## Point use

- PointAccount 요약과 실제 소비할 PointLot만 같은 트랜잭션에서 잠근다.
- 만료가 빠른 Lot부터 차감한다.
- 원장과 요약 잔액을 함께 갱신한다.
- 동일 주문 사용 reference를 Unique Constraint로 방어한다.
- 주문 생성 예약은 `expiresAt > now`인 PointLot을 `(expiresAt, pointLotId)` 순서로
  잠그고 PointReservation allocation, Account/Lot available·reserved 요약을 같은
  주문 생성 트랜잭션에서 갱신한다.
- 예약 시 유효했던 allocation은 주문 lease 동안 사용 확정할 수 있다. PointLot
  만료 worker는 available 금액만 만료하고 reserved allocation을 임의로 해제하지
  않는다.
- Order 만료·취소 transaction에서 PointReservation을 해제할 때 아직 유효한
  allocation은 available로 복원하고 이미 만료된 allocation은 EXPIRATION 원장으로
  확정한다. 일부만 실패하면 Order와 모든 자원 해제를 함께 롤백한다.

## Settlement

- SettlementItem 생성은 원천 거래 reference 단위로 멱등하다.
- Batch 집계와 상태 전환은 Item 전체를 Entity 컬렉션으로 로딩하지 않는다.
- 확정 후 환불·판정은 별도 Adjustment 트랜잭션이다.
- `SettlementItem` 생성의 기준 fact는 `OrderCompleted`다. `PaymentApproved`만으로
  Item을 생성하지 않는다.
- Payment 환불 fact는 미확정 Item 반영 또는 확정 후 Adjustment 생성의 입력이며,
  원천 refund reference를 Unique Constraint로 보호한다.
- Dispute 판정과 SettlementAdjustment 생성은 Context 간 별도 트랜잭션이다. 명령
  실패 시 Dispute가 Adjustment 완료로 가장하지 않고 재시도 가능한 상태를 유지한다.

## Idempotent commands

- `actorId + operation + Idempotency-Key` Unique Constraint의 insert가 동시 요청의
  승자를 정한다.
- payload hash가 다르면 도메인 작업 전에 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다.
- 같은 payload의 후속 요청은 저장된 상태와 응답을 반환하며 작업을 다시 실행하지 않는다.
- 주문 생성 `COMPLETED`/`FAILED`는 최초 HTTP status/body를 그대로 재생한다.
  `PROCESSING`은 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`와 `Retry-After`를 반환한다.
- Provider 결과가 불명확한 레코드는 정리하거나 신규 요청으로 재승인하지 않는다.

## Audit

- BR-30 대상 변경과 AuditRecord는 가능한 경우 같은 로컬 DB 트랜잭션에 기록한다.
- 주문 생성·예약·확정·만료·해제는 변경된 Aggregate target마다 record를 append하고
  correlationId/source reference로 같은 transaction을 묶는다.
- deadline 만료는 worker·조회·결제 trigger와 무관하게 SYSTEM actor와
  `LEASE_DEADLINE_REACHED` reason을 사용한다.
- 외부 호출 결과는 별도 트랜잭션에서 owner state와 AuditRecord를 함께 확정한다.
- 비동기 owner Context 변경은 원본 event/correlation reference를 감사 기록에 남긴다.
- 감사 실패를 로그만 남기고 원본 수동 변경을 성공 처리하지 않는다.
- AuditRecord retention worker는 서울 달력 5주년이 지난 record만
  `(retentionExpiresAt, id)` 순서의 제한된 chunk로 삭제한다. 일반 비즈니스
  transaction과 분리하고 중단·재실행 시 due 이전 record를 삭제하지 않는다.

## Bulk operations

- 만료, 정산, 재집계는 chunk 처리한다.
- 중단·재실행 시 같은 원천을 중복 처리하지 않는다.
- Bulk SQL 이후 영속성 컨텍스트를 clear하거나 별도 트랜잭션·Repository를 사용한다.
