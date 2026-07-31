# Customer Order Cancellation Readiness Audit

## Audit identity

- **Audited at:** 2026-07-31 Asia/Seoul
- **Historical audit branch:** `feature/customer-order-cancellation-docs`
- **Historical audit source SHA:** `04e2b4819a66966952c5436342a05149fd7ac6ee`
- **Historical audit merge:** PR #17, merge commit
  `443fe8ff4d41776f1754e5a5c17ab8566e68398d`, merged 2026-07-31
- **Current reconciliation baseline:** `main`, PR #18 merge commit
  `783298a9c1b349f7b444d49d25c8b3d4099a5576`
- **Scope:** 고객 주문 취소·환불 정책, ADR, 아키텍처, API/OpenAPI, migration,
  Ordering/Payment/Operations/owner/Notification/Settlement 코드, 테스트, active/completed
  ExecPlan과 관련 Git/PR 이력
- **Application code changed by this audit:** No
- **Migration changed by this audit:** No

감사 시작 시 working tree에는 사용자 변경인 `README.md` 실행 안내 확장과 기존 master
plan의 ADR-059 release gate 완료 체크가 있었다. 실행 안내는 보존했고, evidence가 없는
release gate 완료 표시는 아래 역사적 판정에 따라 제거했다. 이후 product owner 확인에서
non-local 환경과 관련 artifact가 모두 없음을 확인해 별도 release evidence로 기록했다.

## Final readiness

```text
READY FOR CONTRACT-BASELINE AND FOUNDATION WORK
BLOCKED FOR CUSTOMER-CANCELLATION COMMAND
```

canonical 문서와 목표 OpenAPI의 취소·환불 의미는 정합화됐고 ADR-059 fact gate도
명시적 0 증거로 닫혔다. 독립 foundation 작업은 clean-cutover 경로로 진행할 수 있다.
고객 취소 command 구현은 다음 선행조건이 닫힐 때까지 시작할 수 없다.

1. 부분 환불 line-level cash/benefit allocation foundation 부재
2. Settlement Context와 consumer 구현 부재
3. trigger-aware 공통 compensation foundation 부재

현재 release evidence와 판정은
[customer-order-cancellation-release-evidence.md](customer-order-cancellation-release-evidence.md)에
기록한다.

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
- 선행 성공 부분 환불 허용, 남은 cash와 아직 복원되지 않은 point allocation만 처리;
  부분 환불은 쿠폰을 복원하지 않고 전체 종료가 원 쿠폰을 한 번 복원
- request retry 최대 3회, UNKNOWN 이후 request 중단과 lookup 최대 5회
- 고객 projection: 내부 processing/unknown/reconciling은 `PROCESSING`,
  failed/manual review/setup incomplete은 `PROCESSING + REFUND_DELAYED`
- `OrderCancelledV1`: PAID 취소에서 Fulfillment/Inventory/Promotion/Loyalty 네 consumer만
- Settlement: 미완료 고객 취소에 Item/Adjustment 없이 source-unique NOT_APPLICABLE Audit
- 선행 Refund 차단 상태: `REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`,
  `RECONCILING`, `MANUAL_REVIEW` 여섯 개. `SUCCEEDED`와 명시 `FAILED`만 허용
- `NOT_REQUIRED`는 취소 요청액 0만 뜻하고 네 금액은 all-or-nothing으로 반환·생략.
  네 금액 0은 `BENEFIT_ONLY`뿐이고 `PENDING_PAYMENT`는 네 금액을 생략한다
- Order projection 분리: 고객 `Order`는 cancelledAt/cancellationCause/
  cancellationReasonCode, 매장 `StoreOrder`는 cancelledAt/cancellationCause만
- Compensation projection 분리: 매장은 trigger/state/updatedAt만 담은
  `StoreCompensationSummary`, 운영자만 여섯 step·attempt·error·caseId·policy version

## Conflict matrix

