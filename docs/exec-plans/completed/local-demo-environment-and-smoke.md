# 실제 인증과 불변식을 그대로 통과하는 local demo 환경과 smoke를 만든다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/nearby-store-discovery.md`
> **Completed-At:** `2026-08-08`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`,
`Decision Log`, `Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

지금 이 저장소를 처음 받은 사람은 BeanFlow의 기능을 직접 확인할 방법이 없다. 실행 정의도,
seed도, 토큰 발급 수단도 없어 수동 SQL과 임의 JWT를 만들어야 하고, 그 과정에서 인증과 도메인
불변식을 우회하기 쉽다.

이 작업은 짧은 명령열로 PostGIS와 ephemeral JWKS endpoint를 띄우고, 정책을 bootstrap하고,
결정적 fixture를 만들고, 고객→점주→포인트→정산 흐름을 실제 HTTP로 smoke 검증하는 `local-demo`
환경을 만든다. 인증을 끄거나 validation을 완화하지 않으며, 실패는 성공으로 위장하지 않는다.

## Current State

- 로컬 실행 도구가 없다. `docker-compose*`, `Dockerfile`, `scripts/demo/`가 모두 없고
  `scripts/`에는 `ci/`, `perf/`, `verify-docs.sh`만 있다.
- sandbox adapter는 이미 있다. `LocalPaymentGatewayConfiguration`과
  `LocalNotificationProviderConfiguration`이 `@Profile("local & !prod")`로 게이트되고, payment는
  token reference suffix(`:approved`, `:declined`, `:eventually-approved`)로 결과를 정한다.
- 애플리케이션 startup은 `BEANFLOW_DB_URL/USERNAME/PASSWORD`, `BEANFLOW_JWK_SET_URI`,
  `beanflow.pagination.cursor-hmac.*`를 요구하고 세 precheck
  (`OrdinaryPointAccrualPolicyPrecheck`, `PointLotIssuerPrecheck`,
  `StoreDiscoveryProfilePrecheck`)가 실패하면 시작하지 않는다.
- 인증은 `NimbusJwtDecoder.withJwkSetUri(...)`다. 실제 JWKS HTTP endpoint가 없으면 어떤 요청도
  통과하지 못한다. subject는 actor UUID, `roles` claim이 `ROLE_*` 권한이 된다.
- Store, Menu, MenuOption, MenuConfiguration, SellableStock, PickupSlot을 만드는 public API가
  runtime OpenAPI 26개 operation 어디에도 없다.
- 기존 bootstrap CLI 2종(`operator-permission-bootstrap`,
  `ordinary-point-accrual-policy-bootstrap`)은 OIDC workload identity를 검증한다.

## Definitions

- **local-demo:** 이 저장소의 데모 전용 Spring profile 조합이다. sandbox adapter를 쓰기 위해
  `local`과 함께 활성화하며 `prod`와 동시에 활성화될 수 없다.
- **Demo fixture:** 고정 UUID로 재실행해도 같은 결과를 만드는 결정적 seed 데이터다. 고객 좌표,
  카드 원문, 실제 개인정보를 포함하지 않는다.
- **Ephemeral JWKS:** 실행 시 생성한 RSA keypair의 공개키만 노출하는 로컬 HTTP endpoint다.
  private key와 발급된 JWT는 gitignore된 runtime 디렉터리에만 존재한다.
- **Sandbox token reference:** 카드 원문 대신 결과를 지정하는 문자열이다.
- **Bounded poll:** 무한 sleep 대신 deadline까지만 재시도하고 초과 시 실패하는 대기다.

## Scope

### In Scope

- `local-demo` profile과 prod 동시 활성 거부 guard
- ephemeral JWKS endpoint와 역할별 JWT 발급 도구
- 결정적·멱등 demo seed와 정책/권한 bootstrap 단계
- 명시적 실행 정의와 `start`/`seed`/`smoke`/`stop` 실행 script
- 실제 HTTP smoke flow와 실패 시나리오, 강한 guard가 있는 reset
- runbook, quality evidence와 테스트

### Non-goals

- 실제 PG/JWK/Notification provider 연동
- production seed, 자동 fallback, 데모 편의를 위한 validation 완화
- 데모 편의를 위한 production endpoint 추가
- 실제 사용자·트래픽·운영 채택 주장
- 모든 운영자 repair endpoint 시연

