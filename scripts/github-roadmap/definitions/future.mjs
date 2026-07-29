import { epic, task } from '../core.mjs';

export const futureEpics = [
  epic({
    id: 'E30',
    title: 'P1 대표 확장: 선불 지갑·POS·점주 AI',
    milestone: 'P1',
    priority: 'P1',
    areas: ['wallet', 'pos', 'ai', 'analytics', 'operations'],
    risks: ['money', 'external-provider', 'data-consistency'],
    currentSource: [
      'MVP에서 Wallet·POS·AI 모듈은 의도적으로 제외되어 있다.',
      '각 확장은 별도 장애 모델과 검증 체계가 필요하므로 한 번에 모두 구현하지 않는다.',
    ],
    invariants: [
      '포인트와 충전금을 같은 balance로 합치지 않는다.',
      '프린터 실패가 주문을 롤백하지 않는다.',
      'AI는 가격·정산·환불을 인간 승인 없이 변경하지 않는다.',
    ],
    decisionRefs: ['docs/product/non-goals.md', 'ADR-011', 'BR-31', 'BR-32'],
    tasks: [
      task(
        'T1',
        '선불 지갑 ledger·부분 환불·reconciliation 확장',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/wallet/**',
          'docs/exec-plans/active/wallet.md',
        ],
        [
          'StoredValueAccount·immutable ledger·Charge·Spend·Refund를 설계한다.',
          'source reference와 account lock으로 이중 차감을 막는다.',
          'sandbox 범위와 실제 자금 비취급을 명시한다.',
        ],
        [
          '동시 이중 차감 테스트',
          '부분 환불 tie-out',
          '잔액·원장 reconciliation',
        ],
        'type:spike',
      ),
      task(
        'T2',
        'POS PrintJob·offline retry·수동 재출력 확장',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/pos/**',
          'docker/pos-emulator/**',
          'docs/exec-plans/active/pos.md',
        ],
        [
          'orderId:ticketType:revision 멱등키로 PrintJob을 생성한다.',
          'ACK 유실·offline·용지 없음 상태를 Adapter로 번역한다.',
          '재출력은 새 revision과 감사 기록을 남긴다.',
        ],
        [
          'TCP emulator 계약 테스트',
          '중복 인쇄 방지 테스트',
          'manual reprint E2E',
        ],
        'type:spike',
      ),
      task(
        'T3',
        '매출 Read Model 기반 점주 AI 브리핑·평가 확장',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/intelligence/**',
          'evals/merchant-briefing/**',
          'docs/exec-plans/active/merchant-ai.md',
        ],
        [
          '승인된 Analytics query만 Tool로 노출한다.',
          '응답에 숫자·기간·근거를 포함한다.',
          '고정 평가셋, prompt/model/schema version과 human approval을 둔다.',
        ],
        [
          '숫자·기간 일치 평가',
          '금지 자동 실행 0 테스트',
          'latency·비용·수정률 측정',
        ],
        'type:spike',
      ),
    ],
  }),
  epic({
    id: 'E31',
    title: 'P2/P3 진화: Kafka 분리·딜리버리·개인화',
    milestone: 'P2',
    priority: 'P2',
    areas: ['events', 'delivery', 'personalization', 'platform'],
    risks: ['external-provider', 'privacy', 'data-consistency'],
    currentSource: [
      '현재는 Spring Modulith 단일 배포이며 Kafka·Delivery·Personalization은 도입 근거가 측정되지 않았다.',
      '아키텍처는 독립 소비자·replay·배포 격리 요구가 생길 때만 물리 분리를 재검토한다.',
    ],
    invariants: [
      '기술 도입 자체가 목표가 아니다.',
      '모든 메시지 소비자는 중복 전달을 가정한다.',
      '배달·개인화는 동의·외부 상태 reconciliation과 별도 Aggregate 경계를 가진다.',
    ],
    decisionRefs: ['ADR-001', 'ADR-010', 'docs/architecture/architecture-overview.md', 'docs/product/non-goals.md'],
    tasks: [
      task(
        'T1',
        'Transactional Outbox·Consumer Inbox와 모듈 추출 기준 실험',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/shared/events/**',
          'docker/kafka/**',
          'docs/benchmarks/event-extraction.md',
        ],
        [
          '현재 영속 publication과 Kafka Outbox를 비교한다.',
          'eventId inbox unique·retry·DLQ·replay를 구현한다.',
          'Notification 또는 Settlement 독립 배포의 운영 이득과 비용을 측정한다.',
        ],
        [
          '중복 소비 부수효과 1회 테스트',
          'DB commit/Kafka publish 장애 테스트',
          'consumer lag·replay 측정',
        ],
        'type:spike',
      ),
      task(
        'T2',
        'DeliveryFulfillment·외부 배달사 상태 reconciliation 실험',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/delivery/**',
          'docs/exec-plans/active/delivery.md',
        ],
        [
          'PICKUP과 DELIVERY 공통 언어와 다른 상태를 분리한다.',
          '외부 webhook·polling 충돌 우선순위를 정의한다.',
          '배차 실패·기사 취소·재배차·환불을 모의 Adapter로 검증한다.',
        ],
        [
          '중복 webhook 테스트',
          '상태 역전 reconciliation',
          'Provider timeout manual review',
        ],
        'type:spike',
      ),
      task(
        'T3',
        '동의 기반 세그먼트·frequency cap·A/B·attribution 실험',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/personalization/**',
          'evals/personalization/**',
          'docs/exec-plans/active/personalization.md',
        ],
        [
          '수집·개인화·광고 동의와 철회를 선행한다.',
          '규칙 기반 baseline과 serving API를 구현한다.',
          '노출 빈도·holdout·노출/클릭/주문 attribution을 검증한다.',
        ],
        [
          '동의 철회 즉시 제외 테스트',
          'frequency cap 동시성 테스트',
          'A/B·attribution 재현 테스트',
        ],
        'type:spike',
      ),
    ],
  }),
];
