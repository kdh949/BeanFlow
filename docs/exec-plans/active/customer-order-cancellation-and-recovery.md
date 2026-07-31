# 고객 주문 취소 구현 순서를 조정한다

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

이 문서는 고객 주문 취소 capability의 구현 계획이 아니라 여섯 하위 ExecPlan의
의존관계와 release gate를 관리하는 master orchestration plan이다. 각 하위 계획은
이전 대화 없이 독립 실행할 수 있으며, 선행 기반이 완료되기 전에는 고객 취소 HTTP
기능을 활성화하지 않는다.

## Current State

- 역사적 readiness 감사 source는 `04e2b4819a66966952c5436342a05149fd7ac6ee`이고
  당시 merge 기준은 PR #17의 `443fe8ff4d41776f1754e5a5c17ab8566e68398d`다.
- 현재 이 master plan의 merge baseline은 `main`의 PR #18 merge
  `783298a9c1b349f7b444d49d25c8b3d4099a5576`다. 역사적 감사 SHA를 현재 HEAD로
  해석하지 않는다.
- ADR-029~065와 OpenAPI에는 목표 계약이 Accepted 상태로 기록돼 있다.
- 고객 취소 Controller, Application Service, Order 취소 필드와 migration은 없다.
- 현재 Refund는 거절 전용이고 부분 환불을 차단하며 REQUEST/LOOKUP 합산 5회를 쓴다.
- compensation은 `RejectionCompensation*`와 단일 benefit 정책으로 구현돼 있다.
- Settlement package, SettlementItem/Batch/Adjustment persistence와 consumer가 없다.
- line-level cash/benefit refund allocation 원장이 없다.
- product owner 운영 상태 확인에서 non-local 환경과 관련 DB·publication·consumer·rollback artifact가
  모두 없음을 확인했고 항목별 0을
  [release evidence](../../quality/customer-order-cancellation-release-evidence.md)에
  기록했다. 현재 `CLEAN_CUTOVER_GATE = PASSED`이며 ADR-059 clean-cutover 경로를 쓴다.

## Definitions

- **Contract baseline:** 구현이 따라야 할 정책·ADR·OpenAPI와 운영 사실 gate의 닫힌 집합.
- **Foundation:** 고객 취소 명령보다 먼저 독립적으로 완성해야 하는 allocation,
  Settlement 또는 공통 compensation 모델.
- **Tx C0:** `PENDING_PAYMENT` 취소와 네 예약 해제를 완결하는 로컬 transaction.
- **Tx C1:** 미수락 `PAID` 취소와 모든 비동기 후속 작업의 내구 착수를 저장하는 로컬
  transaction.
- **Release gate:** 기존 migration 또는 V1 계약을 제자리 변경해도 되는지를 운영
  증거로 판단하는 gate.

## Scope

### In Scope

- 하위 ExecPlan의 순서, 완료 조건과 차단 조건 관리
- 정책·계약 baseline과 fact-verification evidence 연결
- allocation·적립 포인트 회수·issuer snapshot, Settlement, compensation, command, recovery의 범위 분리
- 각 계획 결과를 다음 계획의 검증 가능한 입력으로 전달

### Non-goals

- 이 master plan에서 기능 코드, Entity, Controller 또는 migration 작성
- 하위 계획의 실패 경로를 하나의 거대 milestone로 다시 합치기
- release evidence 없이 clean cutover 가정
- Accepted 제품 범위를 줄여 선행 기반을 생략

## Business Rules and Invariants

- 고객 취소는 `PENDING_PAYMENT`와 acceptance deadline 전 `PAID`만 허용한다.
- 선행 성공 부분 환불은 고객 취소를 막지 않고 남은 현금과 아직 복원되지 않은 point
  allocation만 처리한다. 부분 환불은 쿠폰을 복원하지 않고 전체 종료가 원 쿠폰을 한
  번 복원한다.
- 환불 적립 포인트의 실제 가용 잔액 차감은 `RECOVERY` transaction으로 기록하고,
  미회수 잔액만 PointRecoveryPending으로 보존해 이후 적립에서 먼저 상계한다.
- Plan 10의 만료 부분 환불 compensation은 original PointLot의 immutable issuer snapshot을
  보존하며, legacy issuer source가 unresolvable이면 추정 backfill이나 issuer 없는
  compensation으로 진행하지 않는다.
- Order `CANCELLED`는 Refund·자원 복원·Notification 성공을 뜻하지 않는다.
- 외부 결과 불명은 성공이나 확정 실패로 바꾸지 않는다.
- `200/202` 전에 해당 경로의 내구 commit gate가 완성돼 있어야 한다.
- 확인되지 않은 DB, publication, consumer와 rollback binary 수를 0으로 간주하지 않는다.

