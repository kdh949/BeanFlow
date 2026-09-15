# 방문자별 데모 구현 검증 — 2026-09-15

초기 구현 검증 대상은 BeanFlow 작업 폴더의 데모 변경이다. 아래 원본 검증 후 최신 main `0ea0055`에서 `feature/visitor-demo-journey`로 데모 변경만 분리했다. 성능/복구 변경과 V87은 PR에 포함하지 않는다. 배포는 수행하지 않았다.

관련 문서: [완료 계획](../exec-plans/completed/visitor-demo-workspace-and-guided-journey.md), [ADR-132](../adr/ADR-132-visitor-demo-workspace.md), [활성화 및 11개 화면 링크](../operations/visitor-demo-runbook.md).

## 구현 결과

- `/demo`에서 별도 고객·점주·매장·메뉴·슬롯·포인트와 실제 샘플 주문을 원자적으로 발급한다. 공개 공유 계정을 사용하지 않는다.
- 기존 점주 주문 전환과 고객 조회 API로 PAID → ACCEPTED → PREPARING → READY → COMPLETED를 처리한다. 안내는 서버에서 확인한 상태를 따른다.
- 30분 만료/종료는 양쪽 인증 접근을 차단하고 주문·결제·원장 증거를 보존한다. timeout 재시작은 원래 주문을 보존하며 새 주문을 생성한다.
- V88과 전용 CSRF/Session 경계를 함께 추가한다. 기본 비활성, local + local-demo/toss-sandbox 조합에서만 활성화할 수 있다.

## 자동 검증

| 검사 | 결과 | 근거 |
| --- | --- | --- |
| DemoSafetyTest | Passed, 3개 | 허용 profile, 비운영 경계, 설정 상한 |
| DemoWorkspaceIntegrationTest | Passed, 9개 | PostGIS Testcontainers + Flyway, 실제 DB/쿠키/CSRF/주문 전환 |
| AuthenticationArchUnitTest / ModularityTests / RuntimeOpenApiParityTest | Passed, 5개 | 모듈과 인증 의존성, API 계약 일치 |
| 변경 Kotlin Spotless | Passed | 기존 140자 규칙 적용, 관련 25개 파일만 대상 |
| `scripts/verify-docs.sh` | Passed | BR 58개, ADR 132개, Markdown 958개, ExecPlan 109개 및 OpenAPI 의미 계약 검증 |
| `npm run typecheck` | Passed | OpenAPI 타입 생성과 TypeScript |
| `npm test` | Passed, 37개 파일/250개 테스트 | presentation boundary 및 제품 copy 검사 포함 |
| `npm run check:design` | Passed | 토큰, Storybook, route 계약 |
| `npm run build` | Passed | chunk 크기 경고 1개, 오류 없음 |
| `npm run build-storybook` | Passed | 실제 페이지를 사용하는 17개 demo journey story 포함 |
| `npm run test:sites` | Passed, 4개 | 사이트별 smoke |
| 영향 범위 Storybook MCP | Passed, 134개 | 18개 이하 배치로 interaction + a11y 결과 수신 |
| 전체 Storybook 테스트 실행 | Passed, 121개 파일/792개 테스트 | 서버의 Vitest 최종 집계 확인 |
| 전체 Storybook MCP 응답 수신 | Blocked | 테스트 통과 후 JSON 직렬화 중 Node heap OOM, 클라이언트 연결 종료 |

Backend 통합 검증은 별도 `/tmp/beanflow-visitor-demo/build` 및 Gradle project cache를 사용했다. `/build/` 경로를 유지하여 ArchUnit/Modulith가 테스트 클래스를 제품 모듈로 잘못 검색하는 문제를 피했다. 공용 build 결과를 삭제하지 않았다.

통합 테스트는 방문자 간 소유권, 동일 키 동시 발급, 요청 내용 충돌, 원자적 rollback, 일반 로그인 충돌, 고정 시각에서 최신 공간 선택, 발급 quota, 정확한 만료 경계, timeout 이후 멱등 재시작, Secure HttpOnly 쿠키, CSRF, 종료 후 401을 확인한다. 두 역할이 실제 주문 전환 API를 사용하고 동일한 서버 상태를 조회하는 것도 확인했다.

전체 MCP 호출은 기본 heap 및 4 GiB heap에서 결과 반환에 실패했다. 두 번째 실행의 서버 로그에는 `Test Files 121 passed (121)`, `Tests 792 passed (792)`가 기록된 뒤 `FATAL ERROR: Reached heap limit`와 `JsonStringifier` stack이 남았다. 이를 테스트 실패나 전체 MCP 성공으로 집계하지 않는다. 134개 영향 범위는 별도 배치에서 MCP 결과까지 모두 수신했다. 미리보기 서버를 다시 실행했다.

