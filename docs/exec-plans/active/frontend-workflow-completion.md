# 고객 거래의 표시 오류를 고치고 점주·운영·상담 업무를 화면에서 완료한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 고객 문의 채널은 내장 Support 시스템으로 결정되었으며, 독립적인 거래·관리 화면부터 진행한다.

## Purpose / Big Picture

고객은 실제 날짜·판매 구성·견적·최신 주문 상태를 확인하고 안전하게 결제를 재개할 수 있어야 한다.
점주·운영자·상담사는 이미 구현된 업무 API를 디자인 시스템 기반 화면에서 조회·변경·승인·실행하고
실패 또는 결과 불명을 구분할 수 있어야 한다. API 호출 수가 아니라 사용자가 업무를 끝내는 경로를 완성한다.

## Current State

시작 기준은 `main`/`origin/main`의 `a6199c6848789c1a72ea41532a0202867a5e9397`이고 작업 트리는 clean이었다.
42개 leaf 라우트와 Runtime OpenAPI 224개 operation을 대조했다. 직접 호출 96개/미호출 128개는 결함 수가 아니다.
기존 UI의 메뉴·옵션·구성·가격·판매 정책, 고객 쿠폰 이벤트, 상담 검색·본인 확인·보상 요청 기능을 보존한다.
Storybook HTTP MCP는 `frontend/`에서 실행하는 `http://localhost:6006/mcp`다.

## Definitions

- 수직 슬라이스: 한 사용자 목적의 계약/도메인/조회·명령/화면/실패 상태/검증을 함께 완성한 커밋이다.
- 업무 연결: 목록/상세에서 대상과 현재 버전을 얻고, 권한 있는 명령을 실행하고, 실제 결과를 다시 읽는 흐름이다.
- 불명 결과: UNKNOWN/RECONCILING/MANUAL_REVIEW 등이며 성공/확정 실패/재실행 가능으로 추측하지 않는다.

## Scope

### In Scope

| Slice | 사용자 결과 및 결함 범위 | 예정 PR |
|---|---|---|
| C1 | 320px 검색 버튼 분리, 390px 즐겨찾기 가독성, 공통 중요 글자 크기(F05–F07) | 고객 거래 정합성 |
| C2 | 날짜/요일/운영시간, 서버 견적 이름, 장바구니 이미지 수명(F01,F08,F11,F12) | 고객 거래 정합성 |
| C3 | 공개 판매 구성 조회/선택(F02) | 고객 거래 정합성 |
| C4 | 공개 주문번호 결제 재개, 만료·조회 실패·종료 타임라인·활성 주문 갱신(F03,F04,F09,F10,F13) | 고객 거래 정합성 |
| C5 | 쿠폰 진입 통합, 문의 진입 정리, 생성 타입 정합성, 역할별 코드 분할(F14,F15,F17,F18) | 고객 거래 정합성 |
| S1 | 점주 공개 정보·주간 영업시간, 픽업 슬롯(M01,M02) | 점주 매장 관리 |
| S2 | 메뉴 표시 내용·매장/메뉴 이미지(M03) | 점주 매장 관리 |
| O1 | 운영 매장 목록/생성/식별정보/지역과 브랜드 지정(M04,M05) | 운영 매장·정책 관리 |
| O2 | 정산 조건 버전·기존 점주 소속(M06,M07) | 운영 매장·정책 관리 |
| O3 | 매장별 포인트 정책/변경 이력, 브랜드 페이지 이동(M12,F16) | 운영 매장·정책 관리 |
| R1 | 점주 이의 상세·철회와 운영 검토·판정(M08,M09) | 이의제기·운영 복구 |
| R2 | 알림·publication 조회/단일 재시도/결과 대사(M10) | 이의제기·운영 복구 |
| R3 | 취소 환불 대사·복구 제안/결정·운영 환불·포인트 조사/조정(M11) | 이의제기·운영 복구 |
| H1 | 상담 목록/배정/기록/대상 해제/검증 철회(M13) | 상담 업무 관리 |
| H2 | 주문 조치 평가/요청/수정/승인/배정/실행 및 점주 동의(M14) | 상담 업무 관리 |
| H3 | 수락 후 해결·보상 실행/결과/알림 재시도(M15) | 상담 후속 처리 |
| H5 | 고객의 앱 내 문의 접수·목록/상세·고객 공개 답변과 상담 Case 연결(F15) | 고객 문의 연결 |
| H4 | 고객/매장/배송 정보 정정·운영 결정·실행/알림과 긴급 열람(M16,M17) | 상담 후속 처리 |

