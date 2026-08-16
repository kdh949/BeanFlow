# Storybook MCP Runbook

## Purpose

Storybook은 BeanFlow 디자인 시스템과 현재 route/page state의 실행 가능한 catalog다. MCP는 같은
running Storybook을 읽어 component docs, changed stories, preview URL, interaction과 accessibility
결과를 제공한다.

## Start

`frontend/`에서 lockfile 기반 dependency를 설치하고 Storybook을 시작한다.

```bash
npm ci
npm run storybook
```

기본 UI는 `http://localhost:6006`, MCP HTTP transport는 `http://localhost:6006/mcp`다.
`frontend/.mcp.json`의 `beanflow_storybook` server가 이 endpoint를 사용한다. 정적
`storybook-static/`은 review artefact이며 MCP transport가 아니다.

## Required MCP order

1. 작업 시작 시 `list-all-documentation(withStoryIds=true)`를 한 번 호출한다.
2. 후보 component마다 `get-documentation`을 호출한다.
3. story-specific 사용법이 필요하면 `get-documentation-for-story`를 호출한다.
4. story를 쓰기 전에 `get-storybook-story-instructions`를 호출한다.
5. 변경 뒤 `get-changed-stories`로 영향 story를 확인한다.
6. 모든 시각 변경 소비자를 `preview-stories`로 연다.
7. 반복 중 focused `run-story-tests(a11y=true)`, 인계 전 full run을 실행한다.

Package test는 MCP 검증을 대체하지 않는다. CI의 `test:storybook:ci`는 같은 CSF interaction/a11y
suite를 headless browser에서 재실행하는 별도 merge gate다. 여러 MSW variant를 한 문서에 표시하는
Autodocs는 story별 iframe을 사용한다. inline Canvas는 단일 worker handler를 공유하므로 허용하지 않는다.
한 Docs에서 여러 `play` 함수가 동시에 form input을 조작하지 않도록 결과 상태는 정적 presenter story로
고정하고, 실제 제출 interaction은 `!autodocs` story에서 검증한다.

## Local validation

```bash
npm run check:design
npm run test:unit
npm run typecheck
npm run build-storybook
npm run test:storybook:docs
```

`test:storybook:docs`는 먼저 생성된 `storybook-static/`을 임시 local server로 제공하고 19개 Docs entry를
실제 Chromium에서 연다. 모든 문서의 render error와 10개 상태 문서의 40개 state surface를 검사한다.
`check:design`은 둘 이상의 MSW 구성을 가진 Autodocs meta에 `docs.story.inline: false`가 없으면 먼저 실패한다.

`npm run typecheck`은 generated runtime OpenAPI schema와 TypeScript를 직접 검증한다. 오류가 하나라도
있으면 CI가 실패하며, 알려진 오류를 허용하는 typecheck baseline은 사용하지 않는다.

## Recovery

- MCP 연결 실패: Storybook process와 port 6006을 확인하고, static build URL로 대체하지 않는다.
- browser runner port 충돌: 기본 test API port는 63320이다. 이미 점유됐다면 실행 중인 MCP를 종료하지 말고
  `STORYBOOK_TEST_PORT=63321 npm run test:storybook:ci`처럼 빈 포트로 재정의한다.
- stale module/cache 오류: running Storybook을 정상 종료 후 다시 시작하고 MCP inventory를 새로 읽는다.
- MSW unhandled request: page가 empty/success로 진행하게 두지 말고 story에 정확한 method/path handler를 추가한다.
- accessibility failure: semantic/structural defect는 수정한다. 색·spacing·visual hierarchy 변경은 사용자 결정을 먼저 받는다.

## Generated artefacts

`_ds_bundle.js`, `_ds_manifest.json`, `storybook-static/`은 source가 아니다. Product code에서 import하면
`npm run check:design`이 실패한다. Static build output은 commit하지 않는다.
