# BeanFlow Design System

BeanFlow는 고객 app, 매장 console, 운영 console에서 하나의 거래 언어를 사용한다. 이 디렉터리는
그 언어의 canonical token과 편집 가능한 React component source를 소유한다.

## Source precedence

1. Accepted product/design decision과 accessibility requirement
2. `tokens/*.css`
3. `components/**/*.tsx`와 component CSS
4. canonical Storybook stories/docs
5. product pages
6. generated bundle, manifest, screenshot, archived exploration

`_ds_bundle.js`와 `_ds_manifest.json`은 2026-08-15 이전 생성 snapshot이다. 존재하지 않는 32개 JSX
source와 잘못 분류된 token metadata를 포함하므로 migration 참고용으로만 보존한다. Product code에서
import하거나 public component API로 취급하지 않는다.

## Current editable components

| Layer | Source | Responsibility |
|---|---|---|
| Core | `components/core/Button.tsx` | form action과 router navigation을 위한 typed Button/ButtonLink |
| Feedback | `components/feedback/FeedbackState.tsx` | loading, empty, recoverable error와 assistive announcement |
| Commerce | `components/commerce/StatusBadge.tsx` | server transaction state label과 success/progress/uncertain/failure tone |

새 component는 실제 product reuse 지점이 있을 때 `REUSE → COMPOSE → EXTEND → NEW` 순서로 판단한다.
Generated manifest의 32개 항목을 맞추기 위해 사용되지 않는 API를 복원하지 않는다.
전체 token, manifest 32종 분류, route/state coverage와 debt는
`../../docs/design-system-inventory.md`에서 현재 source 기준으로 관리한다.

## Tokens

`styles.css`가 token과 component CSS의 global entry다. 현재 canonical token은 201개다.

- palette: espresso, caramel, crema, mint, amber, berry, sky
- semantic: surface, text, border, action, status, domain
- typography: family, size, line height, weight, letter spacing, numeral
- layout: spacing, radius, control/layout dimensions
- effects: elevation, focus ring, motion duration/easing

중요 token과 사용 규칙은 Storybook `Foundations/Overview`에서 실제 rendering으로 확인한다. 새 raw
color/font/shadow, undefined token, generated import, route story 누락은 `npm run check:design`이 막는다.
반복 raw pixel은 intrinsic geometry와 legacy layout debt baseline보다 늘어날 수 없다.

## Storybook taxonomy

```text
Foundations/*
Components/Core|Forms|Feedback|Navigation|Commerce/*
Patterns/Customer|Store|Operations/*
Pages/Customer|Store|Operations|Shared/*
Explorations/*
```

Canonical component는 typed props, JSDoc, Autodocs, explicit story description과 a11y `error` gate를
갖는다. 현재 route/page와 중요한 loading, empty, error, permission, pending, unknown, reconciling,
manual-review state는 62개 `Pages/*`·component story에서 직접 열 수 있다. Live component CSS는
editable owner가 있는 `bf-btn`, `bf-status`, `bf-feedback` family만 제공한다.

## Product copy invariants

- 한국어 해요체를 기본으로 하고 내부 구현 용어를 사용자에게 노출하지 않는다.
- 금액·시간·수량을 먼저 말하고 버튼은 행동과 대상을 함께 쓴다.
- `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`는 성공이나 확정 실패로 바꾸지 않는다.
- 고객은 매장 수락 전 전체 취소만 할 수 있고, 부분 환불은 매장·운영자 흐름이다.
- 정산은 픽업 완료일 기준이며 확정 회차를 고치지 않고 다음 회차 조정으로 반영한다.
- 이모지, 임의 SVG, 새 브랜드 palette나 typography system을 추가하지 않는다.

## Workflow and validation

UI 작업 전 `frontend/AGENTS.md`와 `frontend/docs/design-system-governance.md`를 읽고 Storybook MCP로
inventory와 candidate docs를 확인한다. 실행·복구 절차는 `frontend/docs/storybook-runbook.md`를 따른다.

```bash
npm run check:design
npm run test:unit
npm run typecheck
npm run build-storybook
npm run test:storybook:docs
```

UI 변경 뒤 package command와 별개로 MCP `get-changed-stories`, `preview-stories`,
`run-story-tests(a11y=true)`를 실행한다. Visual regression은 clean baseline 승인 전까지
`Not configured`이며 coverage를 주장하지 않는다.