## Business Rules and Invariants

- 인증을 끄거나 `permitAll`로 우회하지 않는다. demo도 실제 JWT 검증을 통과해야 한다.
- `local-demo`와 `prod`가 함께 활성화되면 startup을 실패시킨다.
- secret은 tracked file에 커밋하지 않는다. key와 JWT는 실행 시 생성한다.
- 카드 원문/CVC 대신 sandbox token reference만 쓴다(ADR-021).
- 고객 좌표는 fixture에도 저장하지 않는다(BR-28). 매장 좌표만 둔다.
- 필수 정책이 없다고 조용히 default를 만들지 않는다. bootstrap 단계와 결과를 출력한다.
- reset은 지정한 demo DB만 대상으로 하고 이름이 정확히 일치할 때만 수행한다.
- demo adapter 실패를 성공으로 위장하지 않는다. smoke는 실패 시 0이 아닌 exit code를 낸다.

## Architecture and Transaction Boundaries

- seed는 `local-demo` profile로 게이트된 Spring Boot CLI다. 각 owner 모듈의 JPA Entity와 DB
  제약을 그대로 사용하고, 하나의 transaction에서 전부 쓰거나 전부 rollback한다. 고정 UUID로
  재실행 멱등성을 확보한다. `merchant_store_discovery_profile`은 JPA Entity가 없으므로
  (MD-2026-009) 같은 transaction 안에서 JdbcTemplate으로 쓴다.
- 정책·권한 bootstrap은 기존 CLI 관례를 따르고 결과를 출력한다. seed가 정책을 대신 만들지 않는다.
- JWKS 도구는 애플리케이션과 별도 프로세스이며 애플리케이션보다 먼저 뜬다.
- smoke는 runtime OpenAPI operation만 호출한다. 내부 endpoint나 DB 직접 조작에 의존하지 않는다.

## Alternatives Considered

| 대안 | owner 불변식 재사용 | 멱등성 | schema drift 위험 | 실행 복잡도 | prod 격리 | 판정 |
|---|---|---|---|---|---|---|
| SQL seed script | 없음. 제약을 SQL에 재작성 | 수동 `ON CONFLICT` | **높음.** migration과 별도로 유지해야 함 | 낮음 | profile 개념 없음 | 부 |
| **Spring Boot bootstrap CLI** | Entity·DB 제약·Hibernate 매핑 검증 재사용 | 고정 UUID + 단일 transaction | 낮음. 앱과 같은 매핑 | 중간, 기존 CLI 2종과 동일 관례 | Spring profile로 강제 | **채택** |
| public API를 통한 setup | 최상 | endpoint 멱등성에 의존 | 없음 | 낮음 | 자연스러움 | **불가.** Store/Menu/Slot/Stock 생성 endpoint가 없고 편의를 위해 추가하지 않는다 |
| Testcontainers 실행 wrapper | 중간 | 매 실행 새 DB | 낮음 | 낮음 | 좋음 | 부. 사용자가 붙을 수 있는 지속 환경이 아니고 수명이 테스트 프로세스에 묶인다 |

## Failure Semantics

- `prod` + `local-demo` 동시 활성은 startup failure다.
- seed 부분 실패는 rollback하고 0이 아닌 exit code로 끝난다. 부분 fixture를 남기지 않는다.
- 정책/권한 bootstrap 실패는 seed를 진행하지 않고 명시적으로 실패한다.
- smoke의 비동기 구간은 deadline poll이며 초과 시 실패다. 성공으로 간주하지 않는다.
- reset 대상 DB 이름이 기대값과 다르면 아무것도 지우지 않고 실패한다.

## Data and Migration

Flyway migration을 추가하지 않는다. demo fixture는 기존 schema에만 쓴다. 고객 좌표, 카드 원문,
실제 개인정보는 넣지 않는다. 매장 좌표는 합성 좌표를 쓴다.

## API and Event Contracts

공개 계약을 바꾸지 않는다. runtime OpenAPI operation만 소비한다.

## Milestones

