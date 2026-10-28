# 로컬 데모 실행 방법

`scripts/demo/`의 네 스크립트로 PostgreSQL, API, 프런트엔드를 실행하고 샘플 데이터를 준비한다.
고객 주문부터 매장 처리, 포인트 적립, 환불, 정산과 이의제기까지 HTTP smoke test로 실행할 수 있다.

관련 결정: [ExecPlan](../exec-plans/completed/local-demo-environment-and-smoke.md),
[ADR-021](../adr/ADR-021-payment-method-tokenization.md),
[BR-28](../product/business-policy-decisions.md).

## 1. 데모 환경의 동작

데모 환경에서도 계정 인증, 권한 검사와 주문·결제 규칙을 적용한다.

| 항목 | 데모에서의 동작 |
|---|---|
| 인증 | 고객은 ID/PW·PostgreSQL Session·CSRF Cookie를, 점주는 발급받은 계정과 Merchant Session을 사용한다. 운영자 JWT는 실행 시 만든 RSA JWK set으로 검증한다 |
| 권한 | 인증 없이 호출하면 401, 고객 API에 Bearer token을 보내거나 다른 매장에 접근하면 403을 반환한다 |
| 주문 규칙 | 예약 lease·capacity·멱등성·상태 전이에 실제 애플리케이션 로직을 사용한다 |
| 포인트 정책 | 감사형 bootstrap CLI가 OIDC workload identity로 GLOBAL 적립 정책을 준비한다. 정책이 빠져 있으면 seed 실행이 실패한다 |
| 결제 | `local` scripted adapter가 paymentKey suffix에 따라 승인·조회 결과를 반환한다. 카드번호·CVC·유효기간은 저장하지 않는다 |
| 실행 결과 | 각 단계에 대기시간 제한을 두고 오류가 발생하면 smoke script가 0이 아닌 exit code로 종료된다 |

`local-demo`는 `prod`와 함께 활성화될 수 없다. 동시에 켜면 startup이 실패한다
(`LocalDemoSafetyConfigurationTest`).

## 2. 사전 요구사항

- Java 21, Node.js/npm, Docker daemon, `curl`, `python3`, `lsof`, `ps`
- 포트, Compose project, container 이름은 checkout의 절대경로 해시를 이용해 자동으로 정한다. 다른 checkout과 리소스가 겹치지 않도록 구성하며 포트를 이미 사용 중이면 `start.sh`가 중단된다.

## 3. 실행

```bash
bash scripts/demo/start.sh
bash scripts/demo/seed.sh
bash scripts/demo/smoke.sh --customer-checkpoint  # 고객 주문·결제 API
bash scripts/demo/smoke.sh                        # 매장 처리·환불까지
```

`start.sh`는 여섯 단계를 순서대로 수행하고 각 단계를 출력한다.

1. PostgreSQL 17 / PostGIS 3.5 컨테이너 기동과 준비 대기
2. ephemeral identity server 기동과 JWK set endpoint 준비 대기
3. GLOBAL 일반 적립 정책 bootstrap (감사형 CLI, OIDC workload identity 검증)
4. `local,local-demo` profile로 애플리케이션 기동
5. Runtime OpenAPI client를 생성하고 React frontend 기동
6. API health와 frontend 응답 확인

`seed.sh`는 고정 UUID 고객 계정, 점주 계정 두 개, 0원 포인트 계정을 한 트랜잭션에서 생성한다. 같은 스크립트를 다시 실행하면 데이터를 추가하지 않는다.

`smoke.sh --customer-checkpoint`는 `demo.customer`로 로그인하고 CSRF token·Session Cookie를 발급받아 주문 준비, callback 재요청, 위변조 요청 거부, 승인된 결제 조회를 실행한다. 매장 API는 이 단계에서 호출하지 않는다.

인자 없는 `smoke.sh`는 고객 API 실행 뒤 `demo.merchant`의 임시 비밀번호 로그인, `INITIAL_PASSWORD` 상태, 비밀번호 변경과 Session 갱신을 진행한다. 이어 매장 주문 완료, 부분·전액 환불, 두 번째 결제의 `UNKNOWN → APPROVED` 조회를 실행한다.

