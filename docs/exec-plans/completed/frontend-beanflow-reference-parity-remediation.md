# 참조 이미지 기준 BeanFlow refresh 프레젠테이션 재구현

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/frontend-beanflow-refresh-presentation.md`
> **Completed-At:** `2026-08-27`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

초기 refresh 구현에서 남은 구 디자인 시스템 의존과 참조 이미지에 없는 영문 eyebrow, 초록 pill 상태 배지,
카드 중심의 시각 계층을 제거한다. `feature/customer-home-find-fast`의 고객 홈 구조와 첨부된 여덟 이미지를
시각 기준으로 사용하되, 실제 route와 서버 계약은 유지한다.

## Current State

- 실제 8개 route는 `frontend/src/presentation/beanflow-refresh/**`를 사용한다.
- refresh 페이지가 구 `design-system`의 Button, StatusBadge, FeedbackState, BrandLockup 등에 의존한다.
- customer home에는 참조에 없는 영문 eyebrow와 초록 pill 배지가 남아 있다.
- 기존 구현이 구 design-system token 파일까지 확장해 refresh 전용 값을 전역에 추가했다.

## Definitions

- **reference parity:** 이미지 크롭이 아니라 같은 정보 계층, 밀도, 색 역할, 간격과 가장 가까운 라이브러리 아이콘으로 재현하는 것.
- **isolated refresh UI:** refresh namespace 밖의 디자인 시스템 component/token/CSS를 import하지 않는 프레젠테이션.

## Scope

### In Scope

- refresh 전용 primitive, local token, shell, 8개 page와 stories 재구현
- 구 design-system import 금지와 회귀 테스트
- 초기 구현이 구 design-system에 추가한 refresh extension 제거
- 동일 viewport의 source/implementation 결합 비교와 Storybook 검증

### Non-goals

- backend, OpenAPI, DB, migration, transaction, provider 동작 변경
- 대상 밖 기존 화면의 디자인 시스템 삭제 또는 redesign
- source image crop/배경 사용, 가짜 성공/상태/데이터 추가

## Business Rules and Invariants

- `orderingAvailable`, pickup window, quote `guarantee=NONE`, immutable pricing/lifecycle를 서버 값 그대로 유지한다.
- Checkout은 Toss Payments 통합 결제창이며 저장 카드 UI가 없다.
- 주문 상세 pickup number는 text이며 실제 QR 동작이 없다.
- board는 3초 polling, ETag, overflow, server itemSummary와 allowed actions를 유지한다.
- refund는 previewVersion, reason, idempotency와 unresolved 상태를 명시한다.

## Architecture and Transaction Boundaries

- API/session/domain hook은 presentation-neutral dependency로 재사용한다.
- 모든 시각 primitive와 token은 refresh namespace 안에서만 소유하고 `.bfr-*` root 아래로 scope한다.
- backend transaction과 API request shape는 바꾸지 않는다.

## Alternatives Considered

- 구 디자인 시스템 token을 더 추가해 보정: 전역 결합과 시각 drift가 계속돼 기각.
- screenshot crop 사용: interaction/accessibility/contract 의미를 잃어 기각.
- customer home만 수정: 동일 refresh 언어를 요구한 8개 화면 간 일관성을 깨 기각.

## Failure Semantics

- loading, empty, error, unknown, reconciling, manual review를 성공/빈 값으로 축약하지 않는다.
- 상태는 pill 색만으로 표현하지 않고 구조와 한국어 copy로 전달한다.

## Data and Migration

없음.

## API and Event Contracts

기존 completed plan에 기록된 customer discovery/order/payment/merchant API를 변경 없이 사용한다.

## Milestones

1. 경계 테스트를 RED로 추가하고 구 design-system import를 차단한다.
2. refresh-local primitive/token으로 customer shell/home/search를 재구현한다.
3. store/menu/cart/checkout/order detail을 reference 구조로 맞춘다.
4. merchant board/refund를 dense desktop reference로 맞춘다.
5. 구 design-system extension을 제거하고 boundary/type/unit 검증을 통과한다.
6. 동일 viewport 비교를 반복하고 `frontend/design-qa.md`를 갱신한다.
7. focused/full Storybook interaction+a11y와 build/docs 검증을 완료한다.

## Required Tests

- refresh의 `design-system`, `components/Ui`, `components/Shells`, 전역 styles import 금지
- customer home의 영문 eyebrow/green badge 회귀 방지
- 기존 API interaction/실패/불명 상태 stories
- 390px mobile과 1440px desktop 동일 viewport visual comparison

## Validation Commands

기존 completed plan의 명령과 live Storybook `preview-stories`, focused/full `run-story-tests(a11y=true)`를 실행한다.

## Observability

기존 API error/correlation/domain state를 유지하며 새 production metric은 추가하지 않는다.

## Documentation Updates

이 계획의 Progress/Decision/Outcome과 `frontend/design-qa.md`를 갱신한다.

## Progress

- 2026-08-27: 사용자 annotation, 첨부 이미지, `feature/customer-home-find-fast`, 현재 refresh 의존을 비교했다.
- 2026-08-27: backend 계약 변경 없이 frontend implementation-ready로 판정하고 구현 전 경계를 보고했다.
- 2026-08-27: presentation boundary 테스트를 RED에서 시작해 refresh의 legacy design-system, Shells, Ui,
  global styles import를 차단했고, local primitive와 `.bfr-*` CSS root로 전환해 GREEN으로 만들었다.
- 2026-08-27: customer home을 큰 검색 action, coral active-order row, 주문 가능한 매장과 추천 매장 계층으로
  재구현하고, 영문 eyebrow와 초록 pill badge를 모든 대상 화면 source에서 제거했다.
- 2026-08-27: 초기 구현이 legacy design-system에 추가했던 refresh component/token/story 변경을 제거했다.
  `frontend/src/design-system/**`은 현재 remediation diff를 남기지 않는다.
- 2026-08-27: focused 5개와 full live Storybook interaction/a11y, unit 173 tests, typecheck, design adherence,
  presentation boundary, production/Sites/Storybook build와 Storybook Docs를 통과했다.
- 2026-08-27: in-app browser에서 8개 canonical story를 다시 캡처하고 customer `390 × 692`, merchant
  `1106 × 692`로 source와 정규화해 결합 비교했다. 세 차례 P1/P2 수정 후 `frontend/design-qa.md`를
  `final result: passed`로 갱신했다.
- 2026-08-27: typecheck, unit 173 tests, design/presentation boundary, production/Sites/Storybook build,
  Storybook Docs, changed-story discovery, preview, full interaction/a11y와 `git diff --check`를 통과했다.

## Surprises & Discoveries

- 초기 refresh 구현이 별도 namespace였지만 primitive와 token은 구 design-system에 추가해 실제로는 격리되지 않았다.
- 선택 branch의 핵심은 초록 pill이 아니라 coral full-width active-order row와 큰 검색 중심 hierarchy였다.
- legacy design adherence 검사는 모든 CSS를 global token 체계에 결합하므로 독립 presentation CSS와 충돌했다.
  refresh CSS는 presentation-boundary 검사가 소유하도록 검사 책임을 분리하고 기존 global CSS debt baseline은
  실제 감소한 값으로 정리했다.
- Storybook Docs browser smoke는 restricted sandbox의 macOS Mach port 제한으로 실패했지만 허용된 환경에서는 통과했다.

## Decision Log

- 2026-08-27: 대상 밖 기존 design-system은 보존하되 refresh가 import/전역 token을 소비하지 못하도록 격리한다.
- 2026-08-27: 참조 이미지에 없는 영문 eyebrow는 모든 대상 화면에서 제거한다.
- 2026-08-27: local refresh CSS는 legacy design-adherence token 규칙에서 제외하고, 같은 `check:design` 명령에
  연결된 presentation-boundary가 namespace/import 소유권을 강제한다.
- 2026-08-27: 최종 시각 비교를 새로 확보하기 전에는 automated green만으로 reference parity 완료를 주장하지 않는다.
- 2026-08-27: source에 있으나 runtime 계약에 없는 PII, POS/web channel, VAT, QR, 저장 카드는 시각 parity를
  위해 fixture나 product route에 만들지 않고 계약이 제공하는 orderContext, itemSummary, pickup number로 대체한다.

## Outcomes & Retrospective

구 디자인 시스템 의존 제거, customer home 시각 계층 수정, 8개 route source와 canonical stories의 local
presentation 전환, legacy 삭제와 import 경계 검증을 완료했다. live Storybook 화면을 source와 동일한 비율로
정규화해 full-view와 focused 결합 비교했고, oversized hierarchy, 영문 eyebrow/초록 badge, dark merchant shell,
cart/order/refund block-order와 fixture density 문제를 반복 수정했다. 최종 QA와 지정된 자동 검증은 모두 통과했다.

## Revision Notes

- 2026-08-27: 사용자 시각 피드백에 따른 reference-parity remediation plan 작성.
- 2026-08-27: same-viewport 시각 비교가 가능해진 뒤 P1/P2 iteration과 최종 검증 결과를 반영해 완료 처리.
