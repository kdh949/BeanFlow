# Core Architecture Decision Summary

이 문서는 BeanFlow의 핵심 Accepted ADR 열 개를 현재 source 증거와 함께 요약하는 입구다.
원 ADR의 전체 맥락·amendment·규범을 대체하지 않으며, 충돌 시 링크한 ADR이 우선한다.

## [ADR-001 Modular Monolith](../adr/ADR-001-modular-monolith.md)

**문제:** 거래 Context를 처음부터 서비스로 분리하면 네트워크 실패와 운영 비용이 제품 검증보다
앞서지만, 계층형 단일 모놀리스는 소유권을 흐린다. **결정:** Spring Modulith 단일 배포 단위 안에서
공개 Application API, event와 구조 테스트로 논리 경계를 강제한다. **포기한 대안:** 초기
microservices와 경계 없는 layered monolith를 모두 포기했다.

**현재 증거:** [ModularityTests](../../src/test/kotlin/io/github/kdh949/beanflow/architecture/ModularityTests.kt)와
[Context Map](context-map.md)이 모듈 의존성을 검증한다. **비용/한계:** 한 프로세스 장애가 전체에
영향을 주며 실제 분산 실패를 아직 검증하지 않았다. **Revisit condition:** 독립 배포·확장·장애
격리가 관측 가능한 요구가 될 때 물리 분리를 다시 평가한다.

## [ADR-002 Bounded Context Boundaries](../adr/ADR-002-bounded-context-boundaries.md)

**문제:** 주문 생명주기를 공유해도 가격, 결제, 혜택과 정산의 데이터 소유권·일관성 요구는 다르다.
**결정:** Identity부터 Operations까지 14개 논리 Context를 두고, 초기 물리 모듈 통합 여부와 무관하게
소유권을 유지한다. **포기한 대안:** 주문 중심 거대 도메인과 모든 후보의 즉시 서비스화를 포기했다.

**현재 증거:** [Context Map](context-map.md), [Capability Map](capability-map.md)과
[ModularityTests](../../src/test/kotlin/io/github/kdh949/beanflow/architecture/ModularityTests.kt)가 owner와
public boundary를 기록·검증한다. **비용/한계:** 작은 Context에도 번역 API와 event 계약이 필요하다.
**Revisit condition:** 용어 충돌, 독립 팀 또는 배포 요구가 실제로 확인될 때 경계를 조정한다.

## [ADR-003 Aggregate Reference by ID](../adr/ADR-003-aggregate-reference-by-id.md)

**문제:** Context 전반의 JPA 객체 연관·cascade는 loading 범위와 transaction owner를 숨긴다.
**결정:** 다른 Aggregate는 ID로 참조하고 같은 Aggregate 안에서 생명주기를 공유하는 Entity만 객체
연관을 사용하며 필요한 DB FK는 유지한다. **포기한 대안:** 전면 JPA association과 FK까지 제거한
ID-only storage를 포기했다.

**현재 증거:** [Aggregate Invariants](aggregate-invariants.md),
[Transaction Boundaries](transaction-boundaries.md)와
[OrderingPersistence](../../src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderingPersistence.kt)가
ID·FK 기반 경계를 보여 준다. **비용/한계:** 조회 조합과 DTO projection을 명시해야 해 탐색 편의가
줄어든다. **Revisit condition:** 동일 transaction과 lifecycle을 지속적으로 공유한다는 증거가 생길 때다.

## [ADR-004 Order Price Snapshot](../adr/ADR-004-order-price-snapshot.md)

**문제:** 현재 Menu·가격을 과거 Order에 다시 join하면 메뉴 변경 뒤 환불·정산 결과가 바뀐다.
**결정:** OrderLine에 menu ID와 주문 시점 이름, 옵션, 단가, 수량과 혜택 배분을 snapshot한다.
**포기한 대안:** 현재 Menu 조회와 가격만 보존하는 축소 snapshot을 포기했다.

**현재 증거:** [CreateOrderServiceTest](../../src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/CreateOrderServiceTest.kt),
[Aggregate Invariants](aggregate-invariants.md)와 V3/V15/V20 migration이 금액·allocation snapshot을
보호한다. **비용/한계:** 중복 데이터와 schema evolution 비용이 있다. **Revisit condition:** 규제·감사로
추가 표시·계산 속성을 장기 보존해야 할 때 snapshot 계약을 versioning한다.

## [ADR-006 External Payment Transaction Boundary](../adr/ADR-006-external-payment-transaction-boundary.md)

**문제:** PG latency 동안 DB connection과 lock을 유지하면 pool 고갈과 장기 transaction이 발생한다.
**결정:** Tx1에서 Payment/멱등 준비 상태를 commit하고 transaction 밖에서 Provider를 호출한 뒤 Tx2에서
결과, Order와 reservation owner 확정을 원자 commit한다. **포기한 대안:** transaction 내부 PG 호출과
초기 완전 비동기 승인을 포기했다.

**현재 증거:** [PaymentConfirmationIntegrationTest](../../src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/PaymentConfirmationIntegrationTest.kt)와
[Transaction Boundaries](transaction-boundaries.md)가 Tx1/Provider/Tx2와 rollback을 검증한다.
**비용/한계:** 중간 `UNKNOWN`과 reconciliation이 필수다. **Revisit condition:** Provider가 callback 등
더 강한 원자성·확정 보장을 제공할 때다.

## [ADR-007 Payment Idempotency and Reconciliation](../adr/ADR-007-payment-idempotency-reconciliation.md)

