# Storybook을 편집 가능한 디자인 시스템의 검증 기준점으로 만든다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-08-15`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

현재 frontend에는 187개 CSS token과 32개 컴포넌트를 주장하는 생성 bundle이 있지만, 제품 화면이
소비할 수 있는 편집 가능한 TypeScript 컴포넌트 source는 없다. Storybook도 현재 route 14개 중
`CustomerHomePage`만 다루고 있어 화면·실패 상태·접근성의 실행 가능한 기준점이 아니다.

이 plan은 실제 제품에서 반복되는 primitive를 `frontend/src/design-system/` 아래의 typed source로
복원하고 제품 화면과 Storybook이 같은 구현을 소비하게 한다. 현재 route/page와 중요한 상태를
Storybook에서 직접 열 수 있게 하며, token adherence·unit test·Storybook build/test·a11y를 CI에
연결한다. 완료 후 새 UI는 생성 bundle이나 병렬 CSS가 아니라 이 기준점에서 재사용·합성·확장된다.

## Current State

- 감사 시작 시 token은 187개였고, 접근성·기존 raw value 승격 뒤 canonical token은 201개다.
- `_ds_bundle.js`와 `_ds_manifest.json`은 존재하지 않는 JSX, card HTML과 UI kit 경로를 가리킨다.
- `.button`과 `.bf-btn`, `.status-badge`와 `.bf-status`처럼 제품 CSS와 생성 CSS가 이중화돼 있다.
- route-level page는 14개지만 MCP manifest에는 문서 3개와 story 7개만 있다.
- global a11y는 `todo`이고 baseline story test에서 color contrast 위반 5건이 관측됐다.
- `_adherence.oxlintrc.json`은 실행 script와 dependency가 없어 enforcement가 아니다.
- GitHub CI는 frontend dependency 설치, typecheck, unit test, Storybook 또는 adherence를 실행하지 않는다.
- frontend typecheck/build에는 MD-2026-014가 Plan 80/90에 배정한 CSRF header 누락 3건이 남아 있다.

## Scope

### In Scope

- source precedence와 디자인 결정을 문서화하고 governance를 accepted baseline으로 전환
- 실제 제품이 사용하는 core/status/feedback primitive의 typed TSX source와 canonical stories
- 제품의 기존 공유 UI가 canonical source를 소비하도록 이전
- 현재 route/page의 대표 상태 및 중요 loading/empty/error/permission/transaction story
- Foundations docs, story taxonomy, Autodocs, explicit story description
- executable design adherence check와 기존 debt baseline/removal contract
- Storybook MCP 사용 규칙, a11y error gate, CI frontend validation
- Vitest unit project와 Storybook browser test의 실행 경계 정리

### Non-goals

- 브랜드 재설계 또는 새 UI framework 도입
- 생성 bundle이 주장하는 32개 항목의 일괄 복원
- Plan 80/90/100이 소유한 Session/CSRF, UUID form 제거, Keycloak PKCE 구현
- Proposed ADR-090이 정하지 않은 Support frontend route/boundary 구현
- backend, OpenAPI, database, Aggregate 또는 transaction 변경
- 승인된 visual baseline이 없는 상태에서 visual regression coverage를 주장

## Business Rules and Invariants

1. 금액·상태·allowed action은 server contract를 표시하며 frontend가 거래 상태 머신을 새로 정의하지 않는다.
2. `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`를 성공·확정 실패·빈 상태로 위장하지 않는다.
3. 고객·점주 Session/CSRF와 운영자 Keycloak credential 경계를 story나 production code에서 우회하지 않는다.
4. generated bundle·manifest는 migration/reference input이며 product import source가 아니다.
5. product route와 Storybook은 같은 typed component/pattern source를 소비한다.
6. 새 static visual decision은 semantic/component token을 사용하고 `className`·`style` 우회 API를 열지 않는다.
7. current debt나 unavailable validation은 명시적으로 보고하고 green으로 위장하지 않는다.

## Architecture and Transaction Boundaries

```text
tokens/*.css
     │
     ▼
design-system/components/*.tsx + component CSS
     ├──────────────► canonical component/pattern stories
     └──────────────► product components/pages ─────► route stories

generated bundle/manifest ── reference only
```

이 plan은 browser presentation과 test fixture만 변경한다. Application Service, Aggregate, Repository,
DB transaction과 external provider call 경계는 변경하지 않는다. MSW handler는 runtime fallback이 아니라
Storybook test에서만 활성화되는 명시적 계약 fixture다.

## Alternatives Considered

### 생성 bundle 32개를 모두 typed source로 복원

- 장점: manifest와 폴더 목록을 빠르게 맞춘다.
- 단점: 실제 사용과 문서가 없는 API를 추측하고 후속 productization 화면과 충돌한다.

