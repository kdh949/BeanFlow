# 프론트엔드 기능 공백을 사용자 여정으로 완결한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

BeanFlow의 런타임 API에는 고객 쿠폰·즐겨찾기, 점주 매장 지역 설정, 운영자 점주 계정 관리,
고객지원, 운영 정책 관리 기능이 존재하지만 브라우저에서 접근할 수 있는 화면과 라우트가 없다.
동시에 기존 Storybook에는 실제 네트워크 요청을 발생시키는 Story, 핵심 실패 상태 누락, 접근성
대비 위반과 오래된 현황 문서가 남아 있다. 이 계획은 기존 디자인 시스템의 `Button`,
`FeedbackState`, `StatusBadge`와 셸·폼 패턴을 우선 조합해 API 기능을 사용자 여정으로 완결하고,
각 정상·빈 값·로딩·실패·충돌 상태를 Storybook과 자동 테스트에서 재현 가능하게 만든다.

완료 후 고객은 쿠폰과 즐겨찾기를 관리할 수 있고, 점주는 자신이 관리하는 매장의 법정동을
설정할 수 있다. 운영자는 Keycloak Authorization Code + PKCE로 인증한 뒤 점주 계정,
고객지원 Case와 보호 데이터 접근, 포인트·혜택·브랜드·검색 색인 업무를 처리할 수 있다.

## Current State

- `frontend/src/router.tsx`에는 고객 쿠폰·즐겨찾기, 점주 매장 지역, `/support`, 운영 정책 라우트가 없다.
- `frontend/src/components/Shells.tsx`의 운영자 셸은 수동 Bearer Token 편집기를 노출한다.
- `openapi/beanflow-v1-runtime.yaml`에는 선택된 기능의 런타임 API가 등록되어 있다.
- Storybook 공통 MSW handler가 비어 있고 일부 page Story가 필요한 요청 handler를 제공하지 않는다.
- 로그인 잠금·요청 제한, 위치 권한 거부, 장바구니 가격·재고 충돌, 주문 상태, 점주 주문보드
  충돌, 재주문 변경 상태를 직접 여는 Story가 부족하다.
- 정산 이의제기 진입 Story의 보조 텍스트 색 대비가 WCAG AA 4.5:1을 충족하지 못한다.
- Storybook 인벤토리 문서의 Story 파일·Story·Docs 수와 라우트 목록이 현재 코드와 다르다.

## Definitions

- **수직 슬라이스:** 라우트, API client, 화면, 상태 처리, Story, 자동 테스트와 문서를 한 사용자
  기능 단위로 함께 완성하는 변경이다.
- **PKCE:** 브라우저 공개 클라이언트가 authorization code를 탈취당해도 토큰 교환을 제한하는
  Proof Key for Code Exchange다. BeanFlow는 S256만 허용한다.
- **PII:** 전화번호와 이메일처럼 개인을 식별할 수 있는 정보다.
- **DataAccessGrant:** 고객지원 담당자가 제한된 시간·필드·조회 횟수 안에서 보호 데이터를
  조회하도록 승인받은 서버 권한이다.
- **법정동 코드:** 매장 지역을 식별하는 활성 행정 코드다. 자유 텍스트 입력은 허용하지 않는다.

## Scope

### In Scope

- Storybook 요청 격리, 누락 상태 Story, 접근성 대비와 인벤토리 문서 보정
- 고객 쿠폰 지갑과 매장 문맥 기반 선택 상태
- 고객 즐겨찾기 목록·추가·삭제 및 200개 한도 충돌 표시
- 점주 매장 선택과 활성 법정동 검색·지정
- 운영자 공개 인증 설정과 Keycloak Authorization Code + PKCE S256 세션
- 운영자 점주 계정 exact lookup·발급·임시 비밀번호 재설정·잠금 해제
- 고객지원 exact search, Case, 본인확인, DataAccessGrant, 보호 데이터 reveal, 타임라인, 보상 진입
- 운영자 포인트 적립 정책, 만료 혜택 복원 정책, 브랜드, 검색 색인 재구축 화면
- 각 화면의 loading, empty, unavailable, success, validation, conflict 또는 permission 상태
- 고객·점주·운영자 라우트와 셸 내비게이션

### Non-goals

