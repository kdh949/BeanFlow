# 고객 포인트의 적립 주문과 만료 시점 표시

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-10-03`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture
CUS-09: 적립 원인을 주문으로 확인하고 만료 연도와 가장 가까운 만료 건을 구분한다.

## Current State
고객 거래 DTO는 공용 운영 DTO이고 주문 맥락이 없다. 만료는 월/일만 표시하고 최대20개를 모두 펼친다.

## Definitions
orderContext는 고객 소유 주문의 공개 번호·주문 당시 매장명·첫 주문 항목 메뉴명이다.

## Scope
### In Scope
고객 전용 응답, typed PointLot binding, Ordering-owned batch projection, 만료 UI와 적립 링크.
### Non-goals
DDL, 금액/정산/적립 정책 변경, sourceReference 해석, 운영 응답 변경, 기존 데이터 보정.

## Business Rules and Invariants
BR-10/ADR-011. 원장과 잔액 불변, 고객 소유권 SQL predicate, 페이지 확정 후 batch enrichment.

## Architecture and Transaction Boundaries
고객 reader의 read-only transaction에서 기존 페이지와 lot binding을 읽고 Ordering shared query를 호출한다.
Ordering 내부 SQL이 order 소유권과 immutable 표시 snapshot을 함께 조회한다. 외부 호출·Aggregate 관계 없음.

## Alternatives Considered
문자열 source 파싱, 비인가 Support 조회, 원장 복제 제외. 좁은 shared port는 기존 recent-store 방식과 동일하다.

## Failure Semantics
legacy binding 없음은 optional 생략. binding이 있는 대상 누락/소유권 불일치/DB 오류는503. 빈 목록으로 대체하지 않는다.

## Data and Migration
기존 transaction.pointLotId와 lot.accrualOrderId 사용. migration 없음.

## API and Event Contracts
/me/point-transactions의 고객 전용 schema가 기존 필드를 유지하며 optional orderContext를 추가한다. event 변경 없음.

## Milestones
1. 정책/계약과 Storybook 상태
2. typed query와 고객 reader
3. UI 구성과 focused/full 검증
4. 독립 PR 인계

## Required Tests
고객간 접근·페이지/cursor·legacy/비주문 생략·snapshot 표시·dependency failure·기존 원장 불변. UI연도/최소시각/접기/링크/긴한글/조회실패.

## Validation Commands
Gradle focused integration/architecture/contract, spotless, verify-docs. Frontend typecheck/test/check:design/build-storybook/test:storybook:docs/build/test:sites 및 MCP story tests.

## Observability
503과 기존 point/API 관측 경계 유지. 조회 성능 개선 주장 없음.

## Documentation Updates
ADR011, BR10, 고객 OpenAPI, 이 계획.

## Progress
- [x] 코드와 MCP 조사
- [x] 고객 전용 optional 계약과 typed batch 조회 구현
- [x] 만료 연도·가장 가까운 만료·접기·주문 링크 UI 구현
- [x] 핵심 API/조회/실패 통합 테스트 22개 통과
- [x] Frontend 필수 검사와 focused Storybook MCP 통과
- [x] 모듈 경계와 기존 포인트/정산 회귀 검증 완료
- [x] 최종 diff 검토와 독립 PR 인계 준비
- [ ] 독립 PR 생성과 원격 CI 확인

## Surprises & Discoveries
운영과 공유하는 PointTransactionView를 직접 확장하면 운영 계약에 누출되어 고객 전용 DTO로 분리한다.

## Decision Log
2026-10-03: 독립 main PR, DDL 없음, 기존 snapshot과 scoped batch query만 사용.

## Outcomes & Retrospective
### Passed

- `./gradlew spotlessApply test --tests '*CustomerPointFacadeIntegrationTest' --tests '*CustomerOrderDisplayQueryServiceTest' --tests '*PointAccountQueryIntegrationTest' --tests '*PointAccountQueryMapperTest' --tests '*BeanflowApplicationTests' --tests '*RuntimeOpenApiParityTest'`: 22 tests, 0 failures. 고객 간 격리, 선택적 맥락, 서명 cursor, signed amount, 조회 전후 잔액/원장 보존, 누락된 메뉴 snapshot과 다른 고객 주문의 503을 확인했다.
- `npm run typecheck`, `npm test`: unit 37 files/257 tests, presentation-boundary 10, product-copy 11 통과.
- `npm run check:design`, `npm run build-storybook`, `npm run build`, `npm run test:sites`(4 tests) 통과.
- `npm run test:storybook:docs`: 125 docs entries, 15 stateful docs, 47 state surfaces 통과.
- BeanFlow Storybook MCP `run-story-tests` (6018): Points의 BalanceAndLedger, ZeroBalance, AccountIntegrityFailure, OrderContextAndExpiry, OrderContextUnavailable 5개 story와 a11y 통과. 연도, 접기/펼치기, 조회 범위 안내, 주문 링크와 포커스, 조회 실패를 검사했다.
- `bash scripts/verify-docs.sh`: 18 Python tests와 OpenAPI/문서 검증 통과.

- `./gradlew spotlessCheck test --tests '*ModularityTests' --tests '*AuthenticationArchUnitTest' --tests '*SupportArchitectureTest' --tests '*PointAdjustmentIntegrationTest' --tests '*PartialRefundRestorationBoundaryTest' --tests '*SettlementItemCreationIntegrationTest' --tests '*OrderCreationPointAccrualSnapshotIntegrationTest' --tests '*OperationsCustomerPointAccountIntegrationTest'`: 38 tests, 0 failures. Modulith/ArchUnit 7, 포인트 조정17, 부분 환불1, 정산 생성4, 주문 적립 snapshot3, 운영 조회6 통과.
- `git diff --check` 통과. DDL, 새 dependency, 운영용 포인트 DTO/기존 원장 쓰기 로직 변경 없음.

### Limits

- Chrome 시각 점검은 브라우저 연결 불가로 **Blocked**. 320/390px의 실제 화면 잘림과 확대 시각 검증을 완료했다고 주장하지 않는다.
- 전체 Storybook MCP aggregate는 **Not run**. 동일 환경의 기존 aggregate가 heap OOM/응답 hang을 반복하여 모든 변경/영향 story를 focused 실행했다. 전체 원격 CI 결과는 별도 확인한다.
- 신규 조회는 읽기 전용이며 운영 데이터와 배포 환경을 변경하지 않았다. 실제 배포 후 고객 여정은 **Not run**.
- 초기 검사에서 TypeScript undefined guard와 테스트 fixture의 필수 옵션 snapshot 필드 누락을 고친 뒤 위 검증을 통과했다.

## Revision Notes
2026-10-03: 실행 시작.