**문제:** client retry, response loss와 server crash는 중복 승인과 결과 불명을 만든다. **결정:**
actor+operation+key scope, payload hash, unique constraint와 저장 결과를 사용하고 timeout은 `UNKNOWN`으로
보존한다. 승인 요청을 재전송하지 않고 bounded lookup 뒤에도 불명이면 `MANUAL_REVIEW`와 case를 만든다.
**포기한 대안:** client 중복 방지나 payment key uniqueness만 믿는 방식을 포기했다.

**현재 증거:** [PaymentConfirmationIntegrationTest](../../src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/PaymentConfirmationIntegrationTest.kt),
[Failure Semantics](failure-semantics.md)와 V6 migration이 replay·unknown·late approval recovery를
검증한다. **비용/한계:** durable work, retention과 복잡한 state machine이 필요하다. **Revisit condition:**
Provider idempotency 보장과 key/result 보존 기간이 계약으로 확정될 때다.

## [ADR-008 Settlement Adjustment Ledger](../adr/ADR-008-settlement-adjustment-ledger.md)

**문제:** 확정 정산을 환불·이의제기 후 덮어쓰면 audit와 과거 재현이 깨진다. **결정:** confirmed
Batch/Item은 immutable로 유지하고 후속 변경은 append-only `SettlementAdjustment`로 기록해 다음
Batch에 상계한다. 수락 전 고객 취소는 Item을 만들지 않고 `NOT_APPLICABLE` Audit로 증명한다.
**포기한 대안:** 과거 Batch 재계산·덮어쓰기와 전체 Batch 취소/재생성을 포기했다.

**현재 증거:** [Settlement Refund Adjustment Integration Test](../../src/test/kotlin/io/github/kdh949/beanflow/settlement/internal/SettlementRefundAdjustmentIntegrationTest.kt),
[Settlement Lifecycle Evidence](../quality/settlement-lifecycle-release-evidence.md)와 V21/V28~V30 migration이
불변·중복 방지·carry를 검증한다. **비용/한계:** 현재 금액은 원장 합산이 필요하고 음수 이월 정책이
복잡하다. **Revisit condition:** 실제 지급·채권 또는 외부 회계 연동 요구가 생길 때다.

## [ADR-009 Explicit Failure Semantics](../adr/ADR-009-explicit-failure-semantics.md)

**문제:** 예외를 삼키거나 빈 값·local fallback으로 대체하면 거래 실패가 성공처럼 보인다.
**결정:** 필수 설정은 startup fail-fast, 요청 실패는 명시적 status/error, 비동기 실패는 durable
retry/failed/unknown/manual state로 남기며 fallback은 별도 ADR과 observability가 있어야 한다.
**포기한 대안:** best-effort silent fallback과 모든 부수효과 실패 시 원 거래 rollback을 포기했다.

**현재 증거:** [Failure Semantics](failure-semantics.md), failure-path integration tests와
[Definition of Done](../testing/definition-of-done.md)이 no-fallback rule을 강제한다. **비용/한계:** 사용자에게
503/지연 상태가 더 자주 보이고 운영 case·metric 구현 비용이 든다. **Revisit condition:** degraded mode가
명시적인 제품 기능으로 설계될 때다.

## [ADR-011 PointLot and Ledger](../adr/ADR-011-point-lot-ledger.md)

**문제:** 단일 balance로는 선소멸, issuer, 만료, 부분 환불 복원과 비용 부담을 재현할 수 없다.
**결정:** issuer·expiry별 `PointLot`, append-only signed `PointTransaction`, reservation allocation과
검증 가능한 Account summary를 함께 유지한다. 사용은 만료가 빠른 Lot부터 하며 recovery pending과
감사형 adjustment도 별도 source·transaction으로 tie-out한다. **포기한 대안:** balance-only와 매 조회 시
원장 전체 합산을 포기했다.

**현재 증거:** [Point Adjustment Integration Test](../../src/test/kotlin/io/github/kdh949/beanflow/loyalty/internal/PointAdjustmentIntegrationTest.kt),
[Loyalty Adjustment Evidence](../quality/loyalty-point-adjustment-release-evidence.md)와 V2/V14~V17/V31이
lot·ledger·summary invariant를 검증한다. **비용/한계:** 여러 Lot lock과 reconciliation이 필요하다.
**Revisit condition:** 포인트 통합·선물·양도 요구가 생길 때다.

## [ADR-068 Immutable Integration Event Snapshots](../adr/ADR-068-immutable-integration-event-snapshots.md)

**문제:** consumer가 현재 Aggregate·정책을 다시 읽으면 늦은 event가 과거 정산·Analytics 결과를
현재 값으로 바꾼다. **결정:** producer transaction이 결과 시점의 immutable financial snapshot과
versioned logical source를 persistent publication에 함께 commit하며 V1 의미 변경은 새 version과
cutover gate로 처리한다. **포기한 대안:** live owner 조회, locator-only projection과 V1 field in-place
추가를 포기했다.

**현재 증거:** [Event Catalog](event-catalog.md),
[PaymentRefunded Contract Test](../../src/test/kotlin/io/github/kdh949/beanflow/eventing/internal/PaymentRefundedEventContractTest.kt)와
Plan 15/16/20 및 point-adjustment evidence가 payload·replay·atomicity를 검증한다. **비용/한계:** payload
version, source uniqueness, cutover inventory와 consumer checkpoint 관리가 필요하다. **Revisit condition:**
retention이 보장된 immutable owner projection, 외부 broker 또는 구 version 병행 보존 요구가 생길 때다.
