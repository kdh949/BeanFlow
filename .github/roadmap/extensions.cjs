const T = (key, title, goal, work, acceptance, tests, files = [], docs = [], labels = [], type = 'type:task') => ({ key, title, goal, work, acceptance, tests, files, docs, labels, type });

module.exports = [
  {
    key: 'E26',
    title: '선불 지갑 원장·부분 환불·대사',
    milestone: 'P1 — 대표 확장',
    priority: 'priority:P1',
    areas: ['area:wallet', 'area:payment', 'area:settlement', 'area:operations'],
    risks: ['risk:money', 'risk:external-provider', 'risk:data-consistency', 'risk:concurrency'],
    goal: '포인트와 분리된 StoredValue Wallet에서 충전·사용·부분 환불을 immutable ledger로 처리하고 summary balance와 Provider 결과를 대사한다.',
    sources: ['docs/adr/ADR-012-separate-wallet-and-loyalty.md', 'docs/product/non-goals.md', 'docs/product/business-policy-decisions.md', 'docs/architecture/context-map.md'],
    invariants: ['사용자 충전금과 프로모션 포인트를 같은 balance나 Aggregate로 합치지 않는다.', '모든 충전·사용·환불·조정은 append-only ledger entry로 설명 가능해야 한다.', '잔액은 음수가 될 수 없고 source transaction당 부수효과는 하나다.', '포트폴리오 범위는 sandbox이며 실제 자금 보관·규제 준수를 완료했다고 주장하지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/wallet/**', 'src/main/resources/db/migration/V25__create_wallet.sql', 'src/test/kotlin/io/github/kdh949/beanflow/wallet/**'],
    docs: ['docs/adr/ADR-012-separate-wallet-and-loyalty.md', 'docs/operations/runbooks/wallet-reconciliation.md', 'openapi/beanflow-v1.yaml'],
    done: ['StoredValueAccount와 immutable WalletLedgerEntry가 구현된다.', 'sandbox 충전·주문 사용·부분 환불이 멱등 처리된다.', 'summary와 ledger/provider 대사가 차이를 탐지한다.', '동시 이중 차감·Provider timeout·중단 재실행 테스트가 통과한다.'],
    dependsOn: ['E13', 'E14', 'E21'],
    tasks: [
      T('T1', 'StoredValueAccount·WalletLedgerEntry·DB 제약 구현', 'Wallet이 Loyalty와 독립된 금액 원장을 소유하게 한다.', ['Account summary와 CHARGE/SPEND/REFUND/ADJUST ledger type을 정의한다.', 'source type/id와 provider operation ID Unique Constraint를 둔다.', 'ledger append와 balance summary 갱신을 같은 DB tx에서 처리한다.', 'available balance 음수와 환불 초과를 도메인·check/conditional update로 막는다.'], ['PointAccount Entity나 PointLot을 Wallet에서 참조하지 않는다.', 'ledger entry UPDATE/DELETE 경로가 없다.', '동시 spend 중 잔액을 넘는 성공이 없다.'], ['도메인 원장 합계 테스트', 'PostgreSQL 동시 이중 차감 테스트', 'append-only mapping 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/wallet/**', 'src/main/resources/db/migration/V25__create_wallet.sql']),
      T('T2', 'sandbox 충전·주문 사용·부분 환불 API 구현', '외부 충전 승인과 내부 사용을 트랜잭션 경계가 명확한 유스케이스로 제공한다.', ['충전 REQUESTED tx, Provider 호출, 결과 tx를 분리한다.', 'Wallet spend idempotency를 orderId/source reference로 고정한다.', '부분 환불이 원 사용 ledger와 누적 환불 가능액을 검증한다.', '고객 소유권과 Idempotency-Key payload hash를 API에 적용한다.'], ['Provider timeout은 UNKNOWN/RECONCILING이며 balance를 성공 충전으로 표시하지 않는다.', '동일 주문 사용이 두 번 차감되지 않는다.', '부분 환불 누적액이 원 사용액을 넘지 않는다.'], ['Provider success/decline/timeout 테스트', 'spend/refund 동시성 테스트', 'API 소유권·idempotency 계약 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/wallet/internal/**', 'openapi/beanflow-v1.yaml']),
      T('T3', 'Wallet summary·ledger·Provider reconciliation 구현', '내부 원장과 Provider 결과의 불일치를 자동 수정하지 않고 운영 case로 탐지한다.', ['Account summary와 ledger 합계 정기 대사를 구현한다.', 'UNKNOWN charge/refund를 Provider 조회로 reconcile한다.', 'Provider report와 internal ledger 차이는 ReprocessingCase를 생성한다.', '운영자 조정은 reason/evidence와 append-only ADJUST entry를 남긴다.'], ['대사 재실행이 중복 ledger를 만들지 않는다.', '불일치가 성공 balance로 조용히 덮어써지지 않는다.', '운영 조정 권한·감사가 적용된다.'], ['의도적 summary mismatch 테스트', 'Provider reconciliation restart 테스트', '운영 조정 감사 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/wallet/internal/*Reconciliation*', 'src/main/kotlin/io/github/kdh949/beanflow/operations/**'], ['docs/operations/runbooks/wallet-reconciliation.md'])
    ]
  },
  {
    key: 'E27',
    title: 'POS PrintJob·오프라인 재시도·중복 인쇄 방지',
    milestone: 'P1 — 대표 확장',
    priority: 'priority:P1',
    areas: ['area:pos', 'area:events', 'area:operations', 'area:merchant'],
    risks: ['risk:external-provider', 'risk:data-consistency'],
    goal: 'OrderAccepted를 인쇄 작업으로 변환해 장치 offline·ACK 유실·재출력을 운영 가능한 PrintJob 상태와 revision으로 처리한다.',
    sources: ['docs/product/business-policy-decisions.md', 'docs/architecture/context-map.md', 'docs/architecture/failure-semantics.md', 'docs/architecture/event-catalog.md'],
    invariants: ['주문 수락과 인쇄 성공을 같은 트랜잭션으로 묶지 않는다.', '`orderId:ticketType:revision`은 PrintJob idempotency key다.', 'offline 또는 ACK 유실을 인쇄 성공으로 표시하지 않는다.', '수동 재출력은 동일 revision 중복이 아니라 새 revision과 actor/reason을 가진다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/pos/**', 'src/main/resources/db/migration/V26__create_print_jobs.sql', 'src/test/kotlin/io/github/kdh949/beanflow/pos/**', 'tools/escpos-emulator/**'],
    docs: ['docs/adr/ADR-028-pos-printjob.md', 'docs/operations/runbooks/print-job.md', 'openapi/beanflow-v1.yaml'],
    done: ['PrintJob Aggregate·Outbox consumer·Device Adapter가 구현된다.', 'TCP/ESC-POS emulator로 success/offline/ACK 유실을 재현한다.', 'retry·DLQ/MANUAL_REVIEW·새 revision 재출력이 구현된다.', '주문과 인쇄 상태 독립성·중복 인쇄 한계가 문서화된다.'],
    dependsOn: ['E07', 'E10', 'E21'],
    tasks: [
      T('T1', 'PrintJob Aggregate·OrderAccepted 소비·영속 상태 구현', '인쇄 요청을 주문과 독립된 idempotent 작업으로 저장한다.', ['PENDING/SENDING/SUCCEEDED/RETRY_SCHEDULED/MANUAL_REVIEW 상태를 정의한다.', 'orderId/ticketType/revision Unique Constraint를 둔다.', 'OrderAccepted 중복 event가 같은 revision job을 하나만 만든다.', 'ticket payload는 주문 snapshot DTO로 저장하고 Entity relation을 만들지 않는다.'], ['PrintJob 생성 실패가 Order ACCEPTED를 롤백하지 않는다.', '중복 event가 추가 인쇄 job을 만들지 않는다.', 'job failure가 로그만 남고 PROCESSING에 영구 고착되지 않는다.'], ['상태 머신 테스트', 'event dedup PostgreSQL 테스트', '주문 상태 독립성 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/pos/**', 'src/main/resources/db/migration/V26__create_print_jobs.sql']),
      T('T2', 'TCP/ESC-POS Adapter·offline retry·ACK 유실 emulator 구현', '실제 장비가 없어도 외부 장치 실패 계약을 재현한다.', ['PrintDevice Port와 TCP Adapter를 구현한다.', 'emulator가 정상 ACK, connection refused, timeout, write 후 ACK 유실을 scripted하게 제공한다.', '외부 I/O를 DB tx 밖에서 실행하고 재시도 backoff/상한을 명시한다.', 'ACK 유실에서 device idempotency 한계와 ticket marker 전략을 검토한다.'], ['offline/timeout을 SUCCEEDED로 표시하지 않는다.', 'worker 재시작이 같은 attempt를 무한 병렬 실행하지 않는다.', '장치 endpoint/credential이 로그에 노출되지 않는다.'], ['Adapter contract 테스트', 'ACK 유실/worker restart 테스트', 'DB connection boundary 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/pos/internal/**', 'tools/escpos-emulator/**']),
      T('T3', '운영자 PrintJob 조회·수동 재출력·현장 runbook 구현', '실패 작업을 현장 직원이 확인하고 새 revision으로 재출력하게 한다.', ['매장별 실패 job 조회와 StoreAccessPolicy를 적용한다.', '재출력 명령에 reason을 요구하고 revision+1 새 job을 만든다.', '같은 재출력 idempotency key의 중복 클릭을 방지한다.', '용지 없음/offline/ACK 유실 진단과 안전한 재처리 runbook을 작성한다.'], ['원 job은 수정되지 않고 재출력 lineage가 남는다.', '다른 매장 job 조회·재출력이 차단된다.', '수동 재출력 actor/reason이 AuditRecord에 남는다.'], ['MockMvc 인가·idempotency 테스트', 'revision lineage 테스트', 'AuditRecord 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/pos/internal/*Controller.kt', 'openapi/beanflow-v1.yaml'], ['docs/operations/runbooks/print-job.md', 'openapi/beanflow-v1.yaml'])
    ]
  },
  {
    key: 'E28',
    title: '점주 매출 AI 인사이트·근거·평가 체계',
    milestone: 'P1 — 대표 확장',
    priority: 'priority:P1',
    areas: ['area:ai', 'area:analytics', 'area:security', 'area:platform'],
    risks: ['risk:data-consistency', 'risk:privacy', 'risk:external-provider'],
    goal: 'Analytics Read Model의 검증된 지표만 도구 입력으로 사용해 점주 일일 브리핑을 생성하고 숫자·기간·근거·금지 행동을 고정 평가셋으로 검증한다.',
    sources: ['docs/product/business-policy-decisions.md', 'docs/architecture/context-map.md', 'docs/analytics/metric-definitions.md', 'docs/architecture/failure-semantics.md'],
    invariants: ['AI는 원본 DB를 임의 SQL로 조회하지 않고 승인된 Analytics Tool만 사용한다.', '응답 숫자·기간·metric ID가 근거와 일치해야 한다.', '가격·정산·환불·재고·프로모션 변경을 인간 승인 없이 실행하지 않는다.', 'Provider 실패를 그럴듯한 브리핑이나 오래된 캐시로 숨기지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/intelligence/**', 'src/test/resources/ai-evals/**', 'src/test/kotlin/io/github/kdh949/beanflow/intelligence/**'],
    docs: ['docs/adr/ADR-029-ai-insight-safety.md', 'docs/ai/evaluation.md', 'docs/ai/tool-contracts.md', 'openapi/beanflow-v1.yaml'],
    done: ['일일 브리핑 Analytics Tool contract와 Prompt/Model version이 구현된다.', '응답에 기간·근거 metric과 불확실성이 표시된다.', '고정 평가셋이 숫자 일치·금지 행동·누락 데이터 처리를 검증한다.', 'Provider timeout·비용·latency·수정률이 측정되고 fallback 정책이 명시된다.'],
    dependsOn: ['E18', 'E20', 'E24'],
    tasks: [
      T('T1', '점주 일일 브리핑 Analytics Tool contract 구현', 'AI 입력을 검증된 Read Model과 store scope로 제한한다.', ['전일/전주 대비 매출·주문·AOV·환불·품절·인기 메뉴 Tool schema를 정의한다.', 'StoreAccessPolicy와 요청 기간·metric allowlist를 적용한다.', 'Tool result에 metric ID, 정의 version, period, value, data freshness를 포함한다.', 'AI가 임의 SQL/Entity/다른 매장 데이터에 접근할 Port를 제공하지 않는다.'], ['Tool 숫자가 Analytics API와 동일하다.', '다른 매장 tool 호출이 거부된다.', 'backfill required/freshness 지연이 응답 metadata에 드러난다.'], ['Tool contract 단위 테스트', 'store scope/인가 테스트', 'Analytics metric parity 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/intelligence/api/**', 'src/main/kotlin/io/github/kdh949/beanflow/intelligence/internal/**']),
      T('T2', 'LLM Adapter·근거 포함 브리핑·인간 승인 경계 구현', 'Provider 호출과 생성 결과를 명시적 상태로 관리한다.', ['Prompt version, model ID, tool schema version, input hash를 Generation record에 저장한다.', '응답 schema에 summary, findings, metric citations, suggestedActions를 제한한다.', 'suggested action은 실행 명령이 아니라 제안이며 별도 인간 승인 API 없이는 변경하지 않는다.', 'Provider timeout/failure는 FAILED/UNKNOWN으로 기록하고 fabricated response를 반환하지 않는다.'], ['모든 수치 문장에 근거 metric이 연결된다.', '금지된 mutation tool이 존재하지 않는다.', '같은 generation idempotency key가 중복 비용을 만들지 않는다.'], ['structured output parser 테스트', 'Provider timeout/malformed 테스트', 'idempotency/cost record 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/intelligence/internal/*Gateway*', 'src/main/kotlin/io/github/kdh949/beanflow/intelligence/internal/*Generation*']),
      T('T3', '고정 AI 평가셋·수치 일치·금지 행동·비용 지표 구축', '그럴듯함이 아니라 재현 가능한 품질 기준으로 브리핑을 검증한다.', ['정상·0건·급증·환불 late event·품절·backfill required fixture를 만든다.', '숫자/기간/근거 일치, unsupported claim, 금지 mutation, schema validity evaluator를 구현한다.', 'prompt/model/tool version별 latency, token/cost, refusal, human edit rate를 기록한다.', '평가 실패를 단순 retry로 숨기지 않고 release gate로 사용한다.'], ['고정 seed/dataset에서 평가를 반복할 수 있다.', '허용되지 않은 자동 실행 0건을 테스트한다.', '품질·비용 목표와 실제 측정을 구분한다.'], ['offline deterministic evaluator', 'sandbox model eval run', 'forbidden-action tests'], ['src/test/resources/ai-evals/**', 'src/test/kotlin/io/github/kdh949/beanflow/intelligence/**', 'docs/ai/evaluation.md'], ['docs/ai/evaluation.md'], ['area:performance'], 'type:spike')
    ]
  },
  {
    key: 'E29',
    title: 'Kafka Outbox·Inbox와 모듈 추출 실험',
    milestone: 'P1 — 대표 확장',
    priority: 'priority:P1',
    areas: ['area:events', 'area:platform', 'area:performance', 'area:operations'],
    risks: ['risk:data-consistency'],
    goal: 'Spring Modulith 영속 이벤트의 한계와 독립 배포 필요가 측정된 뒤 한 모듈만 Kafka 경계로 추출해 Outbox/Inbox·중복 소비·replay를 검증한다.',
    sources: ['docs/adr/ADR-009-no-initial-kafka.md', 'docs/adr/ADR-013-outbox-adoption-trigger.md', 'docs/architecture/event-catalog.md', 'docs/architecture/context-map.md'],
    invariants: ['Kafka는 이력서 목적이 아니라 명시된 adoption trigger를 충족할 때만 도입한다.', 'DB 변경과 Kafka publish를 dual write로 처리하지 않는다.', '소비자는 at-least-once 중복과 순서 뒤바뀜을 가정한다.', 'Exactly-once 표현으로 외부 API·DB 부수효과까지 자동 보장된다고 주장하지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/outbox/**', 'src/main/resources/db/migration/V27__create_outbox_inbox.sql', 'docker-compose.kafka.yml', 'src/test/kotlin/io/github/kdh949/beanflow/messaging/**'],
    docs: ['docs/adr/ADR-030-kafka-module-extraction.md', 'docs/benchmarks/event-delivery.md', 'docs/operations/runbooks/kafka-replay.md'],
    done: ['ADR-013 trigger 측정과 대상 모듈 선택 근거가 기록된다.', 'Transactional Outbox publisher와 Consumer Inbox가 구현된다.', '중복·retry·DLQ·replay·schema version 테스트가 통과한다.', '추출 전후 latency/lag/운영 비용과 되돌리기 조건이 문서화된다.'],
    dependsOn: ['E10', 'E11', 'E18', 'E24'],
    tasks: [
      T('T1', 'Kafka 도입 트리거 측정과 추출 대상 ADR 작성', '현재 Modulith 방식의 실제 병목·독립 배포 요구를 먼저 증명한다.', ['publication backlog, consumer latency, deployment coupling, throughput과 failure isolation 요구를 측정한다.', 'Notification 또는 Analytics 중 변경 빈도·복구 요구가 높은 하나만 후보로 비교한다.', 'Kafka 비용, schema governance, local/CI 복잡도와 current registry 유지 대안을 분석한다.', '도입하지 않는 결론도 유효한 결과로 기록한다.'], ['ADR-013 trigger 충족 여부에 측정 근거가 있다.', '대상 모듈과 non-goal이 명확하다.', '실측 없이 MSA/Kafka를 전제로 구현하지 않는다.'], ['metric 수집 재현', 'ADR 링크/결정 검토', 'architecture dependency baseline'], ['docs/adr/ADR-030-kafka-module-extraction.md', 'docs/benchmarks/event-delivery.md'], ['docs/adr/ADR-030-kafka-module-extraction.md'], [], 'type:spike'),
      T('T2', 'Transactional Outbox publisher·schema version·재시도 구현', '원본 DB tx와 event row를 원자적으로 저장하고 Kafka 전송을 재시작 가능하게 한다.', ['outbox event envelope, status, attempts, nextAttemptAt, publishedAt을 모델링한다.', '원본 tx에서 outbox insert하고 별도 publisher가 chunk lock 후 전송한다.', 'Kafka key를 aggregate ID로 정하고 schema/payload version compatibility 정책을 둔다.', 'broker 장애 시 PENDING/RETRY 상태와 lag metric을 유지한다.'], ['dual write 누락 경로가 없다.', 'publisher restart가 event를 유실하지 않고 중복 가능성은 소비자 계약으로 처리한다.', '운영 profile에서 broker 설정 누락을 in-memory bus로 대체하지 않는다.'], ['DB tx 원자성 테스트', 'broker outage/restart Testcontainers 테스트', 'schema version compatibility 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/outbox/**', 'src/main/resources/db/migration/V27__create_outbox_inbox.sql', 'docker-compose.kafka.yml']),
      T('T3', 'Consumer Inbox·DLQ·replay와 추출 전후 비교', '소비자 부수효과를 source event 기준으로 한 번만 적용하고 운영 replay를 통제한다.', ['consumerId+eventId Inbox Unique Constraint를 구현한다.', 'business retry와 poison payload DLQ를 구분한다.', '운영자 replay가 새 이벤트 생성이 아니라 원 event와 reason을 추적하게 한다.', 'Modulith vs Kafka의 end-to-end latency, lag, 복구 시간, 운영 복잡도를 비교한다.'], ['동일 Kafka record 반복 전달이 부수효과를 중복하지 않는다.', 'DLQ 메시지가 조용히 drop되지 않고 운영 case로 보인다.', 'replay 권한·AuditRecord·idempotency가 검증된다.'], ['Kafka duplicate/rebalance 테스트', 'DLQ/replay 통합 테스트', '전후 benchmark 실행'], ['src/main/kotlin/io/github/kdh949/beanflow/inbox/**', 'src/test/kotlin/io/github/kdh949/beanflow/messaging/**'], ['docs/operations/runbooks/kafka-replay.md', 'docs/benchmarks/event-delivery.md'], ['area:operations'], 'type:spike')
    ]
  },
  {
    key: 'E30',
    title: '배달 Fulfillment·외부 배달사 상태 대사',
    milestone: 'P2/P3 — Future Work',
    priority: 'priority:P2',
    areas: ['area:delivery', 'area:ordering', 'area:payment', 'area:operations'],
    risks: ['risk:external-provider', 'risk:data-consistency', 'risk:money'],
    goal: '픽업과 구분되는 DeliveryFulfillment에서 배달 구역·요금·요청·배차·완료·실패를 관리하고 webhook/polling 충돌을 대사한다.',
    sources: ['docs/product/non-goals.md', 'docs/architecture/context-map.md', 'docs/product/roadmap.md', 'docs/architecture/failure-semantics.md'],
    invariants: ['Delivery 상태와 기사 정보를 Order Aggregate에 모두 넣지 않는다.', '외부 Provider 호출과 Order/Payment DB tx를 길게 묶지 않는다.', 'webhook과 polling은 provider event/version/occurredAt 기준 우선순위를 가진다.', '배차 실패·기사 취소·재배차·부분 환불을 성공으로 숨기지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/delivery/**', 'src/test/kotlin/io/github/kdh949/beanflow/delivery/**'],
    docs: ['docs/adr/ADR-031-delivery-fulfillment.md', 'docs/operations/runbooks/delivery-reconciliation.md', 'openapi/beanflow-v1.yaml'],
    done: ['Delivery Context와 pickup 공통/차이 경계가 ADR로 정의된다.', 'service area·fee·Provider adapter 상태 머신이 구현된다.', 'webhook/polling duplicate/out-of-order reconcile이 검증된다.', '실패·재배차·환불과 운영 복구 runbook이 있다.'],
    dependsOn: ['E14', 'E21', 'E29'],
    tasks: [
      T('T1', 'DeliveryFulfillment·서비스 구역·배달 요금 정책 모델링', '픽업과 별도 생명주기의 배달 Aggregate를 설계한다.', ['PICKUP/DELIVERY 공통 FulfillmentType과 분리 소유권을 ADR로 정리한다.', 'DeliveryFulfillment 상태, 주소 reference, service area, fee snapshot을 정의한다.', '정밀 주소·개인정보 최소 저장과 접근 정책을 명시한다.', 'Order는 deliveryFulfillmentId만 참조한다.'], ['Order Aggregate 크기와 transaction 경계가 확장되지 않는다.', '서비스 구역 밖 주문과 fee 변경 snapshot 정책이 테스트된다.', '개인정보 보존·삭제 정책이 문서화된다.'], ['도메인 상태 테스트', '서비스 구역 경계 테스트', 'privacy/인가 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/delivery/**', 'docs/adr/ADR-031-delivery-fulfillment.md']),
      T('T2', '외부 배달사 요청·webhook·polling Adapter와 대사 구현', 'Provider 결과를 멱등하고 순서 안전하게 반영한다.', ['request idempotency key와 external delivery ID를 저장한다.', 'webhook signature/replay 방지와 polling 조회 Port를 구현한다.', 'provider event version/occurredAt으로 stale 상태를 무시하거나 case로 보낸다.', 'timeout/unknown을 FAILED로 단정하지 않고 RECONCILING으로 둔다.'], ['중복 webhook이 상태를 중복 전이하지 않는다.', 'out-of-order event가 terminal 상태를 되돌리지 않는다.', 'Provider timeout 후 polling success가 정상 복구된다.'], ['Webhook signature/replay 테스트', 'out-of-order 상태 테스트', 'timeout/poll reconciliation 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/delivery/internal/**']),
      T('T3', '배차 실패·기사 취소·재배차·환불 운영 흐름 검증', '현장 예외를 명시적 상태와 운영 case로 마무리한다.', ['assignment failure와 driver cancellation의 retry/reassignment 정책을 정의한다.', '고객/매장 취소 시 delivery fee와 주문 item 환불 정책을 분리한다.', '수동 재배차·종결에 actor/reason/AuditRecord를 요구한다.', '상태·환불 불일치 reconciliation과 runbook을 작성한다.'], ['실패 주문이 무기한 PROCESSING에 남지 않는다.', '재배차가 기존 assignment를 덮어쓰지 않고 history를 보존한다.', '환불 누적액과 delivery fee 정책이 tie-out된다.'], ['재배차 상태 머신 테스트', 'refund 연동 E2E 테스트', '운영 재처리 권한·감사 테스트'], ['src/test/kotlin/io/github/kdh949/beanflow/delivery/**', 'docs/operations/runbooks/delivery-reconciliation.md'], ['docs/operations/runbooks/delivery-reconciliation.md'])
    ]
  },
  {
    key: 'E31',
    title: '동의 기반 개인화·추천 오퍼·실험 플랫폼',
    milestone: 'P2/P3 — Future Work',
    priority: 'priority:P2',
    areas: ['area:personalization', 'area:analytics', 'area:security', 'area:ai'],
    risks: ['risk:privacy', 'risk:data-consistency'],
    goal: '개인화·광고를 단순 추천 API로 축소하지 않고 동의·행동 이벤트·세그먼트·frequency cap·A/B·attribution을 먼저 설계한다.',
    sources: ['docs/product/non-goals.md', 'docs/product/roadmap.md', 'docs/architecture/context-map.md', 'docs/adr/ADR-020-nearby-location-privacy.md'],
    invariants: ['명시적 동의와 철회 전에는 개인화 프로필을 생성하지 않는다.', '민감정보와 불필요한 정밀 위치를 타기팅에 사용하지 않는다.', 'frequency cap과 캠페인 예산·기간을 강제한다.', 'A/B holdout과 attribution window 없이 효과를 주장하지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/personalization/**', 'src/test/kotlin/io/github/kdh949/beanflow/personalization/**'],
    docs: ['docs/adr/ADR-032-personalization-consent.md', 'docs/personalization/event-schema.md', 'docs/personalization/experiments.md', 'openapi/beanflow-v1.yaml'],
    done: ['동의·철회·삭제·보존 정책과 데이터 흐름이 ADR로 확정된다.', '행동 이벤트·프로필·세그먼트·frequency cap이 구현된다.', '규칙 기반 serving과 A/B holdout·attribution이 검증된다.', 'ML ranking은 baseline 측정 뒤 별도 Revisit 조건으로 남는다.'],
    dependsOn: ['E18', 'E20', 'E28'],
    tasks: [
      T('T1', '개인화 동의·철회·삭제·행동 이벤트 계약 구현', '데이터 수집의 법적·제품적 경계를 코드와 문서로 명시한다.', ['consent purpose/version/grantedAt/withdrawnAt을 저장한다.', '동의 없는 행동 이벤트 수집·profile update를 거부한다.', '행동 event schema와 retention/deletion request 흐름을 정의한다.', '정밀 위치·결제 token·민감 데이터를 event payload에서 제외한다.'], ['철회 후 새 profile update가 없다.', '삭제 요청이 projection과 원본 event 보존 정책에 따라 추적된다.', 'consent와 event schema version이 감사 가능하다.'], ['consent 상태 테스트', 'withdraw/delete integration 테스트', 'PII schema 검사'], ['src/main/kotlin/io/github/kdh949/beanflow/personalization/**', 'docs/adr/ADR-032-personalization-consent.md']),
      T('T2', '규칙 기반 세그먼트·오퍼 serving·frequency cap 구현', 'ML 전에 설명 가능한 baseline으로 개인화 오퍼를 제공한다.', ['recency/frequency/menu affinity 등 승인된 feature로 batch segment를 만든다.', 'campaign priority/budget/period/target와 user frequency cap을 저장한다.', 'serving API가 consent, segment, cap, campaign state를 원자적으로 검증한다.', '노출 event idempotency와 low-latency query 경로를 구현한다.'], ['동의 철회 사용자에게 오퍼가 제공되지 않는다.', 'frequency cap과 budget 초과 노출이 없다.', '같은 impression request가 중복 집계되지 않는다.'], ['segment rule 테스트', 'cap/budget 동시성 테스트', 'serving latency baseline 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/personalization/internal/**', 'openapi/beanflow-v1.yaml']),
      T('T3', 'A/B holdout·노출/클릭/주문 attribution과 baseline 평가', '추천 효과를 선택 편향 없이 측정할 최소 실험 체계를 만든다.', ['experiment assignment를 user/experiment 기준 결정적으로 저장한다.', 'impression/click/order event와 attribution window를 정의한다.', 'holdout 대비 conversion/lift와 guardrail metric을 계산한다.', '규칙 baseline이 안정된 뒤 ML ranking 도입 조건을 ADR Revisit에 기록한다.'], ['사용자가 같은 experiment에서 variant를 바꾸지 않는다.', '노출 없는 주문을 추천 성과로 과대 attribution하지 않는다.', '실험 결과에 sample size, 기간, confidence/불확실성이 포함된다.'], ['assignment determinism 테스트', 'attribution window 경계 테스트', 'holdout metric fixture 테스트'], ['src/test/kotlin/io/github/kdh949/beanflow/personalization/**', 'docs/personalization/experiments.md'], ['docs/personalization/experiments.md'], ['area:performance'], 'type:spike')
    ]
  }
];