- 런타임 API에 없는 새로운 정산·실패 큐·대량 작업 기능
- 결제수단을 체크아웃에 연결하는 변경
- 서버의 도메인 상태 전이, DB 스키마 또는 Flyway migration 변경
- Support PII를 브라우저 저장소, URL, 로그 또는 장기 전역 상태에 보관하는 기능
- 시각적 브랜드 전면 개편이나 기존 디자인 시스템을 대체하는 신규 UI 프레임워크 도입

## Business Rules and Invariants

- 쿠폰 선택은 현재 매장 문맥에서만 유지한다. 주문 생성 API가 최종 적용 가능성과 할인을 판단한다.
- 쿠폰의 `STORE_NOT_APPLICABLE` 또는 만료·사용 완료 상태를 선택 가능한 상태로 표시하지 않는다.
- 즐겨찾기 추가는 최대 200개이며 동일 매장 `PUT`은 멱등으로 취급한다. 한도 초과 `409`는
  성공이나 일반 네트워크 실패로 바꾸지 않는다.
- 매장 지역은 검색 결과의 활성 법정동 코드만 선택해 저장한다. 자유 텍스트를 서버에 전송하지 않는다.
- 운영자 인증은 수동 Bearer Token을 받지 않고 Keycloak Authorization Code + PKCE S256을 사용한다.
  access token은 메모리에만 두고 local/session storage, URL, 로그에 저장하지 않는다.
- 점주 임시 비밀번호는 발급·재설정 응답 직후 해당 라우트의 메모리에만 표시한다. 자동 재시도하지
  않으며 새로고침·이동 후 복원하지 않는다.
- 고객지원 검색은 정확한 전화번호 또는 이메일을 POST body로만 전송한다. URL query에 PII를 넣지 않는다.
- 고객지원 보호 데이터는 기본 마스킹하며, 본인확인 수준과 승인자 분리 조건을 충족한
  DataAccessGrant로만 reveal한다. reveal 결과는 저장하거나 다른 화면으로 전달하지 않는다.
- CLOSED Support Case는 terminal이다. 허용되지 않은 상태 전이를 UI가 성공으로 표시하지 않는다.
- mutation 요청의 중복 제출을 버튼 loading/disabled 상태로 차단하고, 서버 오류를 성공으로 대체하지 않는다.

## Architecture and Transaction Boundaries

- 브라우저 라우트는 `frontend/src/router.tsx`, 셸 내비게이션은
  `frontend/src/components/Shells.tsx`가 소유한다.
- 고객 API는 `customerClient`, 점주 API는 `merchantClient`, 운영·고객지원 API는 OIDC access
  token을 메모리에서 주입하는 `consoleClient`를 통한다.
- 페이지는 서버 트랜잭션을 만들지 않는다. 각 mutation API 응답이 확정된 뒤 관련 read model을 다시
  조회하며, 불명확한 timeout은 성공으로 추정하지 않는다.
- 공용 UI는 `frontend/src/design-system/`의 기존 primitive를 재사용한다. 반복되는 선택·필터·민감정보
  패턴만 Story와 함께 확장하고, 도메인 페이지는 이를 조합한다.
- 인증 공개 설정 endpoint는 비밀이 아닌 issuer/client ID/redirect contract만 반환한다. 토큰 발급과
  인증 상태의 권위는 Keycloak과 백엔드 audience/role 검증에 있다.

## Alternatives Considered

- 기존 수동 Bearer Token 편집기를 유지하면 API 화면을 빠르게 붙일 수 있지만 Accepted 인증 정책,
  사용자 조작 방지와 토큰 비저장 원칙을 위반하므로 채택하지 않는다.
- 선택 기능을 Storybook mock 화면으로만 만들면 디자인 검토는 가능하지만 실제 라우트와 API 기능
  공백이 남으므로 채택하지 않는다.
- 모든 기능을 하나의 운영자 대시보드에 넣으면 파일 수는 줄지만 권한·실패·업무 문맥이 섞이므로
  계정, 고객지원, 정책 관리 라우트를 분리한다.
- 범용 테이블·폼 프레임워크를 새로 만들 수 있으나 현재 반복이 제한적이고 운영 비용이 크므로
  기존 primitive와 좁은 재사용 패턴을 조합한다.

## Failure Semantics

- read 실패는 빈 목록으로 바꾸지 않고 `FeedbackState(kind="error")`와 재시도 동작을 제공한다.
- 인증 설정 누락·Keycloak 초기화 실패·운영자 role 부족은 운영 화면을 렌더링하지 않고 명시적
  unavailable 또는 forbidden 상태를 표시한다.
