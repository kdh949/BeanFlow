# 매장 쿠폰함에서 해당 이벤트를 탐색하고 돌아오기

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-10-03`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture
CUS-11: 쿠폰함이 비었을 때 같은 매장 이벤트를 찾아 명시적으로 다운로드하고 원래 주문 동선으로 돌아간다.

## Current State
빈 쿠폰함은 메뉴로만 연결된다. 이벤트 목록은 전체 매장 조회만 제공하며 발급 후 쿠폰함 복귀가 원래 장바구니 문맥을 잃는다.

## Definitions
storeId는 기존 Campaign 소유 매장 UUID다. claim은 기존 선착순 쿠폰 발급 명령이며 목록 조회와 별개다.

## Scope
### In Scope
선택적 storeId 필터, 서명 커서 범위, 쿠폰함/이벤트 왕복 링크, 안전한 앱 복귀와 검증.
### Non-goals
자동 발급/선택/적용, 새 DTO/테이블/의존성, #200 배치 hydration, 새 쿠폰 정책.

## Business Rules and Invariants
BR-53과 ADR-120을 따른다. 매장 필터는 고객 인증·발급 권한 증명이 아니다. 잔여 수량은 참고값이다.

## Architecture and Transaction Boundaries
기존 Promotion read-only transaction과 query repository에 필터를 전달한다. claim transaction과 멱등 키는 그대로 유지한다.
외부 이미지 presign은 기존처럼 read transaction 밖에서 수행한다.

## Alternatives Considered
클라이언트 필터는 페이지 누락을 만들므로 제외. 별도 endpoint는 같은 계약을 중복하므로 optional query를 선택한다.

## Failure Semantics
다른 필터/고객 커서는 400 INVALID_REQUEST다. 인증 오류와 DB/media 503을 빈 목록으로 바꾸지 않는다.
UI는 필터별 results를 분리하여 이전 응답과 페이지를 섞지 않는다. claim 실패 안내와 같은 intent 재시도를 유지한다.

## Data and Migration
DDL 없음. 기존 store_id 조건과 정렬 사용. 성능 향상 주장은 하지 않는다.

## API and Event Contracts
GET /me/events optional UUID storeId. 부재 시 기존 전체 조회와 커서 hash 유지. 이벤트/claim 계약 변경 없음.

## Milestones
1. 정책/계약 기록과 Storybook 실패 상태 작성
2. 필터 query/cursor 및 화면 동선 구현
3. 집중 통합·claim 회귀·frontend·MCP·문서 검증

## Required Tests
필터 이전 pagination, 교차 매장/고객/변조 cursor 거부, malformed UUID/auth, claim 회귀.
빈 쿠폰함 CTA, 매장 이벤트/empty, claim 후 원래 store/cart 복귀, 필터 전환과 cursor reset, 기존 전체 목록.

## Validation Commands
./gradlew test --tests '*CustomerEventCampaignControllerTest' --tests '*LimitedCouponClaimIntegrationTest' --tests '*RuntimeOpenApiParityTest'
frontend: npm run typecheck; npm test; npm run check:design; npm run build-storybook; npm run test:storybook:docs; npm run build; npm run test:sites.
Storybook MCP inventory/docs/instructions/preview/run-story-tests. scripts/verify-docs.sh.

## Observability
기존 HTTP 오류와 correlation을 유지하며 새 metric cardinality를 만들지 않는다.

## Documentation Updates
BR-53, ADR-120, OpenAPI, 이 ExecPlan. 생성 schema는 정규 generation으로 갱신한다.

## Progress
- [x] 현재 구현·정책 확인, 독립 브랜치와 Storybook 6019 시작
- [x] MCP inventory와 관련 docs/story instructions 조회
- [x] 매장 필터/서명 커서 및 지갑 왕복 동선 구현
- [x] 집중 백엔드·frontend·MCP·문서 검증
- [ ] 실제 Chrome 320/390px 시각 검증: 연결 불가로 Blocked, 병합/배포 후 별도 확인

## Surprises & Discoveries
Chrome 연결 불가로 실제 320/390px 시각 검증은 Blocked이며 Storybook 자동 interaction/a11y와 구분한다.
처음 시도한 ktlintFormat은 존재하지 않아 실행되지 않았다. 실제 spotlessApply/spotlessCheck에서 발견한 추가 테스트 한 줄 길이를 수정한 후 통과했다.
ExecPlan의 무의존 표기는 검증기가 요구하는 backtick 없는 em dash로 수정했다.

## Decision Log
2026-10-03: 기존 API optional filter를 선택. claim 정책과 발급 이후 명시적 선택을 유지한다.

## Outcomes & Retrospective
구현과 로컬 자동 검증 완료. commit/push/PR 생성과 배포는 이 작업에서 수행하지 않았다.

- Passed: `./gradlew test --tests '*CustomerEventCampaignControllerTest' --tests '*LimitedCouponClaimIntegrationTest' --tests '*RuntimeOpenApiParityTest'` — 실제 PostgreSQL Testcontainers, 11 tests (4/6/1), failures/errors 0.
- Passed: `./gradlew spotlessApply spotlessCheck` 및 `git diff --check`.
- Passed: `npm run typecheck` (OpenAPI generation 포함).
- Passed: `npm test` — unit 262, presentation boundary 10, product copy 11.
- Passed: `npm run check:design`, `npm run build-storybook`, `npm run build`, `npm run test:sites` (4 tests).
- Passed: `npm run test:storybook:docs` — 125 docs entries, 15 stateful docs, 47 state surfaces.
- Passed: Storybook MCP 영향 14 stories의 interaction/a11y. 새 4 states 집중 실행도 통과.
- Passed: `bash scripts/verify-docs.sh` — OpenAPI 의미 검증과 ExecPlan graph 포함.
- Not run: 전체 backend suite/원격 CI, MCP 전체 aggregate suite (다른 감사 slice에서 확인한 aggregate hang/OOM 제한에 따라 영향 story만 실행).
- Blocked: 실제 Chrome 모바일 시각 검증과 배포 환경 재검증. Screenshot visual regression은 Not configured.

최초 필터 없는 cursor hash를 보존하고 storeId가 있는 경우에만 store scope를 추가했다. 캠페인 hydration과 claim transaction은 그대로다.

Storybook previews (로컬 6019 실행 중):
- http://localhost:6019/?statuses=affected;modified;new
- http://localhost:6019/?path=/story/pages-customer-coupon-wallet--empty
- http://localhost:6019/?path=/story/pages-customer-event-campaigns--store-events
- http://localhost:6019/?path=/story/pages-customer-event-campaigns--empty-store-events

## Revision Notes
2026-10-03: 최초 작성. 구현·자동 검증 결과와 미검증 시각/배포 범위를 기록하고 completed로 이동.
