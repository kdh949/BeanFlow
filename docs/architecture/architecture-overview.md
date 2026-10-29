# Architecture Overview

## Architectural style

BeanFlow는 Spring Modulith를 사용한 모듈러 모놀리스로, 도메인별 코드를 나누고 하나의 애플리케이션으로 실행한다.

```text
Customer / Store / Operations API
               |
         Spring MVC
               |
      Spring Modulith Modules
               |
 PostgreSQL / PostGIS / External Adapters
```

모듈마다 다음 규칙을 적용한다.

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
- Promotion
- Loyalty
- Payment
- Settlement
- Dispute
- Notification
- Analytics
- Operations

도메인은 여러 Bounded Context로 나누지만 애플리케이션은 한 번에 배포한다.

## Synchronous versus event communication

동기 호출:

- 현재 요청 성공 여부를 결정하는 검증·예약
- 메뉴 가격과 판매 가능 여부
- 슬롯·쿠폰·포인트 예약
- 사용자의 명령에 대한 즉시 충돌 응답

이벤트:

- 완료된 주문·결제 이후의 처리
- 포인트 적립
- 정산 항목 생성
- 알림 요청
- 분석 Read Model 갱신

이벤트 중복, 처리 순서 변경, 재시도와 실패한 작업의 재실행 방법을 이벤트별로 정한다.

## Persistence

- PostgreSQL을 원본 데이터 저장소로 사용한다.
- 위치 검색은 PostGIS `geography(Point, 4326)`와 GiST 인덱스를 검토한다.
- H2를 PostgreSQL 대체재로 사용하지 않는다.
- 같은 Aggregate 내부 생명주기에만 JPA 객체 연관관계를 적극 검토한다.
- 목록과 집계 조회는 JPA Aggregate 전체 로딩 대신 Projection 또는 Query Repository를 사용한다.

## External systems

- PG, 알림, 시간, UUID와 이벤트 발행을 Port로 추상화한다.
- mock/fake Adapter는 test 또는 명시적 local profile에서만 활성화한다.
- 외부 HTTP를 호출하는 sandbox Adapter도 명시적 sandbox profile과 `!prod`에서만 활성화하고
  테스트 키 접두사를 검사한다. local scripted adapter, 외부 sandbox, live adapter는 각기 다른 profile에서 실행한다.
- 필수 Port가 없거나 adapter가 중복 선택되면 애플리케이션 시작 단계에서 오류를 반환한다.
  `@ConditionalOnMissingBean`을 이용한 scripted/fake/no-op 자동 대체는 사용하지 않는다.
- 외부 호출을 장시간 DB 트랜잭션 안에서 수행하지 않는다.
- timeout은 실패와 구분된 unknown 상태가 필요할 수 있다.

## Evolution

다음 조건이 측정되면 일부 추출을 재검토한다.

- 독립 배포·확장 요구
- 모듈별 장애 격리 요구
- 정산 배치가 온라인 거래에 영향을 줌
- Notification 소비량 또는 Provider 장애가 독립 운영을 요구
- 이벤트 replay와 여러 독립 소비자가 필요

현재는 한 애플리케이션으로 배포하며, 모듈별 독립 배포가 필요해지면 분리를 검토한다.
