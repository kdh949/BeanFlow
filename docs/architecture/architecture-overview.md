# Architecture Overview

## Architectural style

BeanFlow는 DDD 기반 Modular Monolith로 시작한다.

```text
Customer / Store / Operations API
               |
         Spring MVC
               |
      Spring Modulith Modules
               |
 PostgreSQL / PostGIS / External Adapters
```

초기에는 하나의 배포 단위를 유지하지만 다음을 강제한다.

- 모듈별 데이터 소유권
- 공개 Application API와 이벤트
- 다른 모듈의 내부 패키지 접근 금지
- Aggregate 간 ID 참조
- Spring Modulith와 ArchUnit 구조 검증

## Candidate modules

- Identity
- Merchant
- Discovery
- Ordering
- Fulfillment
- Inventory
- Promotion
- Loyalty
- Payment
- Settlement
- Dispute
- Notification
- Analytics
- Operations

Bounded Context 수는 배포 서비스 수와 같지 않다.

## Synchronous versus event communication

동기 호출:

- 현재 요청 성공 여부를 결정하는 검증·예약
- 메뉴 가격과 판매 가능 여부
- 슬롯·재고·쿠폰·포인트 예약
- 사용자의 명령에 대한 즉시 충돌 응답

이벤트:

- 이미 확정된 사실에 대한 후속 처리
- 포인트 적립
- 정산 항목 생성
- 알림 요청
- 분석 Read Model 갱신

이벤트를 사용하면 중복, 순서 역전, 재시도와 replay를 명시한다.

## Persistence

- PostgreSQL을 원본 데이터 저장소로 사용한다.
- 위치 검색은 PostGIS `geography(Point, 4326)`와 GiST 인덱스를 검토한다.
- H2를 PostgreSQL 대체재로 사용하지 않는다.
- 같은 Aggregate 내부 생명주기에만 JPA 객체 연관관계를 적극 검토한다.
- 대량 목록·집계는 JPA Aggregate 로딩이 아닌 Projection 또는 Query Repository를 사용한다.

## External systems

- PG, 알림, 시간, UUID와 이벤트 발행을 Port로 추상화한다.
- mock/fake Adapter는 test 또는 명시적 local profile에서만 활성화한다.
- 외부 HTTP를 호출하는 sandbox Adapter도 명시적 sandbox profile과 `!prod`에서만 활성화하고
  test credential prefix를 검증한다. scripted local, external sandbox와 향후 live Adapter를 같은
  fallback 축으로 취급하지 않는다.
- 필요한 Port가 없거나 adapter 조건이 겹치면 시작을 실패시킨다. `@ConditionalOnMissingBean`
  scripted/fake/no-op fallback은 두지 않는다.
- 외부 호출을 장시간 DB 트랜잭션 안에서 수행하지 않는다.
- timeout은 실패와 구분된 unknown 상태가 필요할 수 있다.

## Evolution

다음 조건이 측정되면 일부 추출을 재검토한다.

- 독립 배포·확장 요구
- 모듈별 장애 격리 요구
- 정산 배치가 온라인 거래에 영향을 줌
- Notification 소비량 또는 Provider 장애가 독립 운영을 요구
- 이벤트 replay와 여러 독립 소비자가 필요

물리적 분리는 측정과 운영 요구에 따른 결정이며 초기 목표가 아니다.
