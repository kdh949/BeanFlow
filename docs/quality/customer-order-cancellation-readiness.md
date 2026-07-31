# Customer Order Cancellation Readiness Audit

## Audit identity

- **Audited at:** 2026-07-31 Asia/Seoul
- **Branch:** `feature/customer-order-cancellation-docs`
- **HEAD:** `04e2b4819a66966952c5436342a05149fd7ac6ee`
- **Related merge:** PR #17, merge commit
  `443fe8ff4d41776f1754e5a5c17ab8566e68398d`, merged 2026-07-31
- **Scope:** 고객 주문 취소·환불 정책, ADR, 아키텍처, API/OpenAPI, migration,
  Ordering/Payment/Operations/owner/Notification/Settlement 코드, 테스트, active/completed
  ExecPlan과 관련 Git/PR 이력
- **Application code changed by this audit:** No
- **Migration changed by this audit:** No

감사 시작 시 working tree에는 사용자 변경인 `README.md` 실행 안내 확장과 기존 master
plan의 ADR-059 release gate 완료 체크가 있었다. 실행 안내는 보존했고, evidence가 없는
release gate 완료 표시는 아래 판정에 따라 제거했다.

## Final readiness

**NOT READY FOR IMPLEMENTATION.** 제품 정책과 목표 API 의미는 결정돼 있지만 다음
선행조건이 닫히지 않았다.

1. ADR-059 clean-cutover 운영 사실 evidence 부재
2. 부분 환불 line-level cash/benefit allocation foundation 부재
3. Settlement Context와 consumer 구현 부재
4. rejection 전용 compensation을 공통 모델로 바꾸는 migration/event 전략 미확정

고객 취소 Controller/Service를 먼저 구현하면 Accepted 부분 환불, 정산 제외와 202 내구
의미를 만족할 수 없다.

## Source hierarchy applied

1. 명시적 `Amends`/`Amended by`와 ADR 본문의 대체 문구
2. Accepted 문서의 merge 시각
3. active ExecPlan
4. 같은 변경의 OpenAPI·아키텍처 문서
5. 현재 코드·migration·테스트

ADR-029~060은 모두 commit `04e2b48` 한 건에서 만들어졌으므로 Git merge 시각만으로
상호 우선순위를 정할 수 없다. 대신 다음 explicit amendment를 적용했다.

- ADR-038은 ADR-033의 고객 환불 projection과 ADR-037 REQUEST 예산을 보완한다.
- ADR-044는 ADR-034의 고객 취소 Notification consumer를 Tx C0/C1 직접 Delivery로 바꾼다.
- ADR-055는 ADR-034의 event payload에서 customer/store/reason을 제거한다.
- ADR-057은 terminal business response의 replay indicator를 제거한다.
- ADR-059는 ADR-033의 migration rename/backfill 전략만 조건부 clean cutover로 바꾼다.

## Canonical target contract

- 허용 상태: `PENDING_PAYMENT`, acceptance deadline 전 `PAID`
- C0: Order·네 예약·target Audit·accepted Delivery·멱등 응답을 한 transaction에
  commit하고 200, Refund/Case/event 없음
- C1: Order·Payment snapshot·필요한 Refund·Case/6 step·두 benefit policy·accepted
  Delivery·target Audit·네 owner publication·멱등 응답을 commit하고 202
- deadline 이후 PAID: timeout work와 Audit 저장 성공 뒤 409, 저장 실패는 503
- 선행 성공 부분 환불 허용, 남은 cash와 미복원 benefit allocation만 처리
- request retry 최대 3회, UNKNOWN 이후 request 중단과 lookup 최대 5회
- 고객 projection: 내부 processing/unknown/reconciling은 `PROCESSING`,
  failed/manual review/setup incomplete은 `PROCESSING + REFUND_DELAYED`
- `OrderCancelledV1`: PAID 취소에서 Fulfillment/Inventory/Promotion/Loyalty 네 consumer만
- Settlement: 미완료 고객 취소에 Item/Adjustment 없이 source-unique NOT_APPLICABLE Audit

## Conflict matrix

