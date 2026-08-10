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
- 환불·취소 `cancelReason`에는 Provider idempotency key 원문 대신 SHA-256 기반의 짧은 operation
  marker를 넣는다. 최초 응답과 조회는 `DONE + 실제 환불 금액 + marker` exact match만 현재 Refund의
  `transactionKey`로 수락하고, 같은 금액의 마지막 취소나 원 승인 총액으로 선택하지 않는다.
- `toss-sandbox-runtime`은 `local,toss-sandbox`를 합성하면서 scripted gateway를 제외한다. API 개별
  연동 `test_ck_`/`test_sk_`만 허용하고 Widget `test_gck_`/`test_gsk_`, live·missing key는 시작 실패한다.
  legacy PaymentMethod provider는 fake 성공이 아니라 명시적 `Misconfigured`다.
- React/TypeScript 앱은 공식 `@tosspayments/tosspayments-sdk@2.7.1`, V2 Standard URL과 Runtime
  OpenAPI 생성 client 하나를 사용하고 `/app`, `/store`, `/ops` 경계를 제공한다. 고객 flow는
  매장→메뉴→slot→주문→결제 준비→callback→상태 조회→주문 추적으로 이어진다. callback query는
  메모리에 읽은 직후 history에서 제거하고 clean URL reload는 owner status query를 호출한다.

## 자동 검증

| 검증 | 결과 |
|---|---|
| `./gradlew clean build --stacktrace --no-daemon` | PASS — 637 tests, 1 skipped, Spotless/check/build 포함, 리뷰 수정 후 8분 30초 |
| `OneTimePaymentMigrationTest` | PASS — V38 clean migrate, constraints, immutable/delete guard |
| `OneTimeCheckoutIntegrationTest` | PASS — prepare/replay, 만료 materialization, confirm, tamper, concurrency, UNKNOWN lookup recovery |
| `TossOneTimePaymentGatewayTest` | PASS — Basic auth, idempotency, confirm/query/cancel, 동일 금액 refund marker와 failure 분류 |
| `LocalPaymentGatewayConfigurationTest` | PASS — lookup reference 보존과 복수 refund reference 분리 |
| `npm run typecheck` | PASS |
| `npm test` | PASS — 4 files, 17 tests; callback query/status, submit intent와 전체 OrderState mapping 포함 |
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

공개 API 개별 연동 test client/secret key 쌍을 실행 환경에만 넣어 실제 Toss endpoint를 검증했다.
credential과 paymentKey 원문은 source, 문서, commit, PR과 애플리케이션 log에 복사하지 않았다.

- `toss-sandbox-runtime`은 active profile `local,toss-sandbox`와 health `UP`으로 기동했다.
- 이전 Widget key 쌍은 실제 SDK에서 `NotSupportedWidgetKeyError`가 났고, 현재 startup guard는
  `test_gck_`/`test_gsk_`를 Provider 호출 전에 거부한다.
- 실제 Toss V2 Payment Window가 Demo Americano 1잔 4,500원과 2잔 9,000원을 표시했다.
- Toss는 국내 공개 테스트 카드번호를 제공하지 않는다. 개인 카드·휴대폰·생년월일을 입력하지 않고
  공식 V2 `sandbox.paymentResult=SUCCESS` 인증 시뮬레이션을 이번 브라우저 실행에만 임시 적용했다.
  Toss hosted 화면의 test 결제 안내와 인증 결과를 거쳐 테스트 paymentKey를 발급받았고, 임시 옵션은
  검증 직후 source에서 원복했다.
- BeanFlow server가 두 callback을 실제 Toss `/v1/payments/confirm`으로 승인해 각각 4,500원과
  9,000원 `APPROVED`를 기록했다. URL query 제거 직후와 clean URL 새로고침 뒤에도 owner status
  query가 같은 승인 화면과 `PAID` 주문을 반환했다.
- 4,500원 주문의 매장 수락 timeout 전액 취소는 Provider REQUEST 1회로 Refund `SUCCEEDED`였다.
- 9,000원 결제는 1잔 4,500원 부분 취소와 남은 4,500원 취소가 각각 REQUEST 1회,
  Refund `SUCCEEDED`였다. Toss 직접 조회는 `PARTIAL_CANCELED`, `balanceAmount=0`, 4,500원
  `DONE` cancel 두 건을 반환했다.
- 후속 리뷰 수정은 위 실제 sandbox transport에서 확인한 `cancels.cancelReason`, `cancelAmount`,
  `cancelStatus`, `transactionKey` 계약을 stub HTTP adapter test로 고정했다. 같은 금액 두 건, 무관한
  전액 취소 공존, 응답 유실 lookup과 동일 key replay를 각각 독립 검증한다.

```bash
export TOSS_CLIENT_KEY='test_ck_...'
export TOSS_SECRET_KEY='test_sk_...'
export BEANFLOW_FRONTEND_BASE_URL='http://127.0.0.1:4173'
export SPRING_PROFILES_ACTIVE='toss-sandbox-runtime'
./gradlew bootRun
```

이는 실제 Toss test API transport 증거이며 production 배포·실자금 이동 증거가 아니다. 국내 카드사
실제 인증 UI는 개인 결제정보를 사용하지 않았으므로 Not run이다.

## 공식 계약 기준

- [Toss V2 Payment Window integration](https://docs.tosspayments.com/guides/v2/payment-window/integration)
- [Toss API reference](https://docs.tosspayments.com/reference)
- [Toss API authorization](https://docs.tosspayments.com/reference/using-api/authorization)
- [Toss API keys](https://docs.tosspayments.com/reference/using-api/api-keys)
- [Toss test environment](https://docs.tosspayments.com/guides/v2/get-started/environment)
- [Toss test payment FAQ](https://docs.tosspayments.com/blog/how-to-test-toss-payments)
- [Toss error codes](https://docs.tosspayments.com/reference/error-codes)

## 남은 위험

- 공식 sandbox auth simulation은 실제 국내 카드사 인증 동작·카드사별 차이를 증명하지 않는다.
- 매장 수락 timeout Refund의 `PaymentRefundedV1` settlement publication은 고객 직접 취소 증적과
  일치하지 않아 기존 fail-closed `SETTLEMENT_SOURCE_CONFLICT` 재처리 backlog에 남았다. 환불 성공과
  정산 publication 완료를 같은 결과로 보고하지 않는다.
- 운영자 부분·잔액 환불로 현금 잔액이 이미 0원이 된 미수락 Order도 이후 수락 timeout에서
  `REJECTED`로 전이됐고, rejection PAYMENT step은 `already partially refunded`로 fail-closed backlog에
  남았다. 중복 Provider 환불은 호출하지 않았지만 이 조합의 Order 보상 종결에는 별도 reconciliation이
  필요하다.
- deprecated Jackson `JsonNode.asText` 컴파일 warning은 동작 실패가 아니지만 후속 dependency 정리에서
  호환 API로 바꿔야 한다.
- 지연 Provider 환경의 부하, connection pool과 recovery worker 처리량은 측정하지 않았다.
