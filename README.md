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
- [핵심 결정 요약](docs/architecture/decision-summary.md)
- [Capability Map](docs/architecture/capability-map.md)
- [실패 의미론](docs/architecture/failure-semantics.md)
- [의사결정 기록 규칙](docs/decisions/README.md)
- [테스트 전략](docs/testing/test-strategy.md)

## 현재 상태

현재 source에서 구현되고 테스트된 주요 capability:

- 주문 시점 메뉴·옵션·가격 snapshot과 쿠폰 후 포인트 배분
- 픽업 슬롯·재고·쿠폰·포인트 원자 예약과 5분 lease 만료
- 주문 생성 멱등성, 감사 기록과 `BENEFIT_ONLY` 결제
- Toss V2 Standard 일회성 결제창 준비·callback 승인·상태 조회
- 외부 결제 승인 Tx1/Provider/Tx2 분리
- 명시 거절 취소·예약 해제, `UNKNOWN` 조회 reconciliation
- 늦은 승인 void/refund 복구와 5회 후 `MANUAL_REVIEW`
- 매장 수락·거절·준비·완료 전이와 2분 경고·3분 timeout
- 거절 후 영속 publication, 전액 환불, 자원 복원과 알림 복구
- 고객의 `PENDING_PAYMENT`/미수락 `PAID` 주문 취소와 멱등 응답 재생
- 부분 환불 allocation, 환불 후 쿠폰·사용 포인트 복원과 적립 포인트 회수
- 일반 적립 정책의 감사형 조회·변경과 주문 시점 정책 snapshot
- 감사형 운영자 포인트 증감 조정
- 정산 Batch/Item 조회, append-only 정산 조정과 이의제기 접수·판정
- 고객 취소 환불 reconciliation과 보상 상태의 역할별 조회
- 인근 매장 검색과 매장 메뉴·픽업 슬롯 조회
- PointAccount summary·transaction 조회
- React/TypeScript 고객·매장·운영 콘솔과 Runtime OpenAPI 생성 client
- 로컬 데모 환경과 고객→결제→매장→포인트→부분/전액 환불 smoke flow

현재 source에 없는 capability:

- Analytics refund/late-event projection과 외부 dashboard API

검증 예정:

- 지연 Provider 환경의 부하·장애 주입 측정

고객 취소 command/recovery orchestration과 PointAccount read는 완료됐다. 실제 non-local
배포, 운영 규모, SLA와 프로덕션 안정성은 증명하지 않았다.

## 실행 방법

### 필수 환경

- Java 21
- Docker daemon
- 로컬 실행용 PostgreSQL
- JWT 검증에 사용할 JWK Set endpoint

Gradle은 별도 설치하지 않고 저장소의 Wrapper(`./gradlew`)를 사용한다. 첫 실행에는
Gradle 의존성과 Testcontainers 이미지 다운로드를 위한 네트워크 연결이 필요할 수 있다.

### 로컬 데모 환경 (가장 빠른 확인 방법)

수동 설정 없이 전체 흐름을 보려면 데모 script를 쓴다. PostGIS, ephemeral JWK set endpoint,
필수 정책 bootstrap, Spring API, React frontend, 결정적 fixture와 실제 HTTP smoke까지 한 번에 실행한다.

```bash
bash scripts/demo/start.sh && bash scripts/demo/seed.sh && bash scripts/demo/smoke.sh
```

정지와 초기화는 `bash scripts/demo/stop.sh [--reset]`이다. 인증을 끄거나 validation을
완화하지 않으며, 실행 시 생성한 키 자료는 gitignore된 `.demo-runtime/`에만 존재한다.
절차와 진단은 [Local Demo Runbook](docs/operations/local-demo-runbook.md)에 있다.
기동 뒤 고객 UI는 `http://127.0.0.1:4173/app`, 매장 콘솔은 `/store`, 운영 콘솔은 `/ops`에서
확인한다. 화면의 로컬 인증 연결 폼에는 `.demo-runtime/demo-identity.env`의 역할별 JWT를 입력한다.

