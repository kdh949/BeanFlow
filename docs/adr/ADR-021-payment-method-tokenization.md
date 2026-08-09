# ADR-021: 결제수단 tokenization과 저장 금지 데이터

- **Status:** Accepted
- **Date:** 2026-07-28
- **2026-08-09 amendments:** Accepted by ADR-079
- **Amended by:** ADR-078의 provider 참조 값 저장, ADR-079의 공개 등록 보안 경계

## Context

BR-29는 BeanFlow가 원본 카드번호, CVC와 전체 유효기간을 저장하지 않고 PG token
reference만 사용하도록 정한다.

## Decision

- PaymentMethod에는 provider, provider token reference, memberId, 표시용 별칭,
  카드 브랜드와 마지막 4자리만 저장한다.
- 원본 PAN, CVC와 전체 유효기간은 API schema, Entity, log, trace, AuditRecord와
  test fixture에 두지 않는다.
- member/provider/token reference를 Unique Constraint로 보호한다.
- 다른 member의 token 사용과 폐기된 token 사용을 Payment Application Service에서
  객체 수준 인가와 상태로 거부한다.
- 필수 Provider credential이 없거나 운영 profile에서 mock Provider가 선택되면
  startup을 실패시킨다.

## Amendment (2026-08-09): 고객용 등록은 결제창 `authKey`만 수신

고객용 결제수단 등록은 Provider 결제창이 발급한 일회성 `authKey`를 받는 방식으로
한정한다. BeanFlow 공개 API는 카드번호, 유효기간, 생년월일, 카드 비밀번호와 CVC를
수신하지 않는다. `authKey`는 registration Port를 통해 opaque token으로 교환하며 Provider
결제창의 휴대폰 본인인증 등 소유 확인 절차를 우회하지 않는다.

카드 원문을 BeanFlow API로 받아 Provider에 전달하는 API 방식은 MVP 공개 계약과 운영
코드의 Non-goal이다. 자동 sandbox 검증을 위한 합성 입력 예외는 고객용 request schema와
같은 코드 경로를 재사용하지 않으며 ADR-079가 별도 테스트 전용 계약으로 확정한다.

## Amendment (2026-08-09): 표시 metadata의 닫힌 목록

표시용 저장·응답 필드는 `displayAlias`, `cardBrand`, `lastFour`뿐이다. 고객은 trim 뒤
1..80자이고 control character가 없는 alias만 입력한다. brand와 last4는 Provider의 검증된
발급 결과만 원천으로 사용하며 각각 trim 뒤 1..40자와 숫자 4자리다. 누락·형식 불일치를
빈 값, `UNKNOWN`, token 파싱이나 고객 입력으로 보정하지 않는다.

expiry month/year는 함께 전체 유효기간을 구성하므로 저장·응답하지 않는다. 카드번호, CVC,
생년월일, 카드 비밀번호, token reference와 provider customer reference도 공개 schema에
넣지 않는다. 표시값은 identity·인가·unique 조건이 아니며 중복을 허용한다.

## Amendment (2026-08-09): provider가 요구하는 non-sensitive 참조 값

원 Decision의 저장 필드는 닫힌 목록이다. 토스페이먼츠 자동결제는 발급된 빌링키만으로
승인할 수 없고 발급 시점에 매핑된 `customerKey`를 함께 보내야 하므로, 이 목록에는 승인에
필수인 값을 담을 자리가 없다. 목록을 최소한으로 연다.

- PaymentMethod는 provider가 승인을 위해 token reference와 함께 요구하는 non-sensitive
  참조 값을 provider별로 저장할 수 있다.
- 저장 금지 데이터는 그대로다. 참조 값은 원본 PAN, CVC와 전체 유효기간 중 어느 것도
  담을 수 없고, 이들을 복원할 수 있는 형태여서도 안 된다.
- provider가 유추 가능한 값을 금지하면 참조 값은 무작위로 생성한다. memberId, 이메일,
  전화번호와 자동 증가 숫자에서 파생하지 않는다.
