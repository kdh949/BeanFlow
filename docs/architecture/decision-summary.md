# BeanFlow 주요 설계 결정

주문·결제·포인트·정산을 구현하면서 선택한 설계와 이유를 정리했습니다. 자세한 정책과 변경 이력은 각 ADR에서 볼 수 있습니다.

## [ADR-001 Modular Monolith](../adr/ADR-001-modular-monolith.md)

처음부터 서비스를 나누면 모듈 사이의 네트워크 호출과 배포 관리가 늘어납니다. BeanFlow는 Spring Modulith를 이용해 하나의 애플리케이션으로 실행하고, 도메인별 공개 Application API와 이벤트로 모듈 간 접근을 관리합니다.

[모듈 의존성 테스트](../../src/test/kotlin/io/github/kdh949/beanflow/architecture/ModularityTests.kt)와 [Context Map](context-map.md)을 함께 관리합니다. 독립적인 배포나 장애 격리가 필요해지면 서비스 분리를 다시 검토할 계획입니다.

## [ADR-002 Bounded Context Boundaries](../adr/ADR-002-bounded-context-boundaries.md)

주문, 결제, 혜택, 정산은 하나의 거래에 참여하지만 각자 관리해야 할 데이터가 다릅니다. Identity부터 Operations까지 14개 Bounded Context로 나누고 데이터 변경은 담당 모듈에서 처리합니다. 처음에는 일부 기능을 한 모듈에서 실행하더라도 데이터 소유 관계는 유지합니다.

[Context Map](context-map.md), [Capability Map](capability-map.md), [모듈 의존성 테스트](../../src/test/kotlin/io/github/kdh949/beanflow/architecture/ModularityTests.kt)에서 구성을 볼 수 있습니다. 모듈 사이에 API·이벤트를 추가로 관리해야 하는 비용이 있습니다.

## [ADR-003 Aggregate Reference by ID](../adr/ADR-003-aggregate-reference-by-id.md)

다른 Aggregate를 JPA 객체로 직접 연결하면 조회 시점과 함께 수정되는 데이터 범위를 파악하기 어려워집니다. Aggregate 사이에는 ID를 저장하고, 같은 Aggregate 안에서만 객체 관계를 사용합니다. DB의 FK 제약은 유지합니다.

조회에 필요한 데이터는 Query Repository나 DTO Projection으로 조합합니다. [Aggregate Invariants](aggregate-invariants.md), [Transaction Boundaries](transaction-boundaries.md), [OrderingPersistence](../../src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderingPersistence.kt)에 구현 방식을 정리했습니다.

## [ADR-004 Order Price Snapshot](../adr/ADR-004-order-price-snapshot.md)

고객이 주문한 뒤 메뉴 가격이 바뀌어도 환불과 정산은 주문 당시 금액으로 계산해야 합니다. OrderLine에는 메뉴 ID와 당시 상품명·옵션·단가·수량·혜택 배분을 함께 저장합니다.

[CreateOrderServiceTest](../../src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/CreateOrderServiceTest.kt), [Aggregate Invariants](aggregate-invariants.md), Flyway V3·V15·V20에서 관련 처리를 볼 수 있습니다. 스냅샷 때문에 데이터가 중복되고, 필드가 바뀌면 migration도 필요합니다.

## [ADR-006 External Payment Transaction Boundary](../adr/ADR-006-external-payment-transaction-boundary.md)

PG 응답을 기다리는 동안 DB 연결과 lock을 점유하면 주문 요청이 몰릴 때 connection pool에 부담이 생깁니다. 첫 트랜잭션에서 결제 요청과 멱등성 정보를 저장하고, PG를 호출한 뒤 두 번째 트랜잭션에서 승인 결과와 주문·예약 변경을 저장합니다.

PG에서 승인이 완료됐지만 DB 저장에 실패하는 상황은 `UNKNOWN`과 reconciliation으로 처리합니다. [결제 승인 통합 테스트](../../src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/PaymentConfirmationIntegrationTest.kt)와 [Transaction Boundaries](transaction-boundaries.md)에 구현 내용이 있습니다.

## [ADR-007 Payment Idempotency and Reconciliation](../adr/ADR-007-payment-idempotency-reconciliation.md)

