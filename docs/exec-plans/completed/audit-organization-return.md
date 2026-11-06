# 조직 로그인 후 원래 업무 화면 복귀

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-10-03`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

SUP-01: 고객센터/운영팀이 조직 로그인 뒤 시작한 업무 화면으로 복귀하고 권한 확인 전 메뉴를 사용 가능한 것으로 표시하지 않는다.

## Current State

main ec61729는 /ops 접두사만 복귀 대상으로 허용하고 수동 login에서만 경로를 저장한다. check-sso 자동 redirect는 /support 목적지를 잃는다. callback render에서 storage를 소비하면 재렌더 시 /ops로 바뀐다. ConsoleShell은 actor 확인보다 먼저 메뉴를 노출한다.

## Definitions

복귀 경로는 exact /ops 또는 /support와 하위 경로 및 query/hash. callback은 기존 /ops/auth/callback.

## Scope

### In Scope

operationsSession, OperationsSessionGate, router와 관련 테스트/stories.

### Non-goals

별도 token refresh 작업, 외부 issuer 정책/permission 변경, 서버 API 변경, 전역 actor 캐시.

## Business Rules and Invariants

BR-41/ADR-092: PKCE S256, memory-only token, callback 성공·실패 후 일회성 storage 삭제. 서버 /operations/me 검증 유지. 외부 URL·유사 접두사·callback loop 차단.

## Architecture and Transaction Boundaries

기존 인증 어댑터와 route gate만 수정. Gate의 단일 actor 조회 결과로 ConsoleFrame 상태 결정. DB/aggregate/transaction 변경 없음.

## Alternatives Considered

전역 actor cache/중복 조회와 auth 토큰 저장 확대는 불필요. 기존 ConsoleFrame을 COMPOSE하고 ErrorState/Button/InlineNotice는 REUSE.

## Failure Semantics

설정·callback·storage 실패는 unavailable. 401은 기존 clear, 403은 권한 안내와 재확인. 인증 token만으로 업무 메뉴를 열지 않는다.

## Data and Migration

없음. 검증된 return path만 일회성 sessionStorage에 저장하며 callback 뒤 메모리에만 잠시 유지.

## API and Event Contracts

변경 없음.

## Milestones

1. 정책/계획 및 story 상태 기록
2. 자동/수동 SSO 경로와 callback 경계 수정
3. 권한에 맞는 shell 및 테스트
4. 로컬 검증 후 독립 main PR

## Required Tests

support/ops query/hash 자동·수동 복귀, callback 재렌더 안정성, 성공·실패 storage 삭제, open redirect/유사 접두사/callback 거절. actor loading/403 메뉴 비노출, 성공 시 역할별 shell. 기존 token 저장/PKCE/만료 회귀.

## Validation Commands

frontend typecheck/test/check:design/build-storybook/test:storybook:docs/build/test:sites. BeanFlow Storybook 6013 MCP docs/preview/changed/tests. verify-docs, diff --check.

## Observability

기존 오류/correlation 사용. 새 토큰·경로 로그 없음.

## Documentation Updates

BR-41과 ADR-092에 현재 동작의 복귀 경계 명확화. 새로운 인증 방식/정책 도입 없음.

## Progress

- [x] 원본 dirty refresh 변경과 독립 범위 대조
- [x] 구현 및 검증
- [x] 독립 PR 제출 준비

## Surprises & Discoveries

callback 중 렌더 반복의 storage 소비는 메모리 목적지로 안정화했다. 미인증 check-sso callback 후 수동 로그인에는 다음 왕복용 경로를 재저장한다. 권한 거절 시 메뉴는 숨기고 로그아웃은 유지한다. 하위 에이전트 검토의 두 회귀를 구현·테스트에 반영했다.

## Decision Log

2026-10-03: 독립 PR, 선행 의존성 없음. 기존 미커밋 token refresh 변경은 보존하고 가져오지 않는다.

## Outcomes & Retrospective

Passed: typecheck; npm test (38 files/273 unit, presentation 10, product-copy 11); check:design; Storybook/product builds; Docs smoke 125 entries/47 states; Sites 4; docs/OpenAPI validation; staged diff check.

Passed: final Storybook MCP focused 5 stories with a11y. Full run previously produced 121 files/792 passed in the runner log but aggregate MCP response failed when the server exceeded its heap limit (also at 4 GiB). Final small changes were covered by focused MCP and unit tests; full aggregate result remains unavailable. An orphan test process was identified by this worktree cwd and stopped before restart, without touching other projects.

Chrome: support waiting/verified at 1024px, permission denial and logout at 1440px; no clipping observed. StrictMode callback and unauthenticated check-sso → manual login → fresh callback tested. Real IdP round trip/deployment: Not run. Existing bundle size warning remains. Visual baseline: Not configured.

## Revision Notes

2026-10-03: 실행 계획 작성.