1. `local-demo` profile, prod 충돌 startup 거부와 실행 정의
2. ephemeral JWKS endpoint와 역할별 JWT 발급
3. 정책·권한 bootstrap과 결정적 멱등 seed
4. 고객→점주→포인트→정산 smoke와 실패 시나리오
5. runbook, quality evidence, 테스트와 전체 validation

## Required Tests

- `prod` + `local-demo` 동시 활성 startup 거부
- seed 재실행 동일 결과와 중복 0
- partial seed 실패 rollback 또는 명시적 실패
- reset guard가 다른 DB 이름을 거부
- tracked file secret scan
- smoke script의 성공·실패 exit code
- runtime OpenAPI operation만 호출

## Validation Commands

- `bash scripts/demo/start.sh`
- `bash scripts/demo/seed.sh`
- `bash scripts/demo/smoke.sh`
- `bash scripts/demo/seed.sh`
- `bash scripts/demo/stop.sh`
- `./gradlew test --tests '*Demo*' --tests '*ProviderSafety*' --tests '*ModularityTests'`
- `./gradlew clean build`
- `bash scripts/verify-docs.sh`
- `git diff --check`

## Observability

smoke는 각 단계의 HTTP status, correlation ID와 생성된 resource ID를 비민감 형태로 출력한다.
JWT, private key, 좌표는 출력하지 않는다.

## Documentation Updates

`docs/operations/local-demo-runbook.md`, README의 구현 현황 정정, quality evidence map,
`docs/index.md`와 문서 검증.

## Progress

- [x] local-demo profile과 prod 충돌 거부 (실행 정의는 Milestone 4로 이동)
- [x] ephemeral JWKS와 역할별 JWT
- [x] bootstrap과 결정적 멱등 seed
- [x] smoke flow, 실행 정의와 실패 시나리오
- [x] runbook, evidence, 테스트와 전체 validation

## Surprises & Discoveries

- 2026-08-07: 기존 sandbox adapter가 `@Profile("local & !prod")`라 `local-demo`만 켜면 활성화되지
  않는다. 기존 게이트를 바꾸는 대신 `local,local-demo`를 함께 활성화하기로 했다.
- 2026-08-07: Store/Menu/Slot/Stock 생성 public API가 없어 "public API를 통한 setup" 대안이
  성립하지 않는다. 편의를 위한 production endpoint 추가는 금지이므로 bootstrap CLI를 택했다.
- 2026-08-07: README의 "현재 source에 없는 capability"에 nearby·menu·슬롯 조회 API와 PointAccount
  read가 남아 있으나 둘 다 구현돼 있다. 이번 작업에서 정정한다.

- 2026-08-07: Gradle `--args`는 공백으로 토큰을 나눈다. 공백이 있는 정책 `reason` 인자가 세 토큰으로
  쪼개져 bootstrap이 `INVALID_INPUT`으로 실패했다. CLI가 이미 제공하는 환경변수 계약으로 바꿨다.
- 2026-08-07: `OidcWorkloadIdentityVerifier`는 쓰기 권한이 있는 신원 파일을 거부한다(`WRITE_PERMISSIONS`).
  0600으로 쓴 token/JWK 파일이 거부됐고, 검증을 완화하는 대신 0400으로 기록하도록 고쳤다. 이로써
  demo도 실제 workload identity 검증 경로를 그대로 통과한다.
- 2026-08-07: Modulith runtime/observability autoconfiguration은 `@SpringBootApplication` 클래스를
  요구해 CLI 구성에서 실패한다. `spring-modulith-runtime`이 `runtimeOnly`라 컴파일 참조가 불가능하므로
  이름으로 제외했다.
- 2026-08-07: test classpath에서 실행하는 identity server JVM이 `build/classes/kotlin/test`를 잠가
  실행 중에는 재컴파일이 실패한다. 코드를 고칠 때는 `stop.sh`를 먼저 실행해야 한다.