## 브라우저 확인

In-app browser에서 Storybook의 실제 제품 페이지를 확인했다. API 응답만 MSW fixture를 사용한다.

- 1440 × 1024: 시작 화면, 체험 시작 버튼, 점주 주문 보드의 304px 안내 패널과 기존 주문 열.
- 390 × 844: 고객 준비 완료, 안내 접기, 픽업 번호와 주문 내용 스크롤. scrollbar를 제외한 `clientWidth=375`, `scrollWidth=375`로 가로 overflow 없음.
- Figma의 기존 색상·타이포·컴포넌트와 단계 구성을 사용했다. 이미지 조각을 화면에 붙이는 방식으로 구현하지 않았다.

## 재현과 로그

Frontend에서 위 npm 명령을 실행한다. Storybook MCP의 `run-story-tests`는 `a11y: true`로 실행하고, 영향 범위는 `get-stories-by-component` 결과와 변경 스토리를 합쳐 배치한다. Backend는 다음 테스트를 선택 실행한다.

```sh
./gradlew test --tests '*DemoSafetyTest' --tests '*DemoWorkspaceIntegrationTest' --tests '*AuthenticationArchUnitTest' --tests '*ModularityTests' --tests '*RuntimeOpenApiParityTest'
```

이번 실행의 원본 로그는 로컬 임시 파일이며 보존 기간을 보장하지 않는다: `/tmp/beanflow-demo-final-backend-tests.log`, `/tmp/beanflow-demo-final-format-check.log`, `/tmp/beanflow-demo-frontend-tests.log`, `/tmp/beanflow-demo-story-batch-{1..8}.log`, `/tmp/beanflow-demo-storybook-server.log`, `/tmp/beanflow-demo-all-story-tests-final.log`. 재현 가능한 계약과 테스트는 저장소에 포함했다.

## 미수행 범위

- 운영 DB V88 적용, 원격 배포, 도메인/HTTPS 활성화.
- 실제 Toss SDK 결제 승인·콜백 및 외부 알림 전달. Backend 테스트는 기존 테스트용 adapter를 사용한다.
- 배포 환경의 부하/장기 보존/만료 worker 운영 안정성 검증.

기능을 켜기 전 실행 문서의 환경 준비와 외부 연동 확인이 필요하다. 기본 데모 주문은 전용 포인트 전액 사용이며 직접 결제는 toss-sandbox에서만 제공한다.

## PR 분리 후 재검증

최신 main `0ea0055`에서 `feature/visitor-demo-journey`를 만들고 데모 관련 51개 파일만 분리했다. 제품/Storybook 소스는 위 검증 대상과 동일하며 문서의 범위와 V87 적용 순서만 PR 기준으로 정리했다. 기존 작업 폴더의 Git 상태와 전체 tracked diff가 분리 전후 동일함을 확인했다.

- Passed: `./gradlew spotlessCheck test --tests '*DemoSafetyTest' --tests '*DemoWorkspaceIntegrationTest' --tests '*AuthenticationArchUnitTest' --tests '*ModularityTests' --tests '*RuntimeOpenApiParityTest' bootJar verifyCiTestShards` — 17개 테스트, 전체 Kotlin 포맷, 실행 파일 빌드 및 326개 test class의 shard 중복/누락 검사.
- Passed: `scripts/verify-docs.sh` — OpenAPI 의미 계약, BR 58개, ADR 131개, Markdown 377개, ExecPlan 108개. 분리 브랜치에는 ADR-131/V87 등 다른 변경이 없다.
- Passed: `npm run typecheck`, `npm test` (250개 unit 및 boundary/copy 검사), `npm run check:design`, `npm run build`, `npm run test:sites` (4개), `npm run build-storybook`.
- Passed: `npm run test:storybook:docs` — 125개 Docs, 15개 상태별 Docs, 47개 상태 화면.
- Passed: 명시적 51개 staged 파일 목록, `git diff --cached --check`, 제품 코드의 원본 일치 및 관련 없는 파일/credential 패턴 검사.

V88의 적용 순서는 #189의 V87 병합 후 최신 main에서 재검증한다. 이 검증은 V87 없이 main 위 데모 코드의 독립 실행을 확인한 것이며 두 migration을 합친 배포 검증이 아니다. 원격 CI, 병합과 배포는 별도 결과다. 전체 Storybook 상호작용/MCP와 390px/1440px 브라우저 검증은 동일 소스에 대해 위에서 실행한 결과를 유지한다.