아래 수동 절차는 데모 script 없이 직접 구성할 때 쓴다.

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
`PaymentGateway` 구성이 필요하며 fake/sandbox로 자동 대체되지 않는다. 실제 Toss 테스트 환경은
API 개별 연동 키 쌍(`test_ck_`/`test_sk_`)과 전용 profile group으로 기동한다.

```bash
export TOSS_CLIENT_KEY='test_ck_...'
export TOSS_SECRET_KEY='test_sk_...'
export BEANFLOW_FRONTEND_BASE_URL='http://127.0.0.1:4173'
export SPRING_PROFILES_ACTIVE='toss-sandbox-runtime'

./gradlew bootRun
```

`toss-sandbox-runtime`은 local 인프라 설정과 `toss-sandbox`를 합성하지만 scripted gateway는
제외하고 Toss V2 confirm/query/cancel HTTP adapter 하나만 선택한다. Widget 키인
`test_gck_`/`test_gsk_`, 누락·live 키와 `prod` 중첩은 시작 실패한다. 이 profile에서 기존
PaymentMethod 등록·폐기는 명시적으로 `Misconfigured`이며 fake 성공으로 대체되지 않는다. 고객
checkout은 PaymentMethod를 조회하지 않고 서버가 준비한 일회성 `CARD` Payment Window만 사용한다.
PAN, CVC와 전체 유효기간은 저장하지 않는다.

### 현재 runtime API

현재 source의 전체 `(path, HTTP method)` 목록은
[Runtime OpenAPI](openapi/beanflow-v1-runtime.yaml)가 canonical이다. Spring
`RequestMappingHandlerMapping`을 사용하는 `RuntimeOpenApiParityTest`가 모든 `/api/v1`
Controller mapping과 이 계약의 operation 집합을 양방향으로 비교하므로 README에 endpoint
목록을 중복 관리하지 않는다. 이 계약은 source 구현 증거이며 실제 배포 증거가 아니다.

### 목표 OpenAPI 계약

[OpenAPI 문서](openapi/beanflow-v1.yaml)는 현재 runtime만의 목록이 아니라 Accepted 정책이
지향하는 `/api/v1` 목표 계약이다. 문서에 endpoint가 존재한다고 runtime 구현 완료를
뜻하지 않는다. 현재 구현 endpoint도 목표 shape와 다른 경우가 있으며, 예를 들어
만료 혜택 정책 PATCH의 목표 계약은
`/operations/policies/expired-benefit-restoration/{trigger}/{benefitType}`다.

현재 구현된 controller mapping과 계약 테스트가 존재하는 shape는 Runtime OpenAPI에
분리한다. operation을 추가·제거하면 Controller와 Runtime OpenAPI를 같은 변경에서
갱신해야 하며 parity test가 drift를 차단한다.

### 계약 inventory

현재 target과 runtime의 public operation inventory는 일치하며 34 paths/37 operations다.
이는 operation 개수이며 처리량·지연·가용성 측정이 아니다. 지연 Provider 부하, 장애 주입,
실제 배포 smoke test와 SLA는 `Not measured`다.

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
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/ci/requirements-docs.txt
bash scripts/ci/test-ci-scripts.sh
PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh
git diff --check origin/main HEAD
./gradlew spotlessCheck
./gradlew build --stacktrace
```

| 명령 | 검증 내용 |
| --- | --- |
| `./gradlew test` | 단위·Application·Repository·API 계약·구조·동시성 테스트 |
| `./gradlew spotlessCheck` | 변경된 Kotlin 소스의 ktlint/Spotless 규칙 |
| `./gradlew build --stacktrace` | 컴파일, 전체 테스트와 빌드 |
| `bash scripts/ci/test-ci-scripts.sh` | CI log capture와 PR 변경 분류 |
| `bash scripts/verify-docs.sh` | 필수 문서, 내부 링크, 정책·ADR·OpenAPI 일관성 |
| `git diff --check origin/main HEAD` | PR compare 범위의 trailing whitespace 등 diff 형식 오류 |