### 제품 화면은 유지하고 stories만 추가

- 장점: route coverage를 빨리 늘릴 수 있다.
- 단점: Storybook과 product가 서로 다른 markup/CSS를 사용해 drift를 막지 못한다.

### 채택: 실사용 vertical slice부터 canonical source로 수렴

typed primitive → 실제 제품 소비 → canonical stories → page/state stories → guardrail 순서로 진행한다.
초기 기준점에는 후순위 bundle-only component backlog가 남지만, API 추측과 병렬 시스템 확장을 막는다.

## Failure Semantics

- story request가 실패하면 empty fixture로 대체하지 않고 recoverable/permission/dependency error로 표시한다.
- polling과 시간 의존 story는 고정 Clock과 bounded handler를 사용한다.
- MCP 또는 browser runner가 unavailable이면 package test 성공으로 대체하지 않는다.
- a11y 위반은 global `error` gate로 실패하며 narrow exception은 linked decision 없이는 허용하지 않는다.
- Plan 80/90 소유 CSRF type error는 이번 plan의 성공으로 숨기지 않고 exact known failure로 남긴다.

## Milestones

1. **Decision baseline** — audit, source precedence, component 분류, token 접근성 결정을 문서화한다.
2. **Canonical core** — typed primitive와 component stories를 만들고 기존 shared UI를 소비자로 바꾼다.
3. **Page catalog** — 모든 현재 route/page와 핵심 상태를 deterministic MSW story로 연다.
4. **Foundations and exploration** — token usage docs와 선택 후 폐기 가능한 exploration template를 제공한다.
5. **Enforcement** — adherence, a11y, unit, Storybook build/test를 local scripts와 CI에 연결한다.
6. **Evidence** — full Storybook MCP test, preview URLs, build/docs/diff 결과와 known failures를 기록한다.

## Required Tests

- canonical component disabled/loading/status/feedback semantics의 unit 또는 story interaction test
- 모든 route-level page에 하나 이상의 directly openable story
- loading, success, empty, recoverable error, permission failure와 transaction uncertainty의 해당 story
- changed/affected stories의 MCP `run-story-tests`와 a11y `error`
- static Storybook build와 MCP manifest/documentation 확인
- adherence checker self-test와 repository scan
- frontend unit test project가 실행 중 MCP port와 독립적으로 종료
- CI script test, docs verification, whitespace check

## Validation Commands

```bash
cd frontend && npm ci
cd frontend && npm run check:design
cd frontend && npm run test:unit
cd frontend && npm run typecheck
cd frontend && npm run build-storybook
cd frontend && npm run build
cd frontend && npm run test:sites
bash scripts/ci/test-ci-scripts.sh
bash scripts/verify-docs.sh
git diff --check
```

Storybook UI 변경 후 MCP `get-changed-stories`, `preview-stories`, `run-story-tests(a11y=true)`를 추가로
실행한다. `typecheck`와 `build`의 MD-2026-014 세 오류는 Plan 80/90 전까지 expected known failure지만,
오류 집합이 달라지거나 늘어나면 이 plan의 regression으로 취급한다.

## Documentation Updates

- `frontend/docs/design-system-governance.md`
- `frontend/docs/design-decisions.md`
- `frontend/src/design-system/readme.md`
- `frontend/AGENTS.md`
- `docs/testing/definition-of-done.md`
- 이 ExecPlan의 living sections

## Progress

- [x] 2026-08-15: repository, token, component, route, story, CSS, CI와 MCP baseline 감사.
- [x] 2026-08-15: 기존 darker palette를 semantic alias에 재매핑해 WCAG AA를 맞추기로 사용자 결정.
- [x] 2026-08-15: canonical Button, StatusBadge, FeedbackState 구현과 product consumer 이전.
- [x] 2026-08-15: 14개 route/page 및 핵심 상태 catalog, foundations, exploration workflow 구축.
- [x] 2026-08-15: design/type baseline, a11y, unit, Storybook test/build와 CI 연결.
- [x] 2026-08-15: MCP 53 stories, local validation, docs, CI script, diff evidence 기록.
- [x] 2026-08-15: completion 후 발견된 inline Autodocs MSW handler 오염을 8개 page meta의 iframe
  isolation, 정적 guard와 19-entry browser smoke test로 교정.
- [x] 2026-08-15: completion audit에서 누락된 design-system inventory를 추가하고 Operations 환불·보상
  success/loading/error/unknown 상태를 보강했다. typed owner 없는 bundle CSS selector를 live entry에서 제거했다.

## Surprises & Discoveries

