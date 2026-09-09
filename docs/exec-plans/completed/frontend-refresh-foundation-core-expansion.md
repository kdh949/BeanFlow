# Refresh 기반 실사용 Core foundation 확장

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-08-27`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

신규 고객·점주 화면에서 확정한 cool white, navy, coral 디자인을 모든 BeanFlow frontend surface의
form, selection, navigation control에 적용한다. 완료 후 제품 화면은 raw input/select/textarea와 화면별
공용 control CSS를 직접 소유하지 않고, Storybook에서 문서화된 canonical component를 조합한다.

## Current State

`Button`, `SearchField`, `QuantityStepper`, `FeedbackState`, `StatusText`, `PageHeading`은 canonical source다.
반면 인증, 고객 주문·알림, 점주 설정·분쟁, 운영 정책·계정, 지원 workspace는 native form control과
중복 CSS를 직접 조합한다. 탭, switch, radio card, query chip도 화면별 contract로 남아 있다.

## Definitions

- Control foundation: field와 selection의 rest, hover, focus, invalid, disabled, selected 상태 token.
- Product presentation: API·domain 상태를 사용자 문구와 presentation-safe prop으로 바꾸는 application layer.
- Canonical component: `frontend/src/design-system/index.ts`가 export하고 Storybook contract가 있는 control.

## Scope

### In Scope

- semantic control token과 Foundation Storybook 문서
- text/textarea/select, checkbox/switch/radio, tabs, icon/chip action, inline notice
- SearchField와 QuantityStepper 상태 확장
- 기존 네 surface consumer 마이그레이션과 중복 control CSS 제거
- raw control, design-system dependency, shared CSS ownership guard와 회귀 테스트

### Non-goals

- API, domain state, transaction, database 변경
- dialog, toast, tooltip, data table, pagination
- 새 production dependency 또는 visual regression service 도입

## Business Rules and Invariants

- 서버가 확정한 order/payment/refund/notification 상태와 실패 의미를 바꾸지 않는다.
- known application error만 presentation layer에서 사용자 문구로 변환한다.
- 제품 route에 fixture, hardcoded success, fake fallback을 추가하지 않는다.
- 모든 interactive control은 최소 44px hit target과 keyboard/focus contract를 지킨다.

## Architecture and Transaction Boundaries

`src/design-system`은 React, router, icon과 presentation-safe prop만 소비한다. API client, auth, feature,
page, presentation module을 import하지 않는다. 데이터 요청과 mutation transaction 경계는 기존 page와
hook에 그대로 있고, component callback은 값 또는 사용자 intent만 전달한다.

## Alternatives Considered

- 컴포넌트만 추가: 기존 parallel control contract가 남으므로 제외한다.
- 일반 목적 전체 UI kit: 검증되지 않은 API와 유지 비용이 생기므로 제외한다.
- 현재 반복 사용처만 canonical화하고 소비자를 함께 전환하는 방식을 선택한다.

## Failure Semantics

Field validation은 전달받은 안전한 문구만 표시한다. `InlineNotice`도 Error 객체를 받지 않는다.
API failure mapping과 correlation reference는 `presentation/shared`에 남긴다. disabled/loading은 명령을
실행하지 않으며 실패를 성공이나 empty로 바꾸지 않는다.

## Data and Migration

데이터와 DB migration은 없다.

## API and Event Contracts

외부 API와 event contract 변경은 없다. 새 public interface는 design-system React prop뿐이다.

## Milestones

1. semantic token, foundation docs, component source와 story를 추가한다.
2. focused Storybook interaction/a11y를 통과한다.
3. 모든 현재 consumer를 canonical component로 전환하고 duplicate CSS를 제거한다.
4. boundary guard와 문서를 갱신하고 전체 validation을 통과한다.

## Required Tests

- field label, described-by, invalid, disabled, read-only
- checkbox/switch Space, radio arrow navigation, tabs manual/automatic navigation
- QuantityStepper min/max/disabled와 모든 action의 44px hit target
- 긴 한국어와 mobile/workspace density
- raw control 및 application dependency guard의 positive/negative fixture
- 관련 route story의 정상·error·permission·transaction state

## Validation Commands

- `cd frontend && npm run typecheck`
- `cd frontend && npm test`
- `cd frontend && npm run check:design`
- `cd frontend && npm run build-storybook`
- `cd frontend && npm run test:storybook:docs`
- `cd frontend && npm run build`
- `cd frontend && npm run test:sites`
- Storybook MCP changed stories, preview, focused/full `run-story-tests(a11y=true)`
- `git diff --check`

## Observability

Runtime telemetry는 변경하지 않는다. UI validation과 dependency failure correlation 표시를 보존한다.

## Documentation Updates

Foundation MDX, design-system inventory, governance, 이 ExecPlan을 실제 export와 검증 결과에 맞춘다.

## Progress

- [x] 2026-08-27: live Storybook inventory와 기존 public API를 조회했다.
- [x] 2026-08-27: raw form/selection/control 사용처와 CSS ownership을 전수 검색했다.
- [x] 2026-08-27: control semantic token, Foundation 문서와 canonical component를 구현했다.
- [x] 2026-08-27: customer, store, operations, support consumer를 canonical component로 전환했다.
- [x] 2026-08-27: raw control/CSS ownership guard, 문서와 전체 validation을 완료했다.

## Surprises & Discoveries

- 기존 `Button`의 `sm`도 이미 44px hit target으로 보정되어 있어 visual density와 pointer target을 분리할
  수 있다.
- dialog는 현재 native/product 사용처가 없어 이번 실사용 Core 범위에서 제외한다.
- 기존 unit과 Storybook play 일부가 선택 항목을 `button` 또는 `checkbox` role로 고정하고 있어
  `radio`, `tab`, `switch`의 실제 semantics에 맞춰 회귀 계약을 함께 갱신했다.
- Storybook Docs smoke의 첫 sandbox 실행은 macOS Chromium MachPort 제한으로 차단됐고, 허용된 환경에서
  재실행해 통과했다. 제품 또는 문서 결함은 아니었다.

## Decision Log

- 2026-08-27: 사용자 선택에 따라 unused kitchen-sink kit가 아닌 현재 사용처 전체를 교체하는 Core 확장을
  선택했다.
- 2026-08-27: design-system은 domain/API error를 소유하지 않고 presentation-safe props만 받는다.

## Outcomes & Retrospective

기존 palette에 새 raw color를 추가하지 않고 `--control-*` semantic alias와 text, textarea, select,
checkbox, switch, radio, tabs, icon/chip action, inline notice를 canonical public API로 추가했다.
인증, 주문, 매장·메뉴, 알림, 점주·운영·지원 화면의 native control을 전환하고 feature CSS에는 배치만
남겼다. `SearchField`의 공통 description/error 연결과 `QuantityStepper` disabled/min/max 및 44px action도
같은 계약으로 보강했다.

raw `input/select/textarea`, feature control CSS와 design-system의 application dependency를 boundary
guard와 9개 회귀 테스트로 고정했다. 전체 unit 176개, typecheck, design/boundary guard, product build,
Sites 4개, Storybook static build, Docs smoke와 live Storybook 전체 interaction/a11y가 통과했다.
Storybook preview로 canonical field/selection/tabs와 customer order/store surface를 확인했다.
Visual regression service는 현재 구성되지 않아 `Not configured`이며 새 production dependency와 API,
domain, transaction 변경은 없다.

## Revision Notes

- 2026-08-27: 최초 구현 계획 작성.
- 2026-08-27: foundation, consumer migration, guard와 전체 검증 완료.