| Topic | Evidence | Classification | Resolution / blocker |
|---|---|---|---|
| release gate 완료 체크와 evidence 부재 | 기존 master plan은 완료 체크, ADR-059/PR #17에는 외부 증거 없음 | `NEEDS_FACT_VERIFICATION` | 역사적 체크 제거·gate 실패 기록 후 운영 상태 evidence를 추가해 현재 `CLEAN_CUTOVER_GATE = PASSED` |
| 결제 승인 recovery와 고객 취소 환불 recovery schema 공유 | `PaymentConfirmation.recovery`, `Cancellation.paymentRecovery`, `Order.paymentRecovery`가 `PaymentRecoverySummary` 하나를 참조 | `CONTRACT_CONFLICT` | `PaymentApprovalRecoverySummary`와 `CancellationRefundRecoverySummary`로 분리 |
| ADR-033 forward rename과 ADR-059 clean cutover | ADR-059가 rename/backfill 부분만 명시적으로 대체 | `RESOLVABLE_BY_RECENCY` | ADR-059를 explicit amendment로 기록; gate 전에는 둘 다 실행 금지 |
| `OrderRejectedV1` 제자리 변경 단정 | BR-14, ADR-034, event catalog가 publication 0을 사실로 단정 | `RESOLVABLE_BY_RECENCY` | ADR-059 조건부 gate에 맞춰 모두 수정 |
| 내부 Refund 상태의 고객 노출 | ADR-030/033 초기 원천과 ADR-038/050 projection | `RESOLVED_BY_AMENDMENT` | ADR-030/031/033의 reciprocal amendment와 OpenAPI customer enum 교정 |
| `OrderCancelledV1` reason/customer/store 포함 여부 | BR-14 과거 전달 문구, ADR-034 초기 payload와 ADR-055 | `RESOLVED_BY_AMENDMENT` | Order/Audit/Refund·Provider/event/log 범위와 최소 payload를 일치시킴 |
| clean-cutover와 legacy compatibility test 범위 | BR-14의 unconditional legacy test와 ADR-059 조건부 gate | `RESOLVED_BY_GATE_PATH` | clean-cutover와 forward-migration Required Tests를 gate 결과별로 분리 |
| 접수 Notification event consumer 여부 | ADR-034 초기 구조와 ADR-044 | `RESOLVABLE_BY_RECENCY` | C0/C1 직접 Delivery, Notification은 event consumer 아님 |
| `OrderRejectedV1` Operations consumer 표기 | Event Catalog 표와 실제/목표 Case 직접 생성 경계 | `RESOLVABLE_BY_RECENCY` | Operations를 event consumer에서 제거; Case는 원 transaction에서 생성 |
| Context Map의 Notification 경계 | 일반 after-commit 서술과 ADR-044의 C0/C1 직접 Delivery | `RESOLVABLE_BY_RECENCY` | 일반 event와 취소 접수 동기 API를 구분 |
| 처리 중 idempotency의 HTTP 의미 | API convention 일반 문구와 주문 생성 409/취소 lock 직렬화 | `RESOLVABLE_BY_RECENCY` | command별 계약으로 일반 문구를 제한 |
| Cancellation `orderState` 범위 | OpenAPI가 전체 OrderState 허용, ADR-031은 성공 시 CANCELLED | `IMPLEMENTATION_DRIFT` | OpenAPI를 `const: CANCELLED`로 교정 |
| detail trim/empty/control 계약 | 정책은 normalize 후 검증, OpenAPI는 raw min/max만 검사 | `RESOLVABLE_BY_RECENCY` | OpenAPI에 normalize 의미와 control-char pattern 반영 |
| 선행 Refund unresolved 상태 | OpenAPI·closure는 RETRY_SCHEDULED 포함, BR-14·ADR-031·ADR-036·error catalog·transaction boundaries는 누락 | `RESOLVED_BY_AMENDMENT` | 2026-08-01 product owner 확정에 따라 여섯 상태로 통일; ADR-036 clarification과 정책·계약 문서 갱신 |
| `NOT_REQUIRED` 금액 계약 | OpenAPI는 네 금액 `const: 0` 강제, ADR-036은 요청액 0만 정의(선행 전액 환불 시 승인액 양수) | `CONTRACT_CONFLICT` | 2026-08-01 확정: OpenAPI는 요청액 0과 notice 부재만 강제하고 네 금액을 all-or-nothing으로 계약 |
| Order 표현의 취소 필드 | ADR-050/030은 취소 시각·원인·reason code 조회를 전제, OpenAPI `Order`(additionalProperties:false)에는 필드 없음 | `CONTRACT_CONFLICT` | 2026-08-01 확정: 고객 `Order`에 세 필드 추가, 매장은 `StoreOrder` projection으로 reason code·`paymentRecovery` 제외 |
| Refund attempt 상한 | ADR-036은 총 `attempt_count` 상한 6, ADR-038·aggregate invariants·payment runbook은 REQUEST 3 + LOOKUP 5 = 8 | `RESOLVED_BY_AMENDMENT` | 2026-08-01 확정: 두 예산은 독립이고 전체 상한은 8; ADR-036 문구를 clarification으로 교정하고 6은 결과 불명 경로 한정으로 남김 |
| `PENDING_PAYMENT` 네 금액 | ADR-036은 네 금액 모두 0, ADR-031·api conventions·ADR-050은 검증 불가 금액 생략 | `RESOLVED_BY_AMENDMENT` | 2026-08-01 확정: 생략이 canonical이고 네 금액 0은 `BENEFIT_ONLY`뿐; ADR-036과 OpenAPI description 교정 |
| 매장 보상 step 노출 | authorization matrix·ADR-030·ADR-033 Verification·api conventions는 운영자 전용, OpenAPI·plan 30·runbook·현재 `StoreOrderContracts.kt`는 매장에 여섯 step 노출 | `CONTRACT_CONFLICT` + `IMPLEMENTATION_DRIFT` | 2026-08-01 확정: 매장은 축약 `StoreCompensationSummary`; OpenAPI에 schema 신설하고 plan 30 clean cutover에서 store DTO 축약 |
| 부분 환불 허용과 현재 전액 거절 Refund | ADR-036 대 `RejectionRefundService` | `IMPLEMENTATION_DRIFT` + `MISSING_FOUNDATION` | allocation foundation plan 10 선행 |
| Settlement NOT_APPLICABLE와 구현 부재 | ADR-048/BR-16, 코드·migration에 Settlement 없음 | `MISSING_FOUNDATION` | Settlement foundation plan 20 선행 |
| trigger×benefit 정책과 singleton 구현 | ADR-041 대 V8/Operations singleton policy | `IMPLEMENTATION_DRIFT` | compensation foundation plan 30 |
| store transition hash/replay 계약 | BR-25/ADR-057 대 hash에서 orderId 누락, V1 operation, replay body mutation | `IMPLEMENTATION_DRIFT` | plan 30의 store regression 범위 |
| 정책 trace의 Ready 과장 | BR-14/15/16 선행 기반·gate 미반영 | `RESOLVABLE_BY_RECENCY` | prerequisite-blocked로 교정 |
| active master plan 과대 범위 | 하나의 plan에 schema·command·recovery·Settlement·repair 혼합 | `MISSING_FOUNDATION` | 여섯 하위 ExecPlan으로 분리 |

