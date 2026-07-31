# BeanFlow

BeanFlow는 다점포 카페의 선주문부터 결제, 픽업, 포인트, 정산, 환불 조정과 이의제기까지 이어지는 거래 생명주기를 다루는 플랫폼이다.

카페 선주문은 단순한 주문 CRUD가 아니다. 짧은 시간 안에 메뉴 가격, 픽업 수용량, 판매 재고, 쿠폰, 포인트, 외부 결제와 정산이 서로 다른 실패 모델 속에서도 일관성을 유지해야 한다.

BeanFlow는 다음 원칙을 중심으로 이 문제를 해결한다.

- 명시적인 Bounded Context와 Aggregate 경계
- 주문 당시 가격·정책 스냅샷
- 멱등 결제와 결과 불명 상태의 reconciliation
- 확정 정산을 덮어쓰지 않는 조정 원장
- 중복 이벤트와 재처리를 가정한 소비자
- 실패를 숨기지 않는 상태·오류·관측 체계
- 실제 PostgreSQL을 사용한 통합·동시성·실행계획 검증

## 문서

- [제품 개요](docs/product/product-overview.md)
- [비즈니스 정책](docs/product/business-policy-decisions.md)
- [End-to-End 흐름](docs/product/end-to-end-flow.md)
- [아키텍처 개요](docs/architecture/architecture-overview.md)
- [실패 의미론](docs/architecture/failure-semantics.md)
- [의사결정 기록 규칙](docs/decisions/README.md)
- [테스트 전략](docs/testing/test-strategy.md)

## 현재 상태

구현됨:

- 주문 시점 메뉴·옵션·가격 snapshot과 쿠폰 후 포인트 배분
- 픽업 슬롯·재고·쿠폰·포인트 원자 예약과 5분 lease 만료
- 주문 생성 멱등성, 감사 기록과 `BENEFIT_ONLY` 결제
- 외부 결제 승인 Tx1/Provider/Tx2 분리
- 명시 거절 취소·예약 해제, `UNKNOWN` 조회 reconciliation
- 늦은 승인 void/refund 복구와 5회 후 `MANUAL_REVIEW`
- 매장 수락·거절·준비·완료 전이와 2분 경고·3분 timeout
- 거절 후 영속 publication, 전액 환불, 자원 복원과 알림 복구

후속 확장:

- 실제 PG sandbox adapter
- 결제수단 등록·폐기 API

검증 예정:

- 지연 Provider 환경의 부하·장애 주입 측정

예정:

- 고객 주문 취소와 부분 환불 allocation 기반
- 주문 완료 후 포인트 적립
- 정산, 환불, 정산 조정과 이의제기

아직 측정하지 않은 실제 운영 규모나 프로덕션 안정성을 주장하지 않는다.

## 실행 방법

### 필수 환경

- Java 21
- Docker daemon
- 로컬 실행용 PostgreSQL
- JWT 검증에 사용할 JWK Set endpoint

Gradle은 별도 설치하지 않고 저장소의 Wrapper(`./gradlew`)를 사용한다. 첫 실행에는
Gradle 의존성과 Testcontainers 이미지 다운로드를 위한 네트워크 연결이 필요할 수 있다.

### PostgreSQL 준비

아래 명령은 테스트와 같은 PostgreSQL 17.6을 로컬 `5432` 포트에 실행한다.

```bash
docker run --name beanflow-postgres --rm -d \
  -e POSTGRES_DB=beanflow \
  -e POSTGRES_USER=beanflow \
  -e POSTGRES_PASSWORD=beanflow \
  -p 5432:5432 \
  postgres:17.6
```

이미 `5432` 포트를 사용 중이면 기존 PostgreSQL을 사용하거나 포트 매핑과
`BEANFLOW_DB_URL`을 함께 변경한다.

### 애플리케이션 실행

BeanFlow는 필수 DB 또는 JWK 설정이 없으면 시작에 실패한다. JWK endpoint는 이 저장소에
포함되어 있지 않으므로 로컬 OIDC/JWT 제공자의 실제 주소를 지정해야 한다.

```bash
export BEANFLOW_DB_URL='jdbc:postgresql://localhost:5432/beanflow'
export BEANFLOW_DB_USERNAME='beanflow'
export BEANFLOW_DB_PASSWORD='beanflow'
export BEANFLOW_JWK_SET_URI='http://localhost:8081/.well-known/jwks.json'
export SPRING_PROFILES_ACTIVE='local'

./gradlew bootRun
```

애플리케이션은 기본적으로 `http://localhost:8080`에서 시작한다. Flyway가 시작 시
스키마를 생성·검증하며, 다음 요청으로 기동 상태를 확인할 수 있다.

```bash
curl http://localhost:8080/actuator/health
```

`/actuator/health`를 제외한 endpoint는 Bearer JWT가 필요하다. 주문 API용 JWT의
`sub`는 UUID 형식의 고객 ID여야 하고, `roles` claim에 `CUSTOMER`가 포함되어야 한다.

`local` profile에서만 scripted 결제 adapter가 활성화된다. 운영 profile에는 실제
`PaymentGateway` 구성이 필요하며 fake/sandbox로 자동 대체되지 않는다. 결제수단 등록
API와 초기 데이터 seed는 아직 없으므로 주문 흐름을 수동 확인하려면 매장·메뉴·슬롯·
재고와 토큰 reference만 가진 결제수단 fixture를 별도로 준비해야 한다. PAN, CVC와 전체
유효기간은 저장하지 않는다. 현재 구현된 API 계약은
[OpenAPI 문서](openapi/beanflow-v1.yaml)에서 확인할 수 있다.

현재 구현된 HTTP endpoint:

```text
POST /api/v1/orders
GET  /api/v1/orders/{orderId}
POST /api/v1/orders/{orderId}/payment-confirmations
```

로컬 PostgreSQL을 위의 일회성 컨테이너로 실행했다면 다음 명령으로 종료한다.

```bash
docker stop beanflow-postgres
```

## 테스트 방법

전체 테스트는 PostgreSQL Testcontainers를 사용하므로 Docker daemon이 실행 중이어야
한다. 별도의 테스트 DB나 `BEANFLOW_*` 환경 변수는 필요하지 않다.

```bash
./gradlew test
```

특정 테스트 클래스만 실행할 수도 있다.

```bash
# Docker가 필요 없는 순수 도메인 테스트
./gradlew test --tests 'io.github.kdh949.beanflow.ordering.OrderTest'

# PostgreSQL Testcontainers를 사용하는 결제 통합 테스트
./gradlew test --tests \
  'io.github.kdh949.beanflow.ordering.internal.PaymentConfirmationIntegrationTest'
```

테스트 결과 HTML은 `build/reports/tests/test/index.html`에 생성된다.

PR 전 로컬 검증은 CI와 같은 순서로 실행한다.

```bash
./gradlew spotlessCheck
./gradlew clean build --stacktrace
bash scripts/verify-docs.sh
git diff --check
```

| 명령 | 검증 내용 |
| --- | --- |
| `./gradlew test` | 단위·Application·Repository·API 계약·구조·동시성 테스트 |
| `./gradlew spotlessCheck` | 변경된 Kotlin 소스의 ktlint/Spotless 규칙 |
| `./gradlew clean build --stacktrace` | 컴파일, 전체 테스트와 빌드 |
| `bash scripts/verify-docs.sh` | 필수 문서, 내부 링크, 정책·ADR·OpenAPI 일관성 |
| `git diff --check` | trailing whitespace 등 diff 형식 오류 |