### Non-goals

명시적 P0 제외인 저장 결제수단, 재고 도메인 부활, 은행 지급 실행, 별도 Analytics/일반 집계의 신규 백엔드
구현, 임의 고객지원 연락처, merge/deploy/실거래 실행은 이번 범위에 포함하지 않는다. 기존 준비 중 화면에
새로 연결된 업무가 있으면 탭/설명/내비게이션을 실제 지원 범위에 맞춘다.

## Business Rules and Invariants

BR-03/04/05/14/25/27/33/37/38/48/49/50/54, ADR-026/076/080/096/101/117/118/123/124/125와
Support 목적별 검증·distinct approval 정책을 보존한다. 날짜는 Asia/Seoul, 금액은 정수 KRW다.
서버의 구성·allowedActions·version·retry eligibility를 클라이언트가 만들어내지 않는다.
메뉴·이미지·표시 정보 변경으로 기존 주문 snapshot을 갱신하지 않는다. 이미 성공한 거래를 다시 실행하지 않는다.

## Architecture and Transaction Boundaries

React route는 기존 typed API client와 feature controller를 통해 command/query를 호출하고 canonical
디자인 시스템으로 표현한다. 서버 변경은 기존 application service/public port에서 조정하고 Controller가
Repository를 직접 사용하지 않는다. 신규 DB schema는 계획하지 않는다. 승인·명령 접수와 비동기 외부 실행은
기존 별도 transaction을 유지한다. 조회 실패를 stale success/empty/0으로 대체하지 않는다.

## Alternatives Considered

- 모든 미사용 API에 독립 폼/JSON 편집기를 만드는 방식은 업무 맥락과 권한/상태를 숨겨 채택하지 않는다.
- 디자인을 새로 만드는 대신 REUSE(Button/Field/Selection/Tabs/Status/Notice)와 COMPOSE(업무 단위 화면)를 우선한다.
- 결제 재개를 localStorage의 internal orderId로 추측하지 않고 공개 주문번호 기반 서버 소유 조회/명령으로 보완한다.
- 옵션 UI의 자유 조합을 서버 정책으로 허용하는 대신 현재 구성 계약을 조회 가능하게 만든다.
- 공통 스타일을 한꺼번에 무관한 리팩터링하지 않고, 결함을 재현한 소비 화면과 공통 의미 토큰을 함께 검증한다.

## Failure Semantics

각 업무에 loading/empty/read failure/403/stale version/invalid input/in-flight/unknown/manual review를 적용한다.
명령 payload가 같을 때 같은 제출 의도 키를 재사용하고 payload가 바뀌면 키를 바꾼다. 중복 클릭을 막고 서버
멱등성을 대체하지 않는다. 202는 접수로 표시하며 실제 완료는 owner 조회로 확인한다. 민감 정보는 상태/URL/
storage/log에 복제하지 않고 기존 본인확인·한시 열람 경계를 유지한다.

## Data and Migration

새 migration 없음. 공개 projection을 확장하는 경우 기존 read model/저장 데이터를 사용한다.
새 migration 필요성이 발견되면 계획과 권한 경계부터 재검토한다. Storybook fixture는 명시적 테스트 데이터다.

## API and Event Contracts

기존 Runtime OpenAPI를 authoritative 입력으로 사용한다. C3의 공개 메뉴 구성과 C4의 공개 주문번호 checkout/
payment-attempt 계약은 target/runtime/schema/backend parity와 함께 갱신한다. API 신규 상태 의미와 비즈니스
정책을 임의로 발명하지 않는다. 이벤트 계약 변경은 예정하지 않는다.

## Milestones

첫 PR branch는 `feature/frontend-customer-consistency`다. 이후 점주 관리 → 운영 매장/정책 → 이의/복구 →
상담 관리 → 상담 후속 처리의 순차 branch/PR로 나눈다. 공유 계약/컴포넌트가 있는 child PR은 바로 전 branch를
base로 하여 증분 diff를 유지한다. 각 PR에는 사용자 목적별 여러 수직 슬라이스 커밋을 둔다. PR 생성/push는
요청 범위에 포함되며 merge는 별도다. 정확한 branch/head/PR URL은 Progress에 기록한다.

## Required Tests

