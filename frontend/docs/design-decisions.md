# Frontend Design Decisions

이 문서는 `frontend/`에 한정된 작고 되돌리기 쉬운 디자인 시스템 결정을 기록한다. 제품 정책,
보안 경계 또는 장기 architecture를 바꾸는 결정은 Business Policy나 ADR에 기록한다.

| ID | Date | Status | Decision | Rationale | Revisit |
|---|---|---|---|---|---|
| DD-001 | 2026-08-15 | Accepted | `tokens/*.css`와 typed React source를 canonical implementation으로 삼고 `_ds_bundle.js`와 `_ds_manifest.json`은 deprecated migration input으로만 보존한다 | 생성 산출물이 존재하지 않는 source와 잘못 분류된 token metadata를 가리켜 직접 수정·import할 수 없다 | 모든 manifest entry가 editable source와 generated provenance를 갖게 될 때 |
| DD-002 | 2026-08-15 | Accepted | 현재 제품에서 반복 사용되는 primitive부터 `REUSE/COMPOSE/EXTEND/NEW` 순서로 복원하고 bundle-only 32종은 일괄 복원하지 않는다 | 사용되지 않는 API를 추측하지 않고 product route와 Storybook이 같은 source를 실제로 소비하게 한다 | productization plan이 새 primitive 또는 pattern을 요구할 때 |
| DD-003 | 2026-08-15 | Accepted | Storybook title은 `Foundations`, `Components`, `Patterns`, `Pages`, `Explorations` taxonomy를 사용하고 canonical component는 Autodocs, changed/new story는 a11y `error`를 기본값으로 한다 | page/state 접근성과 MCP 문서 검색을 안정적인 정보 구조와 실행 gate로 만든다 | Storybook major upgrade가 manifest나 docs convention을 바꿀 때 |
| DD-004 | 2026-08-15 | Accepted | accent text는 `caramel-700`, muted text는 `crema-600`, faint text는 `crema-700`, warning foreground는 `caramel-700`을 사용한다 | crema-100과 sunken crema-200, amber-100 배경에서 WCAG AA 4.5:1을 충족하며 기존 palette를 유지한다 | brand refresh 또는 실제 배경 조합에서 contrast regression이 관측될 때 |
| DD-005 | 2026-08-15 | Accepted | raw color/font/shadow, undefined token, generated bundle import를 executable adherence check로 막고 intrinsic geometry는 명시적 예외로 허용한다 | 실행되지 않는 Oxc 설정과 모든 `px` 금지는 enforcement도 아니고 hit target·icon·breakpoint를 오탐한다 | CSS AST 기반 lint가 같은 정책을 더 정확히 제공할 때 |
| DD-006 | 2026-08-15 | Accepted | visual regression은 중복 CSS 정리와 approved baseline 전까지 `Not configured`로 보고한다 | 승인되지 않은 현재 rendering을 기준선으로 고정하면 기존 drift를 정상으로 굳힌다 | canonical component와 critical page baseline을 사람이 승인할 때 |
| DD-007 | 2026-08-15 | Accepted | 둘 이상의 MSW 상태를 가진 Autodocs page는 `parameters.docs.story.inline: false`로 story iframe을 격리한다. 동일 Docs에서 form을 제출하는 상태는 정적 result presenter로 고정하고 실제 제출은 `!autodocs` interaction story로 검증한다 | inline Docs에서는 MSW handler가 덮어써졌고, iframe Docs에서도 여러 `play`의 동시 typing이 중간 request를 만드는 race가 확인됐다. 정적 state story와 interaction test의 책임을 분리해야 문서와 동작을 모두 결정적으로 검증할 수 있다 | Storybook이 Docs 내 iframe play 실행의 독립성과 순서를 공식 보장할 때 |
| DD-008 | 2026-08-15 | Accepted | live design-system component CSS는 editable TSX owner가 있는 selector family만 import하고 bundle-only form/navigation/card/status selector는 제거한다 | typed API가 없는 CSS가 전역으로 로드되면 generated snapshot이 사실상 병렬 public API로 되살아나며 신규 중복을 막을 수 없다 | 실제 product slice가 둘 이상의 consumer와 검증된 typed component API를 요구할 때 |