매장 처리와 환불은 `BF-XXXX-XXXX` 공개 주문번호와 Stage 6/8 public API를 사용한다. 첫 주문은 10,000 KRW이며 완료 후 `ACCRUAL` 포인트 원장과 잔액 100 KRW 증가를 조회한다. 점주 작업은 Merchant Session으로 실행한다.

전체 smoke에서는 과거 확정 Batch의 5,000 KRW 부분 환불 조정을 조회한 뒤, 해당 정산 항목에 `FILED` 이의제기를 접수·조회한다. 이 데이터는
live 주문과 분리된 immutable history다. seed는 현재 GLOBAL accrual policy와 settlement terms를 snapshot으로
참조하므로, policy나 terms가 없을 때 임의 기본값을 만들지 않고 transaction 전체를 rollback한다.

고객 앱은 `start.sh` 로그에 표시된 주소의 `/app`, 매장 콘솔은 `/store`, 운영 콘솔은 `/ops`에서 연다. 고객은 `/app/login`에서 `demo.customer` 계정과 로컬 테스트 비밀번호로 로그인한다.

보호된 화면은 `GET /me`가 200을 반환할 때 열리며 401은 로그인, 403은 권한 오류, 503은 인증 서비스 오류로 표시한다. 로그아웃할 때는 고객 장바구니와 미완료 요청 키를 지우고 운영 콘솔 token은 유지한다.

운영 화면에는 `.demo-runtime/demo-identity.env`의 `PLATFORM_OPERATOR_TOKEN`, 점주 화면에는 Merchant Session을 사용한다. JWT·Session 값은 저장소 파일과 로그에 기록하지 않는다.

기존 데모 DB에 적립 정책이 이미 있다면 bootstrap CLI가 `POLICY_ALREADY_INITIALIZED`를 반환한다. 이때 `start.sh`는 `stop.sh --reset`으로 초기화하도록 안내한다.

## 4. 인증 키와 secret 파일

실행 시 생성한 자료는 전부 `.demo-runtime/`에만 존재하며 `.gitignore`로 차단돼 있다.

| 파일 | 내용 |
|---|---|
| `jwks.json` | 공개 JWK set만. private key는 프로세스 메모리에만 있다 |
| `demo-identity.env` | 운영자 API JWT와 cursor HMAC secret. 고객·점주 JWT는 생성하지 않음 |
| `workload-token.txt`, `jwks.json` | bootstrap CLI가 검증하는 신원 파일. `0400`으로 기록된다 |

신원 파일은 `0400` 권한으로 저장한다. 쓰기 권한이 있으면 `OidcWorkloadIdentityVerifier` 검증에 실패해 초기 설정을 진행할 수 없다.

`LocalDemoRepositorySafetyTest`는 `git ls-files`에 포함된 파일에서 private key·JWT·데모 secret을 검색한다.

## 5. 샘플 데이터

| 있는 것 | 없는 것 |
|---|---|
| 매장 2곳(합성 좌표), 메뉴 2종(하나는 판매 불가), 옵션 2종 | 고객 좌표 — BR-28상 어디에도 저장하지 않는다 |
| 합성 고객 로그인 계정, INITIAL/ACTIVE 점주 계정과 매장 membership, 픽업 슬롯 3개, 0 KRW 포인트 계정, AVAILABLE 쿠폰 Campaign | 초기 PointLot·PointTransaction, 카드번호, CVC, 유효기간 — ADR-021 |
| local-only scripted payment config와 paymentKey 상태 규칙 | 실제 개인정보, 실제 Toss credential |
| 과거 완료 주문 2건, 승인 결제, 5,000 KRW 부분 환불, immutable 정산 item·confirmed batch 2건·환불 조정 1건 | paymentKey, provider secret, PAN/CVC 또는 실제 고객 결제 자료 |
| — | 매장 검색 색인(`discovery_store_search_term`) — seed는 색인을 만들지 않는다 |

seed는 매장을 SQL로 저장하기 때문에 매장 검색 색인이 생성되지 않는다. 고객 화면의 매장 검색 결과는 빈 목록으로 표시된다. 결과가 있는 검색을 보려면 운영자에게 `STORE_BRAND_MANAGE` 권한을 부여한 뒤
`POST /api/v1/operations/search-index/rebuild`를 호출해야 하고, 그 권한 부여는 seed 범위 밖이다.
홈의 추천 매장과 매장 상세는 색인 없이도 동작하므로 주문 흐름 확인에는 영향이 없다.

