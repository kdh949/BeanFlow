import { epic, task } from '../core.mjs';

export const r5Epics = [
  epic({
    id: 'E21',
    title: 'OpenAPI·REST Docs·오류 계약 완성',
    milestone: 'R5',
    priority: 'P0',
    areas: ['api', 'platform'],
    currentSource: [
      '`openapi/beanflow-v1.yaml`과 주문 Controller 계약 테스트가 존재하지만 신규 도메인 계약과 공통 오류 카탈로그가 완전하지 않다.',
      'README에 현재 endpoint 3개만 명시되어 있다.',
    ],
    invariants: [
      'JPA Entity를 응답으로 노출하지 않는다.',
      '409 상태 충돌, 422 확정 거절, 503 의존성 실패, 202 불명/진행 상태를 구분한다.',
      'Idempotency-Key와 correlationId 계약을 모든 금전 명령에 일관 적용한다.',
    ],
    decisionRefs: ['docs/api/api-conventions.md', 'docs/api/error-catalog.md', 'docs/testing/definition-of-done.md'],
    tasks: [
      task(
        'T1',
        '전체 MVP 리소스·상태·오류 OpenAPI 계약 갱신',
        [
          'openapi/beanflow-v1.yaml',
          'docs/api/api-conventions.md',
          'docs/api/error-catalog.md',
        ],
        [
          'Identity부터 Analytics까지 실제 구현 endpoint만 명세한다.',
          '날짜·KRW·cursor·Idempotency-Key schema를 재사용한다.',
          'UNKNOWN·RECONCILING·MANUAL_REVIEW representation을 포함한다.',
        ],
        [
          'OpenAPI parser 검증',
          '구현 없는 예측 schema 금지 검토',
          'error code 중복 검사',
        ],
        'type:docs',
      ),
      task(
        'T2',
        'Controller별 Spring REST Docs 정상·실패 예제 보강',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/**',
          'src/docs/asciidoc/**',
        ],
        [
          '각 명령의 정상·검증·권한·상태 충돌·의존 실패를 문서화한다.',
          '고정 fixture로 재현 가능한 snippet을 만든다.',
          '민감 token·좌표를 예제에서 제거한다.',
        ],
        [
          'MockMvc snippet 생성',
          'Asciidoctor 문서 빌드',
          'OpenAPI와 응답 필드 비교',
        ],
      ),
      task(
        'T3',
        'API 계약 drift·하위 호환성 검증을 CI에 추가',
        [
          'scripts/verify-docs.sh',
          '.github/workflows/ci.yml',
          'docs/api/change-policy.md',
        ],
        [
          'OpenAPI lint와 generated snippet 누락을 CI에서 실패시킨다.',
          'breaking change 기준과 versioning 절차를 문서화한다.',
          'README endpoint 목록을 계약에서 생성하거나 검증한다.',
        ],
        [
          'CI 실패 fixture 검증',
          'git diff clean 생성 테스트',
          '문서 링크 검사',
        ],
        'type:docs',
      ),
    ],
  }),
  epic({
    id: 'E22',
    title: '보안·개인정보·감사 경계 강화',
    milestone: 'R5',
    priority: 'P0',
    areas: ['security', 'operations', 'identity', 'api'],
    risks: ['privacy'],
    currentSource: [
      '`SecurityConfiguration.kt`와 AuditRecord 구현이 존재한다.',
      '모든 신규 리소스의 권한 매트릭스, 좌표·token 로그 redaction, 수동 명령 사유 정책을 종합 검증해야 한다.',
    ],
    invariants: [
      '역할 검증만으로 객체 소유권을 대체하지 않는다.',
      '정밀 좌표·PG token·민감 payload를 로그·감사에 남기지 않는다.',
      '금액·재고·포인트·정산·권한 변경은 append-only AuditRecord를 남긴다.',
    ],
    decisionRefs: ['BR-28', 'BR-29', 'BR-30', 'ADR-022'],
    tasks: [
      task(
        'T1',
        '사용자·점주·직원·운영자 권한 매트릭스와 Method Security 적용',
        [
          'docs/security/authorization-matrix.md',
          'src/main/kotlin/io/github/kdh949/beanflow/**',
        ],
        [
          'endpoint·명령·리소스별 role과 owner check를 표로 정의한다.',
          'Application Service의 access policy를 재사용한다.',
          '운영자 수동 명령은 reason과 별도 권한을 요구한다.',
        ],
        [
          '역할×소유권 parameterized 테스트',
          '권한 상승 회귀 테스트',
          '403/404 정보 노출 검토',
        ],
      ),
      task(
        'T2',
        'Correlation ID와 구조화 로그 민감정보 redaction 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/shared/internal/**',
          'src/test/kotlin/io/github/kdh949/beanflow/shared/**',
        ],
        [
          'Servlet Filter에서 correlationId를 생성·전파한다.',
          '좌표·token·provider raw payload key를 redaction 한다.',
          '예외 응답과 로그가 같은 ID로 연결되게 한다.',
        ],
        [
          '헤더 전파 테스트',
          '로그 캡처 금지값 테스트',
          '비정상 입력 로그 주입 테스트',
        ],
      ),
      task(
        'T3',
        'AuditRecord append-only·5년 retention·수동 명령 원자성 보강',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/operations/internal/**',
          'src/test/kotlin/io/github/kdh949/beanflow/operations/**',
        ],
        [
          '모든 신규 owner Context에서 audit API를 같은 트랜잭션에 호출한다.',
          '서울 달력 5주년 chunk cleanup을 검증한다.',
          '일반 API 수정·삭제 경로가 없음을 보장한다.',
        ],
        [
          '2월29일 retention 경계 테스트',
          'cleanup 중단 재실행 테스트',
          '감사 실패 시 원본 변경 rollback 테스트',
        ],
      ),
    ],
  }),
  epic({
    id: 'E23',
    title: 'Spring Modulith·ArchUnit·JPA 구조 품질',
    milestone: 'R5',
    priority: 'P0',
    areas: ['platform', 'persistence', 'api'],
    currentSource: [
      '모듈 package-info와 공개 api/internal 패키지가 이미 존재한다.',
      '새 모듈 추가 후 순환 의존, 내부 접근, Entity 노출, Repository 경계 회귀를 자동 검증해야 한다.',
    ],
    invariants: [
      'Controller는 Repository를 직접 호출하지 않는다.',
      '다른 모듈 internal과 Aggregate Entity를 참조하지 않는다.',
      '양방향 관계·ManyToMany·Aggregate 경계 밖 Cascade는 Accepted ADR 없이 금지한다.',
    ],
    decisionRefs: ['ADR-001', 'ADR-002', 'ADR-003', 'AGENTS.md'],
    tasks: [
      task(
        'T1',
        '모든 MVP 모듈 Spring Modulith verify와 의존 허용 목록 갱신',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/ModularityTest.kt',
          'src/main/java/io/github/kdh949/beanflow/**/package-info.java',
        ],
        [
          'Identity·Discovery·Notification·Settlement·Dispute·Analytics를 모듈로 선언한다.',
          '공개 API 의존만 허용하고 cycle을 제거한다.',
          '문서 Context Map과 실제 모듈 그래프를 맞춘다.',
        ],
        [
          'ApplicationModules.verify 통과',
          '모듈 문서 생성',
          '금지 internal 접근 fixture 테스트',
        ],
      ),
      task(
        'T2',
        'ArchUnit 계층·Repository·Entity 노출 규칙 구현',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/architecture/**',
        ],
        [
          'Controller→Application→Domain/Persistence 방향을 검증한다.',
          'Repository 인터페이스와 JPA Entity가 internal에 머무는지 검사한다.',
          'API DTO가 jakarta.persistence 타입에 의존하지 않게 한다.',
        ],
        [
          '의도적 위반 fixture 실패 테스트',
          'Controller Repository 직접 호출 검사',
          'ManyToMany annotation 검사',
        ],
      ),
      task(
        'T3',
        'OSIV·Fetch 전략·DB 제약과 Query count 회귀 테스트',
        [
          'src/main/resources/application*.yaml',
          'src/test/kotlin/io/github/kdh949/beanflow/persistence/**',
          'src/main/resources/db/migration/**',
        ],
        [
          'OSIV를 비활성화하고 트랜잭션 밖 LAZY 접근을 제거한다.',
          '목록은 DTO Projection, 상세는 필요한 fetch 계획을 선택한다.',
          'Unique·CHECK·FK·인덱스가 불변식을 최종 방어하는지 검증한다.',
        ],
        [
          'LazyInitialization 없는 API 테스트',
          '목록 query count 테스트',
          'PostgreSQL 제약 위반 테스트',
        ],
      ),
    ],
  }),
  epic({
    id: 'E24',
    title: 'Micrometer·구조화 로그·운영 대시보드',
    milestone: 'R5',
    priority: 'P0',
    areas: ['platform', 'operations', 'performance'],
    currentSource: [
      'Actuator와 Spring Modulith observability 의존성은 build.gradle.kts에 있다.',
      'Prometheus/Grafana 구성, 도메인 메트릭 이름, 대시보드와 SLO는 아직 없다.',
    ],
    invariants: [
      '메트릭 label에 memberId·orderId 같은 고카디널리티 값을 넣지 않는다.',
      '실패 상태와 retry·manual review가 관측 가능해야 한다.',
      '측정하지 않은 운영 안정성을 주장하지 않는다.',
    ],
    decisionRefs: ['docs/architecture/failure-semantics.md', 'docs/testing/definition-of-done.md'],
    tasks: [
      task(
        'T1',
        '주문·결제·알림·정산 도메인 Micrometer 메트릭 표준화',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/**',
          'docs/operations/observability.md',
        ],
        [
          '명령 결과·latency·UNKNOWN·retry·manual review counter/timer를 정의한다.',
          '공통 tag는 module·operation·outcome으로 제한한다.',
          '기존 PaymentMetrics 이름과 중복을 정리한다.',
        ],
        [
          'MeterRegistry 단위 테스트',
          'tag cardinality 검사',
          '오류 경로 metric 테스트',
        ],
      ),
      task(
        'T2',
        'Prometheus·Grafana 로컬 스택과 핵심 대시보드 구성',
        [
          'docker-compose.yml',
          'docker/observability/**',
          'docs/operations/dashboards.md',
        ],
        [
          '애플리케이션 metrics scrape를 구성한다.',
          'Hikari active/pending, JVM GC, HTTP p95, lock/retry를 한 대시보드에 배치한다.',
          '필수 설정 누락 시 조용한 fallback 없이 문서화된 실패를 낸다.',
        ],
        [
          'Docker Compose smoke 테스트',
          'dashboard JSON validation',
          'scrape health 확인',
        ],
      ),
      task(
        'T3',
        'SLO·경보 조건·장애 Runbook 연결',
        [
          'docs/operations/slo.md',
          'docs/operations/runbooks/**',
        ],
        [
          '가용성·결제 UNKNOWN·알림 backlog·정산 지연의 target과 측정식을 구분한다.',
          'Target과 Measured 값을 명시적으로 구분한다.',
          'alert에서 재현·확인·복구 Runbook으로 연결한다.',
        ],
        [
          '경보 쿼리 단위 검증',
          'Runbook 링크 검사',
          '합성 장애에서 signal 발생 확인',
        ],
        'type:docs',
      ),
    ],
  }),
  epic({
    id: 'E25',
    title: '운영자 ReprocessingCase와 안전한 수동 복구',
    milestone: 'R5',
    priority: 'P0',
    areas: ['operations', 'payment', 'notification', 'analytics', 'settlement', 'security'],
    risks: ['data-consistency', 'money'],
    currentSource: [
      '`ReprocessingCaseOperations.kt`와 `ReprocessingCaseService.kt`가 결제 대사에 사용된다.',
      '알림·Analytics·Settlement까지 공통 lifecycle과 운영 API로 확장해야 한다.',
    ],
    invariants: [
      '원본 Aggregate는 Operations가 직접 수정하지 않고 owner Context 명령을 호출한다.',
      'open case type+target 중복을 막는다.',
      '수동 실행에는 권한·사유·멱등키·감사가 필요하다.',
    ],
    decisionRefs: ['BR-27', 'BR-30', 'BR-32', 'ADR-009', 'docs/architecture/context-map.md'],
    tasks: [
      task(
        'T1',
        'ReprocessingCase lifecycle·유형·소유자 확장',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/operations/internal/**',
          'src/main/resources/db/migration/V*__extend_reprocessing_case.sql',
        ],
        [
          'PAYMENT_RECONCILIATION·NOTIFICATION_DELIVERY·ANALYTICS_BACKFILL·SETTLEMENT_REPAIR 유형을 정의한다.',
          'OPEN·IN_PROGRESS·SUCCEEDED·FAILED·MANUAL_REVIEW 상태를 보호한다.',
          'type+target active unique를 추가한다.',
        ],
        [
          '상태 전이 테스트',
          '중복 case DB 테스트',
          '기존 결제 case migration 테스트',
        ],
      ),
      task(
        'T2',
        '운영자 실패 목록·상세·재처리 명령 API 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/operations/api/**',
          'src/main/kotlin/io/github/kdh949/beanflow/operations/internal/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          '상태·유형·발생시각 cursor 조회를 구현한다.',
          '재처리는 owner API를 호출하고 결과를 case에 기록한다.',
          '오류 원문 대신 안전한 summary와 correlationId를 반환한다.',
        ],
        [
          '운영자 권한 테스트',
          '같은 멱등키 재처리 테스트',
          'owner 실패 시 case 상태 테스트',
        ],
      ),
      task(
        'T3',
        '결제·알림·Analytics·정산 수동 복구 E2E 검증',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/operations/**',
          'docs/operations/runbooks/reprocessing.md',
        ],
        [
          '각 모듈의 MANUAL_REVIEW fixture를 만들고 운영 API로 복구한다.',
          '중복 부수효과가 없는지 source reference를 검증한다.',
          '모든 수동 실행을 AuditRecord로 추적한다.',
        ],
        [
          '네 유형 E2E 테스트',
          '잘못된 상태 재처리 409 테스트',
          '감사·메트릭 검증',
        ],
      ),
    ],
  }),
  epic({
    id: 'E26',
    title: '쿠폰·재고·슬롯 동시성 전략 비교',
    milestone: 'R5',
    priority: 'P0',
    areas: ['performance', 'inventory', 'fulfillment', 'promotion', 'persistence'],
    risks: ['concurrency'],
    currentSource: [
      '현재 Repository 테스트와 주문 생성 동시성 테스트가 존재한다.',
      '세 자원의 낙관·비관·조건부 UPDATE 비교 결과와 선택 근거가 하나의 재현 가능한 harness로 정리되지 않았다.',
    ],
    invariants: [
      '모든 전략은 초과 발급·oversell·capacity 초과 0을 만족해야 한다.',
      '비교는 동일 환경·데이터·VU로 수행한다.',
      '실측하지 않은 수치를 문서에 쓰지 않는다.',
    ],
    decisionRefs: ['ADR-005', 'ADR-024', 'docs/testing/test-strategy.md'],
    tasks: [
      task(
        'T1',
        '동시성 benchmark fixture·합성 데이터·k6 harness 통일',
        [
          'load-tests/concurrency/**',
          'scripts/seed-concurrency-data.*',
          'docs/benchmarks/concurrency-environment.md',
        ],
        [
          'PostgreSQL 버전·CPU·메모리·pool·데이터 크기를 고정한다.',
          '쿠폰·재고·슬롯에 같은 VU/ramp 패턴을 적용한다.',
          '정확성 검증 SQL을 테스트 종료 후 자동 실행한다.',
        ],
        [
          'seed 재실행 멱등성',
          '정확성 검증 자동 실패',
          '환경 manifest 저장',
        ],
        'type:spike',
      ),
      task(
        'T2',
        '낙관·비관·조건부 UPDATE 구현 변형과 지표 수집',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/**',
          'load-tests/concurrency/**',
        ],
        [
          '기능 flag가 아니라 benchmark 전용 명시적 variant로 격리한다.',
          'RPS·p50/p95/p99·error·retry·lock wait·pool pending을 수집한다.',
          'deadlock과 timeout 오류를 분리한다.',
        ],
        [
          '세 전략 동일 시나리오',
          '초과 수량 0 검증',
          '반복 실행 편차 기록',
        ],
        'type:spike',
      ),
      task(
        'T3',
        '결과 보고서·선택 ADR·재검토 조건 작성',
        [
          'docs/benchmarks/coupon-concurrency.md',
          'docs/benchmarks/inventory-concurrency.md',
          'docs/benchmarks/pickup-concurrency.md',
          'docs/adr/**',
        ],
        [
          '충돌률별 장단점과 실패 가능성을 비교한다.',
          '현재 MVP 기본 전략과 대안을 명시한다.',
          'Redis 도입은 측정된 DB 병목 조건으로만 재검토한다.',
        ],
        [
          'Before/After 표 검증',
          '원시 결과 링크 검사',
          'ADR Verification/Metrics 갱신',
        ],
        'type:docs',
      ),
    ],
  }),
  epic({
    id: 'E27',
    title: '주문 조회·공간 검색·정산 쿼리 실행계획 최적화',
    milestone: 'R5',
    priority: 'P0',
    areas: ['performance', 'ordering', 'discovery', 'settlement', 'analytics', 'persistence'],
    currentSource: [
      '주문 상세는 OrderLine 별도 Repository 조회를 사용하고 대규모 목록·집계는 아직 없다.',
      'PostGIS·Settlement·Analytics는 실제 데이터 규모의 EXPLAIN 근거가 필요하다.',
    ],
    invariants: [
      '추측으로 인덱스를 추가하지 않는다.',
      '목록은 Aggregate 전체 로딩보다 Projection을 우선 검토한다.',
      '변경 전후 데이터·쿼리·환경을 동일하게 유지한다.',
    ],
    decisionRefs: ['docs/testing/test-strategy.md', 'AGENTS.md'],
    tasks: [
      task(
        'T1',
        '주문 목록 N+1 기준선과 DTO Projection 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/**',
          'src/test/kotlin/io/github/kdh949/beanflow/ordering/**',
          'docs/benchmarks/order-list-query.md',
        ],
        [
          '고객·매장 주문 목록 필드를 먼저 확정한다.',
          '100건 조회 SQL 수와 p95 기준선을 측정한다.',
          '페이지네이션 가능한 DTO Projection으로 변경하고 query count 회귀 테스트를 둔다.',
        ],
        [
          '변경 전 SQL 캡처',
          'Projection 결과 동일성',
          '페이지 경계 테스트',
        ],
        'type:spike',
      ),
      task(
        'T2',
        'PostGIS·Analytics·Settlement Query EXPLAIN 분석과 최소 인덱스 적용',
        [
          'src/main/resources/db/migration/**',
          'docs/benchmarks/**',
        ],
        [
          '각 Query에 1만·10만 이상 합성 데이터를 준비한다.',
          'EXPLAIN (ANALYZE, BUFFERS)의 actual rows·scan·sort·buffer를 기록한다.',
          '실제 조건과 정렬 순서에 맞는 최소 복합 인덱스를 적용한다.',
        ],
        [
          '인덱스 사용 계획 검증',
          '쓰기 비용 측정',
          '통계 갱신 후 재측정',
        ],
        'type:spike',
      ),
      task(
        'T3',
        '쿼리 성능 회귀 기준과 CI smoke 검증 추가',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/performance/**',
          'scripts/verify-query-count.sh',
          'docs/testing/performance-regression.md',
        ],
        [
          'SQL 수는 결정적 테스트로 고정한다.',
          '시간 threshold는 CI 변동을 고려해 별도 benchmark로 분리한다.',
          '실행계획 핵심 조건을 snapshot 또는 수동 검토 절차로 남긴다.',
        ],
        [
          'query count CI 실패 테스트',
          'benchmark 명령 재현',
          '문서 링크 검증',
        ],
      ),
    ],
  }),
  epic({
    id: 'E28',
    title: 'k6 부하·Provider 장애·worker 중단 복원',
    milestone: 'R5',
    priority: 'P0',
    areas: ['performance', 'platform', 'payment', 'notification', 'settlement', 'operations'],
    risks: ['external-provider', 'data-consistency'],
    currentSource: [
      'README는 지연 Provider 부하·장애 주입을 검증 예정으로 표시한다.',
      '개별 단위·통합 테스트는 있으나 smoke/average/spike/stress/soak와 장애 보고서가 없다.',
    ],
    invariants: [
      '부하 결과는 테스트 환경과 함께 기록한다.',
      '외부 timeout을 확정 실패로 바꾸지 않는다.',
      'worker 재시작 후 terminal state 또는 재처리 가능한 상태가 보장되어야 한다.',
    ],
    decisionRefs: ['docs/testing/test-strategy.md', 'docs/architecture/failure-semantics.md'],
    tasks: [
      task(
        'T1',
        'k6 주문·결제·검색·상태 조회 시나리오와 threshold 구현',
        [
          'load-tests/k6/**',
          'scripts/load-test.sh',
        ],
        [
          'smoke·average·spike·stress·soak를 분리한다.',
          'seed·인증 token·Idempotency-Key 생성을 재현 가능하게 한다.',
          'RPS·p50/p95/p99·error type을 출력한다.',
        ],
        [
          'smoke CI 선택 실행',
          'threshold 실패 확인',
          '결과 JSON 보존',
        ],
        'type:spike',
      ),
      task(
        'T2',
        'PG·알림 Provider timeout과 worker 중단 장애 주입',
        [
          'docker/faults/**',
          'src/test/kotlin/io/github/kdh949/beanflow/resilience/**',
        ],
        [
          '지연·connection reset·ACK 유실·malformed 응답을 주입한다.',
          '결제·환불 UNKNOWN, 알림 retry, 정산 worker 재시작을 검증한다.',
          'DB·Redis·Kafka 자동 local fallback은 추가하지 않는다.',
        ],
        [
          'fault별 명시 상태 테스트',
          '재시작 후 stuck PROCESSING 0 검증',
          'manual review 생성 검증',
        ],
        'type:spike',
      ),
      task(
        'T3',
        '장애 보고서와 Before/After 운영 지표 작성',
        [
          'docs/incidents/payment-connection-pool.md',
          'docs/incidents/notification-provider.md',
          'docs/incidents/settlement-worker-restart.md',
        ],
        [
          '증상→재현→기준선→가설→분석→최소 변경→재측정 순서로 기록한다.',
          '실패한 실험과 남은 한계를 포함한다.',
          'Runbook과 regression test를 연결한다.',
        ],
        [
          '원시 로그·metric 링크 검사',
          '수치 출처 검토',
          '회귀 명령 재실행',
        ],
        'type:docs',
      ),
    ],
  }),
  epic({
    id: 'E29',
    title: 'MVP E2E·배포·README 공개 릴리스',
    milestone: 'R5',
    priority: 'P0',
    areas: ['platform', 'api', 'operations', 'security'],
    currentSource: [
      'README는 구현·예정 범위를 구분하지만 전체 고객→점주→운영자 데모와 배포 절차는 아직 없다.',
      '현재 빌드는 Java 21·PostgreSQL·JWK 설정을 요구한다.',
    ],
    invariants: [
      '실제 운영·프로덕션 규모를 과장하지 않는다.',
      '모의/sandbox Provider와 실제 자금 비취급 범위를 명시한다.',
      '필수 설정 누락과 운영 fake 선택은 시작 실패해야 한다.',
    ],
    decisionRefs: ['README.md', 'docs/testing/definition-of-done.md', 'AGENTS.md'],
    tasks: [
      task(
        'T1',
        '고객·점주·운영자 End-to-End 시나리오 자동화',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/e2e/**',
          'scripts/demo-seed.*',
        ],
        [
          '매장 검색→주문→결제→수락→준비→완료→포인트→정산을 검증한다.',
          '환불→Adjustment→Dispute와 알림 실패 재처리를 별도 시나리오로 둔다.',
          '각 단계 correlationId를 저장해 거래를 추적한다.',
        ],
        [
          '정상 E2E',
          '결제 UNKNOWN 복구 E2E',
          '확정 정산 후 환불 E2E',
        ],
      ),
      task(
        'T2',
        'Docker Compose 실행 환경·설정 검증·health/readiness 구현',
        [
          'docker-compose.yml',
          'src/main/resources/application*.yaml',
          'docs/operations/local-run.md',
        ],
        [
          'PostgreSQL/PostGIS·JWK dev server·관측 stack을 명시한다.',
          '필수 환경변수 validation과 readiness를 구현한다.',
          '운영 profile에서 scripted PG가 활성화되면 시작 실패한다.',
        ],
        [
          'clean environment startup 테스트',
          '설정 누락 실패 테스트',
          'readiness dependency 테스트',
        ],
      ),
      task(
        'T3',
        'README·아키텍처·ERD·성능 결과·릴리스 체크리스트 정리',
        [
          'README.md',
          'docs/architecture/**',
          'docs/benchmarks/**',
          'docs/releases/mvp.md',
        ],
        [
          '현재 구현 endpoint와 데모 명령을 최신화한다.',
          'Context Map·거래 흐름·핵심 ADR·실측 결과를 연결한다.',
          'Future Work와 미측정·미검증 범위를 명확히 표시한다.',
        ],
        [
          '문서 링크 검사',
          '새 환경 README 재현',
          'release checklist 전체 확인',
        ],
        'type:docs',
      ),
    ],
  }),
];
