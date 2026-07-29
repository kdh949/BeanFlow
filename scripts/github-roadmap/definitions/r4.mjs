import { epic, task } from '../core.mjs';

export const r4Epics = [
  epic({
    id: 'E16',
    title: 'OrderCompleted 기반 SettlementItem 생성',
    milestone: 'R4',
    priority: 'P0',
    areas: ['settlement', 'ordering', 'payment', 'promotion', 'loyalty', 'persistence'],
    risks: ['money', 'data-consistency'],
    currentSource: [
      'Settlement 모듈 구현과 테이블은 아직 없다.',
      'event catalog와 BR-16은 PaymentApproved가 아니라 OrderCompleted를 Item 생성 기준으로 정한다.',
    ],
    invariants: [
      'source order/type당 SettlementItem은 하나다.',
      '거래 당시 수수료·쿠폰 부담·PointLot 발급 주체 snapshot으로 정산을 재현한다.',
      '완료되지 않은 주문은 정산 대상이 아니다.',
    ],
    decisionRefs: ['BR-16', 'BR-18', 'BR-19', 'BR-20', 'ADR-017'],
    tasks: [
      task(
        'T1',
        'SettlementItem Aggregate·계산 값·Flyway 스키마 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/domain/**',
          'src/main/resources/db/migration/V*__create_settlement.sql',
        ],
        [
          'gross·cash·refund·coupon shares·point issuer cost·fee·net을 정수 KRW로 저장한다.',
          'sourceOrderId+type unique와 금액 합계 CHECK를 추가한다.',
          'Order Entity를 객체 연관관계로 연결하지 않는다.',
        ],
        [
          '계산 단위 테스트',
          'DB 금액 제약 테스트',
          '수수료 snapshot 재현 테스트',
        ],
      ),
      task(
        'T2',
        'OrderCompleted 멱등 소비자와 SettlementItem 생성 서비스 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/OrderCompletedSettlementHandler.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/settlement/api/**',
        ],
        [
          'eventId와 sourceOrderId를 최종 중복 방어로 사용한다.',
          '필요한 주문 snapshot을 공개 API 또는 event payload에서 받는다.',
          '실패 시 publication을 완료 처리하지 않는다.',
        ],
        [
          '중복 완료 이벤트 Item 1개 테스트',
          'consumer 재시작 테스트',
          '완료 전 주문 제외 테스트',
        ],
      ),
      task(
        'T3',
        '혜택 비용·수수료·환불 전 정산 tie-out 테스트',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/settlement/**',
          'docs/testing/settlement-tieout.md',
        ],
        [
          '쿠폰 PLATFORM/STORE/SHARED와 PointLot issuer 비용을 검증한다.',
          '항목별 합계가 net과 일치하는지 자동화한다.',
          '자정 완료 경계를 Asia/Seoul로 검증한다.',
        ],
        [
          '혼합 혜택 주문 테스트',
          '1원 배분 테스트',
          '결제일과 완료일이 다른 주문 테스트',
        ],
      ),
    ],
  }),
  epic({
    id: 'E17',
    title: '일별 SettlementBatch 계산·확정·재실행',
    milestone: 'R4',
    priority: 'P0',
    areas: ['settlement', 'operations', 'persistence', 'performance'],
    risks: ['money', 'concurrency'],
    currentSource: [
      'SettlementBatch 구현은 아직 없고 아키텍처는 Item 전체를 JPA 컬렉션으로 소유하지 않도록 결정했다.',
      'BR-17은 매장별 전일 완료 주문의 일별 내부 정산을 요구한다.',
    ],
    invariants: [
      'store+settlementDate는 유일하다.',
      'OPEN→CALCULATED→CONFIRMED 전이만 허용하고 CONFIRMED는 직접 수정하지 않는다.',
      '배치 중단·재실행 결과가 동일해야 한다.',
    ],
    decisionRefs: ['BR-17', 'BR-21', 'ADR-008', 'ADR-017'],
    tasks: [
      task(
        'T1',
        'SettlementBatch Aggregate·집계 Query Repository·스키마 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/**',
          'src/main/resources/db/migration/V*__create_settlement_batch.sql',
        ],
        [
          'Batch는 Item ID 컬렉션을 로딩하지 않고 집계 쿼리를 사용한다.',
          'store/date unique와 optimistic version을 둔다.',
          'carry-forward adjustment 합계를 별도 Query Repository로 조회한다.',
        ],
        [
          'Aggregate 전이 단위 테스트',
          '집계 SQL Repository 테스트',
          '동일 store/date 중복 생성 테스트',
        ],
      ),
      task(
        'T2',
        '전일 chunk 계산 worker와 확정 API 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/SettlementBatchWorker.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/settlement/api/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          'Asia/Seoul 전일 구간을 Instant로 변환한다.',
          '매장별 작은 트랜잭션과 결정적 cursor로 chunk 처리한다.',
          '확정 전 Item 합계·조정·held 금액을 검증한다.',
        ],
        [
          '자정 경계 테스트',
          '여러 매장 병렬 worker 테스트',
          '확정 권한 계약 테스트',
        ],
      ),
      task(
        'T3',
        '배치 중단·재실행·대량 데이터 성능 검증',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/settlement/**',
          'load-tests/settlement-batch.js',
          'docs/benchmarks/settlement-batch.md',
        ],
        [
          'worker 중간 실패 후 같은 Batch를 재개한다.',
          'Item 수 증가에 따른 시간·쿼리·heap을 측정한다.',
          'Bulk SQL 후 persistence context 오염을 방지한다.',
        ],
        [
          '중복 Item/합계 변화 0 테스트',
          '1만·10만 Item 실행계획',
          '중단 복구 보고서 작성',
        ],
        'type:spike',
      ),
    ],
  }),
  epic({
    id: 'E18',
    title: '확정 정산 Adjustment 원장과 음수 이월',
    milestone: 'R4',
    priority: 'P0',
    areas: ['settlement', 'payment', 'dispute', 'operations'],
    risks: ['money', 'data-consistency'],
    currentSource: [
      'ADR-008은 확정 Batch를 덮어쓰지 않고 SettlementAdjustment를 추가하도록 결정했지만 구현은 없다.',
      'BR-21은 음수 조정을 다음 Batch로 이월한다.',
    ],
    invariants: [
      '확정 Batch·Item은 직접 수정하지 않는다.',
      'source reason/reference당 Adjustment는 하나다.',
      '음수 잔액은 다음 Batch로 결정적으로 이월하고 연속 음수도 보존한다.',
    ],
    decisionRefs: ['BR-21', 'ADR-008', 'ADR-017'],
    tasks: [
      task(
        'T1',
        'SettlementAdjustment Aggregate·append-only 스키마 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/domain/SettlementAdjustment.kt',
          'src/main/resources/db/migration/V*__create_settlement_adjustment.sql',
        ],
        [
          'target batch/item, source type/reference, signed amount, actor, reason을 필수로 둔다.',
          'source type+reference unique를 추가한다.',
          '애플리케이션 수정·삭제 API를 제공하지 않는다.',
        ],
        [
          '도메인 필수값 테스트',
          '중복 source DB 테스트',
          'append-only Repository 테스트',
        ],
      ),
      task(
        'T2',
        '확정 후 환불·이의 판정 Adjustment와 다음 Batch 이월 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/SettlementAdjustmentService.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/SettlementBatchWorker.kt',
        ],
        [
          'PaymentRefunded·Dispute accepted 명령을 각 source reference로 처리한다.',
          '확정 전이면 Item 반영, 확정 후면 Adjustment를 생성한다.',
          '미상환 음수 balance를 다음 Batch 계산에 포함한다.',
        ],
        [
          '확정 전/후 환불 분기 테스트',
          '연속 음수 이월 테스트',
          '중복 이벤트 Adjustment 1개 테스트',
        ],
      ),
      task(
        'T3',
        '정산 조정 금액 tie-out·감사·조회 API 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/settlement/api/**',
          'src/test/kotlin/io/github/kdh949/beanflow/settlement/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          '원 Batch·Adjustment·carry-forward를 분리해 조회한다.',
          '조정 생성 actor/reason/correlation을 AuditRecord에 남긴다.',
          '총 지급가능액 계산식을 API·문서에 명시한다.',
        ],
        [
          '부분·전액 환불 tie-out 테스트',
          '조정 조회 계약 테스트',
          '감사 원자성 테스트',
        ],
      ),
    ],
  }),
  epic({
    id: 'E19',
    title: '정산 이의제기·held 금액·재이의',
    milestone: 'R4',
    priority: 'P0',
    areas: ['dispute', 'settlement', 'operations', 'notification', 'api'],
    risks: ['money', 'concurrency'],
    currentSource: [
      'Dispute 모듈과 API는 아직 없다.',
      'BR-22~24와 ADR-018은 접수 기간, Item당 진행 중 하나, held 금액, 새 증빙 기반 1회 재이의를 결정했다.',
    ],
    invariants: [
      '미확정 Batch Item에는 이의를 제기할 수 없다.',
      '접수 기간은 [D+1 00:00,D+15 00:00) Asia/Seoul이다.',
      'Item당 active dispute 하나이며 판정 완료를 Adjustment 성공으로 가장하지 않는다.',
    ],
    decisionRefs: ['BR-22', 'BR-23', 'BR-24', 'ADR-018'],
    tasks: [
      task(
        'T1',
        'SettlementDispute·HeldAmount 모델과 partial unique 스키마 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/dispute/**',
          'src/main/resources/db/migration/V*__create_dispute.sql',
        ],
        [
          'FILED·UNDER_REVIEW·ACCEPTED·REJECTED·ADJUSTMENT_PENDING 상태를 정의한다.',
          'active item partial unique와 refileCount<=1을 둔다.',
          'evidence reference와 previousDisputeId를 저장한다.',
        ],
        [
          '상태 전이 단위 테스트',
          'active 중복 DB 테스트',
          '재이의 제약 테스트',
        ],
      ),
      task(
        'T2',
        '이의제기 접수·판정·재이의 API와 Settlement 명령 연계',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/dispute/api/**',
          'src/main/kotlin/io/github/kdh949/beanflow/dispute/internal/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          '점주 membership과 확정 Item 소유권을 검증한다.',
          '접수 시 대상 예상액만 HELD로 관리한다.',
          '수용 판정 후 Adjustment 명령 실패 시 ADJUSTMENT_PENDING을 유지한다.',
        ],
        [
          '기간 경계 계약 테스트',
          '판정과 Adjustment 실패 테스트',
          '새 증빙 없는 재이의 거부 테스트',
        ],
      ),
      task(
        'T3',
        '이의제기 동시성·알림·운영 추적 E2E 테스트',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/dispute/**',
          'src/main/kotlin/io/github/kdh949/beanflow/notification/**',
          'docs/operations/runbooks/dispute.md',
        ],
        [
          '동시 접수에서 하나만 성공한다.',
          '판정·held 해제·알림을 각각 멱등 처리한다.',
          '운영자가 dispute→adjustment→notification 흐름을 correlationId로 추적한다.',
        ],
        [
          '동시 접수 테스트',
          '중복 판정 테스트',
          '운영 E2E·수동 복구 테스트',
        ],
      ),
    ],
  }),
  epic({
    id: 'E20',
    title: '매출 Analytics Read Model·late event 재집계',
    milestone: 'R4',
    priority: 'P0',
    areas: ['analytics', 'events', 'operations', 'performance'],
    risks: ['data-consistency', 'money'],
    currentSource: [
      'Analytics 모듈과 Read Model은 아직 없다.',
      'BR-31은 환불 발생일 지표와 원 주문 완료일 보정 지표를 분리하고, BR-32는 7일 수정 window를 정한다.',
    ],
    invariants: [
      '지표 정의·기간·시간대를 이름으로 구분한다.',
      'eventId/refundId/source day로 중복 반영을 막는다.',
      '7일 초과 late event는 자동 수정하지 않고 BACKFILL_REQUIRED를 생성한다.',
    ],
    decisionRefs: ['BR-31', 'BR-32', 'ADR-023', 'docs/architecture/event-catalog.md'],
    tasks: [
      task(
        'T1',
        'Analytics event inbox와 일별·메뉴별 Read Model 스키마 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/analytics/**',
          'src/main/resources/db/migration/V*__create_analytics.sql',
        ],
        [
          'source eventId+payloadVersion inbox를 둔다.',
          'store/day/menu 집계 테이블과 source freshness를 저장한다.',
          '중복·순서 역전을 허용하는 멱등 upsert를 구현한다.',
        ],
        [
          '중복 이벤트 멱등 테스트',
          '순서 역전 재계산 테스트',
          'Repository upsert 테스트',
        ],
      ),
      task(
        'T2',
        '매출·주문수·객단가·환불률·인기 메뉴·예상 정산 API 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/analytics/api/**',
          'src/main/kotlin/io/github/kdh949/beanflow/analytics/internal/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          '총매출·순매출·환불 발생일·원주문일 보정 지표를 분리한다.',
          '날짜 cursor/기간 상한과 store ownership을 적용한다.',
          '쓰기 Aggregate를 로딩하지 않는 Projection을 사용한다.',
        ],
        [
          '지표 정의 예제 테스트',
          '과거 주문 당일 환불 테스트',
          '조회 권한·기간 검증',
        ],
      ),
      task(
        'T3',
        '7일 late event 재집계와 승인형 backfill 운영 흐름 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/analytics/internal/AnalyticsRebuildService.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/operations/**',
          'docs/benchmarks/analytics-read-model.md',
        ],
        [
          '7일 이내 day를 야간 재집계 queue에 넣는다.',
          '7일 초과 이벤트는 source event/day unique ReprocessingCase를 만든다.',
          '승인 후 chunk backfill을 중단·재실행 가능하게 한다.',
        ],
        [
          '7일 경계 Clock 테스트',
          'backfill 중단·재실행 테스트',
          '집계 실행계획·시간 측정',
        ],
        'type:spike',
      ),
    ],
  }),
];