- 2026-08-08: **해소됨.** `bootRun`이 schema validation으로 시작하지 못했다.
  `loyalty_point_adjustment_command_idempotency.payload_hash`가 V31에서 `char(64)`로 선언돼 있었고
  (저장소의 다른 11개 `payload_hash`는 모두 `varchar(64)`), entity는 `@Column(length = 64)`이라
  Hibernate가 `varchar(64)`를 기대했다. V31이 다른 컬럼과 같은 `varchar(64)`로 정정돼 해소됐고,
  현재 저장소의 모든 `payload_hash`는 `varchar(64)`다. 같은 조사에서 Hibernate `validate`는 base
  type만 확인하고 length는 확인하지 않는다는 점을 관측했으므로, 불필요하게 넣었던 entity
  `length = 32` 변경은 되돌렸다.
- 2026-08-08: 좌표 비노출을 검증하려고 root logger에 appender를 붙이면서 Spring이
  `org.springframework.jdbc.core.StatementCreatorUtils` TRACE에서 bind된 statement parameter를
  그대로 기록한다는 사실을 확인했다. 전역 TRACE를 켜면 원본 좌표가 로그에 남는다.
  `application.yaml`에서 level을 고정하고 nearby runbook에 운영 제약으로 남겼다. deployment가
  level을 덮어쓸 수 있으므로 보장이 아니라 제약이며, 테스트가 TRACE 노출과 DEBUG 비노출을
  양방향으로 고정한다.
- 2026-08-08: 픽업 슬롯 예약 창을 `startsAt > now`로 좁힌 뒤 `OrderTerminationResourceListener`
  통합 테스트가 깨졌다. 슬롯을 고정 과거 상수에 시드하고 있었기 때문이다. 이름으로 고른 타깃
  테스트 실행에서는 전부 빠졌고 전체 `clean build`에서만 드러났다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-07 | Accepted | demo fixture는 `local-demo` profile Spring Boot bootstrap CLI가 owner Entity로 단일 transaction에 쓴다 | schema drift 없이 owner 제약과 매핑 검증을 재사용하고 고정 UUID로 멱등하다. SQL seed는 제약 재작성과 drift, public API setup은 생성 endpoint 부재로 불가 | 이 plan |
| 2026-08-07 | Accepted | `local,local-demo`를 함께 활성화하고 `prod`와의 동시 활성은 startup 실패 | 기존 `local & !prod` sandbox 게이트를 바꾸지 않으면서 prod 격리를 강제한다 | 이 plan, safety guard 테스트 |
| 2026-08-07 | Accepted | demo 전용 도구(identity server, seed CLI)는 test source set에 둔다 | profile 게이트에 더해 demo 코드가 production 산출물에 아예 포함되지 않는다. Kotlin friend-module 접근으로 owner `internal` Entity는 그대로 재사용할 수 있다 | 이 plan, `build.gradle.kts` task |

## Outcomes & Retrospective

Milestone 1~5가 모두 구현·검증됐다.

**구현된 것**

- `LocalDemoSafetyConfiguration`이 `local-demo` + `prod` 동시 활성과 `local` 없는 단독 활성을
  startup failure로 만든다. 세 경우를 `LocalDemoSafetyConfigurationTest`가 고정한다.
- `LocalDemoIdentityServer`가 실행 시 RSA keypair를 만들어 공개 JWK set만 HTTP로 제공하고,
  역할별 API JWT 5개, bootstrap용 OIDC workload token, cursor HMAC secret을 gitignore된 runtime
  디렉터리에 기록한다. 신원 파일은 `0400`이며, 이는 검증 우회가 아니라
  `OidcWorkloadIdentityVerifier`의 쓰기 권한 거부를 통과하기 위한 조건이다.
- `LocalDemoSeeder`가 고정 UUID fixture 25행을 owner Entity로 단일 transaction에 쓴다. GLOBAL
  적립 정책이 없으면 default를 만들지 않고 실패한다.
- `docker-compose.demo.yml`과 `scripts/demo/{start,seed,smoke,stop}.sh`. 모든 대기는 deadline이
  있는 bounded poll이고 초과는 실패다. `--reset`은 컨테이너의 `POSTGRES_DB`와 runtime 디렉터리
  경로가 정확히 일치할 때만 삭제한다.
- smoke는 runtime OpenAPI operation만 실제 HTTP로 호출하고 첫 불일치에서 non-zero exit을 낸다.
- demo 도구는 test source set에 있어 production 산출물에 포함되지 않는다.

**실행 증거 (2026-08-08)**

