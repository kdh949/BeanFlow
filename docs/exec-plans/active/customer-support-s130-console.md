# 고객지원 상담원이 분리된 여덟 화면에서 Case 업무를 처리한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s100-purpose-specific-profile-change.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 발견과 검증 결과를 계속 반영한다.

## Purpose / Big Picture

상담원이 내부 UUID와 원시 토큰을 수동 입력하지 않고 대기열, 문의 접수, 상담 상세, 본인확인·정보 열람,
주문 문제 처리, 고객 보상, 계정·정보 변경, 승인·감사 업무를 분리된 화면에서 처리한다. 여덟 화면은 동일한
`SupportWorkspaceShell`과 디자인 시스템을 사용하며 실제 route와 Storybook story가 같은 컴포넌트를 조합한다.

## Current State

- S20~S100 command 계약은 구현됐지만 `/support`는 여러 유스케이스가 결합된 단일 화면이다.
- Case 목록에는 상담 지표, 문의유형, 최근 채널과 마스킹된 주요 대상이 없다.
- Case/Order bounded overview, approval inbox, 보상·프로필 변경 목록 조회가 없다.
- cool-white/navy/coral Foundation과 `SupportWorkspaceShell`은 canonical presentation이다.

## Definitions

- **Case queue:** 미담당 큐가 아니라 생성 즉시 담당자가 있는 SupportCase의 bounded 목록이다.
- **Case queue scope:** `MINE`은 현재 actor 담당 Case, `ALL`은 `SUPPORT_CASE_ASSIGN` 권한도 가진 actor의 전체 Case다.
- **Approval task scope:** `MINE`은 Case 담당 여부가 아니라 task별 권한과 actor 분리 규칙상 현재 actor가 검토할 수 있는 작업이다. `ALL`은 같은 task별 권한에 `SUPPORT_CASE_ASSIGN`을 더 요구하는 전체 이력 조회다.
- **Approval task:** `DATA_ACCESS_GRANT | BREAK_GLASS | SUPPORT_ACTION | COMPENSATION | PROFILE_CHANGE` closed projection이다.
- **Presentation-safe props:** raw secret 없이 화면 컴포넌트가 렌더할 수 있는 route-local 값이다.

## Scope

### In Scope

- Support queue/overview/approval/list read API와 strict OpenAPI schema
- Identity, Merchant, Ordering 공개 bounded Query Port
- reusable dense-workspace Pattern과 `frontend/src/presentation/support-center/` root
- 여덟 route 및 동일 컴포넌트 기반 Storybook 상태 story
- Support shell navigation, legacy/runtime-story import 경계, desktop visual/a11y QA

### Non-goals

- 미담당 Case, 범용 upload/download, 숫자 pagination, owner truth mirror, S110 Delivery draft
- 새 쓰기 상태 전이·정책 숫자·permission, runtime fixture나 dependency fallback

## Business Rules and Invariants

1. Case 생성 시 담당자 불변식을 유지한다.
2. 모든 조회는 persistent permission과 Case/object relation을 재검사한다.
3. Support는 다른 Context Repository를 직접 import하지 않는다.
4. owner/permission 실패는 `503 DEPENDENCY_UNAVAILABLE`이며 empty가 아니다.
5. 성공은 `no-store`; PII/evidence 원문은 응답·로그·cursor에 없다.
6. 목록은 endpoint/filter-bound signed cursor를 사용한다.
7. reveal은 navigation, expiry, terminal Case, logout, permission loss 시 DOM과 memory에서 제거한다.
8. S20~S100 exact version, actor separation, idempotency, transaction 경계를 변경하지 않는다.

## Architecture and Transaction Boundaries

```text
Support screen -> generated OpenAPI client -> SupportConsoleQueryController
  -> permission + Support read repository
  -> Identity/Merchant/Ordering public bounded Query Port
```

Support-owned row는 read-only transaction에서 읽고 owner fact는 공개 Application Query Port로만 조합한다.
어느 owner라도 실패하면 부분 성공을 반환하지 않는다. command 화면은 기존 Application Service를 사용한다.

