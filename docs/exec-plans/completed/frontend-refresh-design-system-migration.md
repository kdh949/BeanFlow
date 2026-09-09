# 신규 화면 기반 단일 디자인 시스템 전환

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/frontend-beanflow-refresh-presentation.md`
> **Completed-At:** `2026-08-27`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객·점주 핵심 여덟 화면의 navy/coral 기반 정보 구조와 시각 언어를 BeanFlow frontend 전체가
재사용하는 유일한 디자인 시스템으로 승격한다. 임시 `beanflow-refresh` primitive와 frame을
`frontend/src/design-system/**`의 typed component, pattern, token으로 이동하고 고객·점주·운영·지원
화면의 공통 소비자를 새 시스템으로 마이그레이션한다. 기존 espresso/crema token, legacy `Ui`/`Shells`
호환 계층과 사용처가 사라진 CSS는 삭제한다.

완료 상태는 단순한 색상 교체가 아니다. 신규 여덟 page story와 실제 route가 canonical design-system을
소비하고, 다른 route도 같은 token/component/shell을 사용할 수 있으며, legacy design-system source와
import가 0건이어야 한다.

## Current State

- 신규 여덟 화면은 `frontend/src/presentation/beanflow-refresh/**`와 `.bfr-*` CSS에 별도 구현돼 있다.
- 기존 canonical `frontend/src/design-system/**`은 espresso/caramel/crema token과 `Button`,
  `StatusBadge`, `FeedbackState`만 제공한다.
- 다수의 비대상 route가 `frontend/src/components/Ui.tsx`, `frontend/src/components/Shells.tsx`와 기존
  design-system API를 사용한다.
- live Storybook은 기존 component 문서와 신규 page stories를 모두 제공하지만 신규 primitive/frame은
  page namespace 안에 있어 독립 재사용 문서가 없다.

## Definitions

- **new system:** 신규 여덟 화면에서 검증된 cool white, navy, coral, restrained border/radius, text-first
  status 언어를 canonical token/component/pattern으로 일반화한 시스템이다.
- **legacy system:** 기존 espresso/caramel/crema palette, 옛 component CSS, `components/Ui`,
  `components/Shells`와 이들에 종속된 호환 import다.
- **migration adapter:** API error를 FeedbackState props로 바꾸는 것처럼 시각 책임 없이 domain/runtime
  값을 canonical presentation API로 변환하는 작은 helper다.

## Scope

### In Scope

- 전체 canonical token, foundation docs, action/status/feedback/form/brand primitive 재구축
- customer, store, operations shell과 page heading 등 shared pattern 승격
- 신규 여덟 page 및 다른 frontend route의 canonical import 전환
- legacy design-system source, `Ui`/`Shells`, 임시 refresh primitive/frame와 호환 CSS 삭제
- Storybook Autodocs, interaction/a11y, import/design boundary와 visual QA 갱신

### Non-goals

- backend, OpenAPI, database, migration, transaction, authentication 정책 변경
- 모든 비대상 page의 정보 구조를 신규 screenshot과 동일하게 재설계
- 계약에 없는 data, 기능, 성공 fallback 추가
- commit, push, PR 또는 배포

## Business Rules and Invariants

- 서버 transaction state와 permission/error 의미는 기존 계약을 그대로 보존한다.
- `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`, stale, dependency failure를 success/empty로 축약하지 않는다.
- 고객, 점주, 운영 actor 경계와 route/session gate를 바꾸지 않는다.
- 실제 route는 Storybook fixture, hardcoded success 또는 임의 fallback data를 import하지 않는다.
- 고객 화면은 mobile-first, 점주·운영 화면은 dense workspace 책임을 유지한다.

## Architecture and Transaction Boundaries

- token은 `frontend/src/design-system/tokens/**`만 정의한다.
- primitive는 `frontend/src/design-system/components/**`, 여러 primitive를 묶는 shell/card/heading은
  `frontend/src/design-system/patterns/**`가 소유한다.
- feature/page는 canonical public index만 사용하고 raw style API나 unrestricted class/style prop을 받지 않는다.
- API request, idempotency key, polling, stale/retry와 backend transaction은 변경하지 않는다.

## Alternatives Considered

- **refresh CSS를 global import만 하기:** 재사용 API와 component ownership이 생기지 않아 기각.
- **옛 시스템을 compatibility alias로 유지:** 사용자 요구인 완전 삭제와 source-of-truth 단일화를 깨므로 기각.
- **비대상 route를 모두 한 번에 새 정보 구조로 재설계:** 계약과 업무 흐름 범위를 불필요하게 바꾸므로 기각.
- **canonical component API를 유지하고 내부만 교체:** 기본 migration 전략으로 채택하되 옛 시각 variant와
  legacy wrapper는 최종적으로 삭제한다.

## Failure Semantics

- Error adapter는 `ApiRequestError`의 status/message/correlation을 보존한다.
- loading, empty, error, denied, pending, unknown, reconciling, manual review는 서로 다른 semantic treatment를 갖는다.
- visual migration 실패는 old CSS fallback으로 숨기지 않고 Storybook/test failure로 드러낸다.

## Data and Migration

DB와 data migration은 없다. CSS/TypeScript import migration만 수행한다.

## API and Event Contracts

runtime OpenAPI, generated `frontend/src/api/schema.d.ts`, notification event와 기존 route contract를 바꾸지 않는다.

## Milestones

1. 신규 visual token을 canonical token source로 옮기고 foundation docs/story를 갱신한다.
2. Button, StatusText, FeedbackState, SearchField, QuantityStepper, BrandLockup을 canonical component로 구현한다.
3. CustomerShell, ConsoleShell과 PageHeading을 canonical shared presentation/pattern으로 구현한다. API/session 상태를 소유하는 shell은 design-system primitive만 조합하는 `presentation/AppShells`에 둔다.
4. 신규 여덟 page에서 임시 primitive/frame import를 제거하고 canonical system을 사용한다.
5. 비대상 frontend consumer를 canonical public API로 옮기고 legacy `Ui`/`Shells`와 옛 token/style을 삭제한다.
6. live Storybook preview/test와 전체 repository validation을 통과한다.
7. same-viewport visual QA, legacy import 0건 증거와 문서/ExecPlan completion을 기록한다.

## Required Tests

- component stories: default, loading, disabled, long Korean, keyboard interaction, error/uncertain status
- shell stories: customer notification states, store/operations role navigation, responsive layouts
- 신규 여덟 page stories와 비대상 고객·점주·운영 대표 stories
- legacy file/import/token/class boundary test
- 전체 unit, typecheck, build, Sites, Storybook docs/build/interaction/a11y

## Validation Commands

```bash
cd frontend
npm run typecheck
npm run test:unit
npm run check:design
npm run check:presentation-boundary
npm run build
npm run test:sites
npm run build-storybook
npm run test:storybook:docs

# live Storybook MCP
get-changed-stories
preview-stories
run-story-tests(a11y=true)

cd ..
git diff --check
```

## Observability

새 runtime metric이나 log는 추가하지 않는다. 기존 correlation reference와 transaction state 표현을 보존한다.

## Documentation Updates

- `frontend/docs/design-system-governance.md`의 canonical visual direction과 layer 책임 갱신
- Foundations docs와 component JSDoc/Autodocs 갱신
- `frontend/design-qa.md`에 기준 story와 전체 적용 결과 기록
- 이 ExecPlan의 Progress, Surprises, Decision Log, Outcomes를 구현 중 갱신

## Progress

- 2026-08-27: 사용자로부터 신규 여덟 story를 유일한 디자인 기준으로 삼고 기존 시스템을 완전히 삭제하며
  다른 화면도 새 시스템을 사용할 수 있게 하라는 범위를 확인했다.
- 2026-08-27: repository/frontend rules, design governance, completed refresh plan, live Storybook documentation,
  current import graph와 legacy consumer를 확인했다.
- 2026-08-27: backend/API/DB 변경 없이 implementation-ready로 판정하고 구현 전 10개 항목을 보고했다.
- 2026-08-27: cool white/navy/coral token, Button/ButtonLink, StatusText, FeedbackState, BrandLockup,
  SearchField, QuantityStepper, PageHeading과 customer/console shell을 canonical API로 구현했다.
- 2026-08-27: 신규 여덟 화면과 모든 기존 route consumer를 canonical public API로 전환하고 legacy
  `Ui`, `Shells`, `StatusBadge`, refresh primitive/frame, espresso/caramel/crema token과 snapshot bundle을 삭제했다.
- 2026-08-27: Storybook 접근성 검사에서 bright coral과 흰 글자의 대비 부족을 발견해 bright accent와
  accessible dark coral semantic을 분리했다.
- 2026-08-27: typecheck, 23개 unit file/173 tests, design/presentation boundary, production build, Sites,
  Storybook build/docs, live Storybook 40 files/156 stories 및 a11y, `git diff --check`를 통과했다.

## Surprises & Discoveries

- 신규 화면 primitive/frame은 시각적으로 canonical 후보지만 `presentation` 아래에 있고 독립 Storybook 문서가 없다.
- 기존 foundation 문서는 espresso/caramel/crema 201개 token을 canonical로 선언해 사용자 결정과 충돌한다.
- legacy `Ui`/`Shells`는 대부분 얇은 wrapper이므로 API error adapter만 분리하면 canonical system으로 이동 가능하다.
- live Storybook test runner는 watch mode에 남으므로 broad pass마다 깨끗한 단일 Storybook 프로세스를 사용해야
  중복 실행 timeout을 피할 수 있었다.
- 제공된 밝은 coral은 흰색 소형 텍스트와 3.31:1 대비여서 그대로 action semantic으로 사용할 수 없었다.

## Decision Log

- 2026-08-27: 신규 여덟 page story의 navy/coral 시각 언어를 전체 frontend의 유일한 source of truth로 채택한다.
- 2026-08-27: 호환 token/theme을 병행하지 않고 consumer migration 완료 후 legacy source를 삭제한다.
- 2026-08-27: 비대상 route는 업무 정보 구조를 유지하되 새 token/component/shell을 사용하도록 전환한다.
- 2026-08-27: `AppShells`는 actor/session/API 상태를 소유하므로 순수 design-system pattern으로 가장하지 않고
  shared presentation에 두며 canonical component만 조합한다.
- 2026-08-27: bright coral은 장식·강조선에, dark coral은 소형 텍스트·채워진 action에 사용해 시각 방향과
  WCAG AA를 함께 만족시킨다.

## Outcomes & Retrospective

신규 여덟 story의 시각 언어가 frontend 전체의 유일한 canonical design system이 됐다. 고객, 점주, 운영,
지원 route는 동일 token/component/shell을 사용하며 legacy source/import/token은 boundary guard가 재유입을
차단한다. runtime OpenAPI, actor/session gate, transaction/failure semantics에는 변경이 없다.

실행 결과는 typecheck, unit 173 tests, design/presentation boundary, production build, Sites 4 tests,
Storybook build/docs, live Chromium Storybook 40 files/156 stories와 a11y가 모두 통과했다. visual QA는 동일
viewport 비교와 focused crop으로 재확인했으며 `frontend/design-qa.md`의 최종 결과는 passed다.

남은 비차단 사항은 production build와 Storybook build의 기존 large-chunk warning, Storybook의
`vitest.init()` deprecation 안내뿐이다. 이 작업에서는 commit, push, PR 또는 배포를 수행하지 않았다.

## Revision Notes

- 2026-08-27: repository-wide design-system replacement ExecPlan 작성.
- 2026-08-27: 실제 구현·검증 결과와 접근성 token 결정을 기록하고 완료 처리.
