# Toss V2 Standard Payment Window Release Evidence

## Scope

이 증거는 ADR-080과 BR-33의 고객 일회성 `CARD` 결제창을 다룬다. 서버 준비, React checkout,
Toss V2 callback 승인, 상태 조회 복구, 주문 추적과 기존 부분·전액 환불 owner 경계를 포함한다.
PaymentMethod lifecycle, billing, BrandPay, 가상계좌와 payout은 범위 밖이다.

검증일은 2026-08-10이며 source 증거다. 실제 배포, production traffic, SLA 또는 처리량 증거가 아니다.

## 구현 증거

- V38은 `payment_one_time_attempt`의 provider order/customer/order name/amount/currency,
  paymentKey, callback hash, stable Provider key와 claim/recovery state를 보존한다. UNIQUE/CHECK와
  immutable trigger가 prepare snapshot과 상태별 required/null 불변식을 보호한다.
- `GET /payment-config`, `POST /orders/{orderId}/payment-attempts`,
  `POST /payments/{paymentId}/confirmations`, `GET /payments/{paymentId}`가 target/runtime OpenAPI와
  Spring HandlerMapping에 같은 34 paths/37 operations inventory로 존재한다.
- one-time 경로는 PaymentMethod를 조회하지 않는다. callback exact binding을 transaction에서 claim한
  뒤 Toss confirm을 transaction 밖에서 호출하고, timeout/응답 유실은 `UNKNOWN`과 query work로 남긴다.
- Toss adapter는 `secretKey + ":"` Basic credential, stable `Idempotency-Key`, official
  confirm/payment/cancel endpoint, connect 3초·read 8초 deadline과 closed error classification을 사용한다.
- React/TypeScript 앱은 공식 `@tosspayments/tosspayments-sdk@2.7.1`, V2 Standard URL과 Runtime
  OpenAPI 생성 client 하나를 사용하고 `/app`, `/store`, `/ops` 경계를 제공한다. 고객 flow는
  매장→메뉴→slot→주문→결제 준비→callback→상태 조회→주문 추적으로 이어진다.

## 자동 검증

| 검증 | 결과 |
|---|---|
| `./gradlew clean build --stacktrace` | PASS — 622 tests, 1 skipped, Spotless/check/build 포함, 8분 |
| `OneTimePaymentMigrationTest` | PASS — V38 clean migrate, constraints, immutable/delete guard |
| `OneTimeCheckoutIntegrationTest` | PASS — prepare/replay, confirm, tamper, concurrency, UNKNOWN lookup recovery |
| `TossOneTimePaymentGatewayTest` | PASS — Basic auth, idempotency, confirm/query/cancel와 failure 분류 |
| `LocalPaymentGatewayConfigurationTest` | PASS — lookup reference 보존과 복수 refund reference 분리 |
| `npm run typecheck` | PASS |
| `npm test` | PASS — 3 files, 7 tests |
| `npm run build` | PASS — TypeScript, Vite production bundle, Sites package |
| `npm run test:sites` | PASS — 4 tests |
| `npm audit --omit=dev` | PASS — 0 vulnerabilities |
| `bash scripts/ci/test-ci-scripts.sh` | PASS |
| `PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh` | PASS — target/runtime 34 paths/37 operations, 91 schemas, 33 policies, 80 ADRs, 32 ExecPlans |
| bundle/secret/fallback scan | PASS — source map 0, product source·bundle에 local key/fake success/fixture/secret 0 |

`npm audit` 전체 dependency graph에는 `openapi-typescript`가 사용하는 개발 전용
`@redocly/openapi-core → js-yaml`의 high advisory 2건이 남아 있다. `npm audit fix`도 같은
`@redocly/openapi-core@1.34.18 → js-yaml@4.3.0`을 유지했고, latest `openapi-typescript@7.13.0` 범위에서
호환 가능한 수정 버전이 없다. production dependency audit에는 포함되지 않고 product bundle에도
들어가지 않는다. 이를 0건으로 숨기지 않는다.

## Local HTTP와 브라우저 증거

`bash scripts/demo/start.sh`, `seed.sh`, `smoke.sh`를 초기화된 PostGIS demo DB에서 실행했다.

- prepare와 exact replay는 같은 Payment를 반환했다.
- 정상 callback은 `APPROVED`, amount 변경 callback은 `409`, 같은 callback replay는 기존 결과였다.
- 상태 조회, 매장 `ACCEPTED → PREPARING → READY → COMPLETED`, 100 KRW 적립을 확인했다.
- 10,000 KRW 주문을 5,000 KRW 부분 환불한 뒤 남은 5,000 KRW를 전액 환불했고 각 Provider reference는
  stable replay와 distinct command를 동시에 만족했다.
- 두 번째 결제는 confirm `202 UNKNOWN` 뒤 새 confirm 없이 status query로 `200 APPROVED`에 수렴했다.
- 전체 smoke는 39개 correlation step을 통과했다.

Codex in-app browser에서는 실제 local Spring API와 React 앱으로 4,500 KRW 주문을 생성했다. callback
승인 화면, callback URL 새로고침 exact replay와 `PAID` 주문 추적을 확인했다. 제공 zip source와 구현
screenshot을 같은 comparison image로 검토했고 `frontend/design-qa.md`의 최종 결과는 `passed`다.

## 실제 Toss sandbox

`TOSS_CLIENT_KEY`와 `TOSS_SECRET_KEY`가 실행 환경에 없어 실제 Toss Payment Window 인증·confirm·query·
cancel은 **Not run — missing credentials**다. local scripted 결과를 실제 sandbox 증거로 대체하지 않는다.

키를 받은 뒤 다음 설정으로 `toss-sandbox`를 기동하고 같은 브라우저 flow에서 정상 승인, 상태 조회,
5,000 KRW 부분 cancel과 remaining cancel을 재실행한다.

```bash
export TOSS_CLIENT_KEY='test_ck_...'
export TOSS_SECRET_KEY='test_sk_...'
export BEANFLOW_FRONTEND_BASE_URL='http://127.0.0.1:4173'
export SPRING_PROFILES_ACTIVE='toss-sandbox'
./gradlew bootRun
```

실제 credential과 paymentKey는 log, shell transcript, evidence 문서와 PR에 복사하지 않는다.

## 공식 계약 기준

- [Toss V2 Payment Window integration](https://docs.tosspayments.com/guides/v2/payment-window/integration)
- [Toss API reference](https://docs.tosspayments.com/reference)
- [Toss API authorization](https://docs.tosspayments.com/reference/using-api/authorization)
- [Toss API keys](https://docs.tosspayments.com/reference/using-api/api-keys)
- [Toss error codes](https://docs.tosspayments.com/reference/error-codes)

## 남은 위험

- 실제 Toss sandbox credential pair, 결제수단 선택 UI와 test card로 transport 증거를 추가해야 한다.
- deprecated Jackson `JsonNode.asText` 컴파일 warning은 동작 실패가 아니지만 후속 dependency 정리에서
  호환 API로 바꿔야 한다.
- 지연 Provider 환경의 부하, connection pool과 recovery worker 처리량은 측정하지 않았다.