| Topic | Evidence | Classification | Resolution / blocker |
|---|---|---|---|
| release gate 완료 체크와 evidence 부재 | 기존 master plan은 완료 체크, ADR-059/PR #17에는 외부 증거 없음 | `NEEDS_FACT_VERIFICATION` | 체크 제거, `CLEAN_CUTOVER_GATE = FAILED` |
| ADR-033 forward rename과 ADR-059 clean cutover | ADR-059가 rename/backfill 부분만 명시적으로 대체 | `RESOLVABLE_BY_RECENCY` | ADR-059를 explicit amendment로 기록; gate 전에는 둘 다 실행 금지 |
| `OrderRejectedV1` 제자리 변경 단정 | BR-14, ADR-034, event catalog가 publication 0을 사실로 단정 | `RESOLVABLE_BY_RECENCY` | ADR-059 조건부 gate에 맞춰 모두 수정 |
| 내부 Refund 상태의 고객 노출 | ADR-033 초기 원천과 ADR-038/050 projection | `RESOLVABLE_BY_RECENCY` | ADR-038/050과 OpenAPI projection을 canonical로 확정 |
| `OrderCancelledV1` reason/customer/store 포함 여부 | ADR-034 초기 payload와 ADR-055 | `RESOLVABLE_BY_RECENCY` | ADR-055 최소 payload, four-owner consumer로 확정 |
| 접수 Notification event consumer 여부 | ADR-034 초기 구조와 ADR-044 | `RESOLVABLE_BY_RECENCY` | C0/C1 직접 Delivery, Notification은 event consumer 아님 |
| `OrderRejectedV1` Operations consumer 표기 | Event Catalog 표와 실제/목표 Case 직접 생성 경계 | `RESOLVABLE_BY_RECENCY` | Operations를 event consumer에서 제거; Case는 원 transaction에서 생성 |
| Context Map의 Notification 경계 | 일반 after-commit 서술과 ADR-044의 C0/C1 직접 Delivery | `RESOLVABLE_BY_RECENCY` | 일반 event와 취소 접수 동기 API를 구분 |
| 처리 중 idempotency의 HTTP 의미 | API convention 일반 문구와 주문 생성 409/취소 lock 직렬화 | `RESOLVABLE_BY_RECENCY` | command별 계약으로 일반 문구를 제한 |
| Cancellation `orderState` 범위 | OpenAPI가 전체 OrderState 허용, ADR-031은 성공 시 CANCELLED | `IMPLEMENTATION_DRIFT` | OpenAPI를 `const: CANCELLED`로 교정 |
| detail trim/empty/control 계약 | 정책은 normalize 후 검증, OpenAPI는 raw min/max만 검사 | `RESOLVABLE_BY_RECENCY` | OpenAPI에 normalize 의미와 control-char pattern 반영 |
| 선행 Refund unresolved 상태 | OpenAPI 설명에 RETRY_SCHEDULED 누락 | `RESOLVABLE_BY_RECENCY` | OpenAPI 차단 상태 목록에 추가 |
| 부분 환불 허용과 현재 전액 거절 Refund | ADR-036 대 `RejectionRefundService` | `IMPLEMENTATION_DRIFT` + `MISSING_FOUNDATION` | allocation foundation plan 10 선행 |
| Settlement NOT_APPLICABLE와 구현 부재 | ADR-048/BR-16, 코드·migration에 Settlement 없음 | `MISSING_FOUNDATION` | Settlement foundation plan 20 선행 |
| trigger×benefit 정책과 singleton 구현 | ADR-041 대 V8/Operations singleton policy | `IMPLEMENTATION_DRIFT` | compensation foundation plan 30 |
| store transition hash/replay 계약 | BR-25/ADR-057 대 hash에서 orderId 누락, V1 operation, replay body mutation | `IMPLEMENTATION_DRIFT` | plan 30의 store regression 범위 |
| 정책 trace의 Ready 과장 | BR-14/15/16 선행 기반·gate 미반영 | `RESOLVABLE_BY_RECENCY` | prerequisite-blocked로 교정 |
| active master plan 과대 범위 | 하나의 plan에 schema·command·recovery·Settlement·repair 혼합 | `MISSING_FOUNDATION` | 여섯 하위 ExecPlan으로 분리 |

## Previous audit candidate verification

