# 선착순 쿠폰 리뷰 차단 이슈를 수직 슬라이스로 해소한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/limited-coupon-events.md`
> **Completed-At:** —

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

PR #130의 차단 리뷰 중 기능 접근성, 기한·멱등성 정합성, bounded cleanup 진행성과 운영 UI race에
해당하는 아홉 건을 수정한다. STOP 응답과 media 서명의 결합, 운영·고객 목록 N+1은 이 계획 범위가 아니다.

## Current State

운영 캠페인과 고객 이벤트 목록은 각각 첫 100건·50건 뒤 데이터를 노출하지 못한다. 매장 picker도 첫
100개만 반환한다. claim은 Campaign lock 대기 전 시각을 사용하고, draft 요청 해시는 자유 입력 경계를
보존하지 않으며, terminal create replay 전에 현재 메뉴 상태를 다시 검증한다. 두 command table은 90일
만료 시각과 인덱스만 있고 worker가 없고, Campaign banner sweep은 같은 첫 batch에서 정체할 수 있다.
운영 UI의 매장 전환은 늦은 메뉴 응답을 현재 선택에 반영할 수 있다.

## Definitions

- terminal replay: 같은 actor·operation·Idempotency-Key·payload에 저장된 최초 확정 결과 반환.
- bounded cleanup: 한 transaction과 한 실행이 처리하는 command/object 수를 명시적으로 제한하는 정리.
- keyset cursor: 안정적인 정렬 tuple의 마지막 값을 서명해 다음 page를 조회하는 cursor.

## Scope

### In Scope

- 고객 이벤트 `(claimEndsAt, campaignId)` cursor와 고객 UI 더 보기
- 운영 캠페인 cursor 소비와 매장 picker cursor
- Campaign lock 이후 시각, 경계 보존 request hash, create replay 순서
- Promotion command retention worker와 Campaign banner sweep progress
- 운영 UI 매장 메뉴 요청 generation 보호
- ADR-120의 create-only draft MVP 계약 정합화

### Non-goals

- STOP 응답/media failure semantics 변경
- 운영·고객 목록 batch 조회 또는 N+1 최적화
- DRAFT update 기능
- 범용 pagination/command/cleanup framework

## Business Rules and Invariants

- BR-25의 동일 payload replay와 다른 payload 409를 보존한다.
- BR-26에 따라 terminal command row를 90일 보존하고 due row만 bounded chunk로 삭제한다.
- BR-53의 claim 성공 transaction 순서와 `claimStartsAt < claimEndsAt <= couponExpiresAt`을 보존한다.
- 모든 유효 캠페인과 매장은 cursor를 끝까지 순회하면 누락·중복 없이 접근 가능해야 한다.

## Architecture and Transaction Boundaries

Customer와 Operations 목록은 기존 `SignedCursorCodec`과 소유 Context query port를 사용한다. claim은 command
replay를 먼저 확인한 뒤 Campaign root lock을 획득하고 주입 Clock에서 신규 claim 시각을 얻는다. retention은
Promotion 소유 service가 table별 독립 transaction으로 최대 100건을 정리한다. orphan sweep은 Media port가
반환한 continuation을 성공한 batch 뒤에만 전진시킨다.

## Alternatives Considered

- 전체 목록 무제한 반환: 메모리·응답 비용이 입력 크기에 종속되어 제외한다.
- 범용 command framework: 한 create replay 순서 수정에 비해 범위가 넓어 제외한다.
- durable media recovery state machine: ADR-115의 보완 sweep 범위를 넘으므로 제외한다.
- draft update 구현: 원 요청과 completed plan에 없으므로 ADR을 create-only MVP로 맞춘다.

## Failure Semantics

잘못되거나 scope가 다른 cursor는 기존 400 의미를 유지한다. retention table 하나의 실패는 성공 0건으로
기록하지 않으며 다른 table 정리를 막지 않는다. orphan list/delete 실패 시 continuation을 전진시키지 않아
다음 tick이 같은 batch를 재시도한다. 이전 매장 메뉴 요청의 성공·실패·finally는 현재 UI 상태를 바꾸지 않는다.

