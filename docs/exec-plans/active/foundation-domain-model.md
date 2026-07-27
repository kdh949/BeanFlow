# Establish BeanFlow product and domain foundations

이 ExecPlan은 `.agent/PLANS.md`를 따른다. Progress, Surprises & Discoveries, Decision Log와 Outcomes & Retrospective를 작업 중 계속 갱신한다.

## Purpose / Big Picture

기능 코드를 작성하기 전에 BeanFlow의 제품 정책, 용어, Bounded Context, Aggregate, 트랜잭션 경계, 상태 머신, 실패 의미론과 API 기준을 일관된 문서 세트로 만든다.

완료 후 새로운 개발자 또는 stateless coding agent는 이전 대화 없이 문서만 읽고 첫 번째 Feature ExecPlan을 작성할 수 있어야 한다.

이번 계획은 애플리케이션 기능 코드 구현을 포함하지 않는다.

## Current State

초기 starter document audit을 완료했고 다음 foundation을 문서에 반영했다.

- BR-01~BR-32 policy-to-invariant trace
- Context별 write data owner와 동기·이벤트 interaction
- Aggregate별 Repository, DB constraint와 concurrency 후보
- Order, Payment/Refund, Reservation, Notification, Settlement, Dispute,
  Idempotency와 Reprocessing 상태
- 23개 ADR와 정책 related link
- 13개 path와 37개 schema의 self-contained OpenAPI 초기 계약
- required file, 정책, ADR, link와 OpenAPI local contract 검증

애플리케이션 코드와 DB schema는 아직 없으므로 구현과 대조된 상태는 아니다. 이
ExecPlan의 결과는 첫 Feature ExecPlan을 작성하기 위한 계약 기준이다.

## Definitions

- **Business Policy:** 제품 동작과 운영 숫자를 정한 결정
- **ADR:** 구조적이고 장기 변경 비용이 있는 결정
- **Aggregate:** 한 트랜잭션에서 불변식을 보호하는 일관성 경계
- **Context Map:** Context 간 데이터 소유권과 통신 관계
- **Failure Semantics:** 실패·불명·재시도·수동 복구가 시스템에서 표현되는 방식
- **Quality Evidence:** 테스트, SQL, 실행계획, metric과 문서로 검증 가능한 결과
- **Payment Recovery:** 승인·환불 결과 불명을 reconciliation하고 필요 시 수동 검토로
  전환하는 Payment 소유 workflow

## Scope

### In Scope

- 모든 starter document의 상호 일관성 audit
- 누락된 용어, 불변식, 상태 전이와 error contract 발견
- Context Map과 물리 모듈 후보 정리
- ADR과 Business Policy 링크 정리
- OpenAPI skeleton 작성
- 문서 검증 script 보완
- 첫 구현 Feature 후보와 별도 ExecPlan backlog 작성

### Non-goals

- Spring Boot 기능 코드
- JPA Entity·Repository·Controller
- 실제 인증
- Redis·Kafka·Kubernetes
- 실제 PG·알림 Provider
- 성능 결과 생성
- commit 또는 push

## Business Rules and Invariants

`docs/product/business-policy-decisions.md`의 BR-01~BR-32를 기준으로 한다.

핵심:

- 결제 전 예약 lease 5분; Payment `UNKNOWN`이어도 만료
- 만료 후 뒤늦은 승인은 Order를 되살리지 않고 void/refund reconciliation
- 재고·슬롯은 결제 승인 시 확정
- 매장 수락 timeout 3분
- 쿠폰 후 포인트 적용
- 주문 스냅샷과 항목별 혜택 배분
- 실결제액 포인트 적립
- 완료일 기준 일별 정산
- 확정 정산 Adjustment
- 결제 멱등성과 90일 보존
- 알림의 명시적 재시도·수동 검토
- late event 7일 자동 보정 window

정책을 변경할 필요가 있으면 구현 전 질문 절차와 결정 기록을 따른다.