- 참조 값은 결제수단 identity가 아니다. `(memberId, provider, tokenReference)` unique
  제약은 그대로이며 참조 값을 identity에 포함하지 않는다.
- 참조 값은 인가 수단이 아니다. 다른 member의 token 사용 거부는 계속 memberId 기반 객체
  수준 인가로 판정하고, 참조 값 일치를 근거로 접근을 허용하지 않는다.
- 참조 값은 API schema, 로그, trace, metric tag와 AuditRecord에 노출하지 않는다.
  표시용 메타데이터로 승격하지 않는다.
- 참조 값을 요구하는 provider에서 값이 없거나 읽을 수 없으면 Provider 호출을 시도하지 않고
  명시적으로 실패한다. 임의 값으로 대체하지 않는다.
- provider가 실제로 요구하는 값만 저장한다. 다른 provider가 쓸지 모른다는 이유로 미리
  만들지 않는다.

이 amendment의 최초이자 유일한 적용 대상은 토스페이먼츠 자동결제의 `customerKey`다.

저장 컬럼은 `payment_method.provider_customer_reference varchar(200)`다. 기존 row와 참조
값을 요구하지 않는 provider를 위해 nullable로 두되, DB CHECK는 `TOSS_PAYMENTS`에 trim 뒤
non-blank를 요구하고 다른 provider에는 null만 허용한다. 다른 provider가 값을 요구하면 별도
결정과 migration으로 허용 목록을 넓힌다. 다른 provider null branch는 legacy local/test row의
무손실 migration을 위한 것이며 신규 제품 provider를 허용하지 않는다. 이 컬럼은 unique·인가
조건에 넣지 않는다.

registration Application Service의 CSPRNG factory가 등록 시도당 한 번 값을 생성하고 외부
발급 호출 전에 registration 멱등 원장에 고정한다. 성공 PaymentMethod는 그 값을 복사한다.
adapter가 값을 생성하거나 retry마다 바꾸거나 누락을 default로 대체하지 않는다.

## Amendment (2026-08-09): 테스트 환경 합성 카드 입력 전송

원 Decision은 원본 PAN·CVC·전체 유효기간을 test fixture에 두는 것을 금지한다. 그런데
Provider sandbox에서 빌링키를 발급하려면 카드 정보를 요청 본문에 실어야 하므로, 이 금지를
문자 그대로 적용하면 sandbox 승인 경로를 검증할 방법이 사라진다. 토스 테스트 환경은 카드
번호 앞 6자리 BIN만 유효하면 자동결제가 등록되고 승인되어도 실제 출금이 없다.

- `src/test` 통합테스트 harness가 Toss registration adapter를 직접 호출하는 방식만 허용한다.
  공개·내부 HTTP endpoint와 운영 Application Service entry point는 만들지 않는다.
- harness는 `toss-sandbox` profile, `prod` profile 부재, `test_sk_` secret 확인과 별도
  synthetic-issuance enable 조건을 모두 요구한다. 하나라도 맞지 않으면 합성 값 생성과
  Provider 호출 전에 명시적으로 실패한다.
- Provider가 카드 API 발급에 요구하는 합성 카드번호·유효기간·생년월일·카드 비밀번호를
  실행 시점에 생성해 전송한다. CVC는 생성하거나 전송하지 않는다. 공개 API와 Entity의
  카드번호·전체 유효기간 비수신·비저장 금지는 그대로다.
- 합성 값은 소스 코드, fixture 파일, 설정과 seed 데이터에 기록하지 않는다. 매 실행마다
  생성하고, 빌링키 발급 응답을 받은 직후 참조를 폐기한다.
- 합성 값은 어떤 Entity, 로그, trace, metric, AuditRecord와 API schema에도 남기지 않는다.
- live key 환경과 `prod` profile에서는 사용할 수 없다.
- 무작위 생성 값이 실재하는 카드와
  우연히 일치할 수 있으나, 테스트 환경 승인은 실제 결제수단에서 출금을 만들지 않는다.
