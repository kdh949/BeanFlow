import { epic, task } from '../core.mjs';

export const r1Epics = [
  epic({
    id: 'E01',
    title: '회원·인증·매장 객체 수준 권한',
    milestone: 'R1',
    priority: 'P0',
    areas: ['identity', 'security', 'persistence'],
    risks: ['privacy'],
    currentSource: [
      '`src/main/kotlin/io/github/kdh949/beanflow/shared/internal/SecurityConfiguration.kt`에 OAuth2 Resource Server 설정은 있으나 Identity 모듈과 StoreMembership 쓰기 모델은 없다.',
      '현재 Controller는 인증 actor와 매장 소유권을 공통 정책으로 검증하지 않는다.',
    ],
    invariants: [
      '역할이 있어도 자신이 소속되지 않은 매장 리소스에는 접근할 수 없다.',
      '필수 JWK 설정이 없으면 시작에 실패하고 임의 local 인증으로 대체하지 않는다.',
      '권한 변경은 AuditRecord와 같은 트랜잭션에서 기록한다.',
    ],
    decisionRefs: ['BR-30', 'ADR-002', 'ADR-003', 'docs/architecture/context-map.md'],
    tasks: [
      task(
        'T1',
        'Identity 모듈과 StoreMembership 영속 모델 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/identity/**',
          'src/main/resources/db/migration/V*__create_identity.sql',
        ],
        [
          'Member·StoreMembership Aggregate와 상태 전이를 정의한다.',
          'memberId+storeId+role 중복을 DB Unique Constraint로 막는다.',
          '다른 모듈에는 식별자와 membership 조회 Application API만 공개한다.',
        ],
        [
          '도메인 상태 전이 단위 테스트',
          'PostgreSQL Testcontainers 매핑·제약 테스트',
          'Spring Modulith 공개 API 검증',
        ],
      ),
      task(
        'T2',
        'JWT actor 해석과 StoreAccessPolicy 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/shared/internal/SecurityConfiguration.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/identity/api/**',
        ],
        [
          'JWT subject·role claim을 CurrentActor로 변환한다.',
          'Application Service에서 storeId membership을 검증하는 StoreAccessPolicy를 구현한다.',
          'Controller에서 Repository를 직접 조회하지 않는다.',
        ],
        [
          '유효·만료·잘못된 JWT 테스트',
          '다른 매장 접근 403 테스트',
          'JWK 설정 누락 시작 실패 테스트',
        ],
      ),
      task(
        'T3',
        '회원·매장 소속 관리 API와 보안 계약 테스트 추가',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/identity/internal/**',
          'openapi/beanflow-v1.yaml',
          'src/test/kotlin/io/github/kdh949/beanflow/identity/**',
        ],
        [
          '운영자용 membership 생성·비활성 API를 추가한다.',
          'Method Security와 객체 수준 권한을 함께 적용한다.',
          '권한 변경 사유와 correlationId를 AuditRecord에 남긴다.',
        ],
        [
          'MockMvc 401·403·404 계약 테스트',
          '권한 변경과 AuditRecord 원자성 테스트',
          'OpenAPI·REST Docs 검증',
        ],
      ),
    ],
  }),
  epic({
    id: 'E02',
    title: '매장·메뉴·옵션·영업시간 관리',
    milestone: 'R1',
    priority: 'P0',
    areas: ['merchant', 'persistence', 'api'],
    currentSource: [
      '`merchant/api/MenuQuoteUseCase.kt`, `MerchantPersistence.kt`, `JpaMenuQuoteService.kt`는 주문용 quote 조회를 제공한다.',
      '매장·메뉴·옵션·영업시간을 관리하는 명령 API와 상태 전이는 아직 없다.',
    ],
    invariants: [
      '폐점·휴점 매장과 판매 중지 메뉴는 새 주문에 사용할 수 없다.',
      '가격은 정수 KRW이며 음수가 될 수 없다.',
      'MenuConfiguration은 정규화된 option ID 집합별로 유일하다.',
    ],
    decisionRefs: ['BR-01', 'BR-02', 'ADR-004', 'ADR-026'],
    tasks: [
      task(
        'T1',
        'Store·BusinessHours·PickupPolicy 모델과 Flyway 마이그레이션 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/domain/**',
          'src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/MerchantPersistence.kt',
          'src/main/resources/db/migration/V*__create_store_policy.sql',
        ],
        [
          'Store 상태와 Asia/Seoul 영업시간을 모델링한다.',
          '임시 휴점과 주문 가능 상태를 구분한다.',
          '겹치는 영업시간·음수 준비시간을 DB와 도메인에서 거부한다.',
        ],
        [
          '자정 경계·휴무일 단위 테스트',
          'Flyway·Repository 통합 테스트',
          '동시 수정 optimistic lock 테스트',
        ],
      ),
      task(
        'T2',
        '메뉴·옵션·MenuConfiguration 관리 API 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/merchant/api/**',
          'src/main/kotlin/io/github/kdh949/beanflow/merchant/internal/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          'Menu·Option·MenuConfiguration 명령 Application Service를 구현한다.',
          'optionIds 중복을 거부하고 정렬된 집합으로 유일성을 보장한다.',
          'sellableUnitRequirement는 양수만 허용한다.',
        ],
        [
          '메뉴 가격·판매 상태 테스트',
          '동일 option 집합 중복 생성 테스트',
          '매장 소유권 API 계약 테스트',
        ],
      ),
      task(
        'T3',
        'MenuQuote 조회와 관리 모델의 일관성·회귀 테스트 보강',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/merchant/**',
          'src/test/kotlin/io/github/kdh949/beanflow/ordering/**',
        ],
        [
          '관리 API 변경 후 기존 주문 quote 계약이 유지되는지 검증한다.',
          '가격·이름·옵션·sellable requirement가 주문 시점 snapshot으로 복사되는지 검증한다.',
          '조회 편의를 위해 Ordering이 Merchant Entity를 참조하지 않게 한다.',
        ],
        [
          '과거 주문 snapshot 불변 테스트',
          '판매 중지·휴점 quote 거부 테스트',
          'N+1·쿼리 수 회귀 테스트',
        ],
      ),
    ],
  }),
  epic({
    id: 'E03',
    title: 'PostGIS 기반 가까운 매장 검색',
    milestone: 'R1',
    priority: 'P0',
    areas: ['discovery', 'merchant', 'persistence', 'performance'],
    risks: ['privacy'],
    currentSource: [
      '현재 `discovery` 모듈과 PostGIS 마이그레이션이 없다.',
      '아키텍처 문서는 `geography(Point,4326)`, GiST, 정밀 위치 비보존을 결정했다.',
    ],
    invariants: [
      '사용자 원본 좌표는 요청 처리 중에만 사용하고 DB·로그에 저장하지 않는다.',
      '반경 필터는 `ST_DWithin`, 정렬은 `(distance, storeId)`로 결정적이어야 한다.',
      'Discovery는 Merchant 쓰기 Entity를 검색 편의로 확장하지 않는다.',
    ],
    decisionRefs: ['BR-28', 'ADR-020', 'docs/architecture/context-map.md'],
    tasks: [
      task(
        'T1',
        'Discovery Read Model·PostGIS 스키마·GiST 인덱스 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/discovery/**',
          'src/main/resources/db/migration/V*__create_discovery.sql',
        ],
        [
          'PostGIS extension과 geography(Point,4326) 컬럼을 추가한다.',
          'storeId를 projection 키로 사용하고 영업·픽업 가능 검색 필드를 분리한다.',
          'GiST 인덱스와 갱신용 source version을 추가한다.',
        ],
        [
          'PostGIS Testcontainers 기동 테스트',
          '공간 타입·인덱스 존재 검증',
          'projection 중복 이벤트 멱등 테스트',
        ],
      ),
      task(
        'T2',
        '반경·거리순·영업 상태 필터와 커서 API 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/discovery/api/**',
          'src/main/kotlin/io/github/kdh949/beanflow/discovery/internal/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          'lat/lng/radius 입력 범위를 검증한다.',
          'ST_DWithin 후 거리와 storeId로 정렬하고 cursor를 인코딩한다.',
          'openNow·pickupAvailable 필터를 Query Repository에서 적용한다.',
        ],
        [
          '반경 경계 안팎 테스트',
          '거리 동률 cursor 누락·중복 0 테스트',
          '잘못된 좌표 400 계약 테스트',
        ],
      ),
      task(
        'T3',
        '위치정보 비보존과 공간 검색 실행계획 검증',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/discovery/**',
          'docs/benchmarks/nearby-store-postgis.md',
        ],
        [
          '구조화 로그와 trace에 원본 좌표가 남지 않게 마스킹한다.',
          '1만·10만 합성 매장 데이터를 생성한다.',
          'EXPLAIN (ANALYZE, BUFFERS)에서 GiST 사용과 p95를 기록한다.',
        ],
        [
          '로그 좌표 누출 테스트',
          '인덱스 유무 실행계획 비교',
          '동일 조건 k6 검색 시나리오',
        ],
        'type:spike',
      ),
    ],
  }),
  epic({
    id: 'E04',
    title: '픽업 슬롯 관리와 수용량 동시성',
    milestone: 'R1',
    priority: 'P0',
    areas: ['fulfillment', 'persistence', 'performance'],
    risks: ['concurrency'],
    currentSource: [
      '`fulfillment/api/PickupReservationOperations.kt`와 `PickupReservationService.kt`는 주문 예약·확정·해제를 지원한다.',
      '슬롯 생성·조회·수용량 관리 API와 영업 정책 검증은 아직 없다.',
    ],
    invariants: [
      'reserved+confirmed는 capacity를 초과할 수 없다.',
      '주문당 active PickupReservation은 하나다.',
      '5분 lease 이후 확정할 수 없고 승인·만료 경쟁은 guarded transition으로 결정한다.',
    ],
    decisionRefs: ['BR-03', 'BR-05', 'ADR-005', 'ADR-013'],
    tasks: [
      task(
        'T1',
        'PickupSlot Aggregate와 매장 슬롯 관리 API 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/fulfillment/internal/domain/**',
          'src/main/kotlin/io/github/kdh949/beanflow/fulfillment/internal/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          'storeId·startAt·endAt·capacity를 가진 PickupSlot을 도입한다.',
          '영업시간·최소 준비시간 밖 슬롯을 거부한다.',
          '겹치는 슬롯과 음수 capacity를 제약한다.',
        ],
        [
          '시간 경계 단위 테스트',
          'Repository·Unique Constraint 테스트',
          '매장 권한 API 계약 테스트',
        ],
      ),
      task(
        'T2',
        '슬롯 예약 조건부 갱신과 잠금 순서 고정',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/fulfillment/internal/PickupReservationPersistence.kt',
          'src/main/kotlin/io/github/kdh949/beanflow/fulfillment/internal/PickupReservationService.kt',
        ],
        [
          'capacity 조건을 포함한 원자 UPDATE 또는 행 잠금 중 현재 SQL에 맞는 방식을 구현한다.',
          'Order→Pickup 잠금 순서를 유지한다.',
          'update count 0은 명시적 SLOT_FULL 오류로 변환한다.',
        ],
        [
          '마지막 한 자리 50동시 요청 테스트',
          '확정·해제 중복 멱등 테스트',
          'lock wait와 실패율 측정',
        ],
      ),
      task(
        'T3',
        '픽업 슬롯 lease·긴급 휴점·경계 회귀 테스트',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/fulfillment/**',
          'src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/ReservationExpiryTest.kt',
        ],
        [
          '결제 승인과 lease 만료 경합을 반복 검증한다.',
          '긴급 휴점 시 신규 예약 차단과 기존 결제 주문 처리 경계를 문서화한다.',
          'worker와 요청 기반 expiry가 같은 결과를 반환하게 한다.',
        ],
        [
          '고정 Clock 5분 경계 테스트',
          '휴점 전후 API 테스트',
          '중단·재실행 중복 해제 0 테스트',
        ],
      ),
    ],
  }),
  epic({
    id: 'E05',
    title: '판매 재고 관리와 oversell 방지',
    milestone: 'R1',
    priority: 'P0',
    areas: ['inventory', 'persistence', 'operations', 'performance'],
    risks: ['concurrency'],
    currentSource: [
      '`inventory/api/StockReservationOperations.kt`와 `StockReservationPersistence.kt`는 주문별 재고 예약을 제공한다.',
      '점주 재고 관리·수동 조정·조회 API와 비교 가능한 동시성 측정은 없다.',
    ],
    invariants: [
      'available·reserved·committed 수량은 음수가 될 수 없다.',
      '주문·sellableUnit별 active 예약은 하나다.',
      '수동 조정에는 actor·사유·before·after 감사 기록이 필수다.',
    ],
    decisionRefs: ['BR-04', 'ADR-005', 'ADR-026', 'BR-30'],
    tasks: [
      task(
        'T1',
        'SellableStock 관리·수동 조정 API와 감사 기록 구현',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/inventory/internal/**',
          'src/main/kotlin/io/github/kdh949/beanflow/inventory/api/**',
          'openapi/beanflow-v1.yaml',
        ],
        [
          '점주가 판매 단위 재고를 설정·증감하는 명령을 추가한다.',
          '음수 결과를 도메인과 DB CHECK로 막는다.',
          '조정 사유와 AuditRecord를 같은 트랜잭션에 기록한다.',
        ],
        [
          '음수 조정 거부 테스트',
          '권한·사유 누락 계약 테스트',
          '감사 원자성 테스트',
        ],
      ),
      task(
        'T2',
        '재고 예약·확정·복원의 DB 최종 방어 보강',
        [
          'src/main/kotlin/io/github/kdh949/beanflow/inventory/internal/StockReservationPersistence.kt',
          'src/main/resources/db/migration/V*__harden_stock.sql',
        ],
        [
          'available>=requested 조건부 갱신을 명시한다.',
          'active order/sellableUnit partial unique를 추가한다.',
          '확정·복원 source reference 중복을 막는다.',
        ],
        [
          '마지막 재고 동시 예약 테스트',
          '중복 승인 이중 차감 0 테스트',
          '거절·만료 이중 복원 0 테스트',
        ],
      ),
      task(
        'T3',
        '재고 경합 전략 비교와 운영 지표 작성',
        [
          'src/test/kotlin/io/github/kdh949/beanflow/inventory/**',
          'load-tests/inventory-concurrency.js',
          'docs/benchmarks/inventory-concurrency.md',
        ],
        [
          '낙관·비관·조건부 UPDATE를 동일 데이터·VU로 비교한다.',
          'p50/p95/p99, RPS, lock wait, 충돌률을 수집한다.',
          '측정 결과로 선택 ADR과 재검토 조건을 갱신한다.',
        ],
        [
          'oversell 0 검증',
          '동일 부하 반복 측정',
          '회귀 threshold 정의',
        ],
        'type:spike',
      ),
    ],
  }),
];