## Previous audit candidate verification

| Candidate | Verdict | Current evidence and action |
|---|---|---|
| compensation clean-cutover release gate 미검증 | `CONFIRMED, THEN CLOSED` | 역사적 repository/PR에는 external evidence가 없었으나 이후 product owner 운영 상태 확인으로 전 항목 0을 기록해 gate passed |
| 결제 승인 recovery와 취소 환불 recovery schema 혼합 | `FIXED` | 실제로 세 응답이 한 schema를 공유하고 있었으며 OpenAPI schema와 참조를 두 의미로 분리 |
| 고객 환불 상태를 내부 상태 그대로 노출하는 과거 ADR과 최신 projection 충돌 | `FIXED` | ADR-030/033에 ADR-038/050 amendment 관계와 최신 Decision·Consequences·Required Tests 반영 |
| 취소 reason code의 persistent event 포함 여부 충돌 | `FIXED` | BR-14/ADR-055에 Order·Audit·Refund/Provider·event·log별 범위를 단일 계약으로 정리 |
| clean cutover와 legacy event compatibility 요구 충돌 | `FIXED; CLEAN PATH SELECTED` | Required Tests를 조건부 경로로 분리하고 운영 상태 evidence의 전 항목 0에 따라 clean-cutover 경로 선택 |
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