- 실제 사람의 카드·신원 값을 입력하거나 수집하지 않는다.

이 예외는 테스트 환경 전용이다. 라이브 환경의 카드 정보 취급은 이 amendment가 다루지 않는다.

## Amendment (2026-08-09): Provider token 폐기까지 포함하는 soft deactivation

고객의 결제수단 DELETE는 local tombstone만 만들지 않고 Provider token 폐기까지 요청한다.
Application Service는 소유권·상태·멱등성을 검증한 로컬 transaction에서
`DEACTIVATION_REQUESTED`와 복구 work를 먼저 commit한다. 그 시점부터 결제수단은 새 결제에
사용할 수 없다. Provider deactivation Port는 이 transaction 밖에서 호출한다.

확인된 Provider 성공만 `DEACTIVATED`로 전이한다. timeout, 응답 유실과 Provider 성공 뒤
local result transaction 실패는 `DEACTIVATION_UNKNOWN` 또는 `RECONCILING`으로 보존하고,
bounded 복구가 소진되면 `MANUAL_REVIEW`로 전환한다. 어느 상태도 `ACTIVE`나 성공으로
fallback하지 않는다. 진행 중 복구와 감사에 token reference가 필요하므로 이 경로에서
PaymentMethod row를 hard delete하지 않는다.

## Amendment (2026-08-09): 검증된 외부 폐기 알림의 단조 반영

Provider가 보낸 검증된 token 폐기 알림은 provider-neutral inbox에 멱등 수락한 뒤 정확히
하나로 매핑된 PaymentMethod를 `DEACTIVATED`로 단조 전이한다. `ACTIVE`, deactivation 진행·
불명·수동 검토 상태에서 모두 적용할 수 있고 이미 `DEACTIVATED`인 같은 알림은 replay
success다. 이미 시작된 Payment fact를 소급 변경하거나 token을 재활성화하지 않는다. 토스의
최초 event type은 `BILLING_DELETED`다.

transport 인증·서명 검증 실패는 business inbox에 넣지 않는다. 검증된 알림도 단일 row에
매핑되지 않으면 소유자를 추정하거나 임의 결제수단을 폐기하지 않고 `MANUAL_REVIEW`로
보존한다. raw payload, token reference와 provider customer reference는 로그·trace·metric
tag·AuditRecord에 남기지 않는다. DB 수락·상태 전이 실패를 2xx 성공으로 숨기지 않는다.

## Amendment (2026-08-09): 시작된 Payment의 immutable Provider request snapshot

Payment 승인 Tx1은 ACTIVE PaymentMethod를 잠가 검증한 뒤 provider, token reference와 provider
customer reference를 내부 전용 `PaymentProviderRequestSnapshot`에 Payment와 함께 고정한다.
Provider approve·lookup·late recovery는 이 snapshot만 사용한다. 뒤 deactivation은 새 Payment를
차단하지만 이미 시작된 Payment fact를 취소하거나 current PaymentMethod 상태로 재판정하지 않는다.

snapshot 값은 API·로그·trace·metric tag·AuditRecord에 노출하지 않는다. exactly-one, immutable,
Payment/PaymentMethod binding을 DB와 Application Service가 보호하며 누락·불일치를 current
PaymentMethod 또는 default 결제수단으로 보정하지 않는다.

## Amendment (2026-08-09): duplicate token의 exact binding 수렴

registration result의 같은 provider token은 비가역 fingerprint 기반 advisory lock으로
직렬화한다. 기존 `ACTIVE` row의 owner, provider, token reference, provider customer reference,
display alias, card brand와 last4가 모두 같을 때만 기존 PaymentMethod로 수렴한다. 기존 row를
갱신하거나 새 row를 만들지 않고 `isDefault`도 바꾸지 않는다.

