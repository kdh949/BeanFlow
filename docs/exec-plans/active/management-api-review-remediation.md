# 관리 API 리뷰의 복구와 동시성 결함 수정

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

PR #151~#157의 여섯 리뷰를 수정하고 각 최종 HEAD의 CI와 리뷰 해결 상태를 검증한다.
판정 응답 replay 뒤 운영 Case가 남거나, lock 대기 중 시간 불변식이 깨지거나,
publication 수동 요청이 실행되지 않거나 여러 번 실행되는 경로를 제거한다.

## Current State

최초 검증 HEAD는 #151 2d9a233, #152 cacab47, #153 959449a, #154 33d2616,
#155 3e63dbd, #156 e8f7a02, #157 4b5d365다. 모든 PR은 직전 feature branch를 base로 한다.
#151, #153, #154에 각각 한 건, #157에 세 건의 미해결 리뷰가 있다.

## Definitions

Case는 운영 확인 작업이다. publication은 특정 listener 한 건의 실행 기록이다.
claim은 listener 실행을 위해 DB에서 예산을 원자적으로 소비하는 단계다.
결과 불명은 업무 실패나 부수효과 부재의 증거가 아니다.

## Scope

### In Scope

판정 replay의 Case 복구, 슬롯/계약 시간 재검증, publication claim 예산/NULL 호환,
결과 불명 조사·안전한 재실행·늦은 결과 대사, 정책/계약/테스트, 기존 stack 갱신과 리뷰 해결.

### Non-goals

재고, UI, 원본 거래 재생성, 새 broker/범용 workflow, 자동 merge/deploy는 포함하지 않는다.

## Business Rules and Invariants

판정과 Adjustment 및 최초 응답은 불변이다. replay도 현재 인가를 확인한다.
슬롯과 계약의 미래 조건은 owner lock 획득 후 검증한다.
복구 요청 하나는 실제 claim 한 번만 허용하며 원본 payload/source와 누적 attempts를 보존한다.
시간 초과는 조사 시작 조건이다. 결과 불명은 수동 검토로 넘기고 성공/실패로 추정하지 않는다.
미검증 listener는 결과 불명 재실행을 거절하며 검증된 대상은 동시 replay에도 부수효과가 중복되지 않아야 한다.
늦은 결과는 이전 시도와 현재 시도를 구분하며 불명 원장은 보존한다.

## Architecture and Transaction Boundaries

Dispute는 기존 판정 service의 terminal replay로 afterCommit Case 완료를 재시도한다.
Fulfillment/Merchant의 기존 owner lock 아래 Clock을 새로 읽는다.
Ordering의 기존 repository adapter가 publication row와 수동 원장/Case 조건을 actual claim에서 확인한다.
listener 호출은 claim commit 이후이며 외부 Provider 호출을 HTTP transaction에 추가하지 않는다.
Operations Case와 Ordering 결과 원장의 전이는 기존 owner transaction으로 함께 commit한다.

## Alternatives Considered

최초 응답 삭제, 과거 시간 허용, attempt 초기화, timeout 후 무조건 FAILED/retry는 불변식을 위반한다.
새 범용 복구 시스템보다 기존 adapter/원장/Case worker의 제한된 확장을 선택한다.

## Failure Semantics

Case 완료 실패는 재시도로 수렴한다. stale version과 claim 예산 소비는 명시적 conflict/claim 거절이다.
지연 실행은 원본 상태를 보존한 채 EXECUTION_OUTCOME_UNKNOWN으로 조사한다.
독립 결과 대사는 한 건의 실패 때문에 중단하지 않고 실패를 다시 보고한다.

## Data and Migration

앞 세 수정은 schema를 바꾸지 않는다. #157의 unknown/claim metadata가 필요하면 V81을 단일 writer로 추가한다.
기존 V1~V80은 다시 쓰지 않는다. schema 세부사항은 구현 전 #157의 정책/ADR과 이 문서에 확정한다.

## API and Event Contracts

