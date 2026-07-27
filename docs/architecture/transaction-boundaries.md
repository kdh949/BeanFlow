# Transaction Boundaries

## General rule

한 트랜잭션에서 강한 일관성이 반드시 필요한 Aggregate만 함께 변경한다. 외부 네트워크 호출은 트랜잭션 경계 밖에서 실행한다.

## Order creation

Initial decision:

- Order, 슬롯·재고·쿠폰·포인트 예약은 같은 PostgreSQL 배포 단위의 로컬 트랜잭션에서 공개 Application API를 통해 조정한다.
- Ordering이 다른 모듈의 Repository를 직접 호출하지 않는다.
- 일부 실패 시 주문과 모든 예약을 롤백한다.

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

## Order completion

- Order를 `COMPLETED`로 전환하는 트랜잭션은 원본 사실을 확정한다.
- 포인트 적립, 정산 항목, 알림과 분석은 idempotent after-commit 처리다.
- 부수효과 실패로 완료 주문을 되돌리지 않는다.

## Point use

- PointAccount 요약과 실제 소비할 PointLot만 같은 트랜잭션에서 잠근다.
- 만료가 빠른 Lot부터 차감한다.
- 원장과 요약 잔액을 함께 갱신한다.
- 동일 주문 사용 reference를 Unique Constraint로 방어한다.

## Settlement

- SettlementItem 생성은 원천 거래 reference 단위로 멱등하다.
- Batch 집계와 상태 전환은 Item 전체를 Entity 컬렉션으로 로딩하지 않는다.
- 확정 후 환불·판정은 별도 Adjustment 트랜잭션이다.

## Bulk operations

- 만료, 정산, 재집계는 chunk 처리한다.
- 중단·재실행 시 같은 원천을 중복 처리하지 않는다.
- Bulk SQL 이후 영속성 컨텍스트를 clear하거나 별도 트랜잭션·Repository를 사용한다.