owner·provider customer reference·표시 metadata가 다르거나 기존 row가 ACTIVE가 아니면 token이
같아도 overwrite·재활성화하지 않고 conflict/manual review로 보존한다. provider customer
reference는 비교할 binding이지만 identity·인가·unique 제약은 아니며 기존
`(customer_id, provider, token_reference)` unique를 유지한다. migration에서 provider+token의
cross-owner 중복을 발견하면 임의 병합하지 않는다.

## Amendment (2026-08-09): registration authKey의 비저장 멱등성

등록 사전 원장은 raw `authKey`가 아니라 SHA-256과 정규화 alias만 canonical payload로
보존한다. raw authKey는 Provider 호출 request 범위를 벗어나 저장·로그·trace·metric·Audit에
남기지 않는다. 다른 key의 같은 customer/provider/authKey hash도 Provider 호출 전에 거부한다.

Provider call claim 뒤 timeout·응답 유실·process loss는 일회성 authKey를 재전송하거나 성공
token을 추정하지 않는다. lookup 계약이 없는 provider는 새 상호작용 없이 `MANUAL_REVIEW`로
종결한다. registration 원장의 provider customer reference와 hash도 공개·관측 payload로
노출하지 않는다.

## Amendment (2026-08-09): 폐기 DELETE의 단일 외부 시도

폐기는 owner와 operation 단위 멱등 원장을 Tx D1의 비활성 상태·work와 함께 먼저 commit한다.
같은 key·PaymentMethod replay는 새 상태 전이와 외부 호출 없이 기존 결과를 반환하고, 같은 key로
다른 PaymentMethod를 지정하면 409로 거부한다.

내구 claim 뒤 Provider DELETE는 transaction 밖에서 한 번만 호출한다. timeout·응답 유실·process
loss와 result 저장 실패에서는 Provider가 DELETE 멱등키나 결과 조회를 보장하지 않으므로 자동
재호출하거나 not-found를 성공으로 추정하지 않는다. 검증된 `BILLING_DELETED` 알림으로 자동
수렴하며, 최초 unknown 판정부터 96시간 안에 알림이 없으면 `MANUAL_REVIEW`로 보존한다. 어느
경로도 `ACTIVE`로 되돌리거나 hard delete하지 않는다.

## Amendment (2026-08-09): registration/deactivation Port 결과 분리

registration Port는 `Issued`, `RejectedWithoutEffect`, `Unknown`, `Misconfigured`를,
deactivation Port는 `Deactivated`, `RejectedWithoutEffect`, `Unknown`, `Misconfigured`를
닫힌 결과로 반환한다. 성공 variant만 opaque token·검증된 표시 metadata 또는 확인된 폐기
사실을 담는다. `RejectedWithoutEffect`는 adapter contract test로 외부 side effect 부재가
입증된 allowlist code에만 쓸 수 있다.

timeout·연결 실패·응답 유실·5xx·파싱 실패·성공 필수값 누락과 미등록 code는 `Unknown`이다.
credential·인증·계약·필수 설정 결함은 `Misconfigured`로 올려 고객 입력 거절과 분리한다.
정상 Provider 결과는 예외로 숨기지 않으며 raw code/message와 내부 reference를 공개 응답·로그·
trace·metric tag·Audit에 넣지 않는다.

registration `Misconfigured`가 side effect 부재를 확인한 경우만 설정 수정 뒤 같은 key로 새 claim을
허용한다. `Unknown` authKey는 다시 보내지 않는다. deactivation `RejectedWithoutEffect`와
`Misconfigured`는 이미 비활성화된 local state를 manual review로 보내고 Provider DELETE를 다시
호출하지 않는다.

## Amendment (2026-08-09): lifecycle Provider adapter activation

scripted registration/deactivation adapter는 `(local | test) & !toss-sandbox & !prod`에서만 명시적으로 활성화한다.
Toss sandbox adapter는 `toss-sandbox`, `!prod`, `test_sk_` secret에서만 활성화하고 합성 카드
발급에는 별도 enable flag를 추가한다. `prod`에는 현재 lifecycle adapter를 제공하지 않는다.

