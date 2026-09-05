# 고객이 메뉴 선택부터 픽업까지 확인하고 수정할 수 있는 주문 화면

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-09-05`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객 화면의 작은 글자, 중복 정보와 작동하지 않는 것처럼 보이는 조작을 정리한다. 홈에서 매장을 고르고 최근 주문을 다시 시작하며, 메뉴·옵션·수량·포인트를 확인하고 수정한 뒤 결제와 픽업을 진행할 수 있게 한다. 모든 상태를 canonical Storybook에 반영한다.

## Current State

작업 시작 시 고객 refresh 화면은 마지막 CSS override에서 본문을 7–12px로 축소했다. 포인트 요청은 0으로 고정되어 있고 장바구니 옵션 수정·명시적 삭제가 없었다. 홈 추천 사유는 일부 위치로 정하고 같은 매장을 반복했다. 페이지 상단 알림 점은 실제 읽음 상태와 무관했다. 메뉴의 픽업 시간은 선택처럼 보이지만 표시용이었다. 주문 상세 픽업 번호는 중복되고 결제 수단 안내도 조작 가능한 것처럼 보였다. 아래 Outcomes에 구현 완료 상태와 검증을 기록한다.

## Definitions

- 견적: 서버가 현재 메뉴·쿠폰·포인트와 픽업 조건으로 계산한 주문 예정액. 예약이나 가격 보장이 아니다.
- 재주문: 기존 주문 식별자로 현재 판매 조건을 재검증해 새 주문을 만드는 기존 API 흐름.
- 매장 쿠폰: `/me/coupons`의 필수 `storeId`에 해당하는 지갑 조회 결과. 전체 보유 수량으로 해석하지 않는다.

## Scope

### In Scope

고객 홈·검색·매장 메뉴·장바구니·결제·픽업·주문 내역·마이페이지 및 각 Storybook, cart 상태 갱신과 단위 테스트, 고객 타이포그래피와 알림 상태 재사용.

### Non-goals

로그인 정책 변경, 공개 탐색, API·DB·주문 상태 전이 변경, 점주 콘솔 개편, 리뷰·평점·결제수단 저장·옵션 그룹 정책 신설. 기존 미추적 실행 계획은 수정하지 않는다.

## Business Rules and Invariants

BR-01 서울 시간, BR-11 쿠폰 적용 후 잔액까지 포인트 사용과 0원 결제, BR-47 서버 검색 정렬·필터, ADR-116 견적과 현재 재주문 조건, ADR-117 주문 가능성과 영업시간의 구분을 유지한다. 한 장바구니는 한 매장에 속한다. `allowedActions`만 취소·재주문을 노출한다. READY fixture의 잘못된 CANCEL만 제거한다.

## Architecture and Transaction Boundaries

Discovery, Ordering, Loyalty, Customer 계정의 기존 읽기 API와 frontend composition만 변경한다. React 화면은 typed controls를 재사용(REUSE), 요약·편집 화면을 조합(COMPOSE), cart 항목 갱신을 확장(EXTEND)한다. DB transaction과 Aggregate 소유권 변경은 없다. 서버 견적 fingerprint와 주문 command 멱등성 경계를 유지하며 입력 변경은 견적을 무효화한다.

## Alternatives Considered

새 화면 체계나 서버 정책 대신 현재 디자인 시스템과 API를 사용한다. 가짜 전체 쿠폰 수량 대신 현재 장바구니 매장 범위와 조회 실패를 명시한다. 새 픽업 시간 저장 상태 대신 메뉴에서 시간은 안내임을 밝히고 장바구니에서 실제 선택한다.

## Failure Semantics

조회 실패를 잔액 0이나 빈 추천으로 대체하지 않는다. 포인트 입력이 유효하지 않으면 견적·주문을 막는다. 금액과 재고·슬롯 변동은 서버 오류와 stale 견적 확인으로 처리한다. 외부 결제 UNKNOWN/RECONCILING/MANUAL_REVIEW를 그대로 유지한다. 검색 조건이 바뀌면 이전 페이지와 늦게 도착한 응답을 표시하지 않는다.

## Data and Migration

migration 없음. 기존 version 1 cart 스키마를 유지한다. cart 옵션 수정 시 동일 메뉴·옵션은 수량을 합친다.

## API and Event Contracts

기존 `/me/points`, `/me/coupons`, `/me/orders`, `/me/order-quotes`, `/orders`, 재주문·즐겨찾기·검색·알림 API를 사용한다. 공개 계약 변경 없음.

## Milestones

1. 장바구니 편집·포인트 입력과 메뉴 즐겨찾기 Storybook 및 구현.
2. 홈 추천·검색 필터·최근 주문, 주문 내역·마이페이지 요약.
3. 결제 안내·픽업 계층·고객 타이포그래피 정리.
4. Storybook 접근성·상호작용·실제 모바일 렌더링 및 전체 필수 검증.

## Required Tests

정상·빈 값·조회 실패·포인트 초과·견적 재계산·옵션 변경·명시적 삭제·재주문 진입·필터 변경·알림 읽음 상태·취소 권한·긴 한국어·모바일 레이아웃을 검증한다. 서버·DB 변경이 없어 backend 테스트는 Not run으로 보고한다.

## Validation Commands

frontend에서 `npm run typecheck`, `npm test`, `npm run check:design`, `npm run check:product-copy`, `npm run build-storybook`, `npm run test:storybook:docs`, `npm run build`, `npm run test:sites`. Storybook MCP focused 및 full `run-story-tests(a11y=true)`. 저장소 `scripts/verify-docs.sh`.

## Observability

화면의 로딩·실패·서버 충돌 상태와 Storybook 검사 결과를 증거로 남긴다. 트래픽·성능·배포 결과는 주장하지 않는다.

## Documentation Updates

Minor Decision에 인증 경계 유지와 매장별 쿠폰 표시를 기록한다. 완료 시 이 문서를 completed로 이동하고 실제 검증 결과를 기록한다. Accepted ADR의 변경은 필요하지 않다.

## Progress

- [x] 코드·OpenAPI·디자인 시스템과 live Storybook 확인.
- [x] 사용자 로그인 정책 유지 결정.
- [x] 기능 및 canonical stories 구현.
- [x] 실제 검증 및 결과 기록.

## Surprises & Discoveries

- 기존 READY 주문 상세 fixture의 CANCEL은 서버 권한 계약과 달라 상태별 fixture를 바로잡았다.
- 쿠폰 지갑은 매장 ID가 필수이므로 전체 보유 수량을 만들 수 없다.
- 매장 안내 조회 실패와 주문 견적 실패는 별개다. 저장된 매장 이름과 안내 조회 실패를 표시하면서 독립적인 서버 견적 판단을 유지했다.
- 장바구니를 수정하는 여러 story를 Docs에서 동시에 실행하면 같은 localStorage에 영향을 준다. 기본 장바구니는 Docs에 남기고 수정 시나리오는 독립 Canvas와 전체 상호작용 검사에 포함했다.
- 전체 Storybook 검사 중 개발 서버 연결이 끊겨 복구 후 전체 검사를 다시 실행했다. Docs 검사의 Chromium 실행 권한 문제는 승인된 실행으로 해결했고, 변경된 결제 문구에 맞춰 smoke 기준을 갱신했다.

## Decision Log

- 2026-09-05: 사용자 결정에 따라 로그인 정책 유지. MD-2026-033 기록.
- 2026-09-05: 기존 디자인 언어를 유지하면서 고객 정보 계층과 조작 경로를 정리한다.

## Outcomes & Retrospective

고객 본문은 공통 14–16px 중심의 토큰으로 복원하고 보조 정보는 13px로 정리했다. 메뉴 이미지·가격, 추천 매장과 실제 추천 사유, 읽지 않은 알림 상태, 즐겨찾기, 홈·지난 주문의 재주문 진입을 연결했다. 검색은 관련도·거리순과 주문 가능 필터를 제공한다.

장바구니는 옵션 수정·동일 구성 수량 병합·명시적 삭제·메뉴 추가와 포인트 입력을 제공한다. 포인트 변경은 서버 견적을 다시 받고 주문 command에도 같은 사용량과 현재 fingerprint를 보낸다. 재주문도 포인트·쿠폰을 명시적으로 선택한다. 포인트 조회 실패는 0P로 대체하지 않는다. 마이페이지는 포인트와 현재 장바구니 매장 범위의 쿠폰을 요약하며 추가 페이지가 있으면 수량을 하한으로 표시한다.

결제 마감 시각과 다음 결제창 안내를 명확히 했다. 픽업 번호는 한 번 강조하고 진행 단계는 세로로 배치했다. 주문 목록의 기간 입력은 기본으로 접는다. 로그인 정책과 API·DB·상태 전이·외부 결제 실패 정책은 그대로다.

### 실제 검증 결과

- **Passed:** `npm run typecheck`.
- **Passed:** `npm test` — 24개 파일의 단위 테스트 180개, presentation boundary 10개, product copy 11개.
- **Passed:** `npm run check:design` — 190 tokens, 58 story files, 42 route components, 14 style families. 기존 raw pixel baseline 3개 유지.
- **Passed:** `npm run check:product-copy`.
- **Passed:** Storybook MCP 전체 `run-story-tests(a11y=true)` — 243개 story 모두 통과.
- **Passed:** `npm run build-storybook` 및 `npm run test:storybook:docs` — 62 Docs entries, 15 stateful Docs, 47 state surfaces.
- **Passed:** `npm run build` 및 `npm run test:sites` — Sites 테스트 4개 통과. Vite의 500kB 초과 chunk 경고는 남으며 빌드는 성공했다.
- **Passed:** `scripts/verify-docs.sh` — OpenAPI YAML·semantic contract 및 문서 검증. 초기 Depends-On의 빈 값 표기를 수정한 뒤 통과했다.
- **Observed:** 인앱 브라우저 390px 메뉴·장바구니, 320px 홈·긴 매장명·검색·결제·픽업·마이·지난 주문을 직접 확인했다. 320px에서 페이지 가로 스크롤 없이 표시되는 것을 DOM 치수와 화면으로 확인했다.
- **Not run:** backend·DB 테스트(변경 없음), 실제 로그인부터 결제·픽업까지의 통합 E2E, 실제 결제 제공자 작업, 실물 기기·화면 낭독기 전체 흐름, 사용자 사용성·성능 측정, 배포.

기존 미추적 계획을 보존했다.

## Revision Notes

- 2026-09-05: 구현 전 범위·불변식·검증 기준 작성.
- 2026-09-05: 구현 및 검증 결과를 기록하고 completed로 이동.
