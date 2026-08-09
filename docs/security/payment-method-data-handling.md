# PaymentMethod Data Handling

## Scope

결제수단 등록·표시·승인 snapshot·폐기와 Provider notification에서 다루는 값을 분류하고 공개,
저장, 관측과 보존 경계를 정한다. 제품 계약의 원본은 BR-29와 ADR-021/079다.

## Data classes

| Data | Classification | Storage and exposure |
|---|---|---|
| `displayAlias`, `cardBrand`, `lastFour` | 고객 표시 metadata | PaymentMethod에 저장하고 owner 고객에게만 반환. identity·인가·unique 근거가 아님 |
| `provider`, 공개 lifecycle 상태·시각·default | resource metadata | owner 고객에게 반환. Provider는 MVP에서 `TOSS_PAYMENTS` 고정 |
| raw `authKey` | 일회성 Provider authorization credential | request/Provider call 메모리에서만 사용하고 DB·cache·log·trace·metric·Audit에 저장하지 않음 |
| `authKey` SHA-256 | 내부 멱등 verifier | registration 원장에만 저장. 응답·log·trace·metric tag·Audit에 노출하지 않음 |
| opaque token reference (`billingKey`) | 결제 실행 credential | PaymentMethod와 필요한 Payment request snapshot에 저장. API·log·trace·metric tag·Audit에 노출하지 않음 |
| provider customer reference (`customerKey`) | token과 결합되는 내부 Provider credential | registration 원장, PaymentMethod와 필요한 request snapshot에 저장. identity·인가·검색 key가 아니며 외부 노출 금지 |
| Payment request snapshot | 이미 시작된 Payment의 immutable Provider input | Payment 내부 전용. PaymentMethod deactivation 뒤에도 approve/lookup/recovery가 같은 값으로 수렴하도록 보존 |
| Provider notification ID/type/time, token fingerprint | 검증된 폐기 알림의 최소 증적 | provider-neutral inbox에 저장. raw payload/token/customer reference는 inbox와 Audit에 복제하지 않음 |
| 합성 PAN·expiry·birth date·card password | test-only transient sensitive input | 네 activation gate를 만족한 `src/test` harness 메모리에서만 생성·전송 후 즉시 참조 폐기. CVC는 생성하지 않음 |

BeanFlow 고객 API는 PAN, CVC, expiry, 생년월일과 카드 비밀번호를 받지 않는다. unknown request
field도 거부한다. 마스킹 카드번호 전체를 Provider 응답에서 받더라도 저장·응답하지 않고 검증된
brand와 last4만 추출한다. token이나 provider reference에서 표시값을 파싱하지 않는다.

## Authorization and minimization

- 고객은 자기 PaymentMethod의 최소 projection만 목록으로 본다. 다른 owner target은 403이고
  존재하지 않는 target은 404다. 소유권 판정 전에 표시 metadata를 반환하지 않는다.
- Store, Settlement와 일반 Platform Operator API에는 PaymentMethod projection을 제공하지 않는다.
  운영 조사 endpoint·permission은 현재 범위에 없으며 role만으로 DB 값을 노출하지 않는다.
- default, alias, brand, last4, token과 provider customer reference 어느 것도 owner identity를
  증명하지 않는다. authoritative customer ID만 객체 수준 인가에 사용한다.
- 공개 상태는 `ACTIVE|DEACTIVATION_PENDING`으로 축약한다. 내부 attempt, raw failure code와
  `MANUAL_REVIEW`는 공개하지 않고 필요한 경우 `DEACTIVATION_DELAYED` notice만 반환한다.

## Logs, traces, metrics and Audit

다음 값을 message, structured field, trace attribute, metric tag와 AuditRecord에 넣지 않는다.

- Authorization header, Provider secret와 raw webhook payload
- raw 또는 hashed authKey
- token reference, provider customer reference와 그 hash
- Payment request snapshot의 Provider 값
- 합성 카드·신원 입력

관측은 operation, 닫힌 BeanFlow outcome/reason, correlation ID와 aggregate ID만 사용한다. Provider
raw message는 사용자 error detail과 운영 로그에 전달하지 않는다. token fingerprint advisory lock과
notification mapping의 내부 값도 metric tag와 Audit에 쓰지 않는다.