- mutation의 `400/404/409/422/429`는 서버 응답 의미에 맞는 사용자 조치 문구를 표시한다.
- timeout 또는 응답 유실은 확정 실패·성공으로 단정하지 않는다. 재조회 또는 상태 확인 안내를 제공한다.
- Support grant/reveal 실패 시 기존 마스킹 상태를 유지하고 raw PII를 캐시하지 않는다.
- 검색 색인 재구축은 서버가 반환한 작업 상태를 표시하며 요청 접수만으로 완료라고 표시하지 않는다.

## Data and Migration

- DB schema와 migration을 변경하지 않는다.
- 쿠폰 선택, OIDC token, 임시 비밀번호, reveal된 PII는 브라우저 메모리에만 둔다.
- 브라우저 storage에는 기존 정책에서 허용된 비민감 고객·점주 세션 정보 외의 신규 데이터를 쓰지 않는다.

## API and Event Contracts

- canonical runtime contract는 `openapi/beanflow-v1-runtime.yaml`이다.
- 선택된 기존 API의 request/response schema를 `frontend/src/api/schema.d.ts`에서 사용하고 임의의
  프론트엔드 전용 응답 타입을 계약처럼 만들지 않는다.
- 운영자 공개 인증 설정이 런타임 계약에 없다면 OpenAPI source, runtime bundle, backend contract
  test와 frontend generated type을 함께 추가한다.
- API path, method, operation ID를 바꾸지 않는다. 신규 이벤트 계약은 없다.

## Milestones

1. Storybook MSW 격리, 누락 상태 Story, 정산 대비와 인벤토리 문서를 보정한다.
2. 고객 쿠폰 지갑과 즐겨찾기 API client, 화면, 라우트, Story와 테스트를 완성한다.
3. 점주 매장 지역 검색·지정 화면, 라우트, Story와 테스트를 완성한다.
4. 운영자 OIDC 설정·PKCE session과 점주 계정 관리 화면을 완성한다.
5. 고객지원 검색·Case·본인확인·grant/reveal·타임라인·보상 화면을 완성한다.
6. 포인트·혜택·브랜드·검색 색인 운영 화면을 완성한다.
7. 전체 frontend, Storybook, OpenAPI, backend 관련 검증을 실행하고 문서를 현재 수치로 갱신한다.

## Required Tests

- API client: 경로·method·body·인증 header와 오류 유지
- 고객 쿠폰: loading, empty, unavailable, 적용 가능/불가, 매장 문맥 선택
- 즐겨찾기: 목록, 추가, 삭제, 중복 PUT, 200개 한도 `409`
- 매장 지역: 활성 코드 검색, 결과 없음, 선택 저장, 비활성/자유 텍스트 차단
- OIDC: 공개 설정 실패, PKCE S256 초기화, memory-only token, role 부족, logout
- 점주 계정: exact lookup, unknown, create/reset no retry, 잠금 해제, 임시 비밀번호 비지속
- Support: PII POST body, masked default, verification lockout, grant 조건, reveal 비지속,
  Case 상태 전이와 terminal 상태, timeline과 보상 실패
- 운영 정책: read/mutation 정상·empty·validation·conflict·accepted-not-completed 상태
- Storybook: 모든 변경 Story의 play test와 a11y 검사, 실제 backend 네트워크 요청 0건
- Router: 신규 경로와 각 인증 gate 연결

## Validation Commands

작업 중 변경 범위에 맞춰 집중 테스트를 먼저 실행하고 마지막에 다음을 실행한다.

    cd frontend && npm test
    cd frontend && npm run build
    cd frontend && npm run lint
    cd frontend && npm run storybook:test
    ./scripts/verify-openapi.sh
    ./scripts/verify-docs.sh
    ./gradlew test

Storybook MCP의 `run-story-tests`를 각 변경 Story에 실행하고 `get-changed-stories`와
`preview-stories` 결과를 최종 검증 증거에 기록한다.

## Observability

- 화면 오류에는 서버의 안전한 reference/request ID가 있으면 표시하되 PII와 토큰은 표시하지 않는다.
- Support reveal, 점주 비밀번호, OIDC token은 console log와 client telemetry payload에서 제외한다.
- 장기 작업은 서버가 제공하는 상태와 마지막 갱신 시각을 표시한다.
- 실패를 빈 값이나 완료 상태로 축소하지 않아 운영자가 원인과 재시도 가능 여부를 구분할 수 있게 한다.

## Documentation Updates

