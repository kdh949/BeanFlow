# ADR-022: 중요 변경의 append-only AuditRecord

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-30은 금액, 포인트, 재고, 슬롯, terminal 주문 상태, 정산, 이의 판정, 권한과 수동
재처리의 주체·사유·전후 요약을 보존하도록 정한다.

## Decision

- 용어와 저장 이름은 `AuditRecord`로 통일한다.
- actorId, actorType, action, targetType, targetId, occurredAt, reason, before/after
  summary와 correlationId를 필수 또는 action별 필수 규칙으로 저장한다.
- owner Context의 로컬 변경과 가능한 경우 같은 DB 트랜잭션에서 append한다.
- 애플리케이션 API로 수정·삭제하지 않는다.
- 외부 호출 결과는 별도 owner 트랜잭션에서 상태와 AuditRecord를 함께 확정한다.
- summary에는 secret, 원본 결제정보, 정밀 사용자 위치와 불필요한 개인정보를 넣지 않는다.

주문 생성과 예약 lease Feature는 다음 granularity를 사용한다.

- 한 transaction summary로 합치지 않고 상태가 바뀐 Aggregate target마다 별도
  AuditRecord를 append한다.
- 같은 transaction의 record는 correlationId와 source reference로 묶는다.
- 최소 action은 `ORDER_CREATED`, `PICKUP_RESERVED`, `STOCK_RESERVED`,
  `COUPON_RESERVED`, `POINTS_RESERVED`, `BENEFIT_ONLY_PAYMENT_APPROVED`,
  각 reservation `CONFIRMED`, `ORDER_EXPIRED`, `PICKUP_EXPIRED`,
  `STOCK_EXPIRED`, `COUPON_RELEASED`, `POINTS_RELEASED`다.
- 고객 주문 생성과 그 원자적 부수효과는 인증된 Customer actor를 사용하고
  `CUSTOMER_ORDER_CREATION` 표준 reason code를 기록한다.
- worker 또는 조회·결제 명령이 materialize한 deadline 만료는 trigger가 무엇이든
  SYSTEM actor와 `LEASE_DEADLINE_REACHED` 표준 reason code를 기록한다. 요청이
  trigger였다면 correlationId로 원 요청을 추적한다.
- 자동·고객 명령의 reason은 enum 성격의 표준 code다. 수동·운영자 명령은 표준
  action과 별도로 non-blank 자유 입력 사유를 필수로 한다.
- OIDC verified release principal이 실행하는 offline operator permission lifecycle은 민감한 deployment
  input을 감사 저장소에 복제하지 않는 예외다. command는 non-blank 자유 입력 사유를 검증하지만 Audit에는
  `VERIFIED_RELEASE_OPERATOR_PERMISSION_CHANGE` 표준 code와 evidence reference만 저장한다. raw token,
  자유 입력 reason과 evidence body는 저장하지 않는다(ADR-069).
- `(action, targetType, targetId, sourceReference)`를 중복 방지 key로 사용한다.
- target 변경과 해당 AuditRecord 중 하나라도 저장 실패하면 같은 local transaction을
  rollback한다.
- 각 record는 `occurredAt`을 `Asia/Seoul` 현지 시각으로 변환해 달력
  `plusYears(5)`를 적용한 `retentionExpiresAt` Instant를 생성 시 고정한다.
  `now >= retentionExpiresAt`부터 삭제 가능하다. 2월 29일은 달력 연산 결과인
  2월 28일 같은 현지 시각을 사용한다.
- 일반 애플리케이션 API는 수정·삭제를 제공하지 않는다. 권한이 분리된 내부 retention
  worker만 due record를 `(retentionExpiresAt, auditRecordId)` 순서의 제한된 chunk로
  삭제한다.
- cleanup은 중단·재실행 가능해야 하며 실패 count, oldest due age와 삭제 count를
  metric/log로 남긴다. 실패를 성공 cleanup으로 기록하거나 due 이전 record를
  삭제하지 않는다.
- **2026-08-12 운영자 조회 amendment:** BR-44에 따라 AuditRecord 목록은 기본 30일, 요청당 최대
  90일의 `from`·`to` window와 `(occurredAt DESC, id DESC)` signed keyset cursor를 사용한다. 5년
  보존 기간 안의 과거 시작일에는 별도 상한을 두지 않는다. active `AUDIT_RECORD_READ`,
  `PLATFORM_OPERATOR`, 유효한 `X-Access-Reason`과 조회 접근 Audit를 모두 요구하며 permission lock,
  결과 Projection, 접근 Audit append를 같은 local transaction에 둔다. 접근 Audit 저장 실패는
  조회 body를 반환하지 않는다. cursor는 확정된 기간과 모든 filter를 서명하고 기간 역전·90일 초과·
  filter 변경을 400으로 거부한다.

이 clarification은 2026-07-28 주문 생성과 예약 lease Feature의 결정 게이트에서
확정했다.

ADR-054는 같은 target별 granularity를 고객 취소 Tx C0/C1과 후속 owner 보상에
확장한다. cancellation detail은 감사에 복제하지 않고 reason code만 사용하며,
자동 worker attempt 자체와 실제 business 상태 변경을 구분한다.

## Alternatives Considered

- 일반 application log만 사용
- 비즈니스 Entity에 변경 이력 덮어쓰기
- 별도 append-only AuditRecord

## Rationale

중요 변경의 책임과 correlation을 보존하고 일반 로그 보존·변경 정책과 분리한다.

## Consequences

- target별 record와 5년 보존으로 저장량·index·cleanup 운영 비용이 발생한다.
- 감사 저장 실패를 수동 변경 성공으로 숨길 수 없다.
- target별 record로 transaction summary 한 건보다 저장량이 늘어나며 correlation/source
  index가 필요하다.
- append-only는 보존 기간 중 update 금지를 뜻하며 due 이후 통제된 retention purge는
  예외다.
- 운영자 조회는 긴 조사 기간을 여러 90일 window로 나눠야 하며 각 조회가 별도 접근 Audit를 남긴다.

## Verification

- 필수 사유 없는 수동 명령 거부
- owner 변경과 AuditRecord 원자성
- 주문 생성·만료 target별 record와 중복 방지
- GET이 유발한 만료가 Customer 상태 변경으로 잘못 기록되지 않음
- 수정·삭제 API 부재
- 서울 달력 5주년과 윤년 cleanup 경계
- chunk cleanup 중단·재실행
- 민감정보 masking/absence
- 기본 30일·최대 90일 window, 과거 window와 signed cursor filter binding
- `AUDIT_RECORD_READ` grant/reason/query/access-Audit 원자성 및 Audit 장애 fail-closed

**Point adjustment implementation evidence (2026-08-04):**
`POINT_ADJUSTMENT_APPLIED`는 Platform Operator, 자유 입력 reason, evidence reference,
signed effect, before/after Account summary와 affected Lot/transaction ID를 PointAccount target에
append한다. raw key는 저장하지 않으며 Audit insert failure가 Account/Lot/ledger/idempotency/outbox와
함께 rollback됨을 storage fault injection으로 검증했다.

## Metrics

- **Target:** 감사 기록 실패 count와 저장 backlog 관측
- **Not measured:** record volume과 보존 비용

## Revisit Conditions

별도 감사 저장소, 계약·규제 보존 기간, legal hold 또는 tamper-evident storage가
필요할 때

## Related Decisions

- BR-30
- BR-44
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-012](ADR-012-decision-capture-protocol.md)