## Architecture and Transaction Boundaries

하위 계획 순서는 다음과 같다.

```text
00 contract baseline and release facts
 ├─> 10 partial-refund allocation foundation
 ├─> 20 Settlement foundation
 └─> 30 common Order compensation foundation
          10 + 20 + 30 ─> 40 customer cancellation command
          20 + 30 + 40 ─> 50 customer cancellation recovery
```

계획 40의 endpoint는 계획 50이 완료되기 전 production에서 성공 응답을 반환하도록
활성화하지 않는다. 각 외부 Provider 호출은 claim transaction과 result transaction
사이에 위치하며 장시간 DB transaction 안에서 실행하지 않는다.

## Alternatives Considered

- 기존 576줄 단일 계획 유지: 독립 foundation과 feature release가 섞여 선행조건을
  우회할 수 있어 제외한다.
- 고객 취소 command부터 구현: 부분 환불·Settlement·compensation 불변식을 나중에
  채우게 되어 Accepted `202` 의미를 위반하므로 제외한다.
- Settlement를 범위에서 제거: Accepted ADR-048과 ADR-060을 바꾸는 제품 범위 변경이므로
  사용자 결정과 정책 amendment 없이 선택하지 않는다.

## Failure Semantics

- 00의 fact gate는 닫혔다. 구현·배포 전 재확인에서 PASS가 무효화되면 30 이후 schema
  전환을 중단하고 forward-migration ADR/ExecPlan을 먼저 확정한다.
- foundation 검증이 실패하면 후속 계획을 진행하지 않고 실패를 해당 계획에 기록한다.
- endpoint feature flag나 profile로 미완성 경로를 성공시키지 않는다.
- 테스트 fake는 test 또는 명시적 local profile에만 둔다.

## Data and Migration

- 현재 감사에서는 migration을 작성하거나 수정하지 않는다.
- ADR-059 gate의 모든 항목이 0으로 입증돼 clean cutover를 선택했다.
- producer, consumer와 fixture를 같은 변경에서 전환하고 legacy compatibility layer와
  version 이중 발행을 추가하지 않는다.
- gate가 nonzero 또는 unknown이면 forward migration, publication drain,
  compatibility와 rollback을 다루는 Accepted ADR/ExecPlan이 먼저 필요하다.
- 이후 migration 번호는 구현 직전 저장소의 최신 번호에서 다시 계산한다.

## API and Event Contracts

- HTTP 원본은 `openapi/beanflow-v1.yaml`이다.
- `POST /orders/{orderId}/cancellations`: C0 `200`, C1 `202`.
- `PaymentConfirmation.recovery`는 `PaymentApprovalRecoverySummary`, 고객 취소
  `Cancellation.paymentRecovery`와 `Order.paymentRecovery`는
  `CancellationRefundRecoverySummary`를 사용한다.
- `OrderCancelledV1`: PAID 취소에서 네 owner만 소비한다.
- Payment와 Notification은 `OrderCancelledV1` consumer가 아니다.
- 고객은 내부 Refund/Case 상태 대신 `CancellationRefundRecoverySummary` projection만 본다.
- PointTransaction의 공개 금액은 signed balance effect이며 `RECOVERY`는 음수다.
  PointRecoveryPending은 실제 debit transaction이 아니라 회수 대기 잔액의 Loyalty
  owner Aggregate이고, `PointRecoveryPendingRecorded`의 source of truth다.

## Milestones

1. [고객 취소 계약 baseline과 release gate를 닫는다](../completed/customer-order-cancellation-00-contract-baseline.md)
2. [부분 환불 allocation과 point recovery foundation을 만든다](customer-order-cancellation-10-partial-refund-allocation-foundation.md)
3. [Settlement foundation과 취소 제외 증적을 만든다](customer-order-cancellation-20-settlement-foundation.md)
4. [공통 Order compensation foundation을 만든다](customer-order-cancellation-30-order-compensation-foundation.md)
5. [고객 취소 command와 Tx C0/C1을 구현한다](customer-order-cancellation-40-command.md)
6. [고객 취소 recovery와 운영 수렴을 구현한다](customer-order-cancellation-50-recovery.md)

각 링크의 계획이 자체 Required Tests와 Validation Commands를 통과하고 Outcomes에 실제
결과를 남겨야 다음 계획이 시작된다.

## Required Tests

- 하위 계획 링크와 의존관계가 순환하지 않음
- 00 미완료 상태에서 migration 제자리 수정이 시작되지 않음
- 10/20/30 미완료 상태에서 취소 endpoint가 활성화되지 않음
- 10 미완료 상태에서 `RECOVERY`/PointRecoveryPending target contract를 구현된 것처럼
  노출하지 않음