clean cutover는 제품 정책 선택이 아니라 운영 사실 gate다. 증거가 없었던 역사적 감사
시점에는 실패로 처리했고, product owner의 현재 운영 상태 확인을 별도 evidence로
기록해 전 항목 0을 확인했다.

### Future decision condition

gate가 nonzero evidence를 확인하면 실제 legacy schema/publication/consumer를 바탕으로
forward migration·compatibility 범위를 정하는 새 Accepted ADR이 필요하다. 현재 확인은
point-in-time evidence이므로 compensation schema 변경과 최초 non-local 배포 직전에
inventory를 다시 확인한다.

## Fact-verification gate

| Required fact | Attested evidence | Status |
|---|---|---|
| shared/production DB와 compensation table 존재 | non-local 환경과 DB 없음 | Confirmed absent (0) |
| table row 수 | 대상 DB 없음 | Confirmed absent (0) |
| completed `OrderRejectedV1`/`OrderCancelledV1` publication | 외부 publication registry 없음 | Confirmed absent (0) |
| incomplete publication | 외부 publication registry 없음 | Confirmed absent (0) |
| 외부·독립 consumer | 독립 배포 없음 | Confirmed absent (0) |
| rollback 대상 binary/data | production 배포·data 없음 | Confirmed absent (0) |
| migration V8 적용 환경 | 적용 대상 non-local 환경 없음 | Confirmed absent (0) |

```text
CLEAN_CUTOVER_GATE = PASSED
```

PR #17에는 review/comment/evidence attachment가 없었으므로 역사적 감사에서는 gate를
실패로 판정했다. 이후 product owner 확인의 범위와 항목별 0 결과는
[release-gate evidence](customer-order-cancellation-release-evidence.md)에 기록했다.
저장소의 local migration과 test fixture는 이 외부 운영 사실 증거로 계산하지 않는다.

## Implementation drift

| Area | Current behavior | Target behavior | Primary files |
|---|---|---|---|
| Customer command | endpoint/service 없음 | C0/C1/CT와 stored response | `OrderController.kt`, 신규 service/contracts |
| Order model | CANCELLED cause/reason/time 없음 | cancellation fields와 CHECK | `Order.kt`, `OrderingPersistence.kt`, migration |
| Refund composition | 선행 성공 환불이면 거부 | remaining cash + allocation | `RejectionRefundService.kt`, `Refund.kt`, V10 |
| Refund recovery | REQUEST 1 + LOOKUP 4 합산 5 | REQUEST 3 + LOOKUP 5 분리 | `Refund.kt`, `RejectionRefundService.kt` |
| Provider failure | 모든 explicit failure terminal | allowlist만 safe request retry | Payment gateway/service |
| Compensation | rejection 전용, 단일 policy | trigger-aware Case와 두 policy | Operations API/service/persistence, V8 |
| Store compensation projection | 매장 응답이 여섯 step·attemptCount·lastErrorCode·caseId·policyVersion 노출 | trigger·state·updatedAt만 담은 축약 요약 | `StoreOrderContracts.kt`, `StoreOrderTransitionService.kt` |
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