## 6. 정지와 초기화

```bash
bash scripts/demo/stop.sh            # 프로세스와 컨테이너 정지, 데이터 유지
bash scripts/demo/stop.sh --reset    # 데모 DB와 실행 시 키 자료까지 삭제
```

`--reset`은 세 guard를 모두 통과해야 실행된다.

1. 컨테이너가 서비스하는 `POSTGRES_DB`가 정확히 `beanflow_demo`여야 한다. 다르면 거절하고
   아무것도 지우지 않는다.
2. 삭제 대상 runtime 디렉터리 경로가 정확히 `${DEMO_ROOT}/.demo-runtime`이어야 한다.
3. 컨테이너의 Docker compose ownership label이 현재 checkout에서 유도한 project와 일치해야 한다.

`--reset`은 현재 데모 DB와 Compose 자원에만 적용된다.

identity server, 애플리케이션, 프런트엔드는 각각 별도의 process group으로 시작한다. PID record에는
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
| `Timed out after Ns waiting for ...` | 해당 단계가 deadline 안에 준비되지 않았다 | `.demo-runtime/app.log`, `frontend.log` 또는 `identity.log`를 본다. 대기 시간을 넘긴 단계의 로그를 확인한다 |
| `Seed failed. The transaction rolled back` | fixture 쓰기 중 오류 | `.demo-runtime/seed.log`를 본다. 부분 fixture는 남지 않으므로 원인 수정 후 그대로 재실행한다 |
| `Customer Session login expected 200` | 고객 seed 누락, 비밀번호 정책·계정 상태 또는 Session 저장 실패 | `seed.log`의 `LOCAL_DEMO_SEED_CUSTOMER_LOGIN_ID`와 `app.log`의 correlation ID를 확인한다. 고객 JWT로 우회하지 않는다 |
| 기본 smoke의 점주 로그인·전환이 401/403 | seed 누락, 24시간 임시 비밀번호 만료, 초기 비밀번호가 이미 변경됐거나 다른 매장 membership | deterministic 전체 smoke는 `stop.sh --reset` 뒤 start·seed부터 다시 실행한다. JWT/fake Session으로 우회하지 않는다 |
| `The GLOBAL ordinary point accrual policy is missing` | `start.sh`의 bootstrap 단계를 건너뛰었다 | `start.sh`를 실행한다. seed는 정책을 대신 만들지 않는다 |
| `GLOBAL accrual policy is already initialized` | 이전 demo database가 남아 bootstrap result가 deterministic fixture와 다르다 | `stop.sh --reset` 후 `start.sh`를 다시 실행한다. 로그의 단어 `already`만으로 성공 처리하지 않는다 |
| smoke `[fail] ... expected 200 got 500` | 해당 단계의 실제 응답이 계약과 다르다 | 출력된 `correlationId`로 `.demo-runtime/app.log`를 조회한다 |
| `Timed out waiting for the completion accrual transaction` | 완료 event consumer가 deadline 안에 적립 원장을 만들지 않았거나 source/금액이 정책과 다르다 | `.demo-runtime/app.log`에서 order ID와 event publication/retry state를 확인한다. smoke는 성공으로 끝나지 않는다 |
| `unknown confirmation`이 수렴하지 않음 | local paymentKey가 유지되지 않았거나 lookup worker가 deadline 안에 실행되지 않았다 | smoke의 recovered payment ID와 correlation ID로 `.demo-runtime/app.log`를 확인한다. confirm을 새로 보내 성공으로 만들지 않는다 |
| `Port ... is already in use by an unowned process` | 해당 checkout의 결정적 포트 블록을 다른 process가 사용 중 | 해당 process를 확인·정리하거나 다른 checkout에서 실행한다. demo는 기존 process를 kill하지 않는다 |
| 재컴파일이 `build/classes/kotlin/test` 잠금으로 실패 | identity server JVM이 test classpath를 잡고 있다 | `stop.sh`를 먼저 실행한 뒤 컴파일한다 |
| Flyway `checksum mismatch` | 기존 데모 DB가 이전 migration 내용으로 만들어졌다 | `stop.sh --reset` 후 `start.sh`를 다시 실행한다 |