- C1: 좁은 화면에서 검색/지우기 개별 클릭, 긴 매장명/주소, 중요 글자 computed size, 키보드·a11y.
- C2/C3: 다른 날짜 같은 시간, 전체 주간 시간, quote 이름 변경, 이미지 만료/오류, 존재하지 않는/품절 구성.
- C4: READY 재개, UNKNOWN 금지, 만료 경계 재조회, 성공 후 폴링 실패, terminal timeline, 탭 복귀 갱신.
- 관리: 현재 버전 조회→명령→결과 갱신, 커서 이동, owner/staff/ops 권한, stale version, 중복 제출, 503.
- 복구/지원: 승인 전 실행 금지, reason/expectedVersion, 요청 key replay, 202/UNKNOWN/manual review, 민감 정보 제한.
- 변경된 backend: 단위/서비스/PostgreSQL 계약/Runtime parity/구조 검증 중 관련 검사 실제 실행.

## Validation Commands

`cd frontend && npm run typecheck && npm test && npm run check:design && npm run build-storybook &&
npm run test:storybook:docs && npm run build && npm run test:sites`.
Storybook MCP: list-all-documentation → get-documentation → story instructions → story-first →
get-changed-stories → preview-stories → focused/full run-story-tests(a11y=true).
`scripts/verify-docs.sh`와 관련 Gradle 테스트는 backend/계약 변경 범위에 맞춰 실행한다.

## Observability

사용자는 마지막 조회 실패와 추적 ID, 서버가 반환한 retry/manual-review 상태를 볼 수 있어야 한다.
새 성능 주장은 같은 조건에서 측정했을 때만 한다. Storybook과 로컬 테스트는 운영 배포의 성공 증거가 아니다.

## Documentation Updates

현재 계획, 필요한 ADR-026/096 설명, 디자인 시스템 사용 지침/타이포그래피 foundation, 새 업무 화면의
Storybook docs, 실제 문의 채널에 대한 제품 정책을 갱신한다. 원본 대화/개인 정보는 기록하지 않는다.

## Progress

