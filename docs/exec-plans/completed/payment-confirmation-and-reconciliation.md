# Confirm external payments and reconcile unknown results

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/order-creation-and-reservation-lease.md`
> **Completed-At:** `2026-07-29`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

1원 이상인 `PENDING_PAYMENT` 주문에 대해 고객이 토큰화 결제수단으로 승인을
요청한다. BeanFlow는 Provider 대기 중 DB connection을 점유하지 않고, 승인 성공은
Order와 네 예약에 원자적으로 반영하며, 결과 불명은 새 승인 없이 조회로 복구한다.

## Current State

주문 생성, 5분 lease, 슬롯·재고·쿠폰·포인트 예약, 주문 생성 멱등성과
`BENEFIT_ONLY` Payment가 구현돼 있다. 외부 결제 endpoint는 OpenAPI skeleton만 있고
Payment schema는 `BENEFIT_ONLY/APPROVED/0 KRW`만 허용한다.

## Definitions

- Payment confirmation: 외부 결제수단 승인 명령.
- UNKNOWN: Provider 부수효과의 성공·실패를 확정할 수 없는 상태.
- Reconciliation: 승인 재요청 없이 Provider transaction 상태를 조회해 내부 상태를
  맞추는 작업.
- Late approval: Order가 이미 만료 또는 취소된 뒤 확인된 Provider 승인.

## Scope

### In Scope

- 결제 승인 REST API와 PaymentMethod 소유권 검증
- Tx1/Provider/Tx2 분리, 승인·거절·UNKNOWN
- 5회 bounded reconciliation과 claim lease
- late approval void/refund recovery와 ReprocessingCase
- metric, audit, runbook, PostgreSQL/Testcontainers 검증

### Non-goals

- 실제 PG 운영 계약과 실제 자금 이동
- 결제수단 등록·폐기 REST API
- 일반 고객/매장 환불 API
- webhook, Kafka, Redis와 독립 서비스 분리

## Business Rules and Invariants

- BR-03~05, BR-25~26, BR-29~30을 적용한다.
- Order당 Payment는 하나이며 같은 Provider transaction을 중복 기록하지 않는다.
- 명시 거절은 Order `CANCELLED`와 네 예약 해제를 한 번만 수행한다.
- timeout과 응답 유실은 `UNKNOWN`이며 승인 요청을 다시 보내지 않는다.
- 만료 Order와 예약은 늦은 승인으로 복구하지 않는다.
- PAN, CVC, 전체 유효기간과 Provider 원문 응답을 저장하지 않는다.

## Architecture and Transaction Boundaries

- Ordering의 coordinator가 Tx1과 Tx2를 조정하고 Payment와 reservation owner의
  공개 API만 호출한다.
- Tx1은 Order/PaymentMethod 검증과 Payment `APPROVING`, IdempotencyRecord,
  최초 reconciliation due를 커밋한다.
- Provider 호출은 transaction 밖에서 실행한다.
- Tx2는 Order부터 고정 잠금 순서로 승인 또는 거절 결과를 원자 반영한다.
- UNKNOWN은 Payment, idempotency와 reconciliation schedule만 변경한다.

## Alternatives Considered

- Provider 호출을 DB transaction 안에서 수행: pool/lock 장기 점유로 제외.
- Payment 승인 후 after-commit 예약 확정: 승인과 주문의 중간 불일치가 커져 제외.
- 완전 비동기 승인: 초기 API/운영 복잡도가 커서 제외.

## Failure Semantics

- 명시 승인만 `APPROVED`, 명시 거절만 `FAILED`다.
- timeout, 연결 오류, malformed response, 금액·통화 불일치는 `UNKNOWN` 또는
  recovery `RECONCILING`이다.
- Tx2 실패는 Tx1의 `APPROVING`과 due reconciliation로 복구한다.
- 다섯 번 불명은 성공·실패로 바꾸지 않고 `MANUAL_REVIEW`로 전환한다.
- production에서 sandbox/fake가 선택되거나 Provider 설정이 누락되면 시작 실패한다.

## Data and Migration

기존 V5는 수정하지 않는다. forward migration으로 external Payment 필드와 constraint,
PaymentMethod, Payment IdempotencyRecord, PaymentReconciliation,
Operations ReprocessingCase를 추가한다.

## API and Event Contracts

`POST /api/v1/orders/{orderId}/payment-confirmations`는 Idempotency-Key와
`paymentMethodId`를 필수로 받는다. 승인 200, 불명/복구 중 202, 충돌 409, 명시
거절 422, 의존성 장애 503을 반환한다. 같은 key/payload는 현재 Payment 상태를
반환하고 새 Provider 승인을 만들지 않는다.

## Milestones

1. 정책·ADR·OpenAPI와 이 ExecPlan을 먼저 확정한다.
2. schema와 순수 Payment 상태 전이를 RED/GREEN으로 구현한다.
3. Tx1과 local/test sandbox Provider 경계를 구현한다.
4. 승인·거절 Tx2와 owner release를 구현한다.
5. UNKNOWN worker, bounded reconciliation과 late approval recovery를 구현한다.
6. metric, runbook, README와 전체 검증을 완료한다.

## Required Tests

- 상태 전이, 금액·통화 불일치, PaymentMethod 소유권
- 같은 key 동시 요청, 다른 payload 409, Order당 Payment unique
- 승인/거절 Tx2 원자성 및 owner fault rollback
- timeout/응답 유실/TX2 실패와 stuck APPROVING 복구
- 만료 경쟁, late approval 비복구, void/refund 단일 실행
- worker claim 만료·재시작, 다섯 번 후 단일 ReprocessingCase
- Provider 지연 중 Hikari connection 비점유
- MockMvc/OpenAPI/Modulith/startup profile 계약

## Validation Commands

```bash
./gradlew clean build
./gradlew test --tests '*Payment*' --tests '*Reconciliation*'
./gradlew test --tests '*ModularityTests'
bash scripts/verify-docs.sh
git diff --check
```

## Observability

승인 시도·시간, UNKNOWN 수와 age, reconciliation 시도·lag, late approval,
void/refund outcome을 low-cardinality tag로 측정한다. ID와 key는 metric tag에
넣지 않는다.

## Documentation Updates

Business Policy, ADR-006/007, Context Map, Transaction Boundaries, State Machines,
OpenAPI, README, quality evidence와 payment recovery runbook을 동기화한다.

## Progress

- [x] 정책·원자성·bounded retry 결정
- [x] 초기 ExecPlan과 OpenAPI 계약 갱신
- [x] schema와 domain
- [x] 승인·거절 API
- [x] UNKNOWN reconciliation
- [x] late approval recovery
- [x] observability/runbook
- [x] 전체 검증

## Surprises & Discoveries

- 기존 Context Map의 after-commit 표현은 계획의 원자 Tx2와 충돌했다. Tx2 내부의
  상태 반영과 커밋 후 downstream fact를 분리해 문서를 보완했다.
- V5 Payment는 `approved_amount_krw`, `approved_at`이 NOT NULL이고 `created_at`,
  optimistic-lock column이 없었다. V6에서 기존 BENEFIT_ONLY row를 보존해
  forward migration한 뒤 external `APPROVING`의 nullable 승인 결과를 허용했다.
- Hikari pool 1에서 Provider 응답을 latch로 지연한 동안 별도 SQL 조회가 성공해
  외부 대기 중 DB connection을 보유하지 않음을 확인했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-29 | Accepted | 승인 내부 반영은 Ordering coordinator의 로컬 Tx2 | 현재 modular monolith에서 중간 불일치 최소화 | ADR-006 |
| 2026-07-29 | Accepted | UNKNOWN 조회 10s/30s/2m/5m/15m 후 MANUAL_REVIEW | lease 전후 자동 복구와 무한 retry 방지 | BR-25, ADR-007 |
| 2026-07-29 | Accepted | 명시 거절은 Order CANCELLED와 예약 해제 | BR-04/05의 결제 실패 의미 명확화 | Business Policy |
| 2026-07-29 | Accepted | 결제수단 등록 API 제외 | 실제 PG 계약 non-goal과 승인 slice 집중 | ExecPlan |

## Outcomes & Retrospective

2026-07-29 구현과 검증을 완료했다.

- V6 forward migration, Payment/PaymentMethod/Idempotency/Reconciliation/
  ReprocessingCase와 `POST /api/v1/orders/{orderId}/payment-confirmations`를 구현했다.
- 명시 승인·거절, UNKNOWN replay, 5회 lookup, claim lease 재시작, 금액 불일치,
  정확한 lease 경계 경쟁, 늦은 승인 void/refund와 단일 manual case를 PostgreSQL
  Testcontainers로 검증했다.
- Hikari maximum pool size 1에서 Provider 응답을 지연하는 동안 별도 SQL 조회가
  성공했다.
- `./gradlew clean build --stacktrace --no-daemon`: 75 tests, failure/error/skip 0.
- Payment/Reconciliation/Modularity 대상 실행: 통과.
- `bash scripts/verify-docs.sh`: OpenAPI 13 paths/43 schemas, 32 policies,
  26 ADRs, 63 Markdown files 통과.
- `git diff --check`: 통과.

정적 분석과 full OpenAPI semantic validator는 이 기능 범위에 추가하지 않았다.
실제 PG adapter, webhook과 실운영 부하 수치는 여전히 범위 밖이며 local/test
scripted adapter 결과를 프로덕션 안정성 근거로 사용하지 않는다.

## Revision Notes

- 2026-07-29: 승인된 구현 계획을 현재 코드·정책·ADR과 대조해 초기 문서 작성.
- 2026-07-29: 외부 승인·bounded reconciliation·late recovery 구현과 전체 검증 완료.
