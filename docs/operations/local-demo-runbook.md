# Local Demo Runbook

`scripts/demo/`의 네 script로 BeanFlow를 로컬에서 기동하고, 결정적 fixture를 넣고, 고객 →
매장 → 포인트 적립 흐름을 실제 HTTP로 확인하는 방법과 실패 진단을 다룬다.

이 환경은 **데모와 로컬 확인 전용**이다. 실제 배포, 운영 규모, SLA를 증명하지 않는다.
관련 결정: [ExecPlan](../exec-plans/completed/local-demo-environment-and-smoke.md),
[ADR-021](../adr/ADR-021-payment-method-tokenization.md),
[BR-28](../product/business-policy-decisions.md).

## 1. 무엇을 우회하지 않는가

데모라는 이유로 완화한 것이 없다는 점이 이 환경의 전제다.

| 항목 | 데모에서의 동작 |
|---|---|
| 인증 | 실제 `NimbusJwtDecoder`가 실행 시 생성한 RSA 키쌍의 JWK set으로 모든 JWT를 검증한다. `permitAll`이나 decoder 대체는 없다 |
| 인가 | 역할·매장 소속 검사가 그대로 동작한다. smoke가 401·401·403을 명시적으로 확인한다 |
| 도메인 불변식 | 예약 lease, capacity, 멱등성, 상태 전이 모두 production 코드 경로다 |
| 필수 정책 | GLOBAL 일반 적립 정책은 production과 같은 감사형 bootstrap CLI가 OIDC workload identity 검증을 통과해 만든다. seed는 정책이 없으면 실패하고 default를 만들지 않는다 |
| 결제 | `local` scripted adapter가 token reference의 suffix로 결과를 정한다. 외부 sandbox를 호출하지 않으며 PAN·CVC·유효기간은 어디에도 없다 |
| 실패 | 모든 대기는 deadline이 있는 bounded poll이고, 초과는 실패다. smoke는 첫 실패에서 0이 아닌 exit code로 끝난다 |

`local-demo`는 `prod`와 함께 활성화될 수 없다. 동시에 켜면 startup이 실패한다
(`LocalDemoSafetyConfigurationTest`).

## 2. 사전 요구사항

- Java 21, Docker daemon, `curl`, `python3`, `lsof`, `ps`
- 사용 포트: `55432`(PostgreSQL), `18081`(JWK set), `18080`(애플리케이션)

## 3. 실행

```bash
bash scripts/demo/start.sh
bash scripts/demo/seed.sh
bash scripts/demo/smoke.sh
```

`start.sh`는 다섯 단계를 순서대로 수행하고 각 단계를 출력한다.

1. PostgreSQL 17 / PostGIS 3.5 컨테이너 기동과 준비 대기
2. ephemeral identity server 기동과 JWK set endpoint 준비 대기
3. GLOBAL 일반 적립 정책 bootstrap (감사형 CLI, OIDC workload identity 검증)
4. `local,local-demo` profile로 애플리케이션 기동
5. health endpoint 확인

`seed.sh`는 고정 UUID fixture를 단일 transaction으로 쓴다. 재실행하면 삽입 0건이고 같은
fixture가 유지된다. `smoke.sh`는 runtime OpenAPI에 선언된 operation만 호출하고, 완료 주문 뒤
해당 order의 `ACCRUAL` 원장 source와 50 KRW balance delta를 deadline 안에 확인한다.

bootstrap CLI가 `POLICY_ALREADY_INITIALIZED`라는 정확한 terminal result를 내면, 이전 demo DB가
남아 deterministic fixture가 성립하지 않는 상태다. `start.sh`는 임의의 `already` 로그를 성공으로
취급하지 않고 `stop.sh --reset`을 안내하며 실패한다.

## 4. 키 자료와 secret

실행 시 생성한 자료는 전부 `.demo-runtime/`에만 존재하며 `.gitignore`로 차단돼 있다.

| 파일 | 내용 |
|---|---|
| `jwks.json` | 공개 JWK set만. private key는 프로세스 메모리에만 있다 |
| `demo-identity.env` | 역할별 API JWT, cursor HMAC secret |
| `workload-token.txt`, `jwks.json` | bootstrap CLI가 검증하는 신원 파일. `0400`으로 기록된다 |

`OidcWorkloadIdentityVerifier`는 **쓰기 권한이 있는 신원 파일을 거부한다.** 이 파일들이
`0400`인 것은 우회가 아니라 검증을 통과하기 위한 조건이다. 권한을 완화하면 bootstrap이 실패한다.

tracked file에 private key, JWT, demo secret이 들어가지 않는 것은
`LocalDemoRepositorySafetyTest`가 `git ls-files` 전체를 스캔해 확인한다.

## 5. fixture에 들어 있는 것과 없는 것

