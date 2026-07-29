import { epic, task } from '../core.mjs';

export const r3Epics = [
  epic({
    id: 'E12',
    title: '영속 이벤트 발행과 준비 완료 알림 Outbox',
    milestone: 'R3',
    priority: 'P0',
    areas: ['events', 'notification', 'ordering', 'operations', 'persistence'],
    risks: ['external-provider', 'data-consistency'],
    currentSource: [
      'event catalog는 영속 publication을 요구하지만 현재 알림 모듈과 Outbox/Event Publication Registry 구현이 없다.',
      'OrderReady는 Notification 소비 기준 사실로 정의되어 있다.',
    ],
    invariants: [
      '원본 OrderReady 트랜잭션과 publication 기록은 함께 커밋한다.',
      '알림 실패는 주문 READY를 롤백하지 않는다.',
      'event+recipient+channel은 한 delivery만 성공할 수 있다.',
    ],
    decisionRefs: ['BR-27', 'ADR-010', 'ADR-019', 'docs/architecture/event-catalog.md'],
    tasks: [
      task(
        'T1',
        '영속 publication 방식 확정과 NotificationDelivery 스키마 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/notification/**',
          'src/main/resources/db/migration/V*__create_notification.sql',
          'docs/exec-plans/active/notification-outbox.md',
        ],
        [
          'Spring Modulith Event Publication Registry와 직접 Outbox를 현재 버전에 맞게 비교한다.',
          '선택한 발행·재시작 복구 방식을 ExecPlan과 ADR에 기록한다.',
          'NotificationDelivery에 event·recipient·channel unique와 시도 상태를 둔다.',
        ],
        [
          'publication 원자성 테스트',
          '중복 delivery 제약 테스트',
          '애플리케이션 재시작 복구 테스트',
        ],
      ),
      task(
        'T2',
        '앱 내·모의 메시지 Adapter와 1m/5m/30m 재시도 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/notification/internal/**',
          'src/main/kotlin/io/github/kdh949/beanflow/notification/api/**',
        ],
        [
          'Provider Port와 local/test Adapter를 물리적으로 분리한다.',
          '총 4회 시도 후 MANUAL_REVIEW로 전환한다.',
          'timeout·ACK 유실 시 provider idempotency key로 중복 발송을 제어한다.',
        ],
        [
          'retry schedule 고정 Clock 테스트',
          'ACK 유실 중복 성공 0 테스트',
          '4회 실패 Operations case 테스트',
        ],
      ),
      task(
        'T3',
        'OrderReady 알림 E2E·실패 관측·수동 재처리 구현',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/notification/**',
          'src/main/kotlin/io/github/kdh949/beanflow/operations/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          'READY 전이 후 delivery 상태를 주문과 독립적으로 조회한다.',
          '운영자가 같은 delivery key로 안전하게 재처리한다.',
          'correlationId·lastError·nextAttemptAt을 운영 조회에 제공한다.',
        ],
        [
          '주문 READY 유지+알림 실패 테스트',
          '수동 재처리 인가·감사 테스트',
          'REST Docs 상태 예제 생성',
        ],
      ),
    ],
  }),
  epic({
    id: 'E13',
    title: '포인트 적립·만료·환불 복원 원장',
    milestone: 'R3',
    priority: 'P0',
    areas: ['loyalty', 'ordering', 'payment', 'operations', 'persistence'],
    risks: ['money', 'concurrency', 'data-consistency'],
    currentSource: [
      '`PointReservationService.kt`와 `PointReservationPersistence.kt`는 주문 전 예약·확정을 처리한다.',
      'OrderCompleted 적립, PointTransaction 원장, 만료 worker와 POINT_RECOVERY_PENDING은 아직 없다.',
    ],
    invariants: [
      '사용은 만료가 빠른 PointLot부터 수행한다.',
      '잔액·Lot·원장 합계가 일치하고 음수 잔액을 만들지 않는다.',
      'OrderCompleted·refund reference당 적립·복원·회수는 한 번만 처리한다.',
    ],
    decisionRefs: ['BR-10', 'BR-11', 'BR-13', 'ADR-011', 'docs/architecture/transaction-boundaries.md'],
    tasks: [
      task(
        'T1',
        'LoyaltyProgram·PointTransaction·적립 PointLot 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/domain/**',
          'src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/PointPersistence.kt',
          'src/main/resources/db/migration/V*__extend_loyalty.sql',
        ],
        [
          'issuerType·적립률·만료 정책을 프로그램과 발급 Lot에 snapshot한다.',
          'OrderCompleted 실결제액 기준으로 PointLot과 ACCRUAL 원장을 생성한다.',
          'sourceOrderId unique로 중복 적립을 막는다.',
        ],
        [
          '적립률·원 미만 처리 테스트',
          '중복 완료 이벤트 테스트',
          'Account/Lot/원장 tie-out 테스트',
        ],
      ),
      task(
        'T2',
        '포인트 만료 chunk worker와 소멸 예정 조회 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/PointExpirationWorker.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/loyalty/api/**',
        ],
        [
          'available 금액만 `(expiresAt,id)` 순서로 잠가 만료한다.',
          'reserved allocation은 만료 worker가 임의 해제하지 않는다.',
          'chunk 중단·재실행에 같은 EXPIRE 원장이 중복되지 않게 한다.',
        ],
        [
          '만료 직전·경계 Clock 테스트',
          'SKIP LOCKED 또는 잠금 경쟁 테스트',
          '재실행 중복 소멸 0 테스트',
        ],
      ),
      task(
        'T3',
        '환불 포인트 복원·적립 회수·POINT_RECOVERY_PENDING 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/PointRefundService.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/operations/api/**',
        ],
        [
          '사용 포인트를 원 allocation과 만료 정책에 따라 복원한다.',
          '적립 포인트 부족 회수분은 음수 balance 대신 pending 원장으로 기록한다.',
          '이후 적립은 pending 금액 상계에 우선 사용한다.',
        ],
        [
          '부분 환불 복원 테스트',
          '사용 후 환불 pending 테스트',
          '중복 refund 이벤트 이중 회수 0 테스트',
        ],
      ),
    ],
  }),
  epic({
    id: 'E14',
    title: '캠페인·쿠폰 발급·사용 한도와 동시성',
    milestone: 'R3',
    priority: 'P0',
    areas: ['promotion', 'ordering', 'settlement', 'performance', 'api'],
    risks: ['money', 'concurrency'],
    currentSource: [
      '`PromotionApi.kt`와 `CouponReservationService.kt`는 주문 시 쿠폰 예약을 제공한다.',
      'Campaign 생성·쿠폰 발급 API, 수량 한도와 이벤트 트래픽 비교 실험은 없다.',
    ],
    invariants: [
      '한 주문에는 쿠폰 최대 하나를 사용한다.',
      'Campaign type별 금액·rate·minimum·maximum·대상·부담 비율이 유효해야 한다.',
      '같은 CouponIssuance는 동시에 두 주문에 예약·사용될 수 없다.',
    ],
    decisionRefs: ['BR-08', 'BR-09', 'BR-19', 'ADR-024'],
    tasks: [
      task(
        'T1',
        'Campaign·CouponIssuance 관리와 발급 API 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/promotion/internal/domain/**',
          'src/main/kotlin/io/github/kdh949/beanflow/promotion/api/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          'FIXED_KRW·RATE_BPS 정책과 대상 메뉴를 모델링한다.',
          'PLATFORM·STORE·SHARED 비용 비율을 검증한다.',
          '사용자별·전체 발급 한도와 캠페인 시간 경계를 저장한다.',
        ],
        [
          '정액·정률 경계 테스트',
          '부담 비율 100% 검증',
          '캠페인 시간대 API 테스트',
        ],
      ),
      task(
        'T2',
        '쿠폰 발급 수량 조건부 UPDATE와 사용자 한도 제약 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/promotion/internal/CouponIssuanceService.kt',
          'src/main/resources/db/migration/V*__harden_coupon.sql',
        ],
        [
          'remaining>0 조건부 갱신으로 초과 발급을 막는다.',
          'member+campaign 발급 한도 Unique/guard를 추가한다.',
          '발급 실패를 SOLD_OUT·LIMIT_EXCEEDED 오류로 구분한다.',
        ],
        [
          '마지막 1장 100동시 요청 테스트',
          '사용자별 중복 발급 테스트',
          'rollback 후 수량 일치 테스트',
        ],
      ),
      task(
        'T3',
        '쿠폰 계산·예약·발급 동시성 부하 비교와 ADR 갱신',
        [
          'load-tests/coupon-concurrency.js',
          'docs/benchmarks/coupon-concurrency.md',
          'src/test/kotlin/io/github/kdh949/beanflow/promotion/**',
        ],
        [
          '낙관·비관·조건부 UPDATE를 같은 시나리오로 비교한다.',
          '대상/비대상 혼합 주문과 1원 배분을 회귀 테스트한다.',
          '실측 후 Redis 도입 재검토 조건을 명시한다.',
        ],
        [
          '초과 발급 0 검증',
          'p95·lock wait·retry 수 기록',
          '결과 기반 ADR 갱신',
        ],
        'type:spike',
      ),
    ],
  }),
  epic({
    id: 'E15',
    title: '현재 정책 재검증 기반 빠른 재주문',
    milestone: 'R3',
    priority: 'P0',
    areas: ['ordering', 'merchant', 'inventory', 'promotion', 'loyalty', 'api'],
    currentSource: [
      'OrderLine snapshot과 GET 주문 조회는 존재하지만 재주문 유스케이스는 없다.',
      '과거 가격·재고·쿠폰을 그대로 복사하면 현재 정책과 충돌한다.',
    ],
    invariants: [
      '과거 주문은 입력 편의로만 사용하고 현재 가격·판매 상태·옵션·재고·슬롯·혜택을 다시 검증한다.',
      '과거 snapshot은 수정하지 않는다.',
      '재주문도 새 Idempotency-Key와 새 Order ID를 사용한다.',
    ],
    decisionRefs: ['ADR-004', 'ADR-025', 'docs/product/end-to-end-flow.md'],
    tasks: [
      task(
        'T1',
        '재주문용 과거 주문 snapshot 조회 모델 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/api/ReorderQuery.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/**',
        ],
        [
          '과거 line 순서·menuId·optionIds·quantity만 재주문 입력으로 변환한다.',
          'JPA Entity를 API에 노출하지 않는다.',
          '삭제 메뉴도 과거 주문 조회는 가능하게 한다.',
        ],
        [
          '과거 snapshot 조회 테스트',
          '삭제 메뉴 표시 테스트',
          '목록 N+1 회귀 테스트',
        ],
      ),
      task(
        'T2',
        '현재 Merchant·재고·슬롯·혜택 재검증 후 새 주문 생성 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/ReorderService.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/api/**',
        ],
        [
          '기존 CreateOrderUseCase를 우회하지 않고 동일 quote·예약 경로를 재사용한다.',
          '사용 불가 쿠폰·포인트의 제외 또는 오류 정책을 API 계약으로 고정한다.',
          '현재 가격 차이를 응답에서 명시한다.',
        ],
        [
          '가격 변경 재주문 테스트',
          '품절·옵션 삭제 오류 테스트',
          '새 주문 멱등성 테스트',
        ],
      ),
      task(
        'T3',
        '재주문 API 계약과 고객 오류 시나리오 문서화',
        [
          'openapi/beanflow-v1.yaml',
          'src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/ReorderContractTest.kt',
          'docs/api/error-catalog.md',
        ],
        [
          'POST /orders/{orderId}/reorders 계약을 작성한다.',
          '원 주문 소유권과 현재 매장 주문 가능 상태를 검증한다.',
          '부분 성공이나 암묵적 품목 삭제를 금지한다.',
        ],
        [
          '다른 고객 원 주문 403/404 테스트',
          '현재 정책 충돌 409 테스트',
          'REST Docs 예제 생성',
        ],
      ),
    ],
  }),
];
