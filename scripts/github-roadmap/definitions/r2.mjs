import { epic, task } from '../core.mjs';

export const r2Epics = [
  epic({
    id: 'E06',
    title: '주문 접수·제조·준비·완료 상태 머신',
    milestone: 'R2',
    priority: 'P0',
    areas: ['ordering', 'fulfillment', 'events', 'api'],
    risks: ['data-consistency'],
    currentSource: [
      '`src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/domain/Order.kt`의 OrderState는 현재 PENDING_PAYMENT, PAID, EXPIRED, CANCELLED만 가진다.',
      '`OrderController.kt`는 생성·조회·결제 확인만 제공한다.',
    ],
    invariants: [
      '허용된 상태 전이만 Aggregate 메서드로 수행한다.',
      'ACCEPTED 이후 단순 REJECTED 명령을 금지한다.',
      'OrderCompleted만 포인트 적립·정산 Item 생성의 기준 사실이다.',
    ],
    decisionRefs: ['BR-06', 'BR-07', 'BR-16', 'ADR-015', 'docs/architecture/event-catalog.md'],
    tasks: [
      task(
        'T1',
        'Order 상태 모델을 ACCEPTED·REJECTED·PREPARING·READY·COMPLETED로 확장',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/domain/Order.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderingPersistence.kt',
          'src/main/resources/db/migration/V*__extend_order_state.sql',
        ],
        [
          'Order Aggregate에 accept/reject/startPreparation/markReady/complete 메서드를 추가한다.',
          '상태별 필수 시각과 optimistic version을 저장한다.',
          '기존 PAID·EXPIRED 데이터가 손상되지 않는 마이그레이션을 작성한다.',
        ],
        [
          '허용·금지 전이 단위 테스트',
          '낙관적 버전 충돌 테스트',
          'Flyway 기존 데이터 호환 테스트',
        ],
      ),
      task(
        'T2',
        '매장 주문 상태 변경 Application Service와 REST API 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/api/**',
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderController.kt',
          'openapi/beanflow-v1.yaml',
        ],
        [
          '매장 actor와 storeId 소유권을 검증한다.',
          'PATCH /api/v1/store-orders/{orderId}/status 계약을 구현한다.',
          'Application Service 트랜잭션 안에서 Order와 AuditRecord를 함께 기록한다.',
        ],
        [
          '상태별 200·409·403 계약 테스트',
          '다른 매장 주문 접근 차단 테스트',
          '중복 동일 명령 멱등 테스트',
        ],
      ),
      task(
        'T3',
        'OrderAccepted·OrderReady·OrderCompleted 이벤트와 소비 계약 추가',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/**',
          'docs/architecture/event-catalog.md',
          'src/test/kotlin/io/github/kdh949/beanflow/ordering/**',
        ],
        [
          'after-commit 영속 publication에 event envelope 필드를 포함한다.',
          'eventId·aggregateVersion으로 소비자 중복 기준을 정의한다.',
          '부수효과 실패가 완료 주문을 롤백하지 않게 한다.',
        ],
        [
          '이벤트 envelope 테스트',
          '중복 publish/consume 테스트',
          '완료 후 부수효과 실패 회귀 테스트',
        ],
      ),
    ],
  }),
  epic({
    id: 'E07',
    title: '매장 수락 경고·timeout·자동 거절 보상',
    milestone: 'R2',
    priority: 'P0',
    areas: ['ordering', 'payment', 'inventory', 'fulfillment', 'promotion', 'loyalty', 'notification', 'operations'],
    risks: ['money', 'external-provider', 'data-consistency'],
    currentSource: [
      'ADR-015와 BR-06은 결제 후 2분 경고·3분 자동 거절을 확정했지만 구현은 없다.',
      '`PaymentConfirmationService.kt`는 PAID 전이와 예약 자원 확정까지만 수행한다.',
    ],
    invariants: [
      'PAID에서 수락과 timeout 중 하나만 성공한다.',
      '자동 거절 후 Order 상태와 환불·복원 완료 상태를 동일시하지 않는다.',
      '각 owner 보상은 중복 전달에도 한 번만 반영한다.',
    ],
    decisionRefs: ['BR-06', 'BR-07', 'ADR-015', 'ADR-009', 'ADR-019'],
    tasks: [
      task(
        'T1',
        '수락 deadline·2분 경고 worker와 멱등 키 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/StoreAcceptanceWorker.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderingPersistence.kt',
        ],
        [
          '결제 승인 시 acceptanceDeadline을 고정 저장한다.',
          'orderId+deadline으로 warning job을 멱등 실행한다.',
          'PAID가 아니면 경고를 생성하지 않는다.',
        ],
        [
          '1분59초·2분 경계 테스트',
          'worker 중단·재실행 테스트',
          '중복 경고 delivery 0 테스트',
        ],
      ),
      task(
        'T2',
        '3분 자동 거절과 owner별 보상 orchestration 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/StoreAcceptanceTimeoutService.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/payment/api/**',
          'src/main/kotlin/io/github/kdh949/beanflow/operations/api/**',
        ],
        [
          'Order를 guarded REJECTED로 확정한다.',
          'Payment 환불, 슬롯·재고·쿠폰·포인트 복원을 영속 이벤트 또는 owner 명령으로 전달한다.',
          '보상 실패는 retry 또는 ReprocessingCase로 남긴다.',
        ],
        [
          '자동 거절 후 환불 UNKNOWN 테스트',
          '각 보상 중복 이벤트 테스트',
          '부분 실패 시 Order 비롤백 테스트',
        ],
      ),
      task(
        'T3',
        '수락·timeout 경합과 보상 관측 통합 테스트',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/StoreAcceptanceTimeoutTest.kt',
          'docs/incidents/store-acceptance-timeout.md',
        ],
        [
          '수락과 timeout을 동일 시각에 병렬 실행한다.',
          '승자 하나와 정확한 terminal 상태를 검증한다.',
          '보상 진행률·실패 owner·correlationId를 운영 조회에서 확인한다.',
        ],
        [
          '반복 동시성 테스트',
          'Provider timeout 장애 주입',
          '수동 재처리 E2E 테스트',
        ],
      ),
    ],
  }),
  epic({
    id: 'E08',
    title: '고객 주문 취소와 결제 전·후 보상',
    milestone: 'R2',
    priority: 'P0',
    areas: ['ordering', 'payment', 'inventory', 'fulfillment', 'promotion', 'loyalty', 'api'],
    risks: ['money', 'data-consistency'],
    currentSource: [
      'OrderState에 CANCELLED는 있으나 고객 취소 API와 결제 전·후 보상 orchestration이 없다.',
      'BR-14는 PENDING_PAYMENT 또는 미수락 PAID에서만 고객 취소를 허용한다.',
    ],
    invariants: [
      'ACCEPTED 이후 고객 직접 취소를 거부한다.',
      '결제 전 취소는 예약 해제, 결제 후 취소는 환불 상태를 별도로 추적한다.',
      '같은 취소 명령은 부수효과를 반복하지 않는다.',
    ],
    decisionRefs: ['BR-14', 'BR-15', 'ADR-015', 'docs/architecture/transaction-boundaries.md'],
    tasks: [
      task(
        'T1',
        '고객 취소 멱등 명령과 상태 전이 API 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/api/CancelOrderUseCase.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          'actor+operation+Idempotency-Key 범위를 적용한다.',
          'PENDING_PAYMENT·PAID 조건을 Order Aggregate에서 보호한다.',
          '최초 HTTP 응답을 저장하고 동일 payload 재요청에 재생한다.',
        ],
        [
          '같은 키 같은 payload 재생 테스트',
          '같은 키 다른 payload 409 테스트',
          'ACCEPTED 이후 409 계약 테스트',
        ],
      ),
      task(
        'T2',
        '결제 전 예약 해제와 결제 후 환불 요청 분리 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderCancellationService.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/payment/api/**',
        ],
        [
          '결제 전에는 네 예약을 같은 로컬 트랜잭션에서 해제한다.',
          '결제 후에는 CANCELLED 사실을 확정한 뒤 Refund 명령을 분리한다.',
          '환불 UNKNOWN을 취소 금액 정산 완료로 위장하지 않는다.',
        ],
        [
          '예약 해제 전체 rollback 테스트',
          '환불 요청 실패·UNKNOWN 테스트',
          '중복 취소 이중 복원 0 테스트',
        ],
      ),
      task(
        'T3',
        '고객 취소 API 계약·감사·경합 테스트',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderCancellationTest.kt',
          'docs/api/error-catalog.md',
        ],
        [
          '취소와 결제 승인·매장 수락을 병렬 실행한다.',
          '최종 상태와 예약·Payment 결과 조합을 검증한다.',
          '고객 actor와 표준 reason을 AuditRecord에 남긴다.',
        ],
        [
          '승인/취소 경합 테스트',
          '수락/취소 경합 테스트',
          'REST Docs 오류 예제 생성',
        ],
      ),
    ],
  }),
  epic({
    id: 'E09',
    title: '결제수단 tokenization과 소유권 관리',
    milestone: 'R2',
    priority: 'P0',
    areas: ['payment', 'identity', 'security', 'persistence', 'api'],
    risks: ['privacy', 'money'],
    currentSource: [
      'README는 결제수단 등록 API가 아직 없고 local fixture만 사용한다고 명시한다.',
      'Payment 도메인에는 token reference 경계가 있으나 사용자 관리 API와 폐기 상태가 없다.',
    ],
    invariants: [
      'PAN·CVC·전체 유효기간은 Entity·API·로그에 존재할 수 없다.',
      '다른 사용자의 token을 사용할 수 없다.',
      'member+provider+tokenReference는 유일하고 폐기 token은 승인에 사용할 수 없다.',
    ],
    decisionRefs: ['BR-29', 'ADR-021', 'docs/architecture/aggregate-invariants.md'],
    tasks: [
      task(
        'T1',
        'PaymentMethod Aggregate·상태·Flyway 스키마 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/domain/PaymentMethod.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/PaymentPersistence.kt',
          'src/main/resources/db/migration/V*__create_payment_method.sql',
        ],
        [
          'provider token reference·brand·last4·alias만 저장한다.',
          'ACTIVE·REVOKED 상태와 소유자 변경 불가를 정의한다.',
          '유일성·nullable·길이 제약을 추가한다.',
        ],
        [
          '도메인 상태 단위 테스트',
          '민감 컬럼 부재 schema 테스트',
          '중복 token Repository 테스트',
        ],
      ),
      task(
        'T2',
        '결제수단 등록·목록·폐기 API와 소유권 검증 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/payment/api/**',
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          'Provider tokenization 결과를 입력으로 등록한다.',
          '목록은 표시 정보만 반환한다.',
          '폐기와 승인 사이 race를 Payment Tx1에서 다시 검증한다.',
        ],
        [
          '다른 사용자 조회·사용 403 테스트',
          '폐기 token 승인 거부 테스트',
          'API 응답 민감정보 부재 테스트',
        ],
      ),
      task(
        'T3',
        '결제정보 로그·직렬화·계약 보안 회귀 테스트',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/payment/**',
          'scripts/verify-sensitive-data.sh',
        ],
        [
          'DTO·Entity·structured log key를 정적 검사한다.',
          '오류 응답에 provider token 원문을 포함하지 않는다.',
          '샘플 fixture도 실제 PAN 형식을 사용하지 않는다.',
        ],
        [
          '로그 캡처 마스킹 테스트',
          'OpenAPI schema 금지 필드 검사',
          'Repository dump 샘플 검증',
        ],
      ),
    ],
  }),
  epic({
    id: 'E10',
    title: '실제 PG sandbox Adapter와 명시적 실패 계약',
    milestone: 'R2',
    priority: 'P0',
    areas: ['payment', 'platform', 'performance'],
    risks: ['external-provider', 'money'],
    currentSource: [
      '`PaymentGateway.kt`, `ExternalPaymentService.kt`, `LocalPaymentGatewayConfiguration.kt`와 scripted test gateway가 존재한다.',
      '운영 profile에서 사용할 실제 또는 sandbox HTTP Adapter와 계약 테스트는 없다.',
    ],
    invariants: [
      'Provider 호출은 DB 트랜잭션 밖에서 수행한다.',
      'timeout·응답 유실·해석 불가는 DECLINED가 아니라 UNKNOWN이다.',
      '운영 profile에서 fake adapter가 선택되면 시작에 실패한다.',
    ],
    decisionRefs: ['ADR-006', 'ADR-007', 'ADR-009', 'docs/architecture/failure-semantics.md'],
    tasks: [
      task(
        'T1',
        'HTTP 기반 PG sandbox Adapter와 profile 구성 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/gateway/**',
          'src/main/resources/application-*.yaml',
        ],
        [
          'PaymentGateway Port의 approve/query/void/refund 계약을 구현한다.',
          'local·test와 sandbox·production bean 선택을 명시한다.',
          'credential 누락·fake 선택 시 fail-fast 한다.',
        ],
        [
          'profile별 Context 시작 테스트',
          'HTTP Provider 계약 테스트',
          '설정 누락 시작 실패 테스트',
        ],
      ),
      task(
        'T2',
        'Provider 오류·timeout·idempotency 응답 번역과 메트릭 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/ExternalPaymentService.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/PaymentMetrics.kt',
        ],
        [
          'HTTP 상태·provider code를 APPROVED/DECLINED/UNKNOWN으로 번역한다.',
          'provider idempotency key와 correlationId를 전송한다.',
          'latency·timeout·unknown·reconcile metric을 기록한다.',
        ],
        [
          '명시 거절 422 테스트',
          'timeout·malformed UNKNOWN 테스트',
          '민감 응답 로그 마스킹 테스트',
        ],
      ),
      task(
        'T3',
        '지연 Provider 환경의 DB connection 경계·장애 부하 검증',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/PaymentConnectionBoundaryTest.kt',
          'load-tests/payment-provider-latency.js',
          'docs/benchmarks/payment-provider-latency.md',
        ],
        [
          'Provider 2~10초 지연 중 Hikari connection 점유를 측정한다.',
          'Tx1·Provider·Tx2 분리를 회귀 테스트한다.',
          'pool active/pending·RPS·p95·error rate를 기록한다.',
        ],
        [
          '지연 중 connection 반환 테스트',
          'pool exhaustion 비교 실험',
          '동일 조건 결과 문서화',
        ],
        'type:spike',
      ),
    ],
  }),
  epic({
    id: 'E11',
    title: '전체·품목 부분 환불과 reconciliation',
    milestone: 'R2',
    priority: 'P0',
    areas: ['payment', 'ordering', 'loyalty', 'settlement', 'operations'],
    risks: ['money', 'external-provider', 'data-consistency'],
    currentSource: [
      '현재 승인·late approval void/refund 복구는 있으나 사용자/매장 환불 Aggregate와 API가 없다.',
      'OrderLine에는 couponDiscountKrw·pointsAppliedKrw·cashPayableKrw snapshot이 이미 저장된다.',
    ],
    invariants: [
      '누적 환불액은 승인액을 초과할 수 없다.',
      '품목 환불은 저장된 현금·포인트 배분을 재사용하고 쿠폰 할인은 현금 환급하지 않는다.',
      'Provider timeout은 Refund UNKNOWN과 reconciliation을 남긴다.',
    ],
    decisionRefs: ['BR-12', 'BR-13', 'BR-15', 'ADR-014', 'docs/architecture/transaction-boundaries.md'],
    tasks: [
      task(
        'T1',
        'Refund Aggregate·멱등 레코드·Flyway 스키마 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/domain/Refund.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/PaymentPersistence.kt',
          'src/main/resources/db/migration/V*__create_refund.sql',
        ],
        [
          'REQUESTED·SUCCEEDED·DECLINED·UNKNOWN·RECONCILING·MANUAL_REVIEW 상태를 정의한다.',
          'paymentId+source line/reference 중복을 막는다.',
          'Payment summary row lock으로 누적 환불 상한을 보호한다.',
        ],
        [
          '상태 전이 단위 테스트',
          '동시 부분 환불 상한 테스트',
          'Unique Constraint 테스트',
        ],
      ),
      task(
        'T2',
        '전체·품목 부분 환불 Application Service와 Provider 호출 분리',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/payment/api/RefundApi.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/RefundService.kt',
          'openapi/beanflow-v1.yaml',
        ],
        [
          'Tx1 Refund REQUESTED를 먼저 커밋한다.',
          '외부 환불 후 Tx2에서 결과와 AuditRecord를 확정한다.',
          '품목 snapshot 금액을 사용하고 주문 원본을 수정하지 않는다.',
        ],
        [
          '전체·부분 환불 계약 테스트',
          '같은 품목 반복 환불 거부 테스트',
          '외부 호출 중 DB connection 비점유 테스트',
        ],
      ),
      task(
        'T3',
        'Refund UNKNOWN 조회 대사와 Loyalty·Settlement 후속 이벤트 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/RefundReconciliationService.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/**',
        ],
        [
          '10초·30초·2분·5분·15분 조회 정책을 재사용한다.',
          'PaymentRefunded를 refundId 기준으로 한 번 발행한다.',
          '계속 불명 시 단일 ReprocessingCase와 MANUAL_REVIEW를 남긴다.',
        ],
        [
          '중복 reconciliation 부수효과 0 테스트',
          '포인트 복원·정산 조정 consumer 계약 테스트',
          '5회 후 수동 검토 테스트',
        ],
      ),
    ],
  }),
];