## Data and Migration

새 migration은 없다. V69의 `retention_expires_at`과 `(retention_expires_at, id)` 인덱스를 사용한다.

## API and Event Contracts

- `GET /me/events`: cursor·limit을 받고 `items`와 `page.nextCursor`를 반환한다.
- `GET /operations/coupon-campaigns/store-options`: cursor·limit을 받고 같은 page envelope를 반환한다.
- 운영 캠페인 기존 list cursor 계약은 변경하지 않고 UI가 소비한다.
- 이벤트나 도메인 event 계약은 바꾸지 않는다.

## Milestones

1. claim 기한과 draft 멱등 replay/hash를 테스트 우선으로 수정한다.
2. command retention과 Campaign banner sweep 진행성을 수정한다.
3. 고객 이벤트와 운영 캠페인/매장 목록을 cursor로 끝까지 연결한다.
4. 운영 매장 메뉴 race를 수정하고 Storybook·전체 회귀를 검증한다.

## Required Tests

- Campaign lock 대기 중 종료 경계를 넘은 신규 claim 거부
- 구분자 위치만 다른 draft payload 409와 동일 payload replay
- 생성 뒤 메뉴 unavailable이어도 최초 결과 replay
- retention 90일 전후·100건 chunk·실패 후 재실행
- 100개 참조 객체 뒤 orphan에 여러 sweep으로 도달
- 고객/운영/매장 cursor 전 page 순회의 무누락·무중복
- 매장 A/B 역순 응답과 선택 해제 뒤 메뉴 상태

## Validation Commands

- focused Gradle integration/controller/unit tests
- `./gradlew spotlessCheck test build`
- `./scripts/verify-openapi-runtime.sh`
- `./scripts/verify-docs.sh`
- frontend unit/typecheck/design/product-copy/build
- Storybook MCP focused interaction/a11y 뒤 전체 story tests

## Observability

기존 claim, campaign command, media orphan metric을 유지한다. retention worker에는 table과 outcome만 tag로
사용하며 actor, campaign, idempotency key는 기록하지 않는다.

## Documentation Updates

ADR-120의 draft update 표현을 create-only MVP로 바꾸고 customer cursor 구현과 OpenAPI를 일치시킨다.

## Progress

- [x] 2026-09-06: PR #130 head, unresolved review scope, existing V69 retention index와 migration 부재 확인.
- [x] 2026-09-06: Campaign lock 이후 Clock 경계, length-prefixed draft hash, mutable 메뉴 검증 전 replay를
  회귀 테스트로 RED 확인한 뒤 focused PostgreSQL/Controller test GREEN.
- [x] 2026-09-06: 두 Promotion command table의 90일 경계·100건 `SKIP LOCKED` 정리와 테이블별
  실패 격리·재실행, AIStor `startAfter` 기반 Campaign banner sweep 진행성을 RED→GREEN으로 검증.
- [x] 2026-09-06: 고객 이벤트와 운영 캠페인·매장 선택지에 signed keyset cursor를 연결하고 API,
  프론트엔드, 컨트롤러 순회 테스트를 함께 갱신.
- [ ] Milestone 4: 매장 메뉴 요청 generation 보호와 focused Storybook interaction/a11y 검증 완료.
  전체 회귀 검증 뒤 완료 처리한다.

## Surprises & Discoveries

- `StorefrontImageStorageOperations.access()`는 object-store network 호출이 아닌 local signing이므로 STOP
  failure semantics는 이 corrective scope에서 변경하지 않는다.

## Decision Log

- 2026-09-06: 사용자가 앞선 리뷰 분류를 승인해 9개 차단 이슈만 수정하고 STOP·N+1은 제외한다.
- 2026-09-06: draft update를 새로 구현하지 않고 Accepted ADR을 completed implementation 범위와 맞춘다.

## Outcomes & Retrospective

완료 후 검증 결과와 최종 commit을 기록한다.

## Revision Notes

- 2026-09-06: PR #130 corrective implementation plan 최초 작성.