## Architecture and Transaction Boundaries

초기 결정:

- DDD Modular Monolith
- Aggregate 간 ID 참조
- 주문 예약은 로컬 DB 트랜잭션
- 외부 PG 호출과 DB transaction 분리
- 이벤트 후속 처리는 idempotent
- 확정 정산은 불변
- silent fallback 금지
- Ordering은 Order와 매장 주문 상태를, Fulfillment는 PickupSlot/Reservation을 소유
- `store-orders`는 별도 Aggregate가 아닌 매장 관점 Order API

## Alternatives Considered

각 ADR의 Alternatives Considered를 검토하고 문서 간 모순을 발견하면 별도 Decision Log에 기록한다.

## Failure Semantics

`docs/architecture/failure-semantics.md`를 기준으로 다음을 확인한다.

- startup fail-fast
- request-critical failure
- unknown 외부 결과
- asynchronous side-effect failure
- explicit degraded mode only
- no automatic fake/local fallback

특히 Order `EXPIRED`, `REJECTED` 또는 `CANCELLED`는 Payment void/refund 성공을
의미하지 않는다. 외부 결과가 불명확하면 별도 `UNKNOWN`, `RECONCILING`,
`MANUAL_REVIEW`와 ReprocessingCase를 보존한다.

## Data and Migration

이 계획은 schema나 migration을 생성하지 않는다. 첫 Feature는
`docs/architecture/aggregate-invariants.md`의 Repository/constraint 후보를 실제
PostgreSQL migration으로 구체화해야 한다.

필수 data 원칙:

- 금액은 integer KRW
- 시각은 offset/UTC instant, 제품 계산 timezone은 `Asia/Seoul`
- 다른 Aggregate는 ID 참조
- transaction source와 idempotency scope는 DB Unique Constraint로 최종 방어
- 확정 정산과 AuditRecord는 append-only
- 정밀 사용자 위치와 원본 카드정보는 저장 금지

기존 production data가 없으므로 이 단계의 migration/backfill은 없다.

## API and Event Contracts

- `openapi/beanflow-v1.yaml`이 `/api/v1` 초기 HTTP 계약의 원본이다.
- mutation 여섯 종류는 `Idempotency-Key`를 요구한다.
- 202는 Payment/Refund 또는 취소 recovery가 아직 확정되지 않았다는 representation이다.
- integer KRW와 ISO-8601 date-time schema를 재사용한다.
- Event Catalog의 금전·자원·정산·알림·Analytics cross-module fact는 영속 publication
  대상이다.
- SettlementItem 생성의 원천은 `OrderCompleted`이고 `PaymentApproved`만으로 만들지
  않는다.

## Milestones

### Milestone 1: Instruction and content audit

1. `AGENTS.md`, `.agent/PLANS.md`와 모든 문서 index를 읽는다.
2. 누락 파일, 깨진 링크, 중복 ID와 충돌을 보고한다.
3. 공개 저장소와 무관한 개인·외부 문맥이 있는지 확인한다.
4. 파일을 수정하기 전에 audit 결과를 사용자에게 보고한다.

Observable result:

- 읽은 파일 목록
- conflict/open question 목록
- 작업을 막는 문제 여부

Status: Completed. 요구된 40개 파일과 audit 보조 파일을 읽고 파일 수정 전에 Context
Audit을 보고했다.

### Milestone 2: Product and domain consistency

1. BR-01~BR-32가 E2E와 state machine에 반영됐는지 검사한다.
2. Ubiquitous Language와 Aggregate 이름을 통일한다.
3. Context별 데이터 owner와 공개 상호작용을 정리한다.
4. Repository 후보와 DB constraint 후보를 연결한다.

Observable result:

- policy-to-invariant trace
- context ownership table
- unresolved contradictions

