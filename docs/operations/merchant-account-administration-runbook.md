# 점주 계정 자격증명 운영 Runbook

운영 콘솔에서 점주 계정과 최초 매장 소속을 발급하고, 임시 비밀번호를 다시 발급하거나 로그인 잠금을
조기 해제하는 절차다. 모든 요청은 `PLATFORM_OPERATOR` JWT와 active
`MERCHANT_CREDENTIAL_MANAGE` grant를 함께 요구한다. 다른 role·permission이나 DB 직접 변경은 대체 수단이 아니다.

## 공통 안전 경계

- 명령에는 8~128자의 `Idempotency-Key`와 control character가 없는 1~200자 reason을 보낸다.
- 임시 비밀번호는 성공 응답에서 한 번만 표시된다. 응답은 `Cache-Control: no-store`이며 값이나 Hash를
  log, ticket, Audit, frontend storage에 저장하지 않는다.
- create/reset 응답을 잃었을 때 같은 key를 자동 재시도하지 않는다. replay는
  `409 TEMPORARY_PASSWORD_NOT_REPLAYABLE`과 `targetReference`만 반환한다.
- 결과가 불명확하면 exact login ID 조회로 계정 존재를 확인하고, 새 key의 reset으로 새 임시 비밀번호를
  한 번 발급한다. 이전 값을 복구하거나 성공·실패를 추정하지 않는다.
- 권한·Store 조회·Identity write·LOGIN_ID attempt 삭제·Audit·terminal idempotency 중 하나라도 실패하면
  명령 전체가 rollback된다.

## 계정 발급

`POST /api/v1/operations/merchant-accounts`에 canonical login ID, 표시 이름, 존재하는 `storeId`,
`OWNER | STAFF`, reason을 보낸다. 성공한 `201`은 MerchantAccount와 최초 ACTIVE StoreMembership이 함께
생성됐다는 뜻이다. 반환된 32자 Base64URL 임시 비밀번호는 안전한 별도 채널로 한 번 전달한다.

같은 점주 namespace의 login ID가 이미 있으면 `409 LOGIN_ID_UNAVAILABLE`이다. 고객 login ID namespace와는
분리된다. 없는 Store는 404이며 계정이나 membership이 일부만 남지 않는다.

## exact 조회와 수렴

`GET /api/v1/operations/merchant-accounts?loginId={canonical}`에 `X-Access-Reason`을 보낸다. 응답에는 계정
상태, 잠금/임시 비밀번호 만료 시각과 memberships만 있으며 비밀번호는 없다. 조회와
`MERCHANT_ACCOUNT_READ` Audit가 같은 transaction에 성공해야 200이다.

## 임시 비밀번호 재발급

`POST /api/v1/operations/merchant-accounts/{merchantAccountId}/temporary-password-resets`에 새 key와 reason을
보낸다. 성공하면 새 Hash, 24시간 기한, `INITIAL_PASSWORD`, 잠금 해제와 `credentialVersion` 증가가
원자 반영된다. 과거 비밀번호와 Session은 즉시 사용할 수 없다.

## 잠금 조기 해제

`POST /api/v1/operations/merchant-accounts/{merchantAccountId}/lock-releases`에 새 key와 reason을 보낸다.
계정 `lockedUntil`과 같은 login ID의 attempt block이 함께 제거된다. secret이 없으므로 같은 payload/key
replay는 204다. 다른 payload 재사용은 `409 IDEMPOTENCY_KEY_REUSED`다.

## 장애 진단

| 증상 | 의미와 조치 |
|---|---|
| 403 `ACCESS_DENIED` | JWT role 또는 active grant 부재. grant를 우회하지 말고 승인된 bootstrap 절차를 따른다 |
| 404 `MERCHANT_ACCOUNT_NOT_FOUND` | exact login ID 또는 account reference가 없음. 추측 생성 전에 입력을 확인한다 |
| 409 `TEMPORARY_PASSWORD_NOT_REPLAYABLE` | 첫 응답은 이미 terminal. exact 조회 후 새 reset key로 수렴한다 |
| 409 `IDEMPOTENCY_KEY_REUSED` | 같은 actor/operation key의 payload가 다름. 원 요청을 조사하고 새 의미의 명령은 새 key를 쓴다 |
| 503 `DEPENDENCY_UNAVAILABLE` | permission, Store, Identity, Audit 또는 DB 실패. 성공으로 간주하지 않고 같은 조건을 복구한 뒤 재시도한다 |

terminal idempotency row는 90일 보존하고 worker가 시간당 최대 100개를 삭제한다. 실패 metric
`beanflow.operations.merchant_credential.retention.failure`이 증가하면 다음 실행이 재시도한다. 명령 경로는
retention 장애 때문에 멱등 검사를 우회하지 않는다.

관련 결정: [BR-46](../product/business-policy-decisions.md),
[ADR-093](../adr/ADR-093-merchant-credential-lifecycle.md),
[operator permission bootstrap](operator-permission-bootstrap-runbook.md).