기존 endpoint, 최초 응답 replay, 권한과 idempotency contract를 유지한다.
publication 상세의 unknown/retry eligibility가 바뀌면 runtime/target OpenAPI와 runbook을 함께 갱신한다.
event payload/listener identity는 변경하지 않는다.

## Milestones

1. #151에 Case 복구와 회귀 테스트를 commit한다.
2. 순차 전파하면서 #153 슬롯, #154 계약 수정과 lock 대기 테스트를 commit한다.
3. #157에 승인된 unknown 정책을 기록하고 claim/결과 복구 및 회귀 테스트를 완성한다.
4. 증분·통합 검증 뒤 모든 PR을 push하고 terminal CI를 확인한다.
5. 수정 증거로 각 리뷰에 답변하고 정확한 thread를 resolve한 뒤 다시 검증한다.

## Required Tests

- 판정은 commit되고 Case 완료만 실패한 뒤 같은 키 replay가 Case를 완료함
- 실제 PostgreSQL owner lock 대기 사이 시작/적용 시각을 지난 요청 거절
- 두 워커의 stale candidate가 한 요청 예산을 중복 소비하지 않음
- NULL publication의 실제 registry claim과 listener 완료
- claim 뒤 중단, 업무 commit 후 ACK 유실, 이전 실행과 replay 중첩, 늦은 결과
- 미검증 target 차단, 원본 불변, 권한/replay, Audit rollback, unknown 보존
- Runtime OpenAPI parity, Modulith, 인증/ArchUnit, Flyway, 문서 검증

## Validation Commands

`./gradlew test --tests '*SettlementDisputeIntegrationTest'`
`./gradlew test --tests '*PickupSlotManagementIntegrationTest' --tests '*StoreSettlementTermsManagementIntegrationTest'`
`./gradlew test --tests '*PublicationManualRecoveryIntegrationTest' --tests '*EventPublicationRecoveryIntegrationTest'`
`./gradlew spotlessCheck bootJar`; `scripts/verify-docs.sh`; GitHub PR HEAD 별 check 조회.

## Observability

원본 publication/시도/Case와 불명 사유를 조회·감사 이력에 남긴다. payload/PII는 노출하지 않는다.

## Documentation Updates

BR-54, ADR-124를 대체/보완하는 결과 불명 복구 결정, ADR-010, 운영 runbook과 OpenAPI를 갱신한다.

## Progress

- 원격 일곱 HEAD, 여섯 미해결 thread와 원본 dirty checkout 보존을 확인했다.
- #151: 저장 응답 replay에서도 terminal 판정의 afterCommit Case 정리를 재시도한다.
- Passed: SettlementDisputeIntegrationTest 16 tests (failure/error 0), spotlessApply.
- #153: PostgreSQL pg_blocking_pids로 실제 슬롯 lock 대기를 확인한 뒤 Clock을 시작 이후로 이동해 변경 거절과 기존 응답 replay를 검증했다.
- Passed: PickupSlotManagementIntegrationTest 7 tests (failure/error 0), spotlessApply.
- #154: Store shared lock 대기 뒤 계약 적용 시간이 지나면 INVALID_REQUEST로 전체 등록을 rollback한다. 기존 응답 replay는 유지한다.
- Passed: StoreSettlementTermsManagementIntegrationTest 6 tests (failure/error 0), spotlessApply.
- 나머지 구현과 원격 CI/thread 검증은 Pending이다.

## Surprises & Discoveries

없음.

## Decision Log

- 2026-09-10: 결과 불명 조사, 검증된 대상만 재실행, 완료 근거 확인, claim 한 번, 늦은 결과/보존 정책을 승인했다.
- 공개 branch는 rebase/force push 없이 최초 owner 수정부터 순차 merge한다.

## Outcomes & Retrospective

Pending. 코드, 로컬 검증, 원격 CI, thread 해결을 각각 확인한 뒤 기록한다.

## Revision Notes

- 2026-09-10: 리뷰 수정 실행 계획 작성.
