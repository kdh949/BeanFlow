# ADR-109: 고객 가입과 PointAccount 원자 provisioning

- **Status:** Accepted
- **Date:** 2026-08-12
- **Implementation owner:** [Customer account and login](../exec-plans/completed/productization-30-customer-account-and-login.md)

## Context

기존 Loyalty는 `customer_id`가 Unique인 `loyalty_point_account`를 사용하지만 CustomerAccount가 없던
코드베이스라 계정 생성 lifecycle이 없다. 포인트 조회는 `accountId` UUID가 필요하고 계정이 없으면
404, 포인트 예약은 계정이 없으면 잔액 부족이다.

P0 고객 가입을 Identity에만 추가하면 가입 직후 포인트 화면에서 “0 포인트”와 “계정 없음”을 구분할
source of truth가 없다. GET에서 row를 만들거나 0 DTO를 합성하면 조회 실패를 정상 상태로 위장한다.

## Decision

BR-42에 따라 CustomerAccount 가입 Application Service가 하나의 PostgreSQL transaction을 열고 다음을
조정한다.

```text
Tx1
  Identity: canonical login ID Unique 확인, CustomerAccount INSERT
  Loyalty public port:
    CustomerPointAccountProvisioningOperations.create(customerId)
    → PointAccount INSERT(available=0, reserved=0, recoveryPending=0)
  flush both
commit → 201
```

- Loyalty 구현은 `Propagation.MANDATORY`로 호출자의 transaction에 참여한다.
- `(customer_id)` Unique Constraint가 exactly-one을 보장한다. public port는 이미 같은 customer의
  PointAccount가 있으면 성공 replay로 숨기지 않고 가입 무결성 충돌을 반환한다.
- Identity는 Loyalty Repository/table을 직접 읽거나 쓰지 않는다. 두 Aggregate는 customer ID로만
  관련되고 JPA association·cascade를 추가하지 않는다.
- 어느 save/flush도 실패하면 전체 rollback하고 가입은 503이다. CustomerAccount만 반환하지 않는다.
- `/me/points`는 CustomerActor의 customer ID로 실제 PointAccount를 조회한다. 누락은 0/404/lazy-create가
  아닌 `503 POINT_ACCOUNT_INTEGRITY_FAILURE`다.
- P0 CustomerAccount table은 신규라 제품 데이터 backfill은 없다. Support profile을 로그인 계정으로
  추론하지 않는다.

## Alternatives Considered

### 첫 적립 때 lazy-create

거래 전 row는 줄지만 가입 직후 포인트 화면에 계정 없음 상태가 생기고 첫 point command마다 생성
경쟁을 처리해야 한다.

### 첫 GET에서 lazy-create

GET이 쓰기·lock·Unique 경쟁과 503을 만들고 HTTP read 의미가 흐려진다.

### 비동기 provisioning event

Identity와 Loyalty를 느슨하게 결합하지만 계정 생성 지연·재시도·dead letter·가입 응답 의미가 새로
필요하다. 같은 PostgreSQL Modulith인 P0에서는 운영 비용이 이점보다 크다.

### Account row 없이 0 DTO 반환

사용성은 단순하지만 저장소 장애·무결성 손상을 정상 0원으로 숨겨 명시적 실패 정책을 위반한다.

## Rationale

현재 두 Context가 같은 DB와 Spring transaction manager를 사용하므로 public port를 통한 원자
provisioning이 가장 작은 명시적 경계다. 실제 0원 계정이 항상 존재하면 actor-scoped 조회와 이후
적립·예약이 같은 source of truth에서 시작한다.

## Consequences

- 고객 가입 가용성이 Loyalty 저장소에도 의존한다. 이는 0원 fake 계정을 허용하지 않기 위한 의도된
  결합이다.
- CustomerAccount와 PointAccount row 수는 P0에서 1:1이어야 하며 무결성 점검 대상이 된다.
- 서비스 분리 시 로컬 원자 transaction을 유지할 수 없으므로 outbox·provisioning state와 고객에게
  보이는 중간 상태를 새 ADR로 설계해야 한다.

## Verification

- 가입 성공에서 두 row와 세 0원 잔액 확인.
- 동일 사용자명 동시 가입에서 각 Aggregate 한 건만 존재.
- Loyalty save/flush failure 후 Identity row 0건과 503.
- PointAccount 선행 중복 fixture가 가입 성공 replay가 아닌 conflict/rollback인지 검증.
- CustomerAccount만 있는 손상 fixture의 `/me/points`가 503이고 row를 생성하지 않는지 검증.
- Modulith/ArchUnit으로 Identity→Loyalty public API 방향과 cross-Context JPA association 부재 검증.

## Metrics

- 고객 가입과 PointAccount provisioning 성공·실패 수
- CustomerAccount/PointAccount coverage 불일치 수
- provisioning transaction 지연 p50·p95

## Implementation Outcome (2026-08-13)

`productization-30`은 Loyalty public API에 `CustomerPointAccountProvisioningOperations`를 두고
`MANDATORY` 구현이 available/reserved/recovery-pending 세 잔액이 0인 PointAccount를 생성하도록 했다.
Identity 가입 transaction은 CustomerAccount flush, PointAccount flush와 과거 CUSTOMER LOGIN_ID attempt
삭제를 한 번에 commit한다. 성공·canonical 동시 가입, Loyalty trigger 실패와 선행 PointAccount Unique
충돌을 PostgreSQL에서 검증했으며 실패 시 두 row가 모두 0건이고 503이다. Identity와 Loyalty 내부
Repository 또는 JPA association은 ArchUnit/Modulith 검증으로 금지한다.

이 ADR의 actor-scoped `/me/points` 조회와 손상 fixture 503 projection은
`productization-80-customer-web-p0-integration` 소유다. Plan 30은 그 후속 범위를 앞당겨 구현하거나
완료했다고 주장하지 않는다.

## Revisit Conditions

- Identity 또는 Loyalty가 별도 서비스·DB로 분리될 때
- 대량 account import, 탈퇴·익명화 lifecycle이나 provisioning 재처리 상태가 필요할 때
- 가입 가용성을 Loyalty 장애와 분리해야 한다는 실제 운영 요구가 생길 때

## Related Decisions

- [BR-42 고객 가입과 PointAccount 원자 생성](../product/business-policy-decisions.md)
- [ADR-011 PointLot 원장](ADR-011-point-lot-ledger.md)
- [ADR-092 Hybrid 인증](ADR-092-hybrid-authentication.md)
- [ADR-095 CurrentActor](ADR-095-unified-current-actor.md)