- 40만 완료된 상태에서 production success path가 노출되지 않음
- 각 계획의 PostgreSQL, contract, Modulith, 동시성·장애 suite 결과가 실제 기록됨

## Validation Commands

```bash
bash scripts/verify-docs.sh
git diff --check
```

기능 구현 계획에서는 각 하위 문서의 Gradle/PostgreSQL 명령을 추가 실행한다.

## Observability

이 master plan은 runtime metric을 추가하지 않는다. cancellation, refund, compensation,
notification, settlement와 setup integrity metric은 각 하위 계획이 정의한다.

## Documentation Updates

- `docs/quality/customer-order-cancellation-readiness.md`
- `docs/decisions/customer-order-cancellation-decision-closure.md`
- ADR-059 release evidence 또는 대체 forward-migration ADR
- ADR-065 recovery ledger 구현 evidence
- 각 하위 ExecPlan의 Progress, Decision Log와 Outcomes

## Progress

- [x] 2026-07-31 정책·ADR·OpenAPI 감사
- [x] 2026-07-31 recovery schema·projection·reason 전달 범위·release path 계약 정합화
- [x] 2026-07-31 거대 master plan을 여섯 하위 계획으로 분리
- [x] 2026-07-31 00 fact-verification gate 완료 — 모든 외부 항목 0, clean cutover
- [ ] 10 allocation·point recovery foundation 완료
- [ ] 20 Settlement foundation 완료
- [ ] 30 common compensation foundation 완료
- [ ] 40 customer cancellation command 완료
- [ ] 50 recovery와 release verification 완료

## Surprises & Discoveries

- release gate 완료 체크는 저장소·PR의 외부 운영 증거로 뒷받침되지 않았다.
- 이후 product owner 확인으로 non-local 환경과 관련 artifact가 모두 0임을 별도
  release evidence에 기록해 fact gate를 닫았다.
- 최신 정책은 부분 환불을 허용하지만 현재 Refund가 부분 환불을 명시적으로 거부한다.
- Settlement 정책은 Accepted지만 해당 모듈 구현이 전혀 없다.
- 기존 store 전이 멱등 응답도 BR-25/ADR-057보다 오래된 계약을 구현한다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Accepted existing | 고객 취소 범위와 C0/C1 계약 유지 | ADR-029~060이 명확함 | BR-14, ADR-029~060 |
| 2026-07-31 | Fact gate failed | clean cutover를 시작하지 않음 | 외부 운영 증거 없음 | ADR-059, readiness report |
| 2026-07-31 | Fact gate passed | ADR-059 clean cutover 사용 | 운영 상태 확인으로 모든 외부 항목이 명시적 0 | release-gate evidence |
| 2026-07-31 | Plan structure | foundation별 여섯 계획으로 분리 | 독립 검증과 선행조건 강제 | 이 master plan |
| 2026-08-01 | Accepted | `RECOVERY` debit과 PointRecoveryPending foundation은 Plan 10이 소유 | 부분 환불과 이후 고객 취소가 같은 refund source·point recovery 불변식을 소비 | BR-13, ADR-065 |
| 2026-08-01 | Accepted existing | PointLot issuer snapshot precheck/migration은 Plan 10이 소유 | 만료 부분 환불 compensation이 original issuer/cost lineage를 먼저 필요로 함 | BR-20, ADR-063 |

## Outcomes & Retrospective

아직 기능 구현을 시작하지 않았다. 계약 정합성 감사와 fact gate는 완료됐으며
`CLEAN_CUTOVER_GATE = PASSED`다. 10/20/30 foundation은 clean-cutover 전략을 입력으로
진행할 수 있지만, 세 foundation이 완료되기 전 고객 취소 command는 계속 차단된다.

## Revision Notes

- 2026-07-31: 최초 거대 구현 계획을 master orchestration plan으로 교체하고 여섯 하위
  ExecPlan을 연결했다. 근거 없는 ADR-059 release gate 완료 표시는 제거했다.
- 2026-07-31: product owner의 non-local 환경·artifact 부재 확인을 release evidence로
  기록하고 ADR-059 clean-cutover gate를 완료했다.
- 2026-08-01: 정의되지 않았던 `RECOVERY` enum을 ADR-065로 분리하고 Plan 10의
  point recovery foundation으로 구현 소유권을 고정했다.
- 2026-08-01: Plan 10이 만료 부분 환불 compensation의 PointLot issuer snapshot
  precheck/migration도 선행 소유하도록 명확화했다.
