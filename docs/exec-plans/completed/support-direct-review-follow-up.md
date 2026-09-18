# 고객센터 직접 처리 리뷰 후속 수정

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/support-direct-processing.md`
> **Completed-At:** `2026-09-18`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

종료 API 응답, 동일 상담원 실행과 이전 Operations 승인 종료를 SP-23에 맞춘다.

## Current State

PR #197의 6f642a9 기준. Operations 기존 승인 성공 테스트 4개가 CI에서 실패한다.
직접 요청 실행자 재배정과 workflow 요청 권한 검사가 정책에 맞지 않는다.

## Definitions

LEGACY는 과거 실제 승인 기록, SUPPORT_DIRECT는 활성 담당자의 직접 처리 근거다.

## Scope

### In Scope

인증 종료 controller/OpenAPI/타입, 요청 재배정·주문/해결/프로필 실행과 workflow,
Operations 조회·이전 결정 거부·화면 안내·통합 테스트, 기존 PR 갱신.

### Non-goals

보상 상한 거부 추가, DB migration, 새 승인/인계 엔진, 병합·배포.

## Business Rules and Invariants

SP-23 보완/ADR-136을 따른다. 인증과 업무 권한·담당자·대상 연결은 유지한다.
과거 완료 응답·금융 복구는 재실행하지 않는다. 보상 상한 위험은 명시적으로 수용한다.

## Architecture and Transaction Boundaries

기존 controller/application/aggregate/owner 경계와 lock, 감사·멱등 transaction을 유지한다.
신규 직접 처리 실행자 변경은 원자적 변경 전에 거부한다.

## Alternatives Considered

전역 종료 필터, 인계 상태 추가, 과거 승인 호환 모드 대신 기존 경로를 국소 수정한다.

## Failure Semantics

종료 API 410, 담당자 불일치/이전 요청 409 재작성. 감사 실패는 전체 rollback한다.

## Data and Migration

변경 없음. 과거 실제 승인과 완료 멱등 응답을 보존한다.

## API and Event Contracts

종료 API 본문/멱등키 필수 조건 제거. Operations canDecide는 종료된 승인 경로에서 false다.

## Milestones

1. 결정과 계획 기록.
2. 서버/API/화면 및 회귀 검증.
3. 최종 diff와 커밋·PR 갱신 범위를 확정하고 원격 CI를 별도로 확인한다.

## Required Tests

누락/잘못된 인증 입력 410과 미로그인 거부, 직접 요청 재배정 거부, 상담 재배정 후 새 요청,
요청 권한 회수 시 workflow/execute 일치, 과거 Operations 동시 거부·감사 rollback·완료 replay.

## Validation Commands

관련 Gradle integration/architecture/OpenAPI 테스트 및 spotlessCheck, verify-docs.sh.
frontend typecheck/test/check:design/build-storybook/test:storybook:docs/build/test:sites 및 MCP interaction/a11y.

## Observability

기존 감사와 오류 코드 유지. 과거 요청 재작성 안내를 제공한다.

## Documentation Updates

SP-23 보완, ADR-136 amendment, 이 ExecPlan과 PR 검증/위험 설명.

## Progress

- [x] 결정과 계획 기록.
- [x] 서버/API/화면 수정과 회귀 검증.
- [x] 최종 diff 및 기존 PR #197 갱신 범위 확정. 원격 CI는 push 후 별도 확인한다.

## Surprises & Discoveries

기존 Operations 테스트는 legacy 행으로 승인 성공을 기대했다. 승인 실패를 409로만 바꾸지 않고
STALE 상태의 감사 rollback과 동시 거부, 과거 완료 응답 replay를 각각 검증했다.
완료 replay fixture의 결정 시각과 수정 시각 불일치는 실제 DB 제약에 맞춰 수정했다.
환불 해결은 계획 저장과 실행이 분리되어 있어 시작 직전에도 요청 권한을 다시 검사해야 했다.

## Decision Log

2026-09-18: 리뷰 1/2/4 수정. 리뷰 3 보상 상한 거부는 추가하지 않고 위험을 수용한다.

## Outcomes & Retrospective

로컬 구현·검증 완료. 신규 직접 요청의 실행자만 바꿀 수 없으며, 상담 재배정 후 새 요청은
같은 담당자가 실행할 수 있다. 등록 이후 요청 권한 회수도 workflow와 실행에서 함께 거부한다.
종료 인증 API는 잘못된 본문·멱등키 누락에도 인증된 요청이면 410이고 미로그인은 거부한다.
Operations는 과거 이력과 재작성 안내만 제공하며 완료 응답의 멱등 재생을 보존한다.

- Passed: 아래 Gradle 범위 8개 클래스/80개 테스트, 실패·오류·skip 0.
  PostgreSQL Operations 8개, 환불 해결 17개, 프로필 19개, 주문 실행 18개,
  요청 10개, 인증 종료 4개, Support 구조 3개, runtime OpenAPI parity 1개.
- Passed: frontend typecheck, 단위 37개 파일/257개 테스트, design adherence,
  Storybook build와 Docs 122개 문서/47개 상태, build, Sites 4개 테스트.
- Passed: Storybook MCP 변경 Operations 화면 9개 story interaction/a11y.
- Passed: 문서 검증 18개, OpenAPI semantic 109개 operation/102개 schema.
- 보상 상한 계산 변경·추가 거부 없음. 현재 누적 한도를 유지한다.
- 원격 전체 CI와 실제 운영 배포 결과는 이 로컬 검증에 포함하지 않는다. PR checks에서 별도 확인한다.
- 기존 Kotlin/Gradle deprecation, frontend chunk 크기 및 정적 Docs의
  notification-summary ENOENT는 비실패 경고다.

실행 명령:

```sh
./gradlew spotlessApply test --tests '*SupportVerificationIntegrationTest' --tests '*SupportActionRequestIntegrationTest' --tests '*SupportOrderChangeExecutionIntegrationTest' --tests '*PostAcceptanceResolutionIntegrationTest' --tests '*SupportProfileChangeIntegrationTest' --tests '*OperationsSupportInvestigationIntegrationTest' --tests '*OpenApi*' --tests '*SupportArchitectureTest' --console=plain
./gradlew spotlessCheck
scripts/verify-docs.sh
cd frontend
npm run typecheck
npm test
npm run check:design
npm run build-storybook
npm run test:storybook:docs
npm run build
npm run test:sites
```

최종 제출 전 spotlessCheck, 문서 검증과 diff 검사도 통과했다.

## Revision Notes

2026-09-18 후속 작성.