| Candidate | Verdict | Current evidence and action |
|---|---|---|
| compensation clean-cutover release gate 미검증 | `CONFIRMED` | repository/PR에 external evidence 없음; gate failed |
| 결제 승인 recovery와 취소 환불 recovery schema 혼합 | `NOT_PRESENT` | 현재 approval은 `payment_reconciliation`, Refund는 `payment_refund`; cancellation snapshot schema 자체가 아직 없음 |
| 고객 환불 상태를 내부 상태 그대로 노출하는 과거 ADR과 최신 projection 충돌 | `ALREADY_FIXED` | ADR-038/050과 OpenAPI가 고객 projection을 확정; amendment metadata 보강 |
| 취소 reason code의 persistent event 포함 여부 충돌 | `ALREADY_FIXED` | ADR-055와 event catalog가 event에서 reasonCode 제거 |
| clean cutover와 legacy event compatibility 요구 충돌 | `NEEDS_FACT_VERIFICATION` | 모두 gate 조건으로 양립; 현재 unknown이므로 기존 V1 유지 |
| 선행 부분 환불 허용, allocation foundation 부재 | `CONFIRMED` | V10/Refund code에 line allocation 없음 |
| Settlement `NOT_APPLICABLE` 정책, 구현 부재 | `CONFIRMED` | Settlement package/table/test 없음 |
| store transition hash, operation version, replay body drift | `CONFIRMED` | hash는 state/reason만, operation V1, response replay 시 body 변경 |
| trigger×benefit 정책과 단일 전역 정책 구현 차이 | `CONFIRMED` | singleton policy head와 단일 case policy |
| README와 실제 구현 상태 불일치 | `CONFIRMED` | store lifecycle을 예정으로 표기; 이번 감사에서 교정 |
| policy traceability `Ready`가 선행조건 미반영 | `CONFIRMED` | BR-14/15/16을 prerequisite-blocked로 교정 |
| 하나의 active ExecPlan이 독립 기능을 과다 포함 | `CONFIRMED` | 여섯 하위 계획으로 분리 |

## Resolved by recency

다음은 새로운 제품 질문 없이 최신 explicit amendment로 닫았다.

- Refund customer projection: ADR-038/050
- Notification accepted delivery boundary: ADR-044
- Refund terminal notification events: ADR-045/046
- primary notification step 단조성: ADR-047
- event data minimization: ADR-055
- replay indicator 제거: ADR-057
- compensation migration 조건: ADR-059

선택 근거 commit은 `04e2b4819a66966952c5436342a05149fd7ac6ee`이며 PR #17의 단일
commit이다. commit 내부 순서는 explicit amendment 문구로 판단했다.

## User decisions

### Required now

없음. 다음 항목은 이미 Accepted 문서가 명확하므로 다시 묻지 않았다.

- 선행 부분 환불이 있는 주문도 고객 취소 허용
- Settlement NOT_APPLICABLE을 고객 취소 범위에 포함
- trigger×benefit 정책과 두 snapshot
- C0 200/C1 202, 별도 Cancellation Aggregate 없음

clean cutover는 정책 선택이 아니라 운영 사실 gate다. 증거가 없을 때 실패하라는 규칙도
ADR-059와 이번 감사 요청에 명시돼 있으므로 사용자 선택으로 대체하지 않는다.

### Future decision condition

gate가 nonzero evidence를 확인하면 실제 legacy schema/publication/consumer를 바탕으로
forward migration·compatibility 범위를 정하는 새 Accepted ADR이 필요하다. 현재는 사실
입력이 없으므로 구체 호환 전략을 임의로 기록하지 않는다.

## Fact-verification gate

| Required fact | Repository evidence | Status |
|---|---|---|
| shared/production DB와 compensation table 존재 | 없음 | Unknown |
| table row 수 | 없음 | Unknown |
| completed `OrderRejectedV1`/`OrderCancelledV1` publication | 없음 | Unknown |
| incomplete publication | 없음 | Unknown |
| 외부·독립 consumer | 없음 | Unknown |
| rollback 대상 binary/data | 없음 | Unknown |
| migration V8 적용 환경 | 없음 | Unknown |

```text
CLEAN_CUTOVER_GATE = FAILED
```

PR #17에는 review/comment/evidence attachment가 없고, commit message도 gate 통과가 아닌
“통과를 전제로 명시”라고 기록한다. 저장소의 local migration과 test fixture는 외부
운영 사실 증거가 아니다.

## Implementation drift

| Area | Current behavior | Target behavior | Primary files |
|---|---|---|---|
| Customer command | endpoint/service 없음 | C0/C1/CT와 stored response | `OrderController.kt`, 신규 service/contracts |
| Order model | CANCELLED cause/reason/time 없음 | cancellation fields와 CHECK | `Order.kt`, `OrderingPersistence.kt`, migration |
| Refund composition | 선행 성공 환불이면 거부 | remaining cash + allocation | `RejectionRefundService.kt`, `Refund.kt`, V10 |
| Refund recovery | REQUEST 1 + LOOKUP 4 합산 5 | REQUEST 3 + LOOKUP 5 분리 | `Refund.kt`, `RejectionRefundService.kt` |
| Provider failure | 모든 explicit failure terminal | allowlist만 safe request retry | Payment gateway/service |
| Compensation | rejection 전용, 단일 policy | trigger-aware Case와 두 policy | Operations API/service/persistence, V8 |
| Publication failure | 모든 미완료 step manual review | 실패 listener step만 manual review | `RejectionCompensationService.kt`, recovery worker |
| Events | reason/customer/store와 단일 policy | 최소 payload/두 policy 또는 호환 version | `StoreOrderEvents.kt`, producer/listeners |
| Owner restore | `RELEASED_BY_REJECTION`, event-ID source | common termination state, stable owner source | four owner APIs/listeners, V9 |
| Notification | rejection/warning/ready template | accepted/succeeded/delayed logical sources | Notification service/persistence, V11 |
| Store idempotency | orderId hash 누락, V1 op, replay body 변경 | orderId 포함, V2 op, stored body 불변 | store transition files/tests |
| Setup recovery | 없음 | detector/scanner/case/2인 repair | Payment/Operations, V12 이후 |
| Tests | customer cancellation suite 없음 | contract/concurrency/failure/restart tests | 신규 module별 tests |

