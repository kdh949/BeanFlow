# 운영자·고객센터 access token 갱신으로 업무 세션 유지

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-10-01`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

짧은 access token 만료만으로 Operations/Support 업무 화면이 닫히지 않도록 기존 메모리
refresh token을 사용한다. 실제 인증 종료와 의존성 실패는 구분해 보호 화면을 중단한다.

## Current State

`operationsSession.ts`는 만료 timer/callback에서 clear, `consoleClient.ts`는 401에서 clear한다.
`/ops`와 `/support`는 같은 gate/client를 사용한다. Customer/Merchant에는 같은 timer가 없고
서버 Cookie Session의 idle/absolute timeout을 사용한다. 기존 관측·부하테스트 dirty 파일은 별도다.

## Definitions

- access token: 보호 API에 보내는 짧은 수명의 Bearer credential
- refresh token: adapter 메모리 안에서 IdP token 갱신에만 사용하는 credential
- generation: 로그아웃·clear·retry 이전 비동기 결과를 폐기하는 브라우저 session 경계

## Scope

### In Scope

메모리 token 갱신, 요청 전 gate, failure 화면·테스트, 관련 정책/ADR, 나머지 surface 인증 진단.

### Non-goals

Keycloak 관리자 설정·배포·계정 credential 변경, Customer/Merchant session 정책 변경, 업무 요청 replay,
새 backend API/schema/dependency, commit/push/PR.

## Business Rules and Invariants

BR-41와 ADR-138. token 비저장, no offline_access, actor/permission 경계 유지. BR-36은 그대로다.
Support reveal/verification/grant expiry는 token refresh와 무관하다.

## Architecture and Transaction Boundaries

기존 frontend session과 Bearer client가 조정한다. backend Aggregate와 DB transaction은 변경하지
않는다. HTTP 업무 요청은 token 갱신 성공 뒤 한 번만 전송한다.

## Alternatives Considered

token lifetime 연장, 기존 SSO 재인증, BFF와 메모리 adapter refresh를 ADR-138에서 비교했다.

## Failure Semantics

refresh rejection → unauthenticated; transport/dependency failure → unavailable. credential 제거와
보호 화면 중단. 늦은 refresh·이전 token 401은 새 인증을 변경하지 않는다. 업무 명령은 replay하지 않는다.

## Data and Migration

없음. token 영구 storage와 DB 변경 없음.

## API and Event Contracts

backend OpenAPI/events는 변경 없음. browser refresh 실패는 client error이며 서버 오류를 대체하지 않는다.

## Milestones

1. surface 인증·정책 대조와 Storybook 문서 확인
2. BR-41/ADR-138 기록
3. refresh/failure/동시성 구현과 테스트
4. frontend/Storybook/문서 검증과 diff 검토

## Required Tests

만료 갱신, 요청 전 token 교체, shared refresh, 거부·통신 실패, 늦은 refresh, 401/no replay,
token 비저장, Customer/Merchant 인증 분리와 idle/absolute boundary.

## Validation Commands

frontend: `npm run typecheck`, `npm test`, `npm run check:design`, `npm run build-storybook`,
`npm run test:storybook:docs`, `npm run build`, `npm run test:sites`; Storybook MCP focused/full tests.
repository: `./gradlew test --tests '*BrowserSessionAuthenticationFilterTest'`,
`bash scripts/verify-docs.sh`, `git diff --check`.

## Observability

명시적 unavailable UI. token/PII 로그 추가 없음. 기존 actor별 인증 결과와 오류 관측을 유지한다.

## Documentation Updates

BR-41 개정, ADR-138 추가와 ADR-092 supersession/index, 이 plan의 실행 결과.

## Progress

- [x] surface 진단, 필수 문서와 Storybook MCP 확인
- [x] 정책·ADR 갱신
- [x] 구현·focused regression
- [x] 전체 검증과 diff 검토 (full MCP 응답 수집의 도구 제한은 아래와 같이 별도 기록)

## Surprises & Discoveries

Support도 동일 세션을 사용한다. 매장/고객의 `refresh()`는 `/me` 재조회이며 OAuth refresh가 아니다.
검증 도중 macOS sandbox의 Chromium 실행 제한과 일시적 ENOSPC가 있었다. 권한이 있는 문서 smoke
재실행과 일반 product build 재실행은 성공했다. MCP full run은 791 story test가 모두 통과한 뒤
결과 수집에서 과도한 Node 메모리·CPU 사용이 발생했다. framework 실행 결과와 MCP 응답은 구분한다.

## Decision Log

2026-10-01: BR-41 만료 즉시 clear를 메모리 adapter 갱신으로 대체한다. IdP 관리자 수명은 변경하지
않고 자동 갱신의 idle 연장 특성은 ADR-138에 명시한다. Customer/Merchant는 진단과 회귀 검증 범위다.

## Outcomes & Retrospective

Operations/Support는 메모리 token 갱신 성공 시 업무 화면을 유지한다. 실패는 unauthenticated와
unavailable로 구분하며 credential을 제거한다. Customer/Merchant는 동일한 5분 clear 코드가 없고
기존 Cookie Session 정책과 서버 account/version 재검증을 유지한다. 기존 dirty 변경은 수정하지
않았다. commit/push/배포·Keycloak 관리자 설정 변경은 하지 않았다.

- Passed: frontend `npm test` — unit 274, presentation boundary 10, product copy 11.
- Passed: 최종 auth/API focused regression 36; typecheck와 design checks.
- Passed: backend BrowserSessionAuthenticationFilterTest 5, LoginSessionCoordinatorTest 4.
- Passed: Storybook static build, Docs smoke 125 entries/15 stateful docs/47 surfaces.
- Passed: product build와 Sites tests 4.
- Passed: MCP로 시작한 full Storybook browser execution — 121 files/791 tests
  (`/private/tmp/beanflow-token-refresh-storybook-final.log`의 runner summary).
- Passed: 최종 focused MCP interaction/a11y — 운영 인증 4, 고객 gate 4, 매장 gate 4, 총 12 stories.
- Blocked: full MCP 결과 반환 — browser tests 통과 뒤 Node heap OOM과 300초 tool timeout.
  full framework summary의 791 passed와 focused MCP 12 passed를 함께 검증 증적으로 사용한다.
  재시작 후 기본 Vitest 포트 충돌도 있었으며 다른 프로세스를 건드리지 않고 `STORYBOOK_TEST_PORT=63329`
  실행으로 focused 검증을 완료했다. addon 자체 수정은 이 인증 변경 범위에서 제외했다.
- Passed: 문서 검증 18 tests, OpenAPI semantic checks, business policy/ADR/ExecPlan validation;
  최종 plan move 뒤에도 재검증했다.
- Not run: PostgreSQL Testcontainers 전체/전체 backend 구조 suite. backend/persistence 변경이 없다.
- Not run: 배포·실제 IdP 장기 SSO/refresh rotation/revocation smoke.
- Not configured: visual regression baseline. 새 레이아웃·스타일 변경은 없다.

## Revision Notes

2026-10-01 initial plan.
2026-10-01 implementation/validation 완료. 도구의 full-result 수집 제한과 배포 미검증을 분리해 기록.


### PR 재검증 (2026-10-09)

사용자 요청에 따라 최신 main `ec6172959dcebdd55519d44babea9e80b13d27b4`에서 인증 변경만 분리했다.
PR 재검증은 검색·고객 route·관측성·부하 테스트의 별도 로컬 변경을 포함하지 않는다.

- Passed: `npm ci`, typecheck, unit 274·presentation boundary 10·product copy 11.
- Passed: design checks, product build, Sites 4 tests, Storybook static build.
- Passed: Storybook MCP 인증 12 stories의 interaction/a11y와 changed-story inventory 확인.
- Passed: Storybook Docs smoke 125 entries·15 stateful docs·47 state surfaces.
- Passed: 기존 backend session filter 5·coordinator 4 및 통합 해제의 61 tests, 총 70 tests.
- Passed: `spotlessCheck`, `bootJar`, 문서 unit 18와 OpenAPI/정책/ADR/ExecPlan 검증.
- Not run: 이 PR 준비에서 full MCP report 재수집, 전체 backend suite, 실제 IdP 장기 세션·배포 검증.
  2026-10-01 full MCP 수집 제한은 위 historical evidence와 구분한다.
- 원격 CI는 PR 생성 후 확인하며 로컬 결과로 대체하지 않는다.
