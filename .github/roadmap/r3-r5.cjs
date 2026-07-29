const T = (key, title, goal, work, acceptance, tests, files = [], docs = [], labels = [], type = 'type:task') => ({ key, title, goal, work, acceptance, tests, files, docs, labels, type });

module.exports = [
  {
    key: 'E15',
    title: '완료 주문 정산 항목과 일별 정산 배치',
    milestone: 'R3 — 정산·이의제기·분석',
    priority: 'priority:P0',
    areas: ['area:settlement', 'area:events', 'area:persistence', 'area:api'],
    risks: ['risk:money', 'risk:data-consistency'],
    goal: 'OrderCompleted를 유일한 정산 기준 사실로 삼아 주문 단위 SettlementItem을 만들고 매장·완료일별 일일 배치를 재실행 가능하게 계산·확정한다.',
    sources: ['docs/adr/ADR-017-settlement-calculation-and-cost-allocation.md', 'docs/architecture/transaction-boundaries.md', 'docs/architecture/context-map.md', 'docs/product/business-policy-decisions.md', 'docs/architecture/event-catalog.md'],
    invariants: ['PaymentApproved만으로 SettlementItem을 만들지 않는다.', '완료일은 Asia/Seoul의 Order.completedAt 기준이다.', '원천 order/type당 SettlementItem은 하나다.', 'SettlementBatch는 모든 Item을 JPA 컬렉션으로 소유하지 않고 확정 후 직접 수정하지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/settlement/**', 'src/main/resources/db/migration/V20__create_settlement.sql', 'src/test/kotlin/io/github/kdh949/beanflow/settlement/**'],
    docs: ['docs/adr/ADR-017-settlement-calculation-and-cost-allocation.md', 'docs/architecture/aggregate-invariants.md', 'docs/operations/runbooks/settlement-batch.md', 'openapi/beanflow-v1.yaml'],
    done: ['Settlement 모듈과 Item/Batch Aggregate가 구현된다.', '수수료·쿠폰·포인트 부담 snapshot으로 Item 금액을 재현한다.', '일별 batch가 chunk·재실행·중단 복구 가능하다.', '점주 정산 조회와 확정 API·금액 tie-out 테스트가 통과한다.'],
    dependsOn: ['E08', 'E10', 'E12', 'E14'],
    tasks: [
      T('T1', 'SettlementItem 모델과 OrderCompleted 멱등 소비 구현', '완료 주문의 주문·결제·혜택·계약 snapshot을 정산 원장 항목으로 변환한다.', ['SettlementItem에 sourceOrderId, storeId, completionDate, gross/cash/refund/couponCost/pointCost/fee/net을 정수 KRW로 저장한다.', '수수료율은 현재 계약 재조회가 아니라 주문 또는 event snapshot을 사용한다.', '쿠폰 비용은 PLATFORM/STORE/SHARED, 포인트 비용은 PointLot issuer별로 배분한다.', 'source order/type Unique Constraint와 event ID 처리 기록으로 중복 소비를 막는다.'], ['완료되지 않은 주문과 PaymentApproved만 있는 주문은 Item이 없다.', '합계식 `cash - store coupon cost - store point cost - fee - refunds = net`이 성립한다.', '같은 OrderCompleted를 반복해도 Item이 하나다.'], ['정산 계산 단위 테스트', '중복 event PostgreSQL 통합 테스트', '자정 완료일·계약 변경 snapshot 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/domain/SettlementItem.kt', 'src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/**', 'src/main/resources/db/migration/V20__create_settlement.sql']),
      T('T2', '매장·일자 SettlementBatch 계산·확정 worker 구현', '전일 완료 Item을 제한된 chunk로 집계하고 재실행 가능한 상태 머신으로 관리한다.', ['Batch 상태 OPEN→CALCULATED→CONFIRMED와 store/date Unique Constraint를 구현한다.', 'Asia/Seoul 전일 범위를 Instant 반개구간으로 계산한다.', 'Item 전체 Entity 컬렉션을 로딩하지 않고 집계 Query Repository를 사용한다.', 'worker 중단·재실행 시 이미 계산/확정된 source를 중복 반영하지 않는다.'], ['같은 매장·일자 재실행 결과가 동일하다.', '자정 경계 주문이 정확한 날짜에 한 번 포함된다.', '여러 매장 병렬 처리에서 같은 Batch를 두 worker가 확정하지 않는다.'], ['Clock 자정 경계 테스트', 'chunk 중단·재시작 테스트', '동일 batch 동시 실행 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/*Batch*', 'src/test/kotlin/io/github/kdh949/beanflow/settlement/**']),
      T('T3', '점주 정산 조회·확정 API와 금액 tie-out 검증', '점주가 자신 매장의 배치·항목·계산 근거를 조회하고 운영자가 확정하도록 한다.', ['점주용 batch 목록, batch 상세, item cursor 조회를 DTO Projection으로 구현한다.', 'StoreAccessPolicy로 다른 매장 정산 접근을 차단한다.', '확정 권한과 상태 충돌을 명시하고 AuditRecord를 남긴다.', 'Item 합계와 Batch 요약을 SQL 및 Application 검증으로 tie-out한다.'], ['확정 후 Item/Batch 금액 수정 API가 없다.', '목록 조회에서 N+1·collection fetch pagination 문제가 없다.', '금액 불일치 시 확정을 실패시키고 운영 case를 남긴다.'], ['MockMvc 인가·상태 계약 테스트', 'Projection SQL count 테스트', '의도적 tie-out 실패 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/*Controller.kt', 'src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/*QueryRepository.kt', 'openapi/beanflow-v1.yaml'], ['docs/operations/runbooks/settlement-batch.md', 'openapi/beanflow-v1.yaml'])
    ]
  },
  {
    key: 'E16',
    title: '정산 조정 원장과 음수 이월',
    milestone: 'R3 — 정산·이의제기·분석',
    priority: 'priority:P0',
    areas: ['area:settlement', 'area:payment', 'area:operations'],
    risks: ['risk:money', 'risk:data-consistency'],
    goal: '확정 정산 뒤 환불·판정이 발생해도 과거 Batch를 덮어쓰지 않고 SettlementAdjustment를 append-only로 생성해 다음 배치에 상계한다.',
    sources: ['docs/adr/ADR-008-settlement-adjustment-ledger.md', 'docs/adr/ADR-017-settlement-calculation-and-cost-allocation.md', 'docs/product/business-policy-decisions.md', 'docs/architecture/transaction-boundaries.md'],
    invariants: ['CONFIRMED Batch와 Item을 직접 수정하지 않는다.', '조정에는 대상·원인·금액·actor/source reference가 필수다.', '원천 refund/reason reference당 조정은 하나다.', '음수 잔액은 다음 Batch로 이월하며 실제 청구·지급을 구현했다고 주장하지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/settlement/**', 'src/main/resources/db/migration/V21__create_settlement_adjustments.sql', 'src/test/kotlin/io/github/kdh949/beanflow/settlement/**'],
    docs: ['docs/adr/ADR-008-settlement-adjustment-ledger.md', 'docs/operations/runbooks/settlement-adjustment.md', 'openapi/beanflow-v1.yaml'],
    done: ['SettlementAdjustment Aggregate와 source unique 제약이 구현된다.', '확정 전 환불은 Item, 확정 후 환불은 Adjustment로 분기된다.', '음수 조정이 다음 배치에 결정적으로 이월된다.', '운영자 수동 조정과 감사·인가·재실행 테스트가 통과한다.'],
    dependsOn: ['E14', 'E15'],
    tasks: [
      T('T1', 'SettlementAdjustment append-only 모델과 환불 소비 구현', 'PaymentRefunded가 정산 상태에 따라 Item 보정 또는 Adjustment를 한 번만 만들게 한다.', ['Adjustment type, target item/batch, source refund/reason, signed amount, actor/reason, occurredAt을 모델링한다.', '미확정 Item은 환불 fact를 반영하고 확정 Item은 별도 negative Adjustment를 생성한다.', 'source reason/reference Unique Constraint를 추가한다.', 'Adjustment 변경·삭제 API를 제공하지 않는다.'], ['같은 refund event 재처리가 조정을 중복 생성하지 않는다.', 'CONFIRMED Item/Batch row가 UPDATE되지 않는다.', '부분 환불의 수수료·쿠폰·포인트 부담 조정 합계가 원 snapshot과 일치한다.'], ['정산 전/후 환불 분기 테스트', '중복 refund consumer 테스트', 'append-only Repository/SQL 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/domain/SettlementAdjustment.kt', 'src/main/resources/db/migration/V21__create_settlement_adjustments.sql']),
      T('T2', '음수 정산 잔액 다음 배치 이월·연속 상계 구현', '매장별 미상계 adjustment를 다음 일별 배치에 순서대로 반영한다.', ['미상계 adjustment를 storeId/occurredAt/id 순서로 조회한다.', 'Batch 계산 시 carriedAdjustmentKrw와 적용 source 목록을 별도 mapping으로 저장한다.', '한 배치에서 다 상계되지 않으면 잔여 금액을 다음 배치로 이어간다.', '동시 batch 계산이 같은 Adjustment를 두 번 소비하지 않도록 lock/source mapping unique를 둔다.'], ['한 adjustment가 여러 batch에 중복 전액 반영되지 않는다.', '연속 음수 이월과 이후 양수 매출 상계 결과가 재실행해도 같다.', 'Batch 확정 실패 시 adjustment 소비 표시가 함께 rollback된다.'], ['연속 이월 계산 단위 테스트', 'batch 동시 실행 통합 테스트', '중단·재실행 tie-out 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/*CarryForward*', 'src/test/kotlin/io/github/kdh949/beanflow/settlement/**']),
      T('T3', '운영자 수동 정산 조정 API와 감사·조회 구현', '자동 원천이 없는 예외 조정을 승인된 운영 명령으로만 추가한다.', ['운영자 전용 Adjustment 생성 API에 reason, evidenceReference, target, signed amount를 요구한다.', 'actor·before/after·correlationId AuditRecord를 같은 tx에 남긴다.', '점주 조회에는 조정 종류·금액·사유 코드·적용 batch를 노출하되 내부 민감 메모는 분리한다.', 'Idempotency-Key와 source reference 충돌을 409로 처리한다.'], ['권한 없는 점주가 수동 조정을 만들 수 없다.', '필수 reason/evidence 없는 요청은 400이다.', '동일 idempotency key 재요청이 같은 Adjustment 응답을 반환한다.'], ['운영자 권한·API 계약 테스트', 'AuditRecord 원자성 테스트', 'idempotency concurrency 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/*AdjustmentController.kt', 'src/main/kotlin/io/github/kdh949/beanflow/operations/**', 'openapi/beanflow-v1.yaml'], ['docs/operations/runbooks/settlement-adjustment.md', 'openapi/beanflow-v1.yaml'])
    ]
  },
  {
    key: 'E17',
    title: '정산 이의제기·보류 금액·재이의',
    milestone: 'R3 — 정산·이의제기·분석',
    priority: 'priority:P0',
    areas: ['area:dispute', 'area:settlement', 'area:operations', 'area:api'],
    risks: ['risk:money', 'risk:data-consistency'],
    goal: '확정 SettlementItem에 대해 정해진 기간의 이의제기, 대상 조정 예상액 HELD, 운영 판정과 새 증빙 1회 재이의를 별도 Dispute Context로 구현한다.',
    sources: ['docs/adr/ADR-018-settlement-dispute-hold-and-refile.md', 'docs/product/business-policy-decisions.md', 'docs/architecture/context-map.md', 'docs/architecture/aggregate-invariants.md'],
    invariants: ['접수 기간은 확정일 D 기준 `[D+1 00:00, D+15 00:00)` Asia/Seoul이다.', 'Item당 진행 중 Dispute는 하나다.', 'Batch 전체가 아니라 대상 예상 조정액만 HELD로 관리한다.', 'Dispute 판정과 SettlementAdjustment 생성은 별도 tx이며 조정 실패를 판정 완료로 위장하지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/dispute/**', 'src/main/resources/db/migration/V22__create_settlement_disputes.sql', 'src/test/kotlin/io/github/kdh949/beanflow/dispute/**'],
    docs: ['docs/adr/ADR-018-settlement-dispute-hold-and-refile.md', 'docs/operations/runbooks/settlement-dispute.md', 'openapi/beanflow-v1.yaml'],
    done: ['Dispute Aggregate·HELD amount·active partial unique가 구현된다.', '점주 접수·조회와 운영자 판정 API가 객체 수준 인가를 가진다.', '승인 판정이 SettlementAdjustment를 멱등 요청한다.', '기간 경계·중복·재이의·조정 실패 테스트가 통과한다.'],
    dependsOn: ['E01', 'E15', 'E16'],
    tasks: [
      T('T1', 'SettlementDispute Aggregate·HELD 금액·DB 제약 구현', 'Dispute Context가 workflow와 보류 예상액을 소유하도록 한다.', ['FILED/UNDER_REVIEW/APPROVED/REJECTED/ADJUSTMENT_PENDING/COMPLETED 상태를 정의한다.', 'settlementItemId, storeId, filedAt, reasonCode, evidenceReference, heldAmount, previousDisputeId/refileCount를 저장한다.', '진행 중 Item당 하나 partial unique와 refileCount<=1 제약을 둔다.', 'Settlement Entity를 객체 연관관계로 가져오지 않고 ID와 공개 조회 API를 사용한다.'], ['미확정 Item과 기간 밖 Item은 Dispute를 만들지 못한다.', '여러 동시 접수 중 하나만 active가 된다.', 'HELD 합계가 Batch 원본 금액을 직접 수정하지 않는다.'], ['상태 머신 단위 테스트', 'partial unique 동시성 테스트', 'Settlement 공개 API 계약 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/dispute/**', 'src/main/resources/db/migration/V22__create_settlement_disputes.sql']),
      T('T2', '점주 이의제기 접수·조회와 14일 경계 구현', '점주가 자신의 확정 Item에 증빙과 사유를 제출하도록 한다.', ['`POST /settlement-items/{itemId}/disputes`와 목록/상세 API를 구현한다.', 'StoreAccessPolicy와 Item storeId를 함께 검증한다.', 'Clock을 주입해 D+1 시작과 D+15 시작 반개구간을 계산한다.', '중복 접수·기간 만료·미확정 Item 오류 코드를 분리한다.'], ['다른 매장 Item 접수·조회가 차단된다.', 'D+1 00:00은 허용되고 D+15 00:00부터 거부된다.', '증빙 원문 대신 reference와 허용 metadata만 저장한다.'], ['Asia/Seoul 경계 테스트', '객체 수준 인가 테스트', 'MockMvc 오류 계약 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/dispute/internal/*Controller.kt', 'src/main/kotlin/io/github/kdh949/beanflow/dispute/internal/*Service.kt', 'openapi/beanflow-v1.yaml']),
      T('T3', '운영 판정·Adjustment 연결·1회 재이의 구현', '판정 결과가 Settlement owner 명령으로 안전하게 반영되고 실패가 재처리되게 한다.', ['운영자 승인/거절 명령과 필수 reason·evidence를 구현한다.', '승인 시 adjustment command source를 disputeId로 고정한다.', 'Adjustment 실패 시 ADJUSTMENT_PENDING과 ReprocessingCase를 남기고 완료로 표시하지 않는다.', '종결 Dispute는 새 evidenceReference와 previousDisputeId가 있을 때 한 번만 재이의한다.'], ['중복 승인 명령이 Adjustment를 하나만 만든다.', '조정 실패 후 재처리 성공 시 Dispute가 COMPLETED로 전이한다.', '두 번째 재이의와 새 증빙 없는 재이의는 거부된다.'], ['판정 idempotency 테스트', 'Settlement command failure/retry 테스트', '재이의 횟수·증빙 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/dispute/internal/*Decision*', 'src/main/kotlin/io/github/kdh949/beanflow/operations/**'], ['docs/operations/runbooks/settlement-dispute.md'])
    ]
  },
  {
    key: 'E18',
    title: '기초 매출 Analytics Read Model과 재집계',
    milestone: 'R3 — 정산·이의제기·분석',
    priority: 'priority:P0',
    areas: ['area:analytics', 'area:events', 'area:performance', 'area:operations'],
    risks: ['risk:data-consistency', 'risk:money'],
    goal: '원본 거래 Aggregate를 대규모 객체 그래프로 읽지 않고 멱등 이벤트 projection으로 매장 매출·환불·인기 메뉴·예상 정산 지표를 제공한다.',
    sources: ['docs/adr/ADR-023-analytics-refund-and-late-events.md', 'docs/product/business-policy-decisions.md', 'docs/architecture/context-map.md', 'docs/architecture/event-catalog.md'],
    invariants: ['Analytics는 원본 거래를 수정하지 않는 Read Model이다.', 'source event ID와 payload version으로 중복 갱신을 방지한다.', '환불 발생일 지표와 원 주문 완료일 보정 지표를 이름부터 구분한다.', '7일 초과 late event는 자동 수정하지 않고 BACKFILL_REQUIRED 운영 case를 만든다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/analytics/**', 'src/main/resources/db/migration/V23__create_analytics_read_model.sql', 'src/test/kotlin/io/github/kdh949/beanflow/analytics/**'],
    docs: ['docs/adr/ADR-023-analytics-refund-and-late-events.md', 'docs/analytics/metric-definitions.md', 'docs/benchmarks/analytics-query.md', 'openapi/beanflow-v1.yaml'],
    done: ['Analytics projection과 source event dedup 스키마가 구현된다.', '기본 지표 API와 정의 문서가 일치한다.', '7일 late event 자동 재집계와 초과 backfill 승인 흐름이 구현된다.', '실행계획·N+1·재집계 재실행 테스트가 통과한다.'],
    dependsOn: ['E10', 'E14', 'E15', 'E16'],
    tasks: [
      T('T1', 'Analytics projection·source event dedup 스키마 구현', 'OrderCompleted, PaymentRefunded, Settlement events를 일자·매장 Read Model로 투영한다.', ['daily store metrics, menu sales, source event processing 테이블을 정의한다.', 'eventId Unique Constraint와 payloadVersion 처리 정책을 구현한다.', 'OrderCompleted는 gross/net/order count/menu quantity를, Refund는 발생일·원 완료일 보정 값을 갱신한다.', 'Projection update를 원본 Aggregate Repository 직접 접근 없이 payload와 공개 query로 수행한다.'], ['동일 event 재전달이 지표를 바꾸지 않는다.', 'event 처리 중 실패하면 dedup 완료가 함께 commit되지 않는다.', 'refund-day와 original-day corrected 값이 별도 컬럼/지표다.'], ['projection 계산 단위 테스트', '중복 event 통합 테스트', '처리 rollback/dedup 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/analytics/**', 'src/main/resources/db/migration/V23__create_analytics_read_model.sql']),
      T('T2', '매출·주문·객단가·환불률·인기 메뉴·예상 정산 API 구현', '점주가 의사결정에 사용할 기본 지표를 명확한 포함·제외 기준으로 조회한다.', ['기간 총매출/순매출/주문수/AOV/환불액·율/인기 메뉴/쿠폰·포인트 비용/예상 정산을 구현한다.', 'StoreAccessPolicy와 날짜 범위·page size 상한을 적용한다.', 'metric name, numerator/denominator, timezone, refund attribution을 문서화한다.', 'DTO Projection/Native SQL 중 실행계획에 맞는 Query Repository를 사용한다.'], ['같은 기간과 원본 데이터에서 지표가 결정적이다.', '0건 기간의 0/undefined 의미가 API 계약에 명시된다.', '다른 매장 지표 접근이 403이다.'], ['지표 fixture 계산 테스트', 'MockMvc 인가·날짜 계약 테스트', 'Query SQL count 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/analytics/internal/*QueryRepository.kt', 'src/main/kotlin/io/github/kdh949/beanflow/analytics/internal/*Controller.kt', 'openapi/beanflow-v1.yaml'], ['docs/analytics/metric-definitions.md', 'openapi/beanflow-v1.yaml']),
      T('T3', '7일 late event 재집계와 BACKFILL_REQUIRED 운영 흐름 구현', '일반 지연은 자동 보정하고 오래된 변경은 승인 가능한 운영 작업으로 통제한다.', ['event occurredAt 기준 7일 이내 일자를 야간 idempotent rebuild 대상으로 표시한다.', '일자/매장 chunk 재집계가 중단·재시작돼도 같은 결과를 만든다.', '7일 초과 event는 원 지표를 자동 변경하지 않고 ReprocessingCase(BACKFILL_REQUIRED)를 만든다.', '승인된 운영 backfill만 지정 범위를 재집계하고 AuditRecord를 남긴다.'], ['7일 경계 안/밖 동작이 고정 Clock으로 검증된다.', '중복 case와 중복 rebuild가 발생하지 않는다.', 'backfill 실패가 성공 지표로 위장되지 않는다.'], ['late event Clock 경계 테스트', 'rebuild restart 테스트', '운영 승인·권한·감사 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/analytics/internal/*Rebuild*', 'src/main/kotlin/io/github/kdh949/beanflow/operations/**'], ['docs/operations/runbooks/analytics-backfill.md', 'docs/benchmarks/analytics-query.md'], ['area:operations'])
    ]
  },
  {
    key: 'E19',
    title: 'REST API 계약·OpenAPI·REST Docs 완성',
    milestone: 'R4 — 계약·보안·운영 품질',
    priority: 'priority:P0',
    areas: ['area:api', 'area:platform', 'area:ordering'],
    risks: [],
    goal: '현재 세 endpoint 중심 OpenAPI를 전체 MVP 계약으로 확장하고 error code, idempotency, pagination, time/money 표현을 테스트 결과와 일치시킨다.',
    sources: ['openapi/beanflow-v1.yaml', 'src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderControllerContractTest.kt', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/ApiExceptionHandler.kt', 'docs/api/api-conventions.md', 'docs/api/error-catalog.md'],
    invariants: ['JPA Entity를 API 응답으로 노출하지 않는다.', '201/202/204/409/422/503의 의미를 명령 상태와 맞춘다.', 'UNKNOWN·RECONCILING은 성공 또는 확정 실패로 표현하지 않는다.', '같은 idempotency key/payload는 최초 response를 재생하고 다른 payload는 409다.'],
    files: ['openapi/beanflow-v1.yaml', 'src/main/kotlin/io/github/kdh949/beanflow/shared/api/**', 'src/test/kotlin/io/github/kdh949/beanflow/**/**/*ContractTest.kt', 'src/docs/asciidoc/**'],
    docs: ['docs/api/api-conventions.md', 'docs/api/error-catalog.md', 'openapi/beanflow-v1.yaml'],
    done: ['MVP 구현 endpoint가 OpenAPI에 빠짐없이 기록된다.', '공통 오류·correlationId·Retry-After 계약이 통일된다.', 'REST Docs 테스트가 정상·핵심 실패 예제를 생성한다.', 'CI에서 OpenAPI/문서 drift를 검증한다.'],
    dependsOn: ['E09', 'E11', 'E12', 'E13', 'E14', 'E15', 'E17', 'E18'],
    tasks: [
      T('T1', '공통 API 오류·correlationId·재시도 의미 통합', '모듈별 예외 처리를 공통 안정적 error catalog에 맞춘다.', ['DomainFailure와 validation/security/provider failure를 공통 ErrorResponse로 번역한다.', 'errorCode, message, correlationId, fieldErrors, retryable/Retry-After를 필요한 경우 포함한다.', '404/403 정보 노출 정책과 409/422/503 구분을 정리한다.', '예외를 catch해 empty/0/200으로 반환하는 경로를 제거한다.'], ['같은 failure code가 모듈마다 다른 HTTP status를 반환하지 않는다.', '민감 내부 예외·SQL·token이 응답에 노출되지 않는다.', 'UNKNOWN/reconciliation pending은 문서화된 202 또는 상태 조회로 표현된다.'], ['ControllerAdvice 단위 테스트', 'validation/security/provider 오류 계약 테스트', 'correlationId 전파 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/shared/api/**', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/ApiExceptionHandler.kt']),
      T('T2', '전체 MVP OpenAPI와 Spring REST Docs 계약 테스트 작성', '실제 MockMvc 결과로 요청·응답 예제를 생성하고 수동 명세와 drift를 줄인다.', ['회원/매장/검색/주문/결제/포인트/알림/정산/이의/분석 endpoint를 OpenAPI에 추가한다.', 'Idempotency-Key, Authorization, cursor, money/time schema를 component로 재사용한다.', '각 핵심 endpoint의 성공·인가·상태 충돌·검증 실패 REST Docs 테스트를 만든다.', '문서가 구현되지 않은 미래 필드를 예측해 약속하지 않게 한다.'], ['현재 Controller route와 OpenAPI path가 일치한다.', 'REST Docs snippet 생성이 CI에서 재현된다.', 'schema example에 실제 저장 금지 데이터가 없다.'], ['MockMvc REST Docs 테스트', 'OpenAPI parse/lint 검증', '문서 생성 Gradle task 테스트'], ['openapi/beanflow-v1.yaml', 'src/test/kotlin/io/github/kdh949/beanflow/**/**/*ContractTest.kt', 'src/docs/asciidoc/**']),
      T('T3', 'Cursor·시간·금액·버전 정책과 문서 drift CI 구성', 'API 전반의 표현 규칙을 자동 검증한다.', ['cursor encoding/version과 잘못된 cursor 오류를 공통화한다.', 'Instant/offset/Asia-Seoul 표시 규칙과 KRW integer schema를 고정한다.', 'API versioning과 backward-compatible 변경 기준을 문서화한다.', 'CI에서 OpenAPI, REST Docs, verify-docs, broken link와 uncommitted generated drift를 검사한다.'], ['모든 목록 endpoint가 page size 상한과 안정 정렬을 가진다.', '금액에 부동소수점 schema가 없다.', '문서 검증 실패 시 CI가 명확히 실패한다.'], ['cursor property 테스트', 'timezone/money serialization 테스트', 'CI workflow 로컬/Actions 검증'], ['docs/api/api-conventions.md', 'scripts/verify-docs.sh', '.github/workflows/ci.yml'], ['docs/api/api-conventions.md'], ['type:docs'])
    ]
  },
  {
    key: 'E20',
    title: 'Spring Security·매장 격리·민감정보 보호 강화',
    milestone: 'R4 — 계약·보안·운영 품질',
    priority: 'priority:P0',
    areas: ['area:security', 'area:identity', 'area:api', 'area:platform'],
    risks: ['risk:privacy'],
    goal: 'FilterChain 인증과 Application Service 객체 수준 인가를 모든 고객·점주·운영자 endpoint에 일관되게 적용하고 로그·API의 민감정보 노출을 방지한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/shared/internal/SecurityConfiguration.kt', 'AGENTS.md', 'docs/product/business-policy-decisions.md', 'docs/adr/ADR-021-payment-method-tokenization.md', 'docs/adr/ADR-020-nearby-location-privacy.md'],
    invariants: ['인증은 SecurityFilterChain, 리소스 소유권은 Application Service에서 검증한다.', '역할만으로 다른 매장·고객 자원을 읽거나 변경할 수 없다.', '정밀 좌표, token reference, JWT, provider credential을 로그에 남기지 않는다.', '필수 issuer/JWK/credential 누락 시 startup을 실패시킨다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/shared/internal/SecurityConfiguration.kt', 'src/main/kotlin/io/github/kdh949/beanflow/identity/**', 'src/test/kotlin/io/github/kdh949/beanflow/security/**', 'docs/security/**'],
    docs: ['docs/security/access-control-matrix.md', 'docs/security/sensitive-data.md', 'docs/architecture/failure-semantics.md'],
    done: ['endpoint별 actor/role/resource 권한 매트릭스가 구현·테스트된다.', '고객·점주 tenant 격리 회귀 테스트가 전 모듈을 커버한다.', '민감정보 로그·serialization 검증이 통과한다.', 'startup·readiness가 required 보안 설정 실패를 명확히 드러낸다.'],
    dependsOn: ['E01', 'E19'],
    tasks: [
      T('T1', '전체 endpoint 객체 수준 인가와 접근 제어 매트릭스 적용', '역할·소유권·매장 membership을 endpoint별로 명시하고 코드에 일치시킨다.', ['고객 주문/결제수단/포인트/알림은 current actor owner를 검증한다.', '점주 메뉴/슬롯/재고/주문/정산/분석은 store membership을 검증한다.', '운영자 재처리/판정/조정은 별도 운영 역할과 reason을 요구한다.', 'Controller annotation만 믿지 않고 owner Application Service에서 재검증한다.'], ['다른 고객·매장 ID를 순회해도 데이터가 노출되지 않는다.', '역할 없는 actor와 membership 없는 actor가 구분된 정책대로 거부된다.', '권한 경계가 `docs/security/access-control-matrix.md`와 일치한다.'], ['모듈별 parameterized authorization 테스트', 'IDOR 회귀 테스트', 'Method Security + Application policy 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/**/**/*Service.kt', 'src/test/kotlin/io/github/kdh949/beanflow/security/**'], ['docs/security/access-control-matrix.md']),
      T('T2', 'JWT·FilterChain·오류 응답·startup failure 검증 강화', 'Servlet Filter→SecurityFilterChain→DispatcherServlet 흐름에서 인증 실패가 일관되게 동작하게 한다.', ['JWK issuer/algorithm/audience/claim 변환을 명시한다.', 'AuthenticationEntryPoint와 AccessDeniedHandler를 공통 ErrorResponse에 연결한다.', 'Correlation ID Filter가 인증 오류에도 ID를 제공하게 한다.', 'required JWK/production provider 설정 누락 시 fail-fast를 테스트한다.'], ['인증 실패는 401, 인가 실패는 403이다.', '보안 예외에서도 correlationId가 있다.', 'invalid/default secret로 애플리케이션이 부분 기동하지 않는다.'], ['JWT claim matrix 테스트', 'Filter order/오류 body 테스트', 'ApplicationContextRunner startup failure 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/shared/internal/SecurityConfiguration.kt', 'src/main/kotlin/io/github/kdh949/beanflow/shared/internal/*Filter.kt']),
      T('T3', '좌표·결제 token·개인정보 로그·응답 노출 검사 자동화', '저장 금지 데이터가 Entity·DTO·로그·trace에 섞이는 회귀를 막는다.', ['정밀 lat/lng, PAN/CVC/full expiry, tokenReference, Authorization header 금칙 검사를 테스트한다.', 'HTTP request logging과 structured log sanitizer를 적용한다.', 'MDC/metric tag cardinality에 actor raw data나 좌표를 넣지 않는다.', '샘플 fixture와 문서에서도 실제처럼 보이는 secret를 제거한다.'], ['로그 캡처·JSON serialization에서 금칙 데이터가 0건이다.', 'error stack/Provider response 원문이 외부 응답에 없다.', 'secret scan과 privacy 테스트가 CI에서 실행된다.'], ['로그 캡처 privacy 테스트', 'DTO reflection/serialization 테스트', 'secret scanner/CI 검증'], ['src/test/kotlin/io/github/kdh949/beanflow/security/**', 'src/main/kotlin/io/github/kdh949/beanflow/shared/internal/**', '.github/workflows/ci.yml'], ['docs/security/sensitive-data.md'])
    ]
  },
  {
    key: 'E21',
    title: '운영 재처리·감사·멱등성 보존 배치',
    milestone: 'R4 — 계약·보안·운영 품질',
    priority: 'priority:P0',
    areas: ['area:operations', 'area:ordering', 'area:payment', 'area:platform'],
    risks: ['risk:data-consistency', 'risk:money'],
    goal: '이미 구현된 AuditRecord·ReprocessingCase를 제품 전체 실패에 연결하고 90일 멱등성 정리와 5년 감사 보존을 재실행 가능한 worker로 운영한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/operations/api/AuditRecordOperations.kt', 'src/main/kotlin/io/github/kdh949/beanflow/operations/api/ReprocessingCaseOperations.kt', 'src/main/kotlin/io/github/kdh949/beanflow/operations/internal/AuditRetentionWorker.kt', 'docs/product/business-policy-decisions.md', 'docs/adr/ADR-022-audit-record.md'],
    invariants: ['Operations는 owner Aggregate를 직접 수정하지 않고 승인된 공개 명령을 호출한다.', '수동 재처리는 actor·reason·source·before/after 감사가 필수다.', '진행 중·UNKNOWN·open case의 idempotency record를 정리하지 않는다.', 'AuditRecord는 append-only이며 Asia/Seoul 달력 5주년 전 삭제하지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/operations/**', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/**', 'src/main/kotlin/io/github/kdh949/beanflow/payment/**', 'src/test/kotlin/io/github/kdh949/beanflow/operations/**'],
    docs: ['docs/operations/reconciliation.md', 'docs/operations/runbooks/**', 'docs/adr/ADR-022-audit-record.md', 'openapi/beanflow-v1.yaml'],
    done: ['결제·환불·알림·분쟁·분석 failure가 ReprocessingCase로 조회된다.', '운영자 재처리가 owner 명령과 idempotency를 사용한다.', '멱등성 90일 cleanup과 감사 5년 retention 경계가 구현된다.', '메트릭·runbook·감사·권한 테스트가 통과한다.'],
    dependsOn: ['E10', 'E11', 'E14', 'E16', 'E17', 'E18', 'E20'],
    tasks: [
      T('T1', 'ReprocessingCase 조회·승인·실행 API와 owner 명령 라우팅', '운영자가 실패 종류를 확인하고 안전한 재처리를 수행하도록 한다.', ['case type/target/status/last failure/retry count/source reference Projection을 구현한다.', '운영자 전용 승인·실행 API에 reason을 요구한다.', 'Payment/Refund/Notification/Dispute/Analytics owner 공개 API로만 재처리한다.', '중복 실행 lock과 case status PROCESSING/SUCCEEDED/FAILED/MANUAL_REVIEW를 명시한다.'], ['Operations Repository가 다른 Context Entity를 직접 변경하지 않는다.', '동시 재처리 요청 중 하나만 owner 명령을 호출한다.', '실패 시 case와 owner 상태가 성공으로 위장되지 않는다.'], ['owner routing 단위 테스트', '동시 재처리 통합 테스트', '운영자 권한·AuditRecord 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/operations/internal/ReprocessingCaseService.kt', 'src/main/kotlin/io/github/kdh949/beanflow/operations/internal/*Controller.kt', 'openapi/beanflow-v1.yaml']),
      T('T2', '주문·결제 IdempotencyRecord 90일 cleanup worker 구현', 'terminal 거래만 기준 시점 후 제한된 chunk로 정리한다.', ['Ordering/Payment idempotency record의 terminalAt/retentionExpiresAt 기준을 통일한다.', 'COMPLETED/FAILED terminal+90일만 due query로 찾는다.', 'PROCESSING/UNKNOWN/RECONCILING/MANUAL_REVIEW와 open case 연결 record를 제외한다.', 'chunk delete 후 persistence context를 clear하고 중단·재실행 가능하게 한다.'], ['90일 직전 record가 보존되고 경계 이후만 삭제된다.', '진행 중/UNKNOWN record가 오래돼도 삭제되지 않는다.', '동일 worker 병렬 실행이 오류나 과삭제를 만들지 않는다.'], ['고정 Clock 90일 경계 테스트', 'chunk restart/parallel worker 테스트', 'FK/open case 제외 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/*IdempotencyCleanup*', 'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/*IdempotencyCleanup*']),
      T('T3', 'AuditRecord 5년 retention·운영 메트릭·runbook 완성', '감사 보존 worker와 운영 상태를 관측 가능한 형태로 정리한다.', ['현재 AuditRetentionWorker의 서울 달력 plusYears(5), 2월 29일 경계를 검증한다.', 'cleanup 대상 조회 인덱스와 chunk size/삭제 건수/실패 metric을 추가한다.', 'open/manual/retry case 수, oldest age, reconciliation lag metric을 제공한다.', '각 case type별 진단·재처리·중단 조건 runbook을 작성한다.'], ['5주년 이전 record를 삭제하지 않는다.', 'worker 실패가 readiness success나 0건으로 숨겨지지 않는다.', 'runbook이 metric 이름·검색 키·승인 조건을 포함한다.'], ['윤년/달력 경계 테스트', 'retention worker restart 테스트', 'Actuator metric 노출 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/operations/internal/AuditRetentionWorker.kt', 'src/test/kotlin/io/github/kdh949/beanflow/operations/**', 'docs/operations/runbooks/**'], ['docs/operations/runbooks/audit-retention.md', 'docs/operations/reconciliation.md'])
    ]
  },
  {
    key: 'E22',
    title: 'JPA 연관관계·N+1·실행계획 품질 체계',
    milestone: 'R4 — 계약·보안·운영 품질',
    priority: 'priority:P0',
    areas: ['area:persistence', 'area:performance', 'area:platform'],
    risks: ['risk:data-consistency'],
    goal: 'Aggregate 경계와 실제 SQL을 자동 검증해 편의상 객체 그래프 확장, N+1, fetch join pagination, 부적절한 대량 dirty checking을 방지한다.',
    sources: ['AGENTS.md', 'docs/architecture/aggregate-invariants.md', 'docs/architecture/architecture-overview.md', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/OrderingPersistence.kt', 'build.gradle.kts'],
    invariants: ['Repository는 기본적으로 Aggregate Root 단위다.', '다른 Aggregate는 ID 참조를 우선하고 양방향/@ManyToMany는 ADR 없이 금지한다.', 'OSIV로 LazyInitialization/N+1을 숨기지 않는다.', '목록·집계는 Projection/Query Repository를 사용하고 실제 PostgreSQL 실행계획으로 검증한다.'],
    files: ['src/test/kotlin/io/github/kdh949/beanflow/architecture/**', 'src/test/kotlin/io/github/kdh949/beanflow/persistence/**', 'src/main/resources/application.yaml', 'docs/benchmarks/**'],
    docs: ['docs/testing/test-strategy.md', 'docs/benchmarks/query-plan-index.md', 'docs/decisions/minor-decisions.md'],
    done: ['Modulith/ArchUnit/JPA 규칙 테스트가 경계 위반을 실패시킨다.', 'OSIV가 명시적으로 비활성화된다.', '주요 목록·집계 API의 SQL 수와 실행계획 기준선이 기록된다.', '인덱스 추가는 Before/After와 쓰기 비용을 함께 문서화한다.'],
    dependsOn: ['E15', 'E18', 'E19'],
    tasks: [
      T('T1', 'Spring Modulith·ArchUnit·JPA 매핑 구조 테스트 강화', '모듈 내부 침범과 위험한 연관관계가 PR에서 자동 탐지되게 한다.', ['모듈 간 internal package 접근과 순환 의존을 검증한다.', 'Controller→Repository 직접 의존, Entity API 노출을 ArchUnit으로 금지한다.', '@ManyToMany·양방향 relation·Aggregate 경계 넘는 CascadeType.ALL을 reflection 테스트로 탐지한다.', 'OSIV=false를 설정하고 트랜잭션 밖 LAZY 접근을 테스트에서 드러낸다.'], ['규칙을 의도적으로 위반한 fixture가 테스트 실패를 증명한다.', '기존 package-info 모듈 정의가 모두 검증된다.', '의미 없는 whitelist로 현재 위반을 숨기지 않는다.'], ['ApplicationModules.verify 테스트', 'ArchUnit dependency 테스트', 'JPA annotation reflection 테스트'], ['src/test/kotlin/io/github/kdh949/beanflow/architecture/**', 'src/main/resources/application.yaml']),
      T('T2', '주문·정산·분석 목록 DTO Projection과 N+1 회귀 테스트', 'API별 필요한 필드만 조회하고 쿼리 수를 고정한다.', ['주문 고객 목록/매장 목록, Settlement item 목록, Analytics 지표 쿼리를 선정한다.', 'EntityGraph/fetch join/DTO Projection/Batch Fetch 대안을 화면별로 비교한다.', '컬렉션 fetch join pagination을 사용하지 않고 count/query 분리를 설계한다.', 'Hibernate statistics 또는 datasource proxy로 대표 데이터의 SQL 수를 검증한다.'], ['목록 크기가 늘어도 row당 추가 query가 발생하지 않는다.', '상세와 목록 fetch plan이 분리된다.', 'Query DTO가 쓰기 Entity에 setter나 relation을 요구하지 않는다.'], ['100건 fixture SQL count 테스트', 'pagination 중복/누락 테스트', '트랜잭션 밖 serialization 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/*QueryRepository.kt', 'src/main/kotlin/io/github/kdh949/beanflow/settlement/internal/*QueryRepository.kt', 'src/test/kotlin/io/github/kdh949/beanflow/persistence/**']),
      T('T3', '복합 인덱스·EXPLAIN ANALYZE·대량 배치 SQL 검증', '추측이 아니라 실제 실행계획으로 조회·worker 인덱스를 선택한다.', ['due worker, 주문 목록, 정산 batch, dispute active, analytics period 쿼리를 수집한다.', 'predicate/selectivity/order by를 기준으로 복합·partial 인덱스 후보를 만든다.', '`EXPLAIN (ANALYZE, BUFFERS)`의 actual rows, loops, buffers, sort, lock 영향을 기록한다.', 'Bulk update/delete 뒤 persistence context clear와 chunk tx를 검증한다.'], ['각 인덱스에 대상 쿼리와 컬럼 순서 근거가 있다.', 'Before/After는 같은 환경·데이터·쿼리로 측정된다.', '개선과 함께 insert/update 비용·저장 공간 trade-off를 기록한다.'], ['10k/100k 합성 데이터 실행계획 테스트', 'worker chunk 메모리/transaction 테스트', '인덱스 회귀 문서 검증'], ['src/main/resources/db/migration/V24__add_query_indexes.sql', 'docs/benchmarks/**', 'scripts/explain/**'], ['docs/benchmarks/query-plan-index.md'], ['area:performance'], 'type:spike')
    ]
  },
  {
    key: 'E23',
    title: '쿠폰·재고·슬롯 동시성 전략 비교',
    milestone: 'R5 — 성능·장애·공개 릴리스',
    priority: 'priority:P0',
    areas: ['area:performance', 'area:inventory', 'area:fulfillment', 'area:promotion', 'area:persistence'],
    risks: ['risk:concurrency'],
    goal: '동일 PostgreSQL 데이터와 k6 부하에서 비관적 잠금·낙관적 잠금·조건부 원자 UPDATE를 비교해 정확성과 처리량에 맞는 전략을 측정 근거로 선택한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/inventory/internal/StockReservationPersistence.kt', 'src/main/kotlin/io/github/kdh949/beanflow/fulfillment/internal/PickupReservationPersistence.kt', 'src/main/kotlin/io/github/kdh949/beanflow/promotion/internal/CouponReservationPersistence.kt', 'docs/architecture/aggregate-invariants.md'],
    invariants: ['어떤 전략에서도 oversell·capacity 초과·쿠폰 이중 사용은 0이어야 한다.', '재시도 루프가 실패를 성공처럼 숨기거나 무한 반복하면 안 된다.', '각 방식은 같은 데이터·VU·시나리오·connection pool 조건으로 비교한다.', '측정 전 성능 우위를 주장하지 않는다.'],
    files: ['load-tests/concurrency/**', 'src/test/kotlin/io/github/kdh949/beanflow/concurrency/**', 'docs/benchmarks/concurrency-strategies.md'],
    docs: ['docs/benchmarks/concurrency-strategies.md', 'docs/adr/ADR-027-concurrency-strategy.md'],
    done: ['세 전략의 실험 가능한 구현 또는 격리된 benchmark path가 있다.', '정확성 invariant와 RPS/p50/p95/p99/error/lock wait/retry가 수집된다.', '동일 조건 결과와 선택·부작용이 ADR에 기록된다.', '선택한 production path에 회귀 동시성 테스트가 남는다.'],
    dependsOn: ['E04', 'E05', 'E06', 'E22'],
    tasks: [
      T('T1', '비관·낙관·조건부 UPDATE 비교 구현과 정확성 harness 작성', '세 자원에 공통된 경합 실험 계약을 만든다.', ['stock/slot/coupon 각각 마지막 1개를 대상으로 동일 reserve command를 만든다.', 'PESSIMISTIC_WRITE, @Version retry, guarded UPDATE count 전략을 격리된 구현으로 제공한다.', '성공/충돌/품절/재시도 exhausted 결과를 같은 도메인 의미로 정규화한다.', '실험용 선택이 운영 profile에서 무단 전환되지 않게 명시적 config를 둔다.'], ['각 전략에서 최종 자원 합계가 정확하다.', 'retry 상한과 backoff가 명시된다.', '실험 코드가 production 기본 경로에 silent fallback을 추가하지 않는다.'], ['JUnit 반복 동시성 harness', 'PostgreSQL lock/unique 테스트', '최종 row tie-out SQL assertion'], ['src/test/kotlin/io/github/kdh949/beanflow/concurrency/**', 'src/main/kotlin/io/github/kdh949/beanflow/**/internal/*ReservationStrategy.kt']),
      T('T2', 'k6 경합 시나리오와 PostgreSQL·Hikari 메트릭 수집', '전략별 처리량과 대기 비용을 동일 조건으로 측정한다.', ['k6에서 VU/ramp/duration/요청 분포를 고정한다.', 'RPS, p50/p95/p99, error type, collision/retry 수를 출력한다.', 'pg_stat_activity/locks, lock wait, deadlock, Hikari active/pending과 CPU를 수집한다.', 'seed/reset 스크립트로 각 실행의 초기 상태를 동일하게 만든다.'], ['실험 명령과 환경이 다른 개발자에게 재현 가능하다.', '정확성 assertion 실패 시 성능 결과를 유효로 처리하지 않는다.', 'raw 결과와 요약 결과를 구분해 저장한다.'], ['k6 smoke/benchmark 실행', 'metric endpoint 검증', 'seed/reset idempotency 테스트'], ['load-tests/concurrency/**', 'scripts/benchmark/**', 'docker-compose.observability.yml'], ['docs/benchmarks/concurrency-strategies.md'], ['area:performance'], 'type:spike'),
      T('T3', '동시성 전략 ADR·선택 적용·회귀 기준 확정', '충돌률과 운영 복잡도를 바탕으로 자원별 production 전략을 선택한다.', ['정확성, low/high contention, retry amplification, lock wait, 구현·운영 비용을 비교한다.', 'stock/slot/coupon이 반드시 같은 전략일 필요가 없는 이유를 검토한다.', '선택한 방식만 production bean으로 활성화하고 실험 path를 분리한다.', 'Revisit 조건에 충돌률·p95·lock wait·트래픽 임계값을 기록한다.'], ['ADR에 환경·결과·대안·부작용·재검토 조건이 있다.', 'production 설정에서 전략이 모호하지 않다.', '선택 방식의 동시성 regression test가 CI 안정성을 해치지 않는 반복 수로 남는다.'], ['ADR 링크 검증', 'production profile bean 선택 테스트', '선택 방식 회귀 동시성 테스트'], ['docs/adr/ADR-027-concurrency-strategy.md', 'docs/benchmarks/concurrency-strategies.md', 'src/main/kotlin/io/github/kdh949/beanflow/**/internal/**'], ['docs/adr/ADR-027-concurrency-strategy.md'], ['type:docs'])
    ]
  },
  {
    key: 'E24',
    title: '부하·장애 주입·관측 대시보드',
    milestone: 'R5 — 성능·장애·공개 릴리스',
    priority: 'priority:P0',
    areas: ['area:performance', 'area:platform', 'area:operations', 'area:payment', 'area:notification'],
    risks: ['risk:external-provider', 'risk:data-consistency'],
    goal: '주문·결제·검색·정산 핵심 흐름의 기준선과 점심시간 spike, Provider 지연, worker 재시작을 재현해 로그·메트릭·runbook으로 원인을 추적한다.',
    sources: ['build.gradle.kts', 'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/PaymentMetrics.kt', 'README.md', 'docs/testing/test-strategy.md', 'docs/architecture/failure-semantics.md'],
    invariants: ['성능 절차는 증상→고정 재현→기준선→가설→최소 변경→재측정 순서다.', '외부 지연 중 DB connection을 점유하지 않는다.', '오류율·terminal 누락·정합성 실패를 latency 개선으로 숨기지 않는다.', '실제 측정하지 않은 수치를 README에 쓰지 않는다.'],
    files: ['load-tests/**', 'docker-compose.observability.yml', 'config/prometheus/**', 'config/grafana/**', 'docs/benchmarks/**', 'docs/incidents/**'],
    docs: ['docs/operations/observability.md', 'docs/benchmarks/load-test-report.md', 'docs/incidents/**', 'README.md'],
    done: ['Micrometer/Prometheus/Grafana와 구조화 로그가 로컬 compose에서 동작한다.', 'smoke/average/spike/stress/soak 시나리오가 재현된다.', 'PG·알림·worker 장애 실험과 incident report가 작성된다.', 'p50/p95/p99/RPS/error/pool/lock/GC/CPU 지표가 같은 시간축으로 해석 가능하다.'],
    dependsOn: ['E03', 'E11', 'E14', 'E15', 'E18', 'E21', 'E22', 'E23'],
    tasks: [
      T('T1', 'Micrometer·Prometheus·Grafana·구조화 로그 관측 기반 구성', '거래와 인프라 병목을 correlationId와 metric으로 연결한다.', ['Actuator/Micrometer에 주문·결제·refund·publication·notification·batch custom metric을 추가한다.', 'Hikari, JVM, HTTP, PostgreSQL exporter 지표를 Prometheus가 수집하게 한다.', 'Grafana에 거래 성공/UNKNOWN/manual review, latency, pool pending, lock, worker lag dashboard를 만든다.', '로그에 correlationId/eventId/orderId를 넣되 token/좌표/PII를 제거한다.'], ['docker compose 한 번으로 수집과 dashboard가 기동된다.', 'required monitoring 실패가 비즈니스 성공을 왜곡하지 않으며 health 의미가 문서화된다.', '고카디널리티 actor/order 값을 metric label로 사용하지 않는다.'], ['Actuator metric test', 'compose smoke test', '로그 correlation/privacy test'], ['docker-compose.observability.yml', 'config/prometheus/**', 'config/grafana/**', 'src/main/kotlin/io/github/kdh949/beanflow/**/**/*Metrics.kt']),
      T('T2', 'k6 smoke·average·spike·stress·soak 시나리오 작성', '주문 생성→결제→수락→완료와 조회 흐름의 기준선을 반복 가능하게 측정한다.', ['seed fixture와 JWT/test actor 준비 절차를 스크립트화한다.', '가까운 매장, 주문 생성, 결제, 매장 처리, 정산/분석 조회 비율을 시나리오별로 정의한다.', 'threshold는 목표임을 명시하고 실제 결과와 분리한다.', '각 실행에 commit SHA, 환경, 데이터 크기, VU, duration을 기록한다.'], ['최소 smoke가 CI 또는 수동 명령으로 안정 실행된다.', '실패 응답을 제거하거나 자동 재시도로 성공률을 부풀리지 않는다.', 'raw k6 output과 요약 보고서가 재현 가능하다.'], ['k6 smoke/average/spike 실행', 'fixture reset 테스트', 'threshold failure 확인 테스트'], ['load-tests/**', 'scripts/load-test/**'], ['docs/benchmarks/load-test-report.md'], ['area:performance'], 'type:spike'),
      T('T3', 'PG 지연·ACK 유실·worker 중단 장애 주입과 Incident Report 작성', '정상 부하뿐 아니라 불명 결과와 재시작 복구의 실제 동작을 검증한다.', ['PG approve 응답 지연/유실 중 connection pool pending과 Payment UNKNOWN을 관찰한다.', '알림 Provider ACK 유실과 4회 실패를 재현한다.', 'settlement/event/expiry worker 중간 종료 후 재시작한다.', '각 실험을 증상·영향·timeline·근본 원인·변경·재측정·남은 위험 형식으로 문서화한다.'], ['PG 지연 중 외부 호출이 DB tx 밖임이 metric/test로 보인다.', 'worker 재시작 후 중복 원장·terminal 누락이 없다.', '실패가 manual/retry state로 남아 운영자가 발견 가능하다.'], ['Toxiproxy/WireMock 장애 테스트', 'Docker process restart test', '원장/상태 tie-out 검증'], ['src/test/kotlin/io/github/kdh949/beanflow/resilience/**', 'docs/incidents/**', 'load-tests/faults/**'], ['docs/incidents/payment-connection-pool.md', 'docs/incidents/event-worker-restart.md'])
    ]
  },
  {
    key: 'E25',
    title: 'BeanFlow MVP 공개 릴리스·E2E 데모·증거 정리',
    milestone: 'R5 — 성능·장애·공개 릴리스',
    priority: 'priority:P0',
    areas: ['area:platform', 'area:api', 'area:operations'],
    risks: [],
    goal: '고객→점주→운영자의 거래 생명주기를 재현 가능한 환경으로 배포하고 코드·테스트·ADR·실행계획·성능·장애 증거를 일관되게 정리한다.',
    sources: ['README.md', 'AGENTS.md', 'docs/testing/definition-of-done.md', '.github/workflows/ci.yml', '.github/pull_request_template.md'],
    invariants: ['실제 운영 사용자·트래픽·프로덕션 안정성을 과장하지 않는다.', 'mock/sandbox/provider 범위와 미구현 실제 지급을 명확히 구분한다.', '필수 dependency 설정이 없으면 fail-fast하고 silent local fallback을 사용하지 않는다.', '데모도 금액·정합성·장애 상태를 실제 DB에서 검증한다.'],
    files: ['README.md', 'docker-compose.yml', 'docs/**', 'scripts/demo/**', '.github/workflows/ci.yml'],
    docs: ['README.md', 'docs/index.md', 'docs/quality/quality-evidence-map.md', 'docs/testing/definition-of-done.md'],
    done: ['고객·점주·운영자 E2E 시나리오가 자동·수동으로 재현된다.', 'Docker Compose 기동·seed·health·shutdown 절차가 문서화된다.', 'README가 문제·설계·핵심 결정·실패·측정 결과를 출처와 함께 보여준다.', '전체 build/test/docs/architecture/security/load smoke 검증 결과가 릴리스 체크리스트에 남는다.'],
    dependsOn: ['E19', 'E20', 'E21', 'E22', 'E23', 'E24'],
    tasks: [
      T('T1', '고객→점주→운영자 End-to-End fixture와 데모 시나리오 작성', '하나의 주문을 검색부터 완료·포인트·정산·환불·이의까지 추적한다.', ['결정적 ID/Clock을 가진 demo seed를 만든다.', '고객: 검색→주문→결제→상태→완료→포인트를 자동화한다.', '점주: 메뉴/슬롯/재고→수락→제조→정산/분석을 자동화한다.', '운영자: UNKNOWN/retry/manual review→재처리→adjustment/dispute를 시연한다.'], ['각 단계가 correlationId/eventId/source reference로 연결된다.', '반복 실행이 중복 주문·결제·원장을 만들지 않거나 reset 절차가 명확하다.', '실패 시 데모 스크립트가 즉시 non-zero로 종료한다.'], ['E2E happy path 테스트', 'Provider failure/recovery E2E 테스트', '금액·포인트·정산 SQL tie-out'], ['scripts/demo/**', 'src/test/kotlin/io/github/kdh949/beanflow/e2e/**']),
      T('T2', 'Docker Compose 실행 환경·health·seed·배포 문서 완성', '새 환경에서 BeanFlow를 과장 없이 재현 가능하게 한다.', ['PostgreSQL/PostGIS, app, JWK test issuer, sandbox provider, observability compose profile을 정리한다.', 'secret는 example env로만 제공하고 기본 credential로 운영 기동하지 않는다.', 'readiness/liveness가 DB·필수 provider 설정과 worker 상태 의미를 구분한다.', 'migration, seed, start, test, cleanup 명령을 스크립트화한다.'], ['README 명령만으로 local MVP가 기동된다.', '필수 env 누락은 actionable error로 실패한다.', 'mock/sandbox가 활성화된 profile이 응답/health에서 식별 가능하다.'], ['clean machine compose smoke test', 'startup failure test', 'migration/seed idempotency test'], ['docker-compose.yml', '.env.example', 'scripts/dev/**', 'README.md']),
      T('T3', 'README·ERD·Context Map·ADR·성능·장애 증거 릴리스 정리', '코드와 측정 결과를 탐색 가능한 공개 문서로 묶는다.', ['README 첫 화면을 문제→거래 흐름→아키텍처→핵심 결정→측정 증거 순서로 작성한다.', '실제 스키마 기반 ERD와 최신 Context Map을 생성·검토한다.', 'ADR/ExecPlan/benchmark/incident/API 문서 인덱스와 quality evidence map을 갱신한다.', 'Target/Assumption/Measured/Not measured를 구분하고 미측정 수치를 제거한다.'], ['문서 링크와 코드/정책 상태가 일치한다.', '완료되지 않은 P1/P2는 Future Work로 명확히 표시된다.', 'CI와 Definition of Done 결과가 릴리스 tag/노트에 기록된다.'], ['verify-docs/broken link 테스트', 'README 명령 재실행', '릴리스 체크리스트 수동 검토'], ['README.md', 'docs/index.md', 'docs/quality/quality-evidence-map.md', 'docs/architecture/**', 'docs/adr/README.md'], ['README.md', 'docs/quality/quality-evidence-map.md'], ['type:docs'])
    ]
  }
];