현재 code와 test를 target 문서에 맞춰 되돌리지 않았다. 모든 차이는 후속 구현 계획의
입력이다.

## Missing foundations

### Partial refund allocation

현금 총액만으로는 선행 부분 환불이 어느 line 혜택을 복원했는지 알 수 없다. 현재
OrderLine snapshot은 최초 배분만 보존하며 성공 Refund allocation 원장이 없다. plan 10이
line 상한, source unique, 성공 시점 원장과 remaining query를 먼저 구현한다.

### Settlement

정책과 OpenAPI만 있고 module/table/consumer가 없다. no-op consumer로 NOT_APPLICABLE을
표현하면 정상 제외 증거와 실패 의미가 사라진다. plan 20이 Settlement owner와 최소
consumer foundation을 먼저 구현한다.

### Common compensation

rejection 전용 schema/event/API는 customer cancellation trigger와 two-policy snapshot을
표현하지 못한다. plan 30이 gate가 허용한 migration/version 전략 위에서 일반화한다.

## Correct implementation order

1. `00-contract-baseline`: 외부 운영 사실과 migration/event 전략 확정
2. `10-partial-refund-allocation-foundation`: line-level cash/benefit 원장
3. `20-settlement-foundation`: 완료 정산 원천과 취소 NOT_APPLICABLE 증적
4. `30-order-compensation-foundation`: trigger-aware Case, policy, owner convergence
5. `40-command`: Order 취소 모델, C0/C1/CT, API와 commit gate
6. `50-recovery`: Refund budgets, result events, Notification, setup repair, release 검증

10/20/30은 00의 계약·migration 전략을 입력으로 독립 진행할 수 있으나 40은 세 계획이
모두 완료돼야 시작한다. 50과 전체 release가 끝나기 전 production endpoint를
활성화하지 않는다.

## Implementation start checklist

- [ ] environment inventory가 완전함
- [ ] DB/table/row evidence가 있음
- [ ] completed/incomplete publication evidence가 있음
- [ ] external consumer와 rollback binary evidence가 있음
- [ ] gate 결과에 맞는 migration/event ADR과 ExecPlan이 Accepted임
- [ ] partial refund allocation plan이 통과함
- [ ] Settlement foundation plan이 통과함
- [ ] common compensation plan이 통과함
- [ ] OpenAPI semantic/local contract 검사가 통과함
- [ ] 기능 branch에서 기존 사용자 변경을 분리·보존함

위 체크가 모두 닫히기 전 고객 취소 command 구현은 시작할 수 없다.

## Audit validation

- `bash scripts/verify-docs.sh`: **Passed**. OpenAPI 3.1 YAML parse, local `$ref`,
  mutation Idempotency-Key, Error envelope, cancellation semantic assertions,
  32 policies, 60 ADR index/status entries와 108 Markdown link 검사를 통과했다.
- `git diff --check`: **Passed**.
- `./gradlew test --tests '*ModularityTests' --console=plain`: **Passed**.
- `./gradlew test --tests '*OrderControllerContractTest' --tests '*ModularityTests' --console=plain`:
  **Blocked by environment**. 12개 중 API contract 11개가 assertion 전 Spring context
  초기화에서 Docker/Testcontainers provider 미탐지로 실패했다. 같은 실행에서 확인이
  섞이지 않도록 ModularityTests는 위 명령으로 분리 재실행해 통과했다.
- full OpenAPI semantic validator: **Not configured**. 저장소의 parse/local/targeted
  semantic assertions만 실행했다.
- full build, 전체 Testcontainers suite, Spotless: **Not run**. 애플리케이션 코드를
  변경하지 않았고 Docker provider가 이용 불가했다.

이전 CI 결과는 이번 감사 결과로 사용하지 않았다.

## Revisit conditions

- external/independent consumer 또는 applied production migration이 발견될 때
- rollback binary 보존 기간이 확정될 때
- Settlement 범위를 변경하려는 제품 결정이 생길 때
- 부분 환불 정책 또는 allocation source가 변경될 때
