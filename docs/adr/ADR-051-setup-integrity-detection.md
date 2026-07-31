# ADR-051: 고객 취소 환불 준비 손상의 즉시·주기 탐지

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-050은 고객 취소 Refund 또는 recovery snapshot 누락과 source·금액 불일치를 내부
`SETUP_INCOMPLETE`로 유지하고 고객에게는 지연으로 투영하도록 정했다. Tx C1의 원자성
때문에 정상 신규 흐름에서는 이 상태가 생기지 않아야 하지만, legacy data, migration
오류, 수동 데이터 변경이나 결함으로 발생할 수 있다.

접근 시에만 탐지하면 고객과 운영자가 조회하지 않은 주문의 손상이 숨는다. 주기
scanner만 사용하면 worker나 조회가 이미 손상을 확인했는데도 다음 scan까지 alert가
지연된다.

## Decision

### Immediate detection

- 다음 경로가 setup invariant를 확인하다 손상을 발견하면 즉시 Operations의 public
  detection API를 호출한다.
  - 고객 Order/payment recovery 조회
  - 운영자 compensation 조회
  - 고객 취소 Refund worker와 reconciliation
  - settlement exclusion consumer가 고객 취소 source를 검증하는 시점
- detection API는 같은 source에 대해
  `PAYMENT_CANCELLATION_SETUP` ReprocessingCase와 append-only AuditRecord를 한
  local transaction에 저장한다.
- ReprocessingCase unique key는
  `(case_type, order_id, cancellation_aggregate_version)` 또는 동등한 source
  reference다. 여러 detector와 scanner가 경쟁해도 한 case로 수렴한다.
- AuditRecord는 다음 계약을 사용한다.

  | 필드 | 값 |
  |---|---|
  | `actorType` | `SYSTEM` |
  | `action` | `PAYMENT_CANCELLATION_SETUP_INCOMPLETE_DETECTED` |
  | `targetType` | `ORDER` |
  | `targetId` | Order ID |
  | `reason` | 최초 정규화된 setup error code |
  | `sourceReference` | Order terminal version의 customer-cancellation setup source |
  | `beforeSummary` | 확인된 artifact 존재·불변식 boolean |
  | `afterSummary` | `reprocessingCaseState=OPEN` |

- Audit summary에는 금액, customer ID, Provider reference, 자유 입력 detail과 client
  key를 넣지 않는다. missing artifact와 invariant code만 기록한다.
- case와 Audit 저장이 실패하면 손상을 성공적으로 기록했다고 간주하지 않는다.
  조회 경로는 `503 DEPENDENCY_UNAVAILABLE`, worker/consumer는 현재 transaction을
  실패시켜 재시도한다. setup 손상 자체만으로는 고객 조회를 503으로 만들지 않지만,
  확인한 손상을 내구 기록할 수 없는 실패는 숨기지 않는다.

### Periodic detection

- Operations는 고객 접근이 없는 손상을 찾는 bounded integrity scanner를 실행한다.
- 기본 fixed delay는 1분, batch size는 100이며 운영 설정으로 줄일 수 있다. 확대는
  동일 데이터 조건의 DB 실행 계획, 처리시간과 lock 영향 측정 뒤에만 한다.
- scanner는 `CANCELLED + CUSTOMER_REQUEST`이고 외부 결제 승인이 존재하는 Order 중
  다음 위반만 read-only projection으로 keyset 조회한다.
  - Payment recovery snapshot 누락
  - 양수 요청액인데 고객 취소 Refund 누락
  - Order terminal version과 Refund source 불일치
  - 승인액 = 선행 성공 환불액 + 취소 요청액 tie-out 불일치
- 조회 순서는 `(cancelled_at, order_id)`이며 한 batch를 독립 transaction으로
  처리한다. violation query는 이미 같은 source의 open ReprocessingCase가 있는 row를
  제외해 backlog 앞부분에 고착되지 않게 한다.
- cross-context join은 Operations 전용 read-only integrity projection으로만
  허용한다. JPA entity association이나 타 Context Repository write를 만들지 않는다.
- 각 candidate는 immediate detection과 같은 API를 호출하므로 scanner 재시작,
  중복 batch와 detector 경쟁이 case·Audit 중복을 만들지 않는다.