현금 총액만으로는 선행 부분 환불이 어느 line 포인트를 복원했고 coupon 할인액이 어느
line에 귀속됐는지 알 수 없다. 현재 OrderLine snapshot은 최초 배분만 보존하며 성공
Refund allocation 원장이 없다. plan 10이 line 상한, source unique, 성공 시점의
cash/point 원장, 비복원 coupon attribution과 remaining query를 먼저 구현한다.

### Settlement

정책과 OpenAPI만 있고 module/table/consumer가 없다. no-op consumer로 NOT_APPLICABLE을
표현하면 정상 제외 증거와 실패 의미가 사라진다. plan 20이 Settlement owner와 최소
consumer foundation을 먼저 구현한다.

### Common compensation

rejection 전용 schema/event/API는 customer cancellation trigger와 two-policy snapshot을
표현하지 못한다. plan 30이 gate가 허용한 migration/version 전략 위에서 일반화한다.

## Correct implementation order

1. `00-contract-baseline`: 외부 운영 사실 0 확인과 ADR-059 clean-cutover 전략 확정
2. `10-partial-refund-allocation-foundation`: line-level cash/point restoration과 coupon attribution 원장
3. `20-settlement-foundation`: 완료 정산 원천과 취소 NOT_APPLICABLE 증적
4. `30-order-compensation-foundation`: trigger-aware Case, policy, owner convergence
5. `40-command`: Order 취소 모델, C0/C1/CT, API와 commit gate
6. `50-recovery`: Refund budgets, result events, Notification, setup repair, release 검증

10/20/30은 00의 계약·migration 전략을 입력으로 독립 진행할 수 있으나 40은 세 계획이
모두 완료돼야 시작한다. 50과 전체 release가 끝나기 전 production endpoint를
활성화하지 않는다.

## Implementation start checklist

- [x] environment inventory가 완전함
- [x] DB/table/row evidence가 있음
- [x] completed/incomplete publication evidence가 있음
- [x] external consumer와 rollback binary evidence가 있음
- [x] gate 결과에 맞는 migration/event ADR과 ExecPlan이 Accepted임
- [ ] partial refund allocation plan이 통과함
- [ ] Settlement foundation plan이 통과함
- [ ] common compensation plan이 통과함
- [x] OpenAPI semantic/local contract 검사가 통과함
- [ ] 기능 branch에서 기존 사용자 변경을 분리·보존함

남은 foundation 체크가 모두 닫히기 전 고객 취소 command 구현은 시작할 수 없다.

## Historical audit validation

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

## Historical contract reconciliation validation (2026-08-01)

- `bash scripts/verify-docs.sh`: **Passed**. 2026-08-01 모순 해소 반영 후 재실행에서
  19 OpenAPI paths와 59 schemas의 YAML/local reference/targeted semantic 검사, 32
  policies, 60 ADRs와 109 Markdown files 검사를 통과했다. recovery schema 참조 분리,
  고객 projection enum, reason data boundary, release-gate 조건부 test path와
  clean-cutover 운영 상태 evidence 검사를 포함한다. 이 실행은 refund attempt 예산,
  `PENDING_PAYMENT` 금액 표현과 매장 보상 projection 교정을 포함한 상태다.
- `git diff --check`: **Passed**.
- full OpenAPI semantic validator: **Not configured**.
  `openapi_spec_validator`와 별도 `spectral`/`redocly`/`swagger-cli` executable이 현재
  환경에 없다. 저장소 검증 스크립트의 PyYAML parse, local `$ref`와 targeted semantic
  assertions는 통과했다.
- Gradle/application tests: **Not run**. 이번 변경은 문서, OpenAPI와 문서 검증
  스크립트에만 한정됐고 Kotlin/test/migration 파일을 변경하지 않았다.

## Revisit conditions

- compensation schema 변경 또는 최초 non-local 배포 직전 gate inventory 재확인
- external/independent consumer 또는 applied production migration이 발견될 때
- rollback binary 보존 기간이 확정될 때
- Settlement 범위를 변경하려는 제품 결정이 생길 때
- 부분 환불 정책 또는 allocation source가 변경될 때