- `docs/design-system-inventory.md`의 Story 파일·Story·Docs 수와 페이지 목록을 갱신한다.
- `docs/testing/storybook-runbook.md`의 라우트, 실행·검증 절차와 현재 수치를 갱신한다.
- 구현 중 계약 또는 제품 결정을 바꿔야 할 때만 관련 Business Policy/ADR을 먼저 갱신한다.
- 완료 시 이 파일을 `docs/exec-plans/completed/`로 이동하고 실제 검증 결과를 기록한다.

## Progress

- [x] 2026-08-23: 현재 라우트, 런타임 API, Storybook 등록·상태·a11y와 Accepted 인증 정책을 조사했다.
- [x] 2026-08-23: Storybook MCP에서 기존 Button, FeedbackState, StatusBadge와 페이지 조합 규칙을 확인했다.
- [x] 2026-08-23: Storybook 요청 격리, 누락 상태 Story와 정산 색 대비를 보정했다. 현황 문서 수치는
  신규 기능 Story가 모두 등록된 뒤 최종값으로 갱신한다.
- [x] 2026-08-23: 고객 쿠폰·즐겨찾기 수직 슬라이스를 완성했다. 매장 문맥 쿠폰 선택은 메모리에만
  유지하고 주문·재주문 요청에 전달하며, 재주문 가격 변경은 결제 화면에서 서버 비교값으로 표시한다.
- [x] 2026-08-23: 점주 매장 지역 수직 슬라이스를 완성했다. OWNER 매장만 선택하고 서버 검색 결과의
  활성 법정동 코드만 저장하며, 현재 지정값 조회 API가 없는 한계는 저장 응답과 함께 명시한다.
- [ ] 운영자 OIDC·점주 계정 수직 슬라이스를 완성한다.
- [ ] 고객지원 수직 슬라이스를 완성한다.
- [ ] 운영 정책 수직 슬라이스를 완성한다.
- [ ] 전체 검증과 ExecPlan 완료 이동을 수행한다.

## Surprises & Discoveries

- 2026-08-23: 화면 없는 런타임 operation 수는 크지만 모두 독립 화면을 의미하지 않는다. Support의
  다수 operation은 검색→Case→본인확인→grant/reveal로 이어지는 하나의 업무 흐름이다.
- 2026-08-23: 운영 화면을 실제 API와 연결하려면 기존 수동 토큰 편집기를 유지할 수 없고,
  Accepted BR-41/ADR-092의 Keycloak PKCE 선행 조건을 같은 수직 슬라이스에서 충족해야 한다.
- 2026-08-23: macOS sandbox 안의 Chromium은 Mach port 등록 권한으로 Storybook MCP 테스트를
  시작하지 못했다. 권한 허용된 동일 로컬 서버로 재실행한 뒤 27개 파일·115개 Story의 interaction과
  a11y가 모두 통과했으며, 마지막 두 full run에는 MSW unhandled API 요청이 없었다.
- 2026-08-23: 런타임 계약에는 매장 지역 지정 `PUT`은 있지만 현재 지정값 `GET`은 없다. 화면은 이전
  값을 추정하거나 브라우저에 저장하지 않고 이번 지정 성공 응답만 표시한다.

## Decision Log

- 2026-08-23: Story 파일 수만 세지 않고 live registration, 라우트·페이지 coverage, 필수 상태,
  interaction과 a11y를 완료 기준으로 사용한다.
- 2026-08-23: UI 재사용 순서는 REUSE → COMPOSE → EXTEND → NEW로 고정한다. 기존 primitive와
  PageTitle/폼 패턴을 먼저 조합하고 Support 보호 데이터 패널만 새 도메인 패턴으로 허용한다.
- 2026-08-23: 운영·Support API는 임시 수동 토큰 방식으로 연결하지 않고 Accepted 정책의
  Keycloak Authorization Code + PKCE S256을 포함해 구현한다.
- 2026-08-23: 각 수직 슬라이스는 Story·테스트를 먼저 추가해 실패를 확인한 뒤 구현하고 별도 commit한다.

## Outcomes & Retrospective

구현과 검증 완료 후 사용자에게 열린 신규 여정, 재사용한 디자인 시스템, 남은 한계, 실제 실행한
검증 결과와 Draft PR을 기록한다.

## Revision Notes

- 2026-08-23: 최초 작성. 선택된 Storybook 결함과 고객·점주·운영·Support 화면 공백을 하나의
  migration 없는 frontend completion plan으로 구체화했다.