| 있는 것 | 없는 것 |
|---|---|
| 매장 2곳(합성 좌표), 메뉴 2종(하나는 판매 불가), 옵션 2종 | 고객 좌표 — BR-28상 어디에도 저장하지 않는다 |
| 픽업 슬롯 3개, 재고, 0 KRW 포인트 계정, 쿠폰 Campaign | 초기 PointLot·PointTransaction, 카드번호, CVC, 유효기간 — ADR-021 |
| scripted token reference만 가진 결제수단 | 실제 개인정보 |

## 6. 정지와 초기화

```bash
bash scripts/demo/stop.sh            # 프로세스와 컨테이너 정지, 데이터 유지
bash scripts/demo/stop.sh --reset    # 데모 DB와 실행 시 키 자료까지 삭제
```

`--reset`은 두 개의 guard를 모두 통과해야 실행된다.

1. 컨테이너가 서비스하는 `POSTGRES_DB`가 정확히 `beanflow_demo`여야 한다. 다르면 거절하고
   아무것도 지우지 않는다.
2. 삭제 대상 runtime 디렉터리 경로가 정확히 `${DEMO_ROOT}/.demo-runtime`이어야 한다.

임의 DB에 `DROP`을 실행하지 않으며 다른 compose stack을 건드리지 않는다.

identity server와 application은 start 때 별도 session/process group leader로 기동한다. PID record에는
PID·PGID·실행 nonce·repository root가 함께 들어가며, `stop.sh`는 `ps`의 process group, command-line
nonce, `lsof` cwd를 모두 다시 확인한 경우에만 그 group을 signal한다. stale 또는 다른 checkout의 record는
삭제만 하고 process에는 signal하지 않으며 전역 `pkill`은 사용하지 않는다.

`stop.sh`와 `stop.sh --reset`은 Docker/Compose 오류를 성공으로 바꾸지 않는다. reset은 `compose down`이
성공하고 demo container의 부재를 정확히 확인한 뒤에만 `.demo-runtime` key material을 삭제한다. 실패하면
non-zero로 끝나며 key material을 보존한다.

`LocalDemoScriptGuardTest`가 stub docker와 임시 root로 script를 실제 실행해 첫 번째 guard를
양방향으로 확인한다. 다른 DB 이름이면 거절하고 `compose down`을 호출하지 않으며, 일치할 때만
삭제가 진행된다. 두 번째 guard는 불일치 경로를 실행하려면 실제 삭제 대상 경로를 바꿔야 해서
자동 테스트로 고정하지 않았고, 일치 경로만 위 테스트가 지난다.

## 7. 진단

| 증상 | 원인 | 조치 |
|---|---|---|
| `Policy bootstrap failed` | workload identity 검증 실패 또는 정책 입력 오류 | `.demo-runtime/policy-bootstrap.log`를 본다. 신원 파일 권한이 `0400`인지 확인한다. 정책을 수동 INSERT하지 않는다 |
| `Timed out after Ns waiting for ...` | 해당 단계가 deadline 안에 준비되지 않았다 | `.demo-runtime/app.log` 또는 `identity.log`를 본다. deadline 초과는 실패이며 느린 성공이 아니다 |
| `Seed failed. The transaction rolled back` | fixture 쓰기 중 오류 | `.demo-runtime/seed.log`를 본다. 부분 fixture는 남지 않으므로 원인 수정 후 그대로 재실행한다 |
| `The GLOBAL ordinary point accrual policy is missing` | `start.sh`의 bootstrap 단계를 건너뛰었다 | `start.sh`를 실행한다. seed는 정책을 대신 만들지 않는다 |
| `GLOBAL accrual policy is already initialized` | 이전 demo database가 남아 bootstrap result가 deterministic fixture와 다르다 | `stop.sh --reset` 후 `start.sh`를 다시 실행한다. 로그의 단어 `already`만으로 성공 처리하지 않는다 |
| smoke `[fail] ... expected 200 got 500` | 해당 단계의 실제 응답이 계약과 다르다 | 출력된 `correlationId`로 `.demo-runtime/app.log`를 조회한다 |
| `Timed out waiting for the completion accrual transaction` | 완료 event consumer가 deadline 안에 적립 원장을 만들지 않았거나 source/금액이 정책과 다르다 | `.demo-runtime/app.log`에서 order ID와 event publication/retry state를 확인한다. smoke는 성공으로 끝나지 않는다 |
| 재컴파일이 `build/classes/kotlin/test` 잠금으로 실패 | identity server JVM이 test classpath를 잡고 있다 | `stop.sh`를 먼저 실행한 뒤 컴파일한다 |
| Flyway `checksum mismatch` | 기존 데모 DB가 이전 migration 내용으로 만들어졌다 | `stop.sh --reset` 후 `start.sh`를 다시 실행한다 |

## 8. 이 환경이 증명하지 않는 것

- 실제 PG·JWK·알림 provider 연동
- non-local 배포, 운영 규모, 성능 또는 SLA
- 정산 Batch 생성 조건이 충족되는 시나리오 — core smoke의 계약에는 포함하지 않는다. 재현 가능한
  batch fixture와 별도 mandatory probe가 생기기 전에는 정산 성공을 주장하지 않는다