Status: Completed. `docs/architecture/policy-traceability.md`, Context owner table,
Repository/constraint 표와 상태/이벤트 계약을 추가했다.

### Milestone 3: API skeleton

`openapi/beanflow-v1.yaml`을 보완한다.

최소 operation:

- nearby store search
- menu and pickup slot lookup
- order create/get/cancel
- payment confirmation
- payment refund
- store order status transition
- point account and transaction lookup
- settlement lookup
- dispute creation

요구:

- error envelope
- Idempotency-Key
- 409 conflict
- unknown payment representation
- integer KRW
- ISO-8601 time

Observable result:

- parse 가능한 OpenAPI document
- API convention과 error catalog 일치

Status: Completed. OpenAPI는 13개 required path, 37개 schema, local `$ref`,
Idempotency-Key와 explicit recovery representation을 포함한다.

### Milestone 4: Decision and verification readiness

1. ADR index와 related links를 확인한다.
2. 구조적 미결정은 Proposed ADR 후보로 만든다.
3. 사소한 결정 log 형식을 확인한다.
4. `scripts/verify-docs.sh`를 실행·수정한다.
5. 첫 Feature 후보별 ExecPlan 파일명을 제안한다.

Observable result:

- 문서 검증 통과
- ADR gap 목록
- implementation handoff

Status: Completed. ADR-013 결정과 아래 첫 Feature handoff를 반영했고 강화된 문서
검증을 통과했다.

## Required Tests

문서 단계에서 다음을 검증한다.

- required files exist
- BR-01~BR-32가 각각 한 번 존재
- ADR 번호가 중복되지 않음
- Accepted policy에 `Revisit Conditions` 존재
- OpenAPI YAML parse 가능
- required API path와 mutation Idempotency-Key 존재
- 모든 OpenAPI `$ref`가 문서 내부에서 resolve
- ADR index의 파일·상태가 실제 ADR과 일치
- public repository에 불필요한 개인 문맥이 없음
- 링크와 상대 경로가 유효함
- silent fallback을 허용하는 문장이 없음

## Validation Commands

```bash
bash scripts/verify-docs.sh
```

이 script는 현재 환경의 PyYAML로 parse, required path, local `$ref`, mutation
Idempotency-Key와 Error envelope를 검사한다. 별도 full OpenAPI semantic validator는
`Not configured`이며 production dependency를 추가하지 않는다.

## Observability

이 단계에서는 runtime observability를 구현하지 않는다. 향후 각 Feature 문서가 필요한 metric, log, audit와 correlation을 명시하게 한다.

## Documentation Updates

이 작업은 Product Policy, E2E, Ubiquitous Language, Context Map, Aggregate,
Transaction Boundary, State Machine, Event Catalog, Failure Semantics, ADR, API,
Security, OpenAPI와 verification script를 같은 변경에서 갱신한다.

## First Implementation Handoff

첫 구현 Feature 추천:

`주문 생성과 5분 원자적 예약 lease`

ExecPlan 후보:

`docs/exec-plans/active/order-creation-and-reservation-lease.md`

범위:

- Spring Modulith module skeleton과 owner package boundary
- Merchant 가격 조회
- Order/OrderLine snapshot과 integer KRW allocation
- PickupSlot, Stock, Coupon, Point 예약의 단일 로컬 PostgreSQL transaction
- 5분 `reservationExpiresAt` 고정
- `/api/v1/orders` request/response와 IdempotencyRecord
- 실제 PostgreSQL Testcontainers의 unique/check/lock 검증
- 마지막 자원 경합, 부분 예약 rollback과 동일 key 동시 요청

Non-goals:

- 외부 PG 호출, 1원 이상 Payment 승인과 reconciliation. 0원
  `BENEFIT_ONLY Payment(APPROVED)`는 BR-11/ADR-016에 따라 주문 생성 Feature에 포함
- 매장 수락 timeout
- OrderCompleted 후 포인트·정산

후속 ExecPlan 후보:

