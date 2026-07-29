export const ROADMAP_VERSION = '2026-07-29-v1';

export const labels = [
  ['type:roadmap', '5319E7', '전체 개발 로드맵 인덱스'],
  ['type:epic', '8250DF', '여러 작업을 묶는 에픽'],
  ['type:task', '0E8A16', '구현 가능한 작업 단위'],
  ['type:spike', 'FBCA04', '측정·실험·조사 작업'],
  ['type:docs', '0075CA', '계약·ADR·운영 문서 작업'],
  ['priority:P0', 'B60205', 'MVP 필수'],
  ['priority:P1', 'D93F0B', 'MVP 이후 대표 확장'],
  ['priority:P2', 'FBCA04', '장기 확장 또는 재검토'],
  ...[
    'identity', 'merchant', 'discovery', 'ordering', 'fulfillment', 'inventory',
    'promotion', 'loyalty', 'payment', 'events', 'notification', 'settlement',
    'dispute', 'analytics', 'operations', 'api', 'security', 'persistence',
    'performance', 'platform', 'wallet', 'pos', 'ai', 'delivery', 'personalization',
  ].map((name) => [`area:${name}`, '1D76DB', `${name} 영역`]),
  ['risk:money', 'E99695', '금액·원장·정산 정합성 위험'],
  ['risk:concurrency', 'E99695', '동시성·잠금·중복 위험'],
  ['risk:external-provider', 'E99695', '외부 Provider 장애 위험'],
  ['risk:privacy', 'E99695', '개인정보·민감정보 위험'],
  ['risk:data-consistency', 'E99695', '이벤트·Read Model 정합성 위험'],
];

export const milestones = [
  {
    key: 'R1',
    title: 'R1 — 주문 진입·매장 운영',
    dueOn: '2026-08-16T14:59:59Z',
    description: '회원·매장·검색·픽업 슬롯·기초 재고를 완성한다.',
  },
  {
    key: 'R2',
    title: 'R2 — 주문 생명주기·결제 완성',
    dueOn: '2026-08-30T14:59:59Z',
    description: '결제 이후 주문 처리, 결제수단, PG Adapter, 취소·환불을 완성한다.',
  },
  {
    key: 'R3',
    title: 'R3 — 이벤트·알림·로열티',
    dueOn: '2026-09-13T14:59:59Z',
    description: '영속 이벤트, 준비 완료 알림, 포인트, 프로모션과 재주문을 완성한다.',
  },
  {
    key: 'R4',
    title: 'R4 — 정산·이의제기·분석',
    dueOn: '2026-09-27T14:59:59Z',
    description: '정산 Item·Batch·Adjustment·Dispute와 매출 Read Model을 완성한다.',
  },
  {
    key: 'R5',
    title: 'R5 — 계약·보안·성능·릴리스',
    dueOn: '2026-10-11T14:59:59Z',
    description: 'API 계약, 구조 품질, 관측, 성능, 장애 검증과 공개 릴리스를 마친다.',
  },
  {
    key: 'P1',
    title: 'P1 — 대표 확장',
    dueOn: '2026-11-08T14:59:59Z',
    description: '선불 지갑, POS 또는 점주 AI 중 검증 가능한 확장을 선택한다.',
  },
  {
    key: 'P2',
    title: 'P2/P3 — Future Work',
    dueOn: null,
    description: 'Kafka 기반 분리, 딜리버리, 개인화·광고의 재검토 조건을 관리한다.',
  },
];

export function task(id, title, files, steps, tests, type = 'type:task', docs = []) {
  return { id, title, files, steps, tests, type, docs };
}

export function epic({
  id,
  title,
  milestone,
  priority,
  areas,
  risks = [],
  currentSource,
  invariants,
  decisionRefs,
  tasks,
}) {
  return {
    id,
    title,
    milestone,
    priority,
    areas,
    risks,
    currentSource,
    invariants,
    decisionRefs,
    tasks,
  };
}