- [x] 2026-09-11: clean main과 origin/main 일치, Storybook catalog와 컴포넌트 문서 확인.
- [x] C1: 검색 버튼 겹침·즐겨찾기 긴 내용·중요 글자 토큰 수정. Storybook MCP 전체 324개 interaction/a11y Passed, tsc/check:design Passed. 320/390px 렌더링 확인.
- [x] C2: 픽업 날짜/요일·주간 운영시간·현재 견적 이름·이미지 lease 재조회 구현. 관련 Storybook 11개 interaction/a11y와 날짜 단위 3개 Passed. 만료 응답은 명시적 실패.
- [x] C3: 메뉴별 공개 구성 조회, 인증 경로, bounded owner projection, 구성 선택/품절/조회 실패 구현. PostgreSQL·쿼리 수·인증·Runtime parity 36개 Passed; 관련 Storybook 6개 Passed; frontend 단위 217개 및 boundary/copy 21개 Passed, typecheck/check:design Passed. 생성 타입 21개 누락 경로도 동기화(F17).
- [x] C4: 공개 주문번호 checkout/기존 READY 시도 재개, UNKNOWN 차단, 예약 만료·주문 갱신 실패·종료 타임라인·화면 복귀 갱신 구현. 관련 PostgreSQL/계약 21개, Storybook 10개, frontend 단위 221개와 boundary/copy 21개, typecheck/check:design/docs Passed. 결제 SDK는 테스트에서 명시적으로 대체했으며 실결제는 실행하지 않았다.
- [x] C5: 쿠폰 진입을 실제 이벤트로 통합하고 기존 주소 redirect, 점주/운영/상담 route lazy loading 구현. 전체 Storybook MCP 338개 Passed, 단위 221개 및 boundary/copy 21개 Passed. typecheck/check:design/build/build-storybook/docs smoke(70 docs/47 states)/sites(4개) Passed. Storybook docs 브라우저 검사는 sandbox 실행 제한 후 권한을 받아 재실행했다. 고객 PR 생성은 아래 이력에 기록한다.
- [x] S1: 점주 공개 주소/길찾기/7일 운영시간 및 점주·직원 픽업 목록/상세/생성/수정 구현. 기존 준비 중/예시 수치 탭을 실제 관리·정산 진입으로 교체. 관련 Storybook 13개(공통 시간 필드/기존 카탈로그 포함) Passed, 단위 223개 및 boundary/copy 21개, typecheck/check:design Passed. 390px 브라우저에서 필드 넘침 없음 확인.
- [x] S2: 메뉴 카테고리/설명 편집과 매장·메뉴 이미지 조회/업로드/삭제/만료 갱신 구현. 현재 이미지 조회를 권한 있는 기존 authoring 경로에 추가했다. PostgreSQL 이미지 endpoint/Runtime parity 11개, 전체 Storybook MCP 353개, 단위 223개 및 boundary/copy 21개 Passed. typecheck/check:design/build/build-storybook/docs smoke(73 docs/47 states)/sites(4개), 문서 검증(18개) Passed. 이미지 관리 화면 렌더링 확인. 점주 PR 생성은 아래 이력에 기록한다.
- [x] O1: 운영 매장 목록/이전·다음 커서/생성/현재 식별정보 수정/등록 지역 선택 및 브랜드 조회·지정·해제 구현. 브랜드 현재 소속 GET은 기존 grant와 단일 projection을 재사용했다. 관련 Storybook 17개, backend 29개, frontend 단위 223개와 boundary/copy 21개, typecheck/check:design/docs Passed. 390px 브라우저의 필드/목록 넘침 없음 확인. 전체 빌드/Storybook은 O2–O3 후 PR 검증에서 실행한다.
- [x] O2: 선택한 매장의 불변 정산 계약 목록/상세/미래 구간 등록과 기존 계정 소속 추가/역할 변경/철회/재활성화 구현. 조회 권한과 소속 변경 권한을 구분해 계정 exact 조회 또는 확인된 기존 ID를 사용한다. 연도를 포함한 계약 날짜, RESOURCE_STATE_CONFLICT 안내를 보완했다. 관련 Storybook 16개, 기존 PostgreSQL 계약 12개, frontend 단위 224개와 boundary/copy 21개, typecheck/check:design Passed. 390px 계약 폼·오류 상태와 가로 넘침 없음 확인.
- [x] O3/F16: 브랜드 관리에 20개씩 이전/다음 커서를 연결하고 페이지 조회 실패 시 이전 결과를 제거했다. 생성·이름 변경 후 서버 정렬 목록을 다시 조회하며 결과를 별도 안내한다. 소속 매장이 있으면 이름도 바꿀 수 없다는 잘못된 설명과 사유 200자 제한을 수정했다. 관련 Storybook 4개, typecheck/check:design, 단위 224개와 boundary/copy 21개 Passed.
- [x] O3/M12: 매장별 포인트 설정 목록/필터/커서, 실제 적용 정책 조회/변경, 공통·매장별 이력 구현. 불완전한 공통 정책을 0%로 표시하지 않고 편집을 차단한다. 관련 Storybook 16개 및 전체 393개, backend 정책/동시성/Runtime parity 8개, frontend 단위 224개와 boundary/copy 21개 Passed. typecheck/check:design/build/build-storybook/docs smoke(79 docs/47 states)/sites(4개), 문서 검증(18개) Passed. 390px 포인트 화면 넘침 없음 확인. 운영 PR 생성은 아래 이력에 기록한다.
- [x] R1: 점주 상세/철회/새 증빙 재접수와 운영 매장별 목록/검토/판정 구현. 명령 실패 후 현재 pending 판정을 다시 확인하며 상충 판정을 숨긴다. BR-22의 14개 달력 날짜 및 BR-24의 종결 건 재접수에 맞춰 예시 설명을 수정했다. 관련 Storybook 23개, PostgreSQL/도메인 19개, frontend 단위 224개와 boundary/copy 21개, typecheck/check:design Passed. 390px 판정 일부 처리 화면 넘침 없음 확인.
- [x] R2: 알림/이벤트 수동 복구 목록·커서·원본 상세·1회 재시도·결과 확인 구현. 202 접수와 완료를 분리하고 UNKNOWN 재실행은 recoverable/차단 사유를 따른다. 관련 Storybook 13개, PostgreSQL 복구/동시성/부분 실패 19개, frontend 단위 224개와 boundary/copy 21개, typecheck/check:design Passed. 390px 결과 불명 화면 넘침 없음 확인.
- [ ] R3 주문 취소·환불·포인트 조사 및 복구 구현·검증·커밋·PR.
- [ ] H1–H2 상담 관리 구현·검증·커밋·PR.
- [ ] H3–H4 상담 후속 처리 구현·검증·커밋·PR.
- [ ] H5 내장 고객 문의 접수/상태/공개 답변과 Support Case 연결 구현·검증·커밋·PR.
- [ ] 전체 로컬 검증, 원격 CI 확인, 최종 diff/PR topology 검토.