1. `payment-confirmation-and-reconciliation.md`
2. `store-acceptance-timeout-and-compensation.md`
3. `order-completion-loyalty-and-settlement.md`
4. `notification-delivery-retry.md`

## Progress

- [x] Starter kit 생성
- [x] Instruction and content audit
- [x] Product and domain consistency audit
- [x] OpenAPI skeleton
- [x] Decision gap resolution
- [x] Documentation validation
- [x] First implementation handoff

## Surprises & Discoveries

- BR-03의 고정 5분 lease와 Payment `UNKNOWN`은 뒤늦은 승인 시 자원 정합성을
  결정하지 못하고 있었다. 만료 우선과 void/refund recovery로 확정했다.
- Event Catalog의 `PaymentApproved → Settlement` 표현은 BR-16의 완료일 정산과
  혼동될 수 있었다. SettlementItem 원천을 `OrderCompleted`로 명시했다.
- `POINT_RECOVERY_PENDING`이 SettlementAdjustment로도 읽혔다. Loyalty 원장
  소유로 통일하고 금전 정산 영향만 별도 Adjustment로 분리했다.
- Actors 문서의 Settlement Operator가 authorization matrix 열에 없었다.
- 기존 OpenAPI는 parse 가능했지만 request/response schema가 없어 integer KRW,
  ISO-8601 time, unknown/recovery와 인가를 계약으로 검증할 수 없었다.

## Decision Log

| Date | Decision | Rationale | Record |
|---|---|---|---|
| 2026-07-28 | 초기 작업은 기능 코드 없이 문서·계약 audit에 한정 | 구현 전에 정책 drift를 줄이기 위함 | This ExecPlan |
| 2026-07-28 | Payment가 UNKNOWN이어도 5분에 Order와 예약을 만료 | 무기한 자원 점유와 late approval oversell 방지 | BR-03, ADR-013 |
| 2026-07-28 | 만료 후 승인은 Order를 되살리지 않고 void/refund recovery | 해제된 자원과 결제 사실을 명시적으로 reconcile | ADR-013 |
| 2026-07-28 | Ordering이 Order 상태를 소유하고 Fulfillment는 슬롯을 소유 | 중복 상태와 owner 불명확성 제거 | Context Map |
| 2026-07-28 | `store-orders`는 별도 Aggregate가 아닌 매장용 Order 표현 | API view와 write ownership 분리 | Ubiquitous Language |
| 2026-07-28 | SettlementItem은 OrderCompleted를 원천으로 생성 | BR-16 완료일 정산과 event 의미 일치 | Event Catalog, ADR-017 |
| 2026-07-28 | Dispute 14일 window는 `[D+1, D+15)` calendar interval | inclusive 경계와 timezone 모호성 제거 | BR-22, ADR-018 |

## Outcomes & Retrospective

- BR-01~BR-32를 owner, state/E2E, transaction/constraint와 ADR에 추적할 수 있다.
- 모든 `ADR Required` topic이 구체 ADR과 연결됐다.
- 초기 OpenAPI는 required operation과 명시적인 failure/recovery representation을
  포함하고 repository validator로 구조를 검사한다.
- 기능 코드, Entity, Repository, Controller, migration과 production dependency는
  생성하지 않았다.
- `bash scripts/verify-docs.sh`는 32개 정책, 23개 ADR, 56개 Markdown 파일과
  OpenAPI 13개 path/37개 schema 검증을 통과했다.
- `./gradlew test`는 성공했다. 현재 Java source와 Java test는 없고 기존 Kotlin
  compile/test task는 up-to-date였다.
- 별도 full OpenAPI semantic validator는 구성되지 않아 `Not configured`다.

## Revision Notes

- 2026-07-28: starter kit 초기 계획 작성.
- 2026-07-28: Context Audit, policy trace, ADR-013 결정, API contract, validation과
  first Feature handoff를 반영.
