# Establish BeanFlow product and domain foundations

이 ExecPlan은 `.agent/PLANS.md`를 따른다. Progress, Surprises & Discoveries, Decision Log와 Outcomes & Retrospective를 작업 중 계속 갱신한다.

## Purpose / Big Picture

기능 코드를 작성하기 전에 BeanFlow의 제품 정책, 용어, Bounded Context, Aggregate, 트랜잭션 경계, 상태 머신, 실패 의미론과 API 기준을 일관된 문서 세트로 만든다.

완료 후 새로운 개발자 또는 stateless coding agent는 이전 대화 없이 문서만 읽고 첫 번째 Feature ExecPlan을 작성할 수 있어야 한다.

이번 계획은 애플리케이션 기능 코드 구현을 포함하지 않는다.

## Current State

이 starter kit은 다음 초기 자료를 제공한다.

- `docs/product/`
- `docs/architecture/`
- `docs/decisions/`
- `docs/adr/`
- `docs/testing/`
- `docs/api/`
- `docs/security/`
- `docs/quality/`

현재 문서는 초기 결정이며 실제 코드·DB schema·OpenAPI implementation과 아직 대조되지 않았다.

## Definitions

- **Business Policy:** 제품 동작과 운영 숫자를 정한 결정
- **ADR:** 구조적이고 장기 변경 비용이 있는 결정
- **Aggregate:** 한 트랜잭션에서 불변식을 보호하는 일관성 경계
- **Context Map:** Context 간 데이터 소유권과 통신 관계
- **Failure Semantics:** 실패·불명·재시도·수동 복구가 시스템에서 표현되는 방식
- **Quality Evidence:** 테스트, SQL, 실행계획, metric과 문서로 검증 가능한 결과

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

- 결제 전 예약 lease 5분
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

### Milestone 2: Product and domain consistency

1. BR-01~BR-32가 E2E와 state machine에 반영됐는지 검사한다.
2. Ubiquitous Language와 Aggregate 이름을 통일한다.
3. Context별 데이터 owner와 공개 상호작용을 정리한다.
4. Repository 후보와 DB constraint 후보를 연결한다.

Observable result:

- policy-to-invariant trace
- context ownership table
- unresolved contradictions

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

## Required Tests

문서 단계에서 다음을 검증한다.

- required files exist
- BR-01~BR-32가 각각 한 번 존재
- ADR 번호가 중복되지 않음
- Accepted policy에 `Revisit Conditions` 존재
- OpenAPI YAML parse 가능
- public repository에 불필요한 개인 문맥이 없음
- 링크와 상대 경로가 유효함
- silent fallback을 허용하는 문장이 없음

## Validation Commands

```bash
bash scripts/verify-docs.sh
```

OpenAPI validator가 저장소에 아직 없다면 YAML parse까지만 수행하고 `Not configured`로 보고한다. validator dependency를 임의로 추가하지 않는다.

## Observability

이 단계에서는 runtime observability를 구현하지 않는다. 향후 각 Feature 문서가 필요한 metric, log, audit와 correlation을 명시하게 한다.

## Documentation Updates

작업 결과에 따라 모든 관련 문서를 같은 변경에서 갱신한다.

## Progress

- [x] Starter kit 생성
- [ ] Instruction and content audit
- [ ] Product and domain consistency audit
- [ ] OpenAPI skeleton
- [ ] Decision gap resolution
- [ ] Documentation validation
- [ ] First implementation handoff

## Surprises & Discoveries

- 아직 없음.

## Decision Log

| Date | Decision | Rationale | Record |
|---|---|---|---|
| 2026-07-28 | 초기 작업은 기능 코드 없이 문서·계약 audit에 한정 | 구현 전에 정책 drift를 줄이기 위함 | This ExecPlan |

## Outcomes & Retrospective

작업 완료 시 작성한다.

## Revision Notes

- 2026-07-28: starter kit 초기 계획 작성.