- 정상 `BENEFIT_ONLY`, `PENDING_PAYMENT`, 매장 거절과 고객 취소가 아닌 Refund는
  scanner 대상이 아니다.
- scanner query/transaction 실패는 성공 scan으로 기록하지 않고 다음 fixed delay에
  다시 실행한다. in-memory cursor나 local fallback을 사용하지 않는다.

### Observability

- 새 setup case 생성은 고우선순위 alert를 발생시킨다.
- 같은 미해결 case의 반복 탐지는 새 alert event를 만들지 않고 last-observed metric만
  갱신한다. AuditRecord는 append-only 한 건을 유지한다.
- 운영자 compensation 조회는 ADR-050의 `paymentSetupIssue`와 ReprocessingCase ID,
  최초 감지 시각을 연결해 보여준다.

## Alternatives Considered

### 접근 시 탐지만

- scanner와 cross-context projection이 없다.
- 조회되지 않는 손상이 무기한 숨을 수 있다.

### 주기 scan만

- 탐지 진입점이 하나다.
- 조회·worker가 이미 확인한 손상도 다음 주기까지 내구 기록되지 않는다.

### 건강 상태까지 검사 원장에 저장

- watermark 기반 증분 scan이 쉽다.
- 정상 취소마다 새 원장 row가 생기고 이후 데이터 손상을 놓치지 않으려면 다시 scan해야
  하므로 저장 비용에 비해 보장이 늘지 않는다.

## Rationale

즉시 탐지는 관측된 손상의 경보 지연을 없애고, violation-only bounded scanner는
접근 사각지대를 보완한다. 두 경로가 같은 unique case와 Audit로 수렴하면 탐지 주체가
늘어도 운영 작업과 증적은 하나로 유지된다.

## Consequences

- Operations에 read-only cross-context integrity projection과 scheduled worker가
  추가된다.
- setup 손상을 발견한 customer read가 Operations 내구 기록 실패 때문에 503이 될 수
  있다.
- scanner 부하를 위한 index와 실제 실행계획 검증이 필요하다.

## Failure Scenarios

- customer projection만 축약하고 case를 만들지 않으면 손상이 고객에게만 반복
  표시되고 운영자는 모른다.
- in-memory dedup을 사용하면 재시작 뒤 case와 alert가 중복된다.
- batch에 offset pagination을 쓰면 데이터 추가·해결 중 row를 건너뛸 수 있다.
- open case row를 제외하지 않으면 같은 초기 100건이 매분 scanner를 독점한다.
- detection 저장 실패를 로그만 남기고 조회 성공으로 반환하면 내구 복구 근거가 없다.
- read projection이 owner table을 수정하면 Context 쓰기 소유권을 우회한다.

## Verification

- 네 immediate 진입점과 scanner의 동일 case/Audit 수렴
- 고객 접근 없는 손상의 1분 scanner 탐지
- detection 저장 실패의 503 또는 worker retry
- batch 100 상한과 keyset 결정성
- 정상 취소의 false positive 0건
- 재시작·중복 scanner 실행의 case/Audit 수 불변

## Required Tests

- Refund/snapshot 누락과 source/tie-out 위반별 immediate detection
- 고객 read에서 detection DB 실패의 503
- worker/consumer detection 실패의 transaction retry
- detector 두 개와 scanner 동시 실행의 unique arbitration
- scanner batch 경계, 중단·재실행과 backlog 진행
- open case 제외와 resolved data 재검증
- BENEFIT_ONLY/PENDING/STORE_REJECTION 제외
- read-only projection의 write 0회
- summary의 민감정보 부재

## Metrics

- `beanflow.operations.payment_setup.scan.count{outcome}`
- `beanflow.operations.payment_setup.scan.duration`
- `beanflow.operations.payment_setup.scan.candidates`
- `beanflow.operations.payment_setup.case.count{reason,state}`
- `beanflow.operations.payment_setup.oldest_age.seconds`

Order, Payment, Refund, Customer와 Provider 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** production query plan과 scan 처리시간 — 구현 후 동일 데이터
  조건에서 측정한다.

## Revisit Conditions

scanner query가 OLTP 부하 예산을 넘거나 CDC 기반 무결성 감시가 도입될 때

## Related Decisions

- BR-14, BR-30
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-022](ADR-022-audit-record.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
- [ADR-050](ADR-050-setup-incomplete-customer-projection.md)
