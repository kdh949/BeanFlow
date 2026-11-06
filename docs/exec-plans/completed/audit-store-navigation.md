# 즉시 주문 안내와 매장 검색 복귀 보존

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-10-03`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

CUS-02 신규 탐색의 legacy 슬롯 안내를 실제 즉시 주문 상태로 정정하고 CUS-03 검색→상세→복귀에서 조건·페이지·스크롤을 복원한다.

## Current State

두 카드가 생략된 nextPickupWindow를 예약 불가로 표시한다. query만 URL에 있고 상세 뒤로 링크가 검색 루트로 고정되어 있다.

## Definitions

Visit은 검색 경로·좌표·읽은 페이지 수·scroll 메타데이터다. 서버 결과나 cursor cache가 아니다.

## Scope

### In Scope

홈/검색/최근/즐겨찾기 두 카드, 상세/cart 상태 안내, URL 조건과 entry별 검색 복귀.

### Non-goals

API/DDL/거래 변경, 검색 alias/category 작업, 전역 cache framework, 좌표 storage 저장.

## Business Rules and Invariants

BR-28 좌표는 브라우저 메모리만 사용한다. history state는 opaque ID만 담는다. BR-50 정책과 실제 즉시 주문 가능성을 구분한다. 새로고침 뒤 URL 조건만 보존하며 위치는 재요청한다.

## Architecture and Transaction Boundaries

Frontend 표시/탐색만 변경한다. Aggregate와 transaction은 그대로다. 최대 20개 Visit을 보존하고 logout registry로 지운다.

## Alternatives Considered

navigate(-1)만으로는 직접 진입을 다룰 수 없다. sessionStorage는 좌표 수명을 불필요하게 늘린다. 검증된 로컬 경로와 opaque ID, 기존 API 재조회를 선택한다.

## Failure Semantics

첫 페이지부터 fresh cursor로 재조회하고 오류를 명시한다. 옛 응답으로 실패를 대체하지 않는다. Visit 소실은 URL 조건으로 시작하고 위치 재요청을 안내한다. request generation으로 늦은 응답을 폐기한다.

## Data and Migration

없음. 메모리 메타데이터만 추가한다.

## API and Event Contracts

변경 없음.

## Milestones

1. Story-first 회귀 추가.
2. 공통 상태 helper, URL 조건과 Visit 복귀 구현.
3. Storybook MCP/frontend 필수 검증과 문서 검증.

## Required Tests

조건 복귀, 좌표 A/B history, 2페이지/scroll 복귀, 직접 진입/새로고침, 잘못된 경로, logout·20개 제한, 재조회 실패, 두 카드 상태.

## Validation Commands

npm run typecheck; npm test; npm run check:design; npm run build-storybook; npm run test:storybook:docs; npm run build; npm run test:sites; Storybook MCP preview-stories/run-story-tests; scripts/verify-docs.sh; git diff --check.

## Observability

기존 오류 UI 재사용. 검색어/좌표 log 추가 없음.

## Documentation Updates

BR-28/50와 ADR-117에 탐색 표시와 브라우저 복귀 수명을 기록한다.

## Progress

- [x] 저장소 규칙·정책·route·Storybook 문서 확인.
- [x] 두 카드·상세·cart 즉시 주문 상태 통일.
- [x] URL 조건·메모리 Visit·fresh 페이지 및 scroll 복귀.
- [x] route 회귀 9건과 전체 unit 266건 통과.
- [x] Storybook MCP 영향 124건 및 후속 영향 27건, 마지막 append 보정 후 검색 8건 a11y 포함 통과.
- [x] 최종 Storybook/제품 빌드, Docs smoke, Sites, 문서·diff 검증 통과.
- [x] root의 query-less 복귀 회귀 및 표시/행동 기준 통일 요청 반영.

## Surprises & Discoveries

최종 독립 리뷰에서 추가 페이지 조회 오류를 fresh first-page 재시도로 연결하면 이미 읽은 2페이지를 잃는 회귀를 발견했다. 표시된 page가 있는 오류는 동일 nextCursor로 append 재시도하며, 초기 복원 실패는 fresh 조회를 유지한다. 1·2페이지 → 3페이지 실패 → 재시도 후 1·2·3페이지 유지 회귀를 추가했다.

상단 뒤로 Link는 새 history entry를 만들므로 원래 Visit을 공유하지 않고 메타데이터를 복사한다. 복귀 후 위치 B가 원래 entry A의 좌표를 덮지 않는 회귀를 추가했다.

최근/즐겨찾기는 별도 StoreCard를 사용한다. useBrowserLocation initial은 mount 시점만 반영하므로 entry별 remount한다.

## Decision Log

2026-10-03: query/sort/openOnly URL, 좌표·페이지·scroll은 최대 20개 메모리 Visit. 새로고침 위치 보존 없음.

## Outcomes & Retrospective

로컬 typecheck, npm test(unit 38 files/266 tests + boundary 10 tests + product-copy 11 tests), check:design, Storybook MCP 영향 124건, 후속 변경 27건, 마지막 append 재시도 보정 후 검색 8건 통과. 마지막 코드 변경 후 Storybook build, Docs smoke(125 docs/15 stateful docs/47 surfaces), 제품 build, Sites 4건을 다시 통과했다. 문서 검증 18 tests 및 OpenAPI semantic, 113 ExecPlan graph 검증과 git diff --check도 통과했다.

초기 typecheck는 새 테스트 mock의 options 추론이 never여서 실패했고, 테스트용 명시적 타입으로 수정 후 통과했다. 최초 문서 검증은 Depends-On의 backtick으로 —를 경로로 해석하여 실패했고 기존 문서와 동일한 bare —로 정정했다. 실패를 숨기지 않는다.

Chrome 실서비스 재검증은 연결 제한으로 Blocked. DB/backend 테스트는 API·서버·transaction 미변경으로 Not run. 성능 향상 측정/주장 없음. 기존 큰 bundle 경고는 유지된다.

## Revision Notes

2026-10-03: 독립 PR 구현과 로컬 필수 검증 완료. commit/push/PR은 root 인계 후 수행하며 병합·배포 완료를 뜻하지 않는다.