결제 요청은 네트워크 오류나 재시도로 여러 번 들어올 수 있습니다. 사용자·작업 종류·멱등성 키를 묶고 요청 내용의 해시와 결과를 저장합니다. DB unique constraint로 중복 처리를 막습니다.

응답 유실 후에는 같은 승인 요청을 다시 보내는 대신 PG 조회로 상태를 가져옵니다. 정해진 횟수만큼 조회해도 결과를 알 수 없으면 `MANUAL_REVIEW`로 넘깁니다. [결제 승인 통합 테스트](../../src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/PaymentConfirmationIntegrationTest.kt), [Failure Semantics](failure-semantics.md), Flyway V6에 구현 사항을 연결했습니다.

## [ADR-008 Settlement Adjustment Ledger](../adr/ADR-008-settlement-adjustment-ledger.md)

정산이 확정된 후 환불이나 이의제기가 발생할 수 있습니다. 확정된 Batch·Item을 유지하고 이후 변경은 `SettlementAdjustment`에 추가해 다음 정산에서 상계합니다. 매장 수락 전 취소처럼 정산 항목이 생성되지 않은 환불은 Audit에 `NOT_APPLICABLE`로 남깁니다.

[정산 환불 조정 통합 테스트](../../src/test/kotlin/io/github/kdh949/beanflow/settlement/internal/SettlementRefundAdjustmentIntegrationTest.kt), [정산 처리 기록](../quality/settlement-lifecycle-release-evidence.md), Flyway V21·V28~V30과 연결됩니다. 현재 금액을 조회할 때는 관련 조정 내역과 음수 이월을 함께 계산합니다.

## [ADR-009 Explicit Failure Semantics](../adr/ADR-009-explicit-failure-semantics.md)

필수 Port나 설정이 누락되면 애플리케이션 시작 단계에서 오류를 반환합니다. 요청 처리 실패는 API 상태·오류 코드로 전달하고, 비동기 작업은 재시도·실패·결과 불명·수동 처리 상태로 기록합니다.

[Failure Semantics](failure-semantics.md), 실패 시나리오 통합 테스트와 [Definition of Done](../testing/definition-of-done.md)에서 관련 규칙을 관리합니다. 필수 기능이 실패했을 때 대체 데이터로 성공 응답을 만드는 대신 사용자에게 처리 상태를 보여줍니다.

## [ADR-011 PointLot and Ledger](../adr/ADR-011-point-lot-ledger.md)

포인트는 발급처와 유효기간이 다를 수 있고, 부분 환불 시 원래 사용한 포인트를 다시 돌려줘야 합니다. `PointLot`으로 발급처·유효기간을 관리하고, 사용·적립·조정을 append-only `PointTransaction`에 기록합니다. 사용 시에는 만료가 빠른 Lot부터 차감합니다.

잔액 조회용 Account summary와 예약·배분 내역을 함께 관리하며, [포인트 조정 통합 테스트](../../src/test/kotlin/io/github/kdh949/beanflow/loyalty/internal/PointAdjustmentIntegrationTest.kt), [포인트 처리 기록](../quality/loyalty-point-adjustment-release-evidence.md), Flyway V2·V14~V17·V31에서 관련 내용을 볼 수 있습니다.

## [ADR-068 Immutable Integration Event Snapshots](../adr/ADR-068-immutable-integration-event-snapshots.md)

오래된 이벤트를 나중에 처리할 때 현재 가격이나 정책을 다시 읽으면 주문 당시의 금액과 달라질 수 있습니다. 이벤트를 생성하는 트랜잭션에서 해당 시점의 금액·정책 snapshot과 source version을 함께 저장합니다. 이벤트 구조를 바꿀 때는 새 version과 전환 절차를 사용합니다.

[Event Catalog](event-catalog.md), [PaymentRefunded Contract Test](../../src/test/kotlin/io/github/kdh949/beanflow/eventing/internal/PaymentRefundedEventContractTest.kt), Plan 15·16·20과 포인트 조정 기록을 참고할 수 있습니다. 변경 시에는 이벤트를 소비하는 모듈의 version과 checkpoint도 함께 관리합니다.
