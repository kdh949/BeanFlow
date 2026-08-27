# 프런트엔드 디자인 시스템 리뷰 결함 보정

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

PR #122의 디자인 시스템 리뷰에서 확인된 계층 역전, 안전하지 않은 오류 문구, 도메인 상태 소유권,
터치 영역, semantic token 위계, CI 회귀 게이트, 전역 공용 CSS, 문서 drift를 보정한다. 같은 유형의
문제가 다른 화면에 남지 않도록 저장소 전체를 검사하고 자동 회귀 검사를 추가한다.

## Current State

`src/design-system`이 `api/client`를 참조하고, canonical `StatusText`가 주문 상태와 한국어 문구를
소유한다. 일부 사용자 화면은 임의의 `Error.message`를 렌더링한다. 공용 스타일 일부는 전역
`src/styles.css`에 남아 있고, 소형 컨트롤과 화면별 override 중 44px 미만 터치 영역이 있다.
presentation-boundary 회귀 테스트는 기본 frontend CI 명령에 연결되지 않았다.

## Definitions

- presentation-safe prop: HTTP 또는 도메인 객체 없이 렌더링 가능한 제목, 설명, tone, children.
- application presentation boundary: API 오류와 도메인 상태를 사용자 문구와 시각 tone으로 변환하는 계층.
- canonical design system: `frontend/src/design-system`의 token, component, pattern, Storybook 계약.

## Scope

### In Scope

- 디자인 시스템의 API 및 제품 도메인 의존성 제거
- 알려진 오류만 안전한 사용자 문구로 변환하고 나머지는 고정 문구로 처리
- 모든 모바일 상호작용 영역의 44px 최소 크기 보장
- semantic text token 위계 교정과 Foundations 문서화
- 공용 시각 class의 디자인 시스템 소유권 이전
- boundary 회귀 테스트의 기본 CI 연결과 유사 위반 검사
- inventory와 Storybook 문서의 실제 계약 동기화

### Non-goals

- 백엔드 API, DB, 주문 상태 계약 변경
- 신규 제품 흐름 또는 상태 추가
- PR merge

## Business Rules and Invariants

- 사용자에게 네트워크, 라이브러리 또는 구현 예외의 원문을 노출하지 않는다.
- correlation/reference 값은 안전한 알려진 필드로만 노출한다.
- 주문 상태 의미는 제품 presentation 계층이 소유하고 디자인 시스템은 tone과 content만 렌더링한다.
- 포인터 입력용 canonical control은 `--tap-min` 이상을 보장한다.

## Architecture and Transaction Boundaries

변경은 frontend presentation 및 styling 경계에 한정된다. 서버 요청, Aggregate, DB 트랜잭션,
동시성 및 멱등성 경계는 변경하지 않는다. `presentation/shared`가 API 오류와 도메인 상태를
presentation-safe prop으로 변환하고 `design-system`은 이를 렌더링한다.

## Alternatives Considered

- 디자인 시스템에 adapter hook을 두는 안은 HTTP 및 도메인 모델 의존성을 계속 끌어오므로 제외한다.
- 모든 공용 class를 React component로 치환하는 안은 과도하다. 반복 시각 contract만 디자인 시스템
  소유 CSS로 이동하고 feature-specific layout은 전역 진입 CSS에 유지한다.
- desktop dense variant만 44px 예외로 두는 안은 현재 customer surface 사용과 정책 충돌 위험이 있어
  canonical 상호작용 box 전체에 44px를 적용한다.

## Failure Semantics

알 수 없는 오류는 고정된 안전 문구로 표시한다. 알려진 `ApiRequestError`의 code/status만 명시적으로
매핑하며, 원본 `message`는 UI fallback으로 사용하지 않는다. 로깅 또는 correlation 수집 동작은 기존
API client 경계를 유지한다.

## Data and Migration

DB 및 데이터 migration 없음.

## API and Event Contracts

외부 API와 이벤트 계약 변경 없음. 내부 React component prop과 import 경계만 변경한다.

## Milestones

1. 오류와 상태 presentation adapter를 `presentation/shared`로 이동한다.
2. canonical component를 presentation-safe API로 축소하고 소비자를 전환한다.
3. 터치 영역, token 위계, 공용 CSS 소유권을 수정한다.
4. guard, unit, Storybook 및 문서를 갱신한다.
5. 전체 검증 후 commit/push하고 정확한 리뷰 thread를 해결한다.

## Required Tests

- 임의 `Error.message`가 사용자 문구에 포함되지 않는 단위 테스트
- design-system 역방향 import 및 전역 shared selector를 탐지하는 boundary 회귀 테스트
- Button, SearchField, QuantityStepper computed hit area Storybook interaction test
- 변경 Storybook a11y와 전체 Storybook test

## Validation Commands

- `npm test`
- `npm run typecheck`
- `npm run check:design`
- `npm run check:presentation-boundary`
- `npm run build`
- `npm run test:sites`
- `npm run build-storybook`
- `npm run test:storybook:docs`
- Storybook MCP changed-story preview와 a11y test
- `git diff --check`

## Observability

운영 관측성 계약은 변경하지 않는다. UI에는 안전한 reference만 유지하고 알 수 없는 오류 원문은 노출하지 않는다.

## Documentation Updates

`frontend/docs/design-system-inventory.md`, Foundations Storybook, frontend agent validation 명령을 실제 계약과 맞춘다.

## Progress

- [x] 8개 리뷰 thread와 유사 패턴 초기 감사
- [x] presentation 경계 수정
- [x] styling, token, CI, 문서 수정
- [x] 전체 검증
- [ ] push 및 review thread 해결

## Surprises & Discoveries

- 화면별 refresh CSS가 canonical control의 크기를 다시 줄이는 override를 포함한다.
- presentation-boundary guard 자체 테스트는 존재하지만 기본 CI 명령에서 실행되지 않는다.
- 기존 unit 및 Storybook play assertion 일부가 서버 오류 원문 노출을 계약으로 고정하고 있어 안전한
  presentation 문구로 함께 갱신해야 했다.

## Decision Log

- 2026-08-27: 도메인-aware wrapper는 `presentation/shared`, visual primitive는 `design-system`에 둔다.
- 2026-08-27: dense 예외 없이 모든 pointer control에 44px hit area를 적용한다.

## Outcomes & Retrospective

디자인 시스템의 application import와 domain 상태 소유권을 제거했고, 안전한 오류 adapter와 domain 상태
adapter를 `presentation/shared`로 분리했다. 공용 CSS 소유권과 44px 터치 영역을 guard와 Storybook
interaction test로 고정했다. unit 176개, boundary 7개, live Storybook 159개(a11y 포함), typecheck,
design guard, product/Storybook build, Sites 및 Storybook docs isolation이 통과했다. 원격 반영과 review
thread 해결은 다음 단계에서 수행한다.

## Revision Notes

- 2026-08-27: PR #122 review remediation 시작.