## Alternatives Considered

- 단일 페이지 탭: route/state/a11y 경계가 불명확해 기각.
- 화면별 shell: navigation drift와 legacy 혼입 때문에 기각.
- frontend fixture 우선: runtime 계약 공백을 숨겨 기각.
- 범용 resource API: object permission과 cursor tuple을 약화해 기각.

## Failure Semantics

invalid filter/cursor/limit은 400, 권한 부족 403, 부재 404, stale version 409, owner/permission 장애 503이다.
`UNKNOWN | RECONCILING | MANUAL_REVIEW | RESOLUTION_REQUIRED`는 실패나 완료로 재해석하지 않는다.

## Data and Migration

기존 index로 구현하고 migration은 만들지 않는다. 동일 fixture query-plan 근거가 생기면 별도 writer 계획으로
인덱스를 추가한다.

## API and Event Contracts

- `GET /support/case-queue/summary`, `GET /support/case-queue`
- `GET /support/cases/{caseId}/overview`, `GET /support/orders/{orderId}/overview?caseId=...`
- `GET /support/approval-tasks`, detail, timeline
- `GET /support/compensations`, `GET /support/profile-changes`

모든 object schema는 `additionalProperties: false`; event 계약은 추가하지 않는다.

## Milestones

1. ADR/API surface/OpenAPI 계약을 고정한다.
2. owner Query Port와 Support read service/controller를 구현한다.
3. 다섯 reusable dense-workspace Pattern을 추가한다.
4. Storybook 상태와 여덟 screen을 구현한다.
5. 실제 route/legacy boundary를 전환한다.
6. backend/frontend/Storybook/visual/a11y 검증을 완료한다.

## Required Tests

- permission, `MINE|ALL`, signed cursor, masking, owner failure 503, object relation
- approval closed type/detail/timeline과 보상·프로필 cursor
- public Query Port invalid projection, OpenAPI parity, PII leak
- Pattern a11y와 screen loading/empty/error/permission/transaction story
- reveal cleanup과 presentation boundary violation fixture

## Validation Commands

- `./gradlew test --tests '*SupportConsole*'`
- `./gradlew test`
- `./scripts/verify-docs.sh`; `git diff --check`
- `cd frontend && npm run typecheck && npm run test:unit`
- `cd frontend && npm run test:presentation-boundary && npm run check:design && npm run build`
- `cd frontend && npm run test:sites && npm run test:storybook:docs && npm run build-storybook`
- live Storybook MCP changed/preview/a11y story tests

## Observability

query duration/error와 owner dependency failure를 endpoint/type tag로 기록한다. PII, evidence, cursor는 tag가 아니다.

## Documentation Updates

ADR-081, API surface, target/runtime OpenAPI, Foundation Overview, design-system inventory와 이 계획을 갱신한다.

## Progress

- [x] Storybook MCP, Support shell, S20~S100 계약과 read gap 확인
- [x] S130 scope/read-model/API 경계 기록
- [ ] backend read-model과 OpenAPI 구현
- [ ] Storybook-first pattern/screen 구현
- [ ] route/legacy boundary 전환
- [ ] 전체 검증과 visual QA

## Surprises & Discoveries

- 현행 Case는 미담당 상태가 없으므로 reference의 미담당 지표를 그대로 구현할 수 없다.
- owner exact-search port는 ID 기반 overview가 없어 bounded lookup 확장이 필요하다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-29 | Accepted | `SupportWorkspaceShell`만 사용 | navigation owner 단일화 | ADR-090 |
| 2026-08-29 | Accepted | bounded read API 계약 우선 | runtime 계약 공백 방지 | ADR-081 |
| 2026-08-29 | Accepted | 미담당·숫자 pagination·범용 file 제외 | 현행 불변식/보안 유지 | this plan |

## Outcomes & Retrospective

완료 시 실제 endpoint/story/route 수와 Passed/Failed/Not run/Blocked 결과를 기록한다.

## Revision Notes

- 2026-08-29: initial contract-first plan authored.