## Retention and deletion

| Record | MVP retention |
|---|---|
| terminal registration/default/deactivation command | terminal 시점부터 90일 뒤 별도 bounded worker가 삭제 가능 |
| `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW` command/work | 운영 해소 전 자동 삭제 금지; 해소 뒤 terminal 90일 적용 |
| notification inbox | terminal 처리 뒤 90일; ambiguous/manual-review row는 해소 전 삭제 금지 |
| deactivated PaymentMethod tombstone와 token/provider reference | MVP 자동 purge 없음. soft deactivation, late notification, duplicate binding과 기존 Payment 복구 증적을 위해 보존 |
| Payment request snapshot | 연결된 Payment와 recovery의 보존 수명 동안 immutable 보존 |
| AuditRecord | 공통 BR-30의 Asia/Seoul 달력 5년 정책 |

MVP에는 PaymentMethod token·provider reference와 Payment snapshot의 법정/계약 기반 최종 purge
시점이 없다. 따라서 live Provider adapter와 `prod` lifecycle activation은 별도 retention·고객 탈퇴·
legal hold 정책 없이 허용하지 않는다. 후속 정책은 terminal tombstone의 표시 metadata, token,
provider reference, notification fingerprint와 Payment snapshot을 각각 언제 redact/delete할지,
진행 중 Payment·분쟁·정산 hold가 purge를 어떻게 막는지 결정해야 한다. 현재 구현은 이 값을
임의 TTL로 지우거나 hard delete하지 않는다.

## Failure behavior

- 저장·redaction·Audit 경계 검증 실패를 빈 metadata, local token, fake Provider 또는 성공으로
  대체하지 않는다.
- Provider 결과가 불명확하면 token 발급·폐기를 추정하지 않고 내구 unknown 상태를 남긴다.
- credential·설정 결함은 고객 거절이 아니라 `PAYMENT_METHOD_PROVIDER_UNAVAILABLE`과 startup/
  operational failure로 노출한다.
- production profile에서 scripted/sandbox adapter가 선택되거나 필수 live 계약·retention 정책이
  없으면 시작을 실패시킨다.

## Verification

- target/runtime OpenAPI와 request DTO에서 금지 필드 및 내부 reference 부재
- owner별 목록·default·폐기와 403/404 metadata 비노출
- log capture, tracing, metric registry와 AuditRecord의 금지 값 부재
- raw authKey 비저장과 terminal/unknown 원장 retention boundary
- 합성 입력의 source·fixture·설정·DB·관측 데이터 부재와 test-only activation gate
- deactivation·Payment 경쟁에서 snapshot 사용과 current PaymentMethod fallback 부재
- prod profile의 scripted/sandbox/missing Port fail-start

### Implementation evidence (2026-08-09)

- V37은 lifecycle 상태, TOSS provider customer reference, ACTIVE default 0..1, command/inbox retention과
  immutable Payment request snapshot을 PostgreSQL CHECK·unique·index·trigger로 보호한다.
- `PaymentMethodApplicationService`와 `PaymentMethodLifecycleTransactions`는 R1/RC/R2, M,
  D1/DC/D2를 분리하고 registration/deactivation Port 호출을 DB transaction 밖에서 수행한다.
- `PaymentMethodProviderNotificationService`는 검증 완료 입력만 W1/W2로 받고 raw token 대신
  fingerprint만 inbox에 남긴다. mapped W2는 진행 중 deactivation 원장도 stored 204로 함께 수렴한다.
- `PaymentMethodLifecycleMaintenance`는 재기동 때 이전 프로세스의 미완료 claim을 외부 재호출 없이
  unknown으로 복구하고, 96시간 deadline과 90일 terminal cleanup을 별도 transaction으로 수행한다.
- 결제수단 통합·동시성·notification·profile safety 테스트는 owner 격리, exact replay/conflict,
  provider 결과불명, immutable snapshot, startup recovery와 민감값 비노출 경계를 실행 검증한다.

## Revisit Conditions

라이브 Provider 계약, 고객 탈퇴·삭제권, 법정 결제기록 보존, legal hold, 운영 조사 permission 또는
두 번째 Provider를 도입하기 전에 재검토한다.
