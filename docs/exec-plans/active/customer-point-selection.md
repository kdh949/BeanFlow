# 고객을 검색하고 선택하여 포인트 조회와 조정하기

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

운영자가 내부 UUID 없이 고객 login ID로 대상을 검색하고 선택해 현재 포인트 조회와 조정을 완료한다.

## Current State

기준은 #169 e8fde1e이다. PointAccountWorkspace는 account ID 입력을 요구하지만 가입 시
customer ID와 PointAccount의 1:1 관계가 이미 생성된다. 기존 조회·조정 API를 재사용한다.

## Definitions

customerId는 가입 고객의 식별자, accountId는 Loyalty 원장 식별자다. 사용자는 두 ID를 입력하지 않는다.

## Scope

### In Scope

Identity 정확 검색/최소 마스킹/별도 grant/audit, Loyalty 계정 연결, 컴포넌트 메모리 기반 선택과
기존 조회·조정 연결, 실패/경쟁/동일 명령 재시도, 계약과 Storybook 검증.

### Non-goals

이름·연락처 검색, 영구 browser cache, 기존 금융 정책 변경, permission 자동 부여, merge/deploy.

## Business Rules and Invariants

BR-34/42/56, ADR-066/069/109/127을 따른다. 검색 권한과 포인트 읽기/조정은 분리한다.
조회 실패를 빈 결과/0P로 대체하지 않는다. 조정 대상과 key를 불명 결과 동안 보존한다.

## Architecture and Transaction Boundaries

Identity/Loyalty 각각 Controller→Application Service→자기 Query Repository/public Operations port.
검색 및 연결은 grant lock·DTO 조회·Audit append를 같은 transaction에서 완료한다. 외부 호출 없음.
기존 PointAccount→grant→idempotency→Lot 잠금 순서와 원장·Audit/outbox atomic commit을 유지한다.

## Alternatives Considered

UUID 입력, persistent browser 목록, 새 조정 API, Support profile 추론을 ADR-127 이유로 제외한다.

## Failure Semantics

invalid 400, unauthenticated 401, role/grant/revoked 403, DB/Audit/missing-account 503.
검색 0건만 정상 empty. 대상 전환의 late response는 무시한다. uncertain adjustment는 동일 payload/key 재시도.

## Data and Migration

V83에 CUSTOMER_ACCOUNT_SEARCH grant 어휘와 두 audit action만 등록한다. 기존 고객/포인트 데이터는
변경하지 않는다. ADR-127의 exact parent/직렬 migration handoff를 따른다. 기존 V82는 수정하지 않는다.

## API and Event Contracts

POST /operations/customer-searches: loginId/reasonCode → items(customerId, maskedLoginId, maskedDisplayName).
GET /operations/customers/{customerId}/point-account + X-Access-Reason → customerId/accountId.
기존 포인트 API와 이벤트 유지. target/runtime OpenAPI 및 생성 TS를 동기화한다.

## Milestones

1. 고객 정확 검색을 API/권한/감사/선택 컴포넌트/Storybook까지 수직 구현하고 커밋.
2. 선택 고객의 포인트 연결·조회·조정·전환/불명 상태를 API부터 화면까지 구현하고 커밋.
3. 전체 관련 검증, 최종 diff 검토, 단일 PR 생성과 최신 head CI 확인.

## Required Tests

PostgreSQL 정확 검색과 마스킹, grant 교차/철회, audit 즉시/commit 실패, 실제 고객-계정 연결과
누락/다른 고객, 기존 조회/조정 회귀. UI empty/error/loading/long/permission, 선택·전환·late response,
no persistent storage, 조정 중 전환 차단/동일 키 재시도. Runtime parity/Modulith/디자인/문서.

## Validation Commands

JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-26.jdk/Contents/Home ./gradlew '-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g' spotlessCheck test (관련 필터 우선).
frontend: npm test, npm run typecheck, npm run check:design, npm run build, npm run test:sites,
npm run build-storybook, npm run test:storybook:docs, 실제 6007 MCP run-story-tests(a11y=true).
scripts/verify-docs.sh; git diff --check; PR latest head checks.

## Observability

CUSTOMER_ACCOUNT_SEARCHED와 POINT_ACCOUNT_RESOLVED 감사에 원본 login/display name을 넣지 않는다.
DB/권한/연결 실패는 명시적인 오류로 남긴다.

## Documentation Updates

BR-56, ADR-127, OpenAPI target/runtime와 생성 client, 본 계획을 갱신한다.

## Progress

- [x] 사용자 선택 흐름과 메모리 보관 방식 승인, 기준 PR/head 및 별도 worktree 확인.
- [x] 정책/설계/직렬 migration 범위를 기록.
- [x] 고객 검색 수직 슬라이스: API/DB 권한·감사/마스킹/선택 컴포넌트와 10개 Storybook 상태.
- [ ] 포인트 연결/기존 명령 통합 수직 슬라이스.
- [ ] 로컬 검증 및 단일 PR/원격 CI.

## Surprises & Discoveries

신규 permission과 audit action은 DB의 닫힌 어휘라 V83이 필요하다. 상담 검색은 실제 가입 고객 검색의
대체재가 아니다. 분리 worktree Storybook은 6007/63321을 사용해 메인 작업의 6006과 충돌하지 않는다.

## Decision Log

- 2026-09-11: 사용자는 서버 검색 결과 선택, 선택값은 React 메모리 보관, 기존 계정 API 자동 호출을 승인했다.
- 2026-09-11: 한 PR 안에 기능별 수직 커밋을 두고 부모 #169를 보존한다.

## Outcomes & Retrospective

1차 검증: 고객 검색 PostgreSQL 통합 6개, Runtime parity 1개, Modulith 1개 통과.
Storybook MCP 신규 10개(a11y 포함), TypeScript, 디자인 검사, 문서 검증 통과.
별도 6007 브라우저에서 선택 결과와 검색 복귀를 확인했다. 포인트 연결과 전체 최종 검증은 진행 중.
초기 테스트 fixture의 grant version 제약과 감사 commit 실패 처리를 수정하고 재검증했다.
실제 서비스의 고객 조회/포인트 조정은 실행하지 않는다.

## Revision Notes

- 2026-09-11: 최초 작성.