- manifest의 token 수 187은 맞지만 type 분류와 reduced-motion 값이 canonical CSS와 다르다.
- Storybook MCP와 기존 `npm test`가 Vitest browser API port 63316을 함께 사용했다. unit project를 분리하고 CI browser API를 63320으로 격리했다.
- macOS sandbox는 Playwright Chromium의 Mach port 등록을 거부했다. 같은 MCP/CI 검사를 sandbox 밖에서
  재실행해 53개 story가 모두 통과함을 확인했다.
- 개별 story interaction/a11y test와 static build는 여러 story를 한 화면에 합치는 Autodocs의 MSW
  handler 오염을 탐지하지 못한다. 실제 Docs URL browser smoke가 별도 검증 축으로 필요하다.
- existing CSRF type errors는 새 발견이 아니라 MD-2026-014가 후속 Plan 80/90에 명시적으로 배정한 debt다.

## Decision Log

- 2026-08-15: generated artefact는 삭제하지 않고 deprecated migration input으로 보존한다. editable source와
  실제 소비가 안정된 뒤 별도 cleanup에서 제거 여부를 결정한다.
- 2026-08-15: existing palette의 `caramel-700`과 `crema-600`을 accent, warning, muted semantic role에
  재사용한다. 새 palette token과 브랜드 재설계 없이 AA contrast를 확보한다.
- 2026-08-15: Support frontend는 ADR-090이 Accepted되기 전 story·route 범위에 넣지 않는다.
- 2026-08-15: visual regression은 clean baseline과 service credential이 없으므로 `Not configured`로 남긴다.
- 2026-08-15: 둘 이상의 MSW 상태를 가진 page Autodocs만 story iframe으로 격리한다. 전역 격리는
  component Controls를 제한하므로 적용하지 않고 `check:design`과 실제 Chromium smoke로 범위를 고정한다.

## Outcomes & Retrospective

editable TypeScript source로 `Button`, `StatusBadge`, `FeedbackState`를 복원하고 기존 제품 consumer를
같은 구현으로 수렴했다. Storybook은 17개 story 파일과 62개 story에서 현재 14개 route/page, role shell,
loading/empty/error/permission 및 `UNKNOWN`/`RECONCILING`/`MANUAL_REVIEW` 상태를 직접 연다. Foundations는
201개 canonical token과 exploration 승격·폐기 흐름을 문서화한다.

검증 결과는 다음과 같다.

- PASS — MCP `run-story-tests(a11y=true)`: 62 stories.
- PASS — `npm run test:storybook:ci`: 17 files, 62 tests.
- PASS — `npm test`: 6 files, 37 unit tests.
- PASS — `npm run check:design`: 201 tokens, 17 story files, 16 route components; raw-pixel debt 15개를
  감소 시 함께 갱신해야 하는 ratchet baseline으로 고정.
- PASS — `npm run check:type-baseline`: MD-2026-014의 CSRF 오류 3건과 정확히 일치.
- PASS — `npm run build-storybook`, `bash scripts/ci/test-ci-scripts.sh`, `bash scripts/verify-docs.sh`,
  `git diff --check`.
- EXPECTED FAIL — `npm run build`: Plan 80/90 소유 CSRF header 누락 3건 때문에 typecheck에서 종료.
- EXPECTED DERIVATIVE FAIL — `npm run test:sites`: 위 build가 `dist/client/index.html`을 만들지 못해
  packaging assertion 1건 실패(나머지 worker behavior 3건 통과).
- PASS — `npm ci`: lockfile 기준 373 packages 설치.
- NOT CONFIGURED — visual regression. 승인된 baseline과 service credential을 후속 결정에서 마련한다.

생성 bundle/manifest와 15개 repeated raw-pixel baseline은 제거되지 않은 명시적 migration debt다.
Support frontend는 ADR-090 승인 전까지 의도적으로 제외했으며, CSRF baseline gate는 Plan 80/90이 세 오류를
제거할 때 함께 삭제해야 한다.

Completion correction: 최초 완료 검증은 개별 story만 확인해 8개 network Autodocs에서 마지막 MSW
handler가 모든 inline Canvas를 덮어쓰는 결함을 놓쳤다. 교정 후 `test:storybook:docs`가 19개 Docs entry와
10개 상태 문서의 40개 state surface를 실제 Chromium에서 검증하며 CI의 Storybook build 직후 실행된다.
추가 완료 감사에서는 32개 generated component의 `KEEP/MERGE/MIGRATE/DEPRECATE/DELETE` 결과와 14개
route의 state matrix를 `frontend/docs/design-system-inventory.md`에 고정했다. Live design-system CSS는
`Button`, `StatusBadge`, `FeedbackState`가 소유하는 세 family만 남고 `check:design`이 orphan selector
family의 재도입을 차단한다. Storybook은 17개 파일, 62개 story로 확장됐고, 그중 8개 multi-state MSW
Docs만 iframe 격리를 적용한다. Operations의 실제 제출 `play`는 `!autodocs` interaction story로 분리했다.
