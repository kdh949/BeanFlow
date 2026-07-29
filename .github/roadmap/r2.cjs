const T = (key, title, goal, work, acceptance, tests, files = [], docs = [], labels = [], type = 'type:task') => ({ key, title, goal, work, acceptance, tests, files, docs, labels, type });

module.exports = [
  {
    key: 'E10',
    title: '영속 이벤트 발행과 재시작 복구 기반',
    milestone: 'R2 — 이벤트·알림·로열티·환불',
    priority: 'priority:P0',
    areas: ['area:events', 'area:platform', 'area:persistence', 'area:operations'],
    risks: ['risk:data-consistency'],
    goal: '현재 동기 주문·결제 구현 위에 원본 트랜잭션과 함께 영속 publication을 기록하고 재시작·중복 전달에서도 후속 소비자가 안전하게 처리되도록 한다.',
    sources: ['docs/adr/ADR-010-initial-event-publication.md', 'docs/architecture/event-catalog.md', 'docs/architecture/transaction-boundaries.md', 'build.gradle.kts', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/**'],
    invariants: ['이미 확정된 사실의 후속 처리를 in-memory event만으로 완료했다고 간주하지 않는다.', '모든 영속 이벤트는 eventId, eventType, aggregateId/version, occurredAt, payloadVersion, correlationId, causationId를 가진다.', '소비자는 중복 전달을 가정하고 source reference Unique Constraint로 부수효과를 보호한다.', 'publication 실패를 로그만 남기고 완료 처리하지 않는다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/shared/events/**', 'src/main/resources/db/migration/V15__create_event_publications.sql', 'src/test/kotlin/io/github/kdh949/beanflow/events/**', 'build.gradle.kts'],
    docs: ['docs/adr/ADR-010-initial-event-publication.md', 'docs/architecture/event-catalog.md', 'docs/operations/reprocessing.md'],
    done: ['Spring Modulith Event Publication Registry 또는 ADR에 맞는 영속 publication 방식이 설정된다.', 'OrderReady·OrderCompleted·PaymentRefunded 등 첫 이벤트가 원본 tx와 함께 저장된다.', '소비자 성공 전 publication이 완료 처리되지 않는다.', '재시작·중복·실패·수동 재처리 테스트가 통과한다.'],
    dependsOn: ['E08'],
    tasks: [
      T('T1', 'Spring Modulith 영속 Event Publication Registry 구성', 'ADR-010을 실제 스키마와 Spring 설정으로 구체화한다.', ['현재 Spring Modulith 2.1.0 의존성과 Boot 4.1.0 호환 구성을 확인한다.', 'JPA 기반 publication registry 스키마를 Flyway가 소유하도록 구성하고 자동 DDL에 의존하지 않는다.', 'event completion, retry count, last failure, published/completed 시각을 운영자가 추적할 수 있게 한다.', 'local/test에서도 required DB가 없을 때 in-memory event store로 자동 전환하지 않는다.'], ['원본 비즈니스 tx rollback 시 publication row도 남지 않는다.', '원본 tx commit 시 publication이 같은 DB에 존재한다.', '애플리케이션 재시작 후 미완료 publication을 다시 찾을 수 있다.'], ['Testcontainers 원자성 테스트', 'migration 검증', '필수 설정 누락 startup failure 테스트'], ['build.gradle.kts', 'src/main/resources/db/migration/V15__create_event_publications.sql', 'src/main/kotlin/io/github/kdh949/beanflow/shared/events/**']),
      T('T2', '공통 이벤트 envelope와 Order·Payment 이벤트 발행 구현', '문서의 event catalog를 타입 안전한 공개 계약으로 옮긴다.', ['공통 EventEnvelope와 payloadVersion 정책을 정의한다.', 'OrderPaid/Rejected/Ready/Completed 및 PaymentRefunded/Unknown/Reconciled 중 현재 구현 가능한 이벤트를 발행한다.', 'Entity나 외부 PG SDK 타입을 payload에 노출하지 않는다.', 'correlationId와 causationId를 요청·worker·reconciliation 흐름에서 전파한다.'], ['같은 Aggregate version에서 동일 event type이 중복 저장되지 않는다.', 'payload가 소비자에 필요한 immutable snapshot ID와 금액만 포함한다.', '문서 이벤트 이름과 코드 타입이 일치한다.'], ['event envelope 단위 테스트', 'Ordering/Payment publication 통합 테스트', '중복 상태 명령 event count 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/shared/events/**', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/api/**Event*.kt', 'src/main/kotlin/io/github/kdh949/beanflow/payment/api/**Event*.kt']),
      T('T3', '이벤트 재시도·중복 소비·운영 재처리 검증', 'publication과 consumer 실패가 관측되고 안전하게 재실행되는 운영 경로를 만든다.', ['고의로 실패하는 consumer로 retry/last failure/completion 상태를 재현한다.', '동일 event를 여러 번 전달해 owner 원장의 Unique Constraint가 중복 부수효과를 막는지 검증한다.', 'Operations ReprocessingCase에서 승인된 publication 재처리 명령을 제공한다.', '무한 재시도 대신 정책상 terminal/manual 상태와 metric을 둔다.'], ['consumer 실패 후 publication이 완료 상태가 아니다.', '재시작 후 한 번 성공하면 추가 중복 부수효과 없이 완료된다.', '운영 재처리는 actor·reason·source event를 AuditRecord에 남긴다.'], ['consumer failure/restart 통합 테스트', '동일 event 병렬 소비 테스트', '운영 재처리 권한·감사 테스트'], ['src/test/kotlin/io/github/kdh949/beanflow/events/**', 'src/main/kotlin/io/github/kdh949/beanflow/operations/**'], ['docs/operations/reconciliation.md', 'docs/operations/runbooks/event-publication.md'])
    ]
  },
  {
    key: 'E11',
    title: '준비 완료 알림·재시도·수동 복구',
    milestone: 'R2 — 이벤트·알림·로열티·환불',
    priority: 'priority:P0',
    areas: ['area:notification', 'area:events', 'area:operations', 'area:api'],
    risks: ['risk:external-provider', 'risk:data-consistency'],
    goal: 'OrderReady와 수락 경고를 소비해 앱 내·모의 메시지 알림을 발송하고 1분·5분·30분 재시도 뒤 MANUAL_REVIEW로 운영한다.',
    sources: ['docs/adr/ADR-019-notification-retry-and-manual-recovery.md', 'docs/product/business-policy-decisions.md', 'docs/architecture/event-catalog.md', 'src/main/kotlin/io/github/kdh949/beanflow/operations/api/ReprocessingCaseOperations.kt'],
    invariants: ['알림 실패는 주문 상태를 롤백하지 않는다.', 'event+recipient+channel은 하나의 delivery idempotency scope다.', 'Provider timeout과 ACK 유실에서 중복 발송과 누락을 함께 다룬다.', '총 4회 실패 후 MANUAL_REVIEW이며 운영 재처리는 같은 delivery key를 사용한다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/notification/**', 'src/main/resources/db/migration/V16__create_notification_delivery.sql', 'src/test/kotlin/io/github/kdh949/beanflow/notification/**'],
    docs: ['docs/adr/ADR-019-notification-retry-and-manual-recovery.md', 'docs/operations/runbooks/notification.md', 'openapi/beanflow-v1.yaml'],
    done: ['Notification 모듈·Delivery Aggregate·Provider Port가 구현된다.', 'OrderReady와 StoreAcceptanceWarningRequested가 멱등 소비된다.', '재시도·MANUAL_REVIEW·운영자 재처리 경로가 구현된다.', '주문 상태와 delivery 상태 독립성 테스트가 통과한다.'],
    dependsOn: ['E07', 'E08', 'E10'],
    tasks: [
      T('T1', 'NotificationDelivery Aggregate·스키마·Provider Port 구현', '알림 요청과 실제 Provider 전달 상태를 독립 Aggregate로 관리한다.', ['PENDING, PROCESSING, DELIVERED, RETRY_SCHEDULED, MANUAL_REVIEW 상태와 attempt metadata를 정의한다.', '`event_id + recipient_id + channel` Unique Constraint를 추가한다.', 'NotificationProvider Port가 success, definitive failure, timeout/unknown을 구분해 반환하게 한다.', '앱 내 저장 Adapter와 명시적 local profile의 scripted message Adapter를 구현한다.'], ['운영 profile에서 fake provider가 선택되면 startup이 실패한다.', '원본 event 중복으로 delivery row가 추가되지 않는다.', 'Provider 민감 payload가 로그·AuditRecord에 남지 않는다.'], ['상태 머신 단위 테스트', 'PostgreSQL unique/transition 테스트', 'profile/startup failure 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/notification/**', 'src/main/resources/db/migration/V16__create_notification_delivery.sql']),
      T('T2', 'OrderReady·수락 경고 소비와 1m/5m/30m 재시도 worker 구현', '영속 이벤트에서 delivery를 생성하고 정책 시간에 재시도한다.', ['OrderReady와 StoreAcceptanceWarningRequested consumer를 구현한다.', '첫 실패 뒤 1분, 5분, 30분 시각을 고정 Clock으로 계산한다.', 'due delivery를 제한된 chunk로 lock하고 외부 호출은 DB tx 밖에서 실행한다.', '결과 저장 실패나 Provider timeout은 성공으로 표시하지 않는다.'], ['총 네 번째 실패 후 MANUAL_REVIEW다.', '재시도 worker 중단·재실행이 attempt를 중복 증가시키지 않는다.', 'DELIVERED delivery는 다시 Provider를 호출하지 않는다.'], ['Clock 경계 테스트', 'worker restart/parallel execution 테스트', 'timeout→성공 및 4회 실패 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/notification/internal/*Consumer.kt', 'src/main/kotlin/io/github/kdh949/beanflow/notification/internal/*Worker.kt']),
      T('T3', '알림 조회·수동 재처리 API와 ACK 유실 테스트 추가', '고객과 운영자가 delivery 상태를 확인하고 실패를 안전하게 복구하도록 한다.', ['고객 알림 목록 API를 recipient 기준 Projection으로 구현한다.', '운영자 MANUAL_REVIEW 목록과 reason 필수 재처리 명령을 추가한다.', 'Provider가 발송했지만 응답이 유실된 scripted scenario를 구현한다.', 'Provider idempotency key와 BeanFlow delivery key의 계약을 문서화한다.'], ['고객은 다른 사용자의 알림을 조회할 수 없다.', '운영 재처리는 동일 delivery를 새 row로 복제하지 않는다.', 'ACK 유실 후 Provider 중복 전송 여부와 한계를 테스트·문서에 명시한다.'], ['MockMvc 소유권·운영자 권한 테스트', 'ACK 유실 복구 테스트', 'AuditRecord/ReprocessingCase 원자성 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/notification/internal/*Controller.kt', 'src/test/kotlin/io/github/kdh949/beanflow/notification/**', 'openapi/beanflow-v1.yaml'], ['docs/operations/runbooks/notification.md', 'openapi/beanflow-v1.yaml'])
    ]
  },
  {
    key: 'E12',
    title: '포인트 적립·만료·환불 회수',
    milestone: 'R2 — 이벤트·알림·로열티·환불',
    priority: 'priority:P0',
    areas: ['area:loyalty', 'area:events', 'area:operations', 'area:persistence'],
    risks: ['risk:money', 'risk:data-consistency', 'risk:concurrency'],
    goal: '현재 주문 생성의 PointLot 예약·확정 경계를 확장해 OrderCompleted 적립, 선소멸 만료, 환불 복원·회수와 POINT_RECOVERY_PENDING을 원장으로 구현한다.',
    sources: ['src/main/kotlin/io/github/kdh949/beanflow/loyalty/api/PointReservationOperations.kt', 'src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/PointReservationService.kt', 'src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/PointReservationPersistence.kt', 'docs/adr/ADR-011-point-lot-ledger.md', 'docs/product/business-policy-decisions.md'],
    invariants: ['PointAccount 가용 잔액과 PointLot/원장 합계는 대사 가능해야 한다.', '사용은 expiresAt, pointLotId 순서이며 잔액을 음수로 만들지 않는다.', 'OrderCompleted source order당 적립은 한 번이다.', '환불 회수 부족액은 POINT_RECOVERY_PENDING으로 남고 이후 적립을 우선 상계한다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/loyalty/**', 'src/main/resources/db/migration/V17__extend_loyalty_ledger.sql', 'src/test/kotlin/io/github/kdh949/beanflow/loyalty/**'],
    docs: ['docs/adr/ADR-011-point-lot-ledger.md', 'docs/product/business-policy-decisions.md', 'docs/operations/runbooks/point-reconciliation.md', 'openapi/beanflow-v1.yaml'],
    done: ['LoyaltyProgram·PointTransaction·적립 PointLot이 구현된다.', '만료 worker가 available만 chunk 만료하고 reserved allocation을 침범하지 않는다.', '부분·전체 환불의 사용 포인트 복원과 적립 포인트 회수가 멱등 처리된다.', '잔액 대사와 고객 포인트 내역 API가 구현된다.'],
    dependsOn: ['E08', 'E10'],
    tasks: [
      T('T1', 'OrderCompleted 기반 포인트 적립과 LoyaltyProgram 구현', '실결제액 기준 정책으로 발급 주체별 포인트를 한 번만 적립한다.', ['LoyaltyProgram의 issuerType/issuerId/accrual rate/expiration policy를 구현한다.', 'OrderCompleted consumer가 coupon과 points 사용 후 실결제액을 기준으로 적립액을 계산한다.', 'source order Unique Constraint로 PointTransaction과 PointLot 중복 생성을 막는다.', 'POINT_RECOVERY_PENDING이 있으면 새 적립을 먼저 상계하고 남은 금액만 Lot으로 발급한다.'], ['0원 주문과 환불된 금액에는 적립이 없다.', '같은 OrderCompleted 재전달이 잔액을 변경하지 않는다.', '발급 주체와 expiresAt이 PointLot snapshot에 남는다.'], ['적립 정책 단위 테스트', '중복 event 통합 테스트', 'pending 상계·원장 tie-out 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/**', 'src/main/resources/db/migration/V17__extend_loyalty_ledger.sql']),
      T('T2', 'PointLot 만료 worker와 잔액 reconciliation 구현', '만료 가능한 available 포인트를 제한된 chunk로 소멸하고 요약과 원장을 대사한다.', ['`expiresAt <= now`인 available 금액만 `(expiresAt,id)` 순서로 lock한다.', 'reserved allocation은 lease owner가 해제·확정하도록 건드리지 않는다.', 'EXPIRE PointTransaction과 Account/Lot 요약을 같은 tx에서 갱신한다.', 'Account summary와 ledger/lot 합계 차이를 탐지해 ReprocessingCase를 생성한다.'], ['worker 중단·재실행이 중복 만료를 만들지 않는다.', '만료와 주문 예약/확정 경쟁에서 음수·이중 사용이 없다.', '대사 차이를 자동 수정하지 않고 명시적 운영 case로 남긴다.'], ['Clock 경계·chunk restart 테스트', '만료-vs-reservation 동시성 테스트', '의도적 불일치 reconciliation 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/*Expiration*', 'src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/*Reconciliation*']),
      T('T3', '환불 포인트 복원·적립 회수와 고객 내역 API 구현', '품목별 snapshot을 사용해 사용 포인트를 복원하고 적립 포인트 비용을 회수한다.', ['PaymentRefunded payload의 refund line/reference를 사용해 원 사용 allocation으로 RESTORE한다.', '환불액에 대응하는 적립 PointLot available을 회수하고 부족하면 POINT_RECOVERY_PENDING 원장을 만든다.', '같은 refund reference 중복 전달을 Unique Constraint로 막는다.', '잔액·소멸 예정·PointTransaction cursor API를 구현한다.'], ['환불 후 PointAccount/PointLot/Transaction 합계가 일치한다.', '이미 사용한 적립분 때문에 환불 자체가 실패하지 않는다.', '고객은 자신의 account만 조회한다.'], ['부분 환불 배분·복원 테스트', '중복 refund event 테스트', '내역 cursor·인가 API 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/*Refund*', 'src/main/kotlin/io/github/kdh949/beanflow/loyalty/internal/*Controller.kt', 'openapi/beanflow-v1.yaml'], ['docs/operations/runbooks/point-reconciliation.md', 'openapi/beanflow-v1.yaml'])
    ]
  },
  {
    key: 'E13',
    title: '결제수단 생명주기와 PG sandbox Adapter',
    milestone: 'R2 — 이벤트·알림·로열티·환불',
    priority: 'priority:P0',
    areas: ['area:payment', 'area:security', 'area:api'],
    risks: ['risk:external-provider', 'risk:privacy', 'risk:money'],
    goal: '현재 개발 fixture token에 의존하는 PaymentMethod를 등록·조회·폐기 가능한 모델로 만들고 명시적 설정의 PG sandbox Adapter를 추가한다.',
    sources: ['README.md', 'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/PaymentGateway.kt', 'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/LocalPaymentGatewayConfiguration.kt', 'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/PaymentPersistence.kt', 'docs/adr/ADR-021-payment-method-tokenization.md'],
    invariants: ['PAN, CVC, 전체 유효기간을 Entity·API·로그에 저장하지 않는다.', 'member/provider/token reference 범위는 유일하다.', '폐기되거나 다른 사용자의 token은 승인에 사용할 수 없다.', '운영 profile에서 sandbox/fake 자동 fallback을 금지한다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/payment/**', 'src/main/resources/db/migration/V18__create_payment_methods.sql', 'src/test/kotlin/io/github/kdh949/beanflow/payment/**', 'openapi/beanflow-v1.yaml'],
    docs: ['docs/adr/ADR-021-payment-method-tokenization.md', 'docs/architecture/failure-semantics.md', 'docs/operations/runbooks/payment-provider.md', 'openapi/beanflow-v1.yaml'],
    done: ['PaymentMethod Aggregate와 등록·목록·폐기 API가 구현된다.', 'Payment confirmation이 fixture가 아닌 소유권 검증된 PaymentMethod를 사용한다.', 'PG sandbox Adapter가 explicit profile/config로만 활성화된다.', '민감정보 부재·Provider timeout·startup failure 테스트가 통과한다.'],
    dependsOn: ['E01'],
    tasks: [
      T('T1', 'PaymentMethod Aggregate·토큰 메타데이터 스키마 구현', 'PG token reference와 표시용 정보만 BeanFlow가 소유한다.', ['PaymentMethod 상태 ACTIVE/REVOKED와 owner memberId, provider, tokenReference, alias, brand, last4를 모델링한다.', '민감 필드가 코드·migration·DTO에 존재하지 않게 정적/반사 검증을 추가한다.', '`member_id + provider + token_reference` Unique Constraint를 적용한다.', '폐기 시 과거 Payment reference는 유지하되 신규 승인 사용을 막는다.'], ['PAN/CVC/full expiry 필드가 없다.', '동일 token 중복 등록 정책이 DB에서 보강된다.', 'REVOKED token으로 approval Tx1이 시작되지 않는다.'], ['도메인 상태 테스트', 'PostgreSQL unique/ownership 테스트', '민감 필드 reflection/serialization 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/payment/internal/domain/PaymentMethod.kt', 'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/PaymentPersistence.kt', 'src/main/resources/db/migration/V18__create_payment_methods.sql']),
      T('T2', '결제수단 등록·목록·폐기 API와 소유권 검증 구현', '고객이 자신의 Provider token을 안전하게 관리하도록 한다.', ['`POST/GET /api/v1/payment-methods`, `DELETE /{id}` 계약을 구현한다.', '현재 인증 actor를 owner로 강제하고 요청 body의 memberId를 신뢰하지 않는다.', 'tokenReference 전체를 응답·로그에 노출하지 않고 provider/alias/brand/last4만 반환한다.', 'PaymentConfirmationService가 PaymentMethod owner/status를 Tx1에서 검증한다.'], ['다른 사용자의 결제수단 조회·폐기·사용은 거부된다.', '폐기 API는 idempotent 결과를 정의한다.', 'API와 REST Docs에 저장 금지 데이터가 포함되지 않는다.'], ['MockMvc 소유권·마스킹 테스트', '결제 승인 연결 통합 테스트', '삭제/폐기 idempotency 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/payment/internal/*PaymentMethod*', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/PaymentConfirmationService.kt', 'openapi/beanflow-v1.yaml']),
      T('T3', '명시적 PG sandbox Adapter와 장애 계약 구현', '실제 HTTP 경계를 검증하되 설정 누락을 local fake로 숨기지 않는다.', ['PaymentGateway Port의 approve/query/void/refund 계약을 sandbox HTTP Adapter로 구현한다.', 'connect/read timeout, 인증, provider idempotency key와 응답 번역을 구성한다.', 'sandbox profile과 required credentials를 명시하고 prod에서 sandbox 선택 시 startup 실패하게 한다.', 'WireMock 또는 test server로 승인·거절·timeout·malformed 응답을 재현한다.'], ['Provider latency 동안 DB connection을 점유하지 않는다.', 'timeout/malformed 응답은 definitive decline이 아니라 UNKNOWN이다.', 'credential/token이 구조화 로그에 노출되지 않는다.'], ['HTTP adapter 계약 테스트', 'PaymentConnectionBoundaryTest 확장', 'prod profile startup failure·secret redaction 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/payment/internal/*Sandbox*', 'src/test/kotlin/io/github/kdh949/beanflow/payment/**', 'src/main/resources/application*.yaml'], ['docs/operations/runbooks/payment-provider.md'])
    ]
  },
  {
    key: 'E14',
    title: '고객 취소·전체 환불·품목 부분 환불',
    milestone: 'R2 — 이벤트·알림·로열티·환불',
    priority: 'priority:P0',
    areas: ['area:ordering', 'area:payment', 'area:loyalty', 'area:settlement', 'area:operations'],
    risks: ['risk:money', 'risk:external-provider', 'risk:data-consistency', 'risk:concurrency'],
    goal: '주문 불변 snapshot을 유지하면서 허용 상태의 고객 취소, 매장·운영자 전체/품목 부분 환불과 Provider reconciliation을 구현한다.',
    sources: ['docs/adr/ADR-014-money-allocation-and-partial-refund.md', 'docs/architecture/transaction-boundaries.md', 'docs/product/business-policy-decisions.md', 'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/domain/Payment.kt', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/domain/Order.kt'],
    invariants: ['고객은 PENDING_PAYMENT 또는 PAID이면서 ACCEPTED 전까지만 직접 취소한다.', '결제 후 OrderLine을 수정하지 않고 품목 snapshot 기준 Refund를 추가한다.', '누적 환불 현금액은 승인액을 넘지 않고 같은 line을 중복 환불하지 않는다.', 'Refund REQUESTED tx, 외부 Provider 호출, 결과 tx를 분리하고 timeout은 UNKNOWN이다.'],
    files: ['src/main/kotlin/io/github/kdh949/beanflow/ordering/**', 'src/main/kotlin/io/github/kdh949/beanflow/payment/**', 'src/main/resources/db/migration/V19__create_refunds.sql', 'src/test/kotlin/io/github/kdh949/beanflow/payment/**'],
    docs: ['docs/adr/ADR-014-money-allocation-and-partial-refund.md', 'docs/architecture/transaction-boundaries.md', 'docs/api/error-catalog.md', 'openapi/beanflow-v1.yaml'],
    done: ['결제 전 취소와 결제 후 refund 흐름이 구분된다.', 'Refund Aggregate·멱등성·provider reconciliation이 구현된다.', '품목별 cash/points/coupon snapshot으로 부분 환불을 재현한다.', 'Loyalty/Settlement 후속 이벤트와 금액 tie-out 테스트가 통과한다.'],
    dependsOn: ['E07', 'E10', 'E12', 'E13'],
    tasks: [
      T('T1', '고객 주문 취소 유스케이스와 상태별 보상 구현', 'BR-14에 따라 ACCEPTED 전 취소를 한 번만 확정한다.', ['PENDING_PAYMENT 취소는 Order CANCELLED와 슬롯·재고·쿠폰·포인트 예약 해제를 한 로컬 tx에서 처리한다.', 'PAID 취소는 Order 원본 전이를 확정한 뒤 별도 Refund 명령을 시작한다.', 'ACCEPTED 이후 고객 취소는 409로 거부한다.', 'Idempotency-Key와 AuditRecord를 적용한다.'], ['중복 취소가 자원 수량이나 Refund를 두 번 변경하지 않는다.', 'owner release 하나가 실패하면 결제 전 취소 tx 전체가 rollback된다.', '결제 후 CANCELLED가 환불 성공을 의미하지 않는다.'], ['상태별 취소 단위·통합 테스트', '중복 취소 동시성 테스트', 'release 실패 rollback 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/ordering/internal/*Cancellation*', 'src/main/kotlin/io/github/kdh949/beanflow/ordering/api/**']),
      T('T2', 'Refund Aggregate와 외부 전체·품목 부분 환불 구현', 'Refund 요청·Provider·결과를 분리하고 누적액 불변식을 Payment owner가 보호한다.', ['Refund 상태 REQUESTED/PROCESSING/SUCCEEDED/UNKNOWN/FAILED/MANUAL_REVIEW를 정의한다.', 'refund idempotency scope와 원 Payment/line source reference Unique Constraint를 둔다.', 'OrderLine cashPayableKrw와 pointsAppliedKrw snapshot으로 품목 환불 금액을 계산한다.', 'Provider 호출을 DB tx 밖에서 실행하고 timeout은 조회 reconciliation 대상으로 남긴다.'], ['누적 cash refund가 Payment approved amount 이하이다.', 'couponDiscount는 현금으로 환급되지 않는다.', '같은 line/refund key 재요청이 Provider 부수효과를 반복하지 않는다.'], ['Refund 상태 머신·금액 단위 테스트', 'PostgreSQL 누적/unique 동시성 테스트', 'Provider success/decline/timeout 통합 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/payment/internal/domain/Refund.kt', 'src/main/kotlin/io/github/kdh949/beanflow/payment/internal/*Refund*', 'src/main/resources/db/migration/V19__create_refunds.sql']),
      T('T3', '환불 reconciliation·포인트 복원·정산 후속 이벤트 검증', '환불 성공 사실을 각 owner Context가 한 번만 반영하고 UNKNOWN을 운영 가능하게 한다.', ['Refund UNKNOWN 조회 일정을 결제와 동일한 10s/30s/2m/5m/15m 정책으로 구현하거나 ADR 차이를 기록한다.', 'PaymentRefunded 이벤트에 refund/line/amount/source 정보를 포함한다.', 'Loyalty가 사용 포인트 복원과 적립 포인트 회수를 멱등 처리하도록 연결한다.', 'Settlement는 미확정 Item 수정 또는 확정 후 Adjustment의 입력으로 사용한다.'], ['5회 후 계속 불명이면 MANUAL_REVIEW와 단일 ReprocessingCase가 남는다.', '중복 PaymentRefunded가 포인트·정산을 이중 변경하지 않는다.', '승인액=잔여 cash+환불 cash tie-out이 테스트된다.'], ['reconciliation worker restart 테스트', 'Loyalty/Settlement consumer 중복 테스트', '전체·부분 환불 E2E 금액 tie-out 테스트'], ['src/main/kotlin/io/github/kdh949/beanflow/payment/internal/*RefundReconciliation*', 'src/test/kotlin/io/github/kdh949/beanflow/payment/**', 'src/test/kotlin/io/github/kdh949/beanflow/loyalty/**'], ['docs/operations/runbooks/refund-reconciliation.md'])
    ]
  }
];