lifecycle use case가 활성인데 Port가 없거나 adapter가 둘 이상인 경우, sandbox와 scripted
조건이 겹치는 경우와 `live_sk_` 입력은 startup failure다. Bean 부재와 외부 실패를
`@ConditionalOnMissingBean` scripted/fake/no-op으로 자동 대체하지 않는다. scripted adapter는
local/test capability이며 제품 Provider, 공개 provider 값과 운영 fallback이 아니다.

## Alternatives Considered

- 카드 원문 직접 저장
- token reference만 저장하고 소유권 미검증
- Provider tokenization과 최소 표시 메타데이터

## Rationale

민감 결제정보 저장 책임을 피하면서 사용자에게 필요한 결제수단 식별 정보를 제공한다.

## Consequences

- 실제 Provider의 token 수명과 폐기 callback 계약이 필요하다.
- Provider 장애 시 임의 local token으로 대체할 수 없다.

## Verification

- 다른 사용자의 token 사용 거부
- 민감 필드 이름과 값이 schema/log에 없음
- token 중복 등록과 폐기 상태
- production profile mock startup failure
- 고객용 등록 request schema에 카드번호·유효기간·생년월일·카드 비밀번호·CVC가 없고
  unknown field가 거부됨
- 공개·운영 profile에 카드 원문 기반 등록 endpoint와 request DTO가 없음
- 표시값 validation과 Provider 결과 누락 시 placeholder 없는 명시적 실패
- Entity·OpenAPI·response의 expiry month/year와 내부 token/provider customer reference 부재
- test harness의 profile/key/enable gate와 공개·내부 HTTP route 부재
- 합성 카드번호·유효기간·생년월일·카드 비밀번호의 source·fixture·설정·seed·DB·관측 데이터 부재
- deactivation 의도 commit 이후 Provider 호출 전·중 신규 결제 선택 거부
- Provider timeout·응답 유실·result 저장 실패의 unknown/reconciliation 보존과 hard delete 부재
- 검증된 외부 폐기 알림의 멱등 단조 전이와 미검증·ambiguous 알림의 무변경
- 시작된 Payment snapshot과 deactivation 경쟁의 선형화, snapshot 비노출과 fallback 부재
- duplicate token exact ACTIVE binding 수렴과 다른 binding의 overwrite·재활성화 부재
- raw authKey 비저장, same/cross-key 멱등 중재와 unknown 뒤 재전송·성공 추정 부재
- 폐기 same-key/cross-target 중재와 claim 뒤 Provider DELETE 재호출 부재
- 폐기 불명 결과의 96시간 `BILLING_DELETED` 대기 또는 MANUAL_REVIEW 수렴
- registration/deactivation Port의 닫힌 결과와 미등록 응답 Unknown fail-closed
- profile별 lifecycle adapter 상호배타와 prod Port 부재의 fail-start
- provider 참조 값이 API 응답, 로그, trace, metric tag와 AuditRecord에 없음
- provider 참조 값의 무작위 생성과 memberId·이메일·전화번호 비파생
- `TOSS_PAYMENTS`의 참조 값 필수와 다른 provider의 null 강제
- 등록 retry·timeout에서 registration 원장과 PaymentMethod의 참조 값 불변
- 참조 값이 일치해도 다른 memberId의 결제수단 사용이 거부됨
- 참조 값을 요구하는 provider에서 값 부재 시 Provider 호출 없는 명시적 실패
- 참조 값이 `(memberId, provider, tokenReference)` unique 제약에 포함되지 않음

## Metrics

- **Not measured:** Provider token 수명과 API latency

## Revisit Conditions

실제 PG sandbox 계약, 규제·인증 범위 또는 network token이 도입될 때

## Related Decisions

- BR-29
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-078](ADR-078-toss-payments-sandbox-gateway-adapter.md)
- [ADR-079](ADR-079-payment-method-token-management.md)