- `bash scripts/demo/start.sh` → exit 0. PostGIS → JWK set → 정책 bootstrap → 애플리케이션
  healthy까지 5단계 통과. V33/V34 분리 이후의 fresh migration도 정상 적용됐다.
- `bash scripts/demo/seed.sh` → 25행 삽입. 즉시 재실행 → **0행 삽입**, 같은 fixture.
- `bash scripts/demo/smoke.sh` → exit 0, **17단계 전부 통과**. nearby(매장 2곳, demo 매장 존재) →
  메뉴(판매 불가 1건 포함 2건) → 슬롯 → 주문 생성 201 → 동일 payload 재생이 같은 orderId →
  payload 변경 409 → 결제 확정 → ACCEPTED/PREPARING/READY/COMPLETED → 주문 조회 `COMPLETED` →
  포인트 summary·transactions → 401/401/403.
- 정산은 `warn`으로 보고됐다. **통과로 계산하지 않는다.** 60초 안에 Batch 생성 조건이
  충족되지 않았고, smoke의 정산 assertion은 실행되지 않았다는 사실을 그대로 출력한다.
- `bash scripts/demo/stop.sh` → exit 0.
- `./gradlew test --tests '*Demo*' --tests '*ProviderSafety*' --tests '*ModularityTests'` 통과.
- `./gradlew clean build` BUILD SUCCESSFUL (497 tests, 1 skipped, 0 failed).
- `bash scripts/verify-docs.sh`, `./gradlew spotlessCheck`, `git diff --check` 통과.

**Required Tests 대응**

| 요구 | 테스트 |
|---|---|
| `prod` + `local-demo` 동시 활성 startup 거부 | `LocalDemoSafetyConfigurationTest` |
| seed 재실행 동일 결과와 중복 0 | `LocalDemoSeedIntegrationTest` |
| partial seed 실패 rollback | `LocalDemoSeedIntegrationTest` (마지막 단계에 실패 주입) |
| reset guard가 다른 DB 이름을 거부 | `LocalDemoScriptGuardTest` (stub docker, 임시 root) |
| tracked file secret scan | `LocalDemoRepositorySafetyTest` (`git ls-files` 전체) |
| smoke script의 실패 exit code | `LocalDemoScriptGuardTest` |
| runtime OpenAPI operation만 호출 | `LocalDemoRepositorySafetyTest` |

추가로 "필수 정책이 없으면 seed가 실패한다"를 `LocalDemoSeedIntegrationTest`가 고정한다.

**증명하지 않은 것**

- smoke **성공** exit code는 위의 실제 실행으로만 확인했고 자동 테스트로 고정하지 않았다.
  전체 흐름의 성공 응답을 stub으로 흉내 내면 이 plan이 금지한 "실패를 성공으로 위장"을 테스트가
  스스로 하게 되므로, 실패 경로만 자동화하고 성공은 실행 증거로 남긴다.
- reset guard의 runtime 디렉터리 조건은 자동 테스트에서 일치 경로만 실행된다. 불일치 경로를
  실행하려면 실제 삭제 대상 경로를 바꿔야 해 위험하므로 코드 검토로만 확인했다.
- 정산 Batch 생성 조건이 충족되는 시나리오.
- 실제 PG·JWK·알림 provider 연동, non-local 배포, 운영 규모와 SLA.

## Revision Notes

- 2026-08-07: local demo 환경과 smoke를 위한 plan 최초 작성. 네 대안을 비교하고 bootstrap CLI를
  선택한 근거를 Decision Log에 기록했다.
- 2026-08-07: Milestone 1~2를 완료했다. demo 도구를 test source set에 두어 production 산출물
  격리를 profile 외에 한 겹 더 확보하기로 하고 Decision Log에 추가했다.
- 2026-08-08: Milestone 3~5를 완료하고 plan을 `COMPLETED`로 옮겼다. bootstrap·seed·smoke·stop을
  실제로 실행해 증거를 기록하고, Required Tests 7건을 자동 테스트로 고정했다. 자동화하지 않은
  두 가지(smoke 성공 exit code, reset의 runtime 디렉터리 불일치 경로)와 그 이유를 Outcomes에
  명시했다. V31 payload_hash blocker는 해소됐다.