- 고객 PR: https://github.com/kdh949/BeanFlow/pull/160 (`feature/frontend-customer-consistency`, head `d777992`, base `main`). 후속 점주 branch: `feature/frontend-store-management`.
- 고객 PR #160의 preflight/frontend/backend-build/6개 backend test shard/CodeQL 및 집계 build 원격 CI가 모두 통과했다.
- 점주 PR: https://github.com/kdh949/BeanFlow/pull/161 (`feature/frontend-store-management`, head `753d28a`, base `feature/frontend-customer-consistency`). 운영 branch: `feature/frontend-operations-management`.
- 점주 PR #161의 preflight/frontend/backend-build/6개 backend test shard 및 집계 build 원격 CI가 모두 통과했다.

- 운영 PR: https://github.com/kdh949/BeanFlow/pull/162 (`feature/frontend-operations-management`, head `1577242`, base `feature/frontend-store-management`). 이의·복구 branch: `feature/frontend-dispute-recovery`.

## Surprises & Discoveries

- 기존 active plan의 일부 Current State는 이미 구현된 OIDC·카탈로그·쿠폰 selector보다 오래됐다.
  해당 계획을 근거 없이 완료로 바꾸지 않고 실제 코드/계약을 기준으로 이번 범위를 추적한다.
- git metadata 쓰기는 sandbox 바깥 권한이 필요하며 요청한 branch/commit/push/PR 목적에만 사용한다.
- Kotlin 증분 캐시가 기존 타입을 찾지 못했으나 `-Pkotlin.incremental=false` 전체 컴파일 후 관련 테스트가 통과했다. 저장소 설정은 변경하지 않았다.
- Storybook watcher에 EMFILE 경고가 있다. catalog/read가 동작해도 HMR/변경 감지는 별도 재확인한다.
- S2 전체 Storybook 첫 검증은 장시간 실행한 Node의 4GiB heap 소진으로 중단됐다. 재시작 후 남은 task-owned Vitest가 테스트 포트를 점유해 초기화가 실패했다. 해당 프로세스를 종료하고 8GiB heap을 지정한 로컬 서버에서 정적 빌드를 동시에 실행하지 않은 최종 전체 353개 검증이 통과했다. 저장소 런타임 설정은 변경하지 않았다.
- 계정 조회의 X-Access-Reason에 한글을 직접 넣으면 브라우저 Headers 생성 단계에서 실패한다. 기존 조회 화면처럼 한글 목적 선택을 ASCII 사유 코드로 전송하고, 명령 본문의 변경 사유는 한글을 유지한다. 계정 exact 조회는 MERCHANT_CREDENTIAL_MANAGE, 소속 변경은 별도 STORE_MEMBERSHIP_WRITE 권한이므로 기존 ID 입력 경로도 제공한다.

- 포인트 정책 OpenAPI의 discriminator mapping 누락과 global request의 required-only allOf 때문에 생성 타입이 실제 상태 값을 허용하지 않았다. 기존 서버 상태와 필드 제약을 명시하도록 계약을 보정하고 생성 타입 및 runtime parity를 검증했다. 제품 정책/서버 동작은 변경하지 않는다.

## Decision Log

- 2026-09-11: 보고된 결함과 현재 API의 미연결 업무를 구현하고 수직 슬라이스 커밋/업무 단위 PR로 분할한다.
- 2026-09-11: 토큰·기존 컴포넌트 재사용을 우선하며 단순 시각 개선은 기존 정책 범위 안에서 진행한다.
- 2026-09-11: C3 구성 조회는 메뉴 펼침 시 단일 메뉴 endpoint로 연결한다. 기존 메뉴별 500개 상한을 재사용하며 매장 전체 구성 전송과 N+1 초기 조회를 피한다.
- 2026-09-11: 고객 도움말은 내장 Support 시스템에 연결한다. 고객 소유 문의 접수/진행 조회와 상담 Case 연결을 별도 H5/일곱 번째 PR로 구현한다. 내부 노트/본인 확인 자료는 공개하지 않는다. 관련 persistence 변경 필요성은 H5 계약 검토에서 결정한다.

## Outcomes & Retrospective

구현/검증 미완료. 이 문서는 완료 증거가 아니라 실행 계획이다.

## Revision Notes

- 2026-09-11: 초기 실행 범위와 단계·검증 경계 작성.
