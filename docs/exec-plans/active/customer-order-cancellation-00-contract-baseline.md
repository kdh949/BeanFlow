# 고객 취소 계약 baseline과 release gate를 닫는다

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

기능 코드나 migration을 바꾸기 전에 고객 취소의 canonical 계약과 배포 사실을 하나의
검증 가능한 baseline으로 닫는다. 완료 시 구현자는 clean cutover 또는 forward
migration 중 증거가 허용한 한 경로만 선택할 수 있어야 한다.

## Current State

- 정책 원본은 BR-14/15/16/25~27/30과 ADR-029~060이다.
- target OpenAPI는 고객 취소 200/202와 고객용 환불 projection을 정의한다.
- 현재 앱에는 고객 취소 endpoint가 없고 V1~V12 migration만 있다.
- repository와 PR #17에는 production DB, publication, consumer, rollback binary,
  applied environment 증거가 없다.
- 따라서 현재 `CLEAN_CUTOVER_GATE = FAILED`다.

## Definitions

- **Fact gate:** 정책 선택이 아닌 실제 DB·배포·consumer 상태 확인.
- **Clean cutover:** 적용 전 migration 또는 V1 계약을 최종 shape로 제자리 변경.
- **Forward migration:** 이미 적용되거나 소비된 계약을 보존하며 새 migration/version으로 이행.
- **Canonical source:** explicit amendment, merge recency와 Accepted 상태를 적용한 최종 문서.

## Scope

### In Scope

- 운영 환경별 migration 적용 이력과 compensation table/row 확인
- 완료·미완료 `OrderRejectedV1`/`OrderCancelledV1` publication 수 확인
- 외부·독립 consumer와 rollback 대상 binary inventory
- 결과를 release evidence로 저장하고 migration 전략을 확정
- readiness, closure, traceability와 OpenAPI semantic check 유지

### Non-goals

- 운영 DB 수정, publication drain, binary 배포 또는 migration 작성
- 확인할 수 없는 값을 0으로 기록
- 제품 취소 정책 재선택

## Business Rules and Invariants

- 증거는 환경, 조회 시각, 대상, 명령 또는 시스템, 결과와 검토자를 포함한다.
- shared/production 환경 하나라도 unknown이면 gate는 실패다.
- nonzero 또는 unknown이면 V8과 V1을 수정하지 않는다.
- gate 실패는 고객 취소 범위를 줄이지 않으며 migration 전략만 차단한다.

## Architecture and Transaction Boundaries

이 계획은 애플리케이션 transaction을 변경하지 않는다. 운영 조회는 read-only로 수행하고
DB, publication registry, 배포 inventory와 consumer inventory를 서로 독립 증거로
수집한다.

## Alternatives Considered

- Git 문구를 운영 증거로 인정: 실제 외부 상태를 증명하지 못해 제외한다.
- local/test DB만 확인: shared/production과 rollback 상태를 놓치므로 제외한다.
- gate 실패 상태에서 forward 전략을 즉시 추측: 실제 legacy shape와 consumer를 모른 채
  호환 범위를 정하게 되므로 제외한다.

## Failure Semantics

- 조회 권한 부재, 환경 목록 미확정, consumer owner 미응답은 모두 `UNKNOWN`이고 gate 실패다.
- 일부 환경만 0이어도 전체 gate를 통과시키지 않는다.
- evidence 저장 실패 시 완료로 표시하지 않는다.

## Data and Migration

- 이 계획에서 migration 파일은 수정하지 않는다.
- 모든 대상이 명시적으로 0이면 ADR-059 clean-cutover 실행 계획을 승인한다.
- 하나라도 nonzero/unknown이면 관찰된 schema와 payload를 입력으로 forward migration,
  publication drain, compatibility, rollback ADR/ExecPlan을 새로 작성한다.

## API and Event Contracts

gate가 닫힐 때까지 OpenAPI의 목표 고객 취소 계약은 유지하되 기존 운영 V1 event를
변경하지 않는다. 목표 계약과 현재 구현은 readiness의 implementation drift로 구분한다.

## Milestones

1. shared/production 환경과 migration 적용 목록을 확정한다.
2. 환경별 compensation table 존재·row 수를 read-only로 확인한다.
3. event registry의 completed/incomplete publication을 event type별 확인한다.
4. 외부 consumer, 독립 배포 consumer, rollback binary와 보존 payload 의존성을 확인한다.
5. 증거를 release-gate 문서에 기록하고 gate를 계산한다.
6. 통과면 clean-cutover 승인, 실패면 forward 전략 ADR/ExecPlan을 확정한다.

## Required Tests

- table 없음, row 0, row nonzero와 권한 오류 fixture
- completed/incomplete publication 각각 nonzero 차단
- 외부 consumer/rollback binary nonempty 차단
- 환경 하나 누락 시 실패
- evidence 없이 Progress 완료 체크 금지

## Validation Commands

```bash
bash scripts/verify-docs.sh
git diff --check
```

운영 조회 명령은 환경별 release evidence에 실제 실행 결과와 함께 추가한다.

## Observability

runtime metric은 추가하지 않는다. gate evidence는 고정 시점의 release artifact다.

## Documentation Updates

- ADR-059 또는 이를 대체하는 forward-migration ADR
- `docs/quality/customer-order-cancellation-readiness.md`
- `docs/decisions/customer-order-cancellation-decision-closure.md`
- 이 계획의 Progress/Outcomes

## Progress

- [x] repository와 PR의 evidence 부재 확인
- [x] 현재 gate 실패 기록
- [ ] 환경 inventory 확정
- [ ] DB/table/row evidence 확보
- [ ] completed/incomplete publication evidence 확보
- [ ] consumer/rollback/applied migration evidence 확보
- [ ] 허용된 migration 전략 확정

## Surprises & Discoveries

- 기존 master plan의 release gate 완료 체크에는 연결된 증거가 없었다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Fact gate failed | 기존 migration과 V1을 수정하지 않음 | unknown을 0으로 볼 수 없음 | ADR-059 |

## Outcomes & Retrospective

현재 결과는 `CLEAN_CUTOVER_GATE = FAILED`다. 외부 운영 사실 확인 전에는 완료되지 않는다.

## Revision Notes

- 2026-07-31: readiness audit에서 최초 작성.
