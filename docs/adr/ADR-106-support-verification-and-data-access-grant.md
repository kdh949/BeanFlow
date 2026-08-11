# ADR-106: opaque challenge verification과 audit-gated DataAccessGrant reveal

- **Status:** Accepted
- **Date:** 2026-08-11

## Context

ADR-082는 Case+Subject+Purpose-bound verification, field/time/count-bound raw reveal과 별도 break-glass path를
요구한다. S30은 owner-local Vault ciphertext와 masked search를 구현했지만 raw reveal API, challenge Provider
contract, attempt lockout과 terminal Case revocation transaction은 구현하지 않았다.

## Decision

### Verification challenge boundary

Support는 `VerificationSession`과 append-only `VerificationAttempt`를 소유한다. Session은 Case, active subject
link, purpose, action scope와 requested level에 묶이며 생성 뒤 binding이나 requested level을 바꾸지 않는다.

외부 `VerificationChallengePort`가 secret 생성, 등록 채널 전송과 proof 검증을 소유한다. Support는 provider에
stable challenge intent ID를 전달하고 opaque provider reference와 닫힌 결과만 저장한다. OTP, raw link와 proof는
DB, log, metric, Audit 또는 snapshot에 저장하지 않는다. issue/verify는 DB transaction 밖에서 수행하며 timeout,
응답 유실, malformed response와 allowlist 밖 결과는 `UNKNOWN`이다. local/test scripted adapter는 명시적 profile과
test source에서만 허용하며 production adapter/configuration 부재는 startup failure다.

Session은 15분, challenge는 5분 유효하다. 한 Session에서 invalid proof 5회면 `LOCKED`가 되고 같은
Case+Subject는 30분 동안 새 Session을 만들 수 없다. BASIC은 등록 채널 한 종류, ENHANCED는 서로 다른 등록 채널
두 종류의 성공을 요구한다. 같은 challenge는 terminal provider outcome 뒤 다시 검증할 수 없다.
Lockout key는 subject-link ID가 아니라 Case+Subject type+Subject ID라서 unlink/relink로 우회할 수 없다. Process가
Provider 호출 중 종료되어 `PENDING_ISSUE`/`VERIFYING`이 남으면 challenge expiry 뒤 recovery worker가 Case-first
lock으로 `ISSUE_UNKNOWN`/`VERIFICATION_UNKNOWN`과 terminal idempotency receipt를 원자 기록한다.

### DataAccessGrant와 reveal

일반 Grant는 operator+Case+Subject+purpose+field set+reason+expiry+reveal budget에 묶인다. Display-name 계열은
BASIC field이며 10분/3회 budget으로 요청자에게 활성화할 수 있다. Phone/email/provider-reference 계열은
SENSITIVE field이며 ENHANCED verification, distinct approver와 5분/1회 budget이 필요하다. Reveal 시점에도
persistent permission, current Case assignment/state, active subject link, verification binding, grant version/scope,
expiry/budget과 owner field allowlist를 다시 검사한다. R4 secret은 field vocabulary에 포함하지 않는다.

Reveal은 다음 순서다.

1. 짧은 Support transaction이 Case와 Grant를 잠그고 budget을 예약한 `RevealAttempt`와 PII-free
   `SUPPORT_PII_ACCESS_RECORDED` Audit를 함께 commit한다.
2. owner public Application API가 owner-local ciphertext를 읽고 Vault decrypt를 DB transaction 밖에서 수행한다.
3. 별도 Support transaction이 current Case assignment/state, active subject link와 persistent permission을 다시
   확인하고 attempt를 `REVEALED` 또는 `FAILED`로 commit한다.
4. `REVEALED` commit 뒤에만 Controller가 raw DTO를 반환한다.

Audit, first commit, owner decrypt 또는 result commit이 실패하면 raw response가 없다. Audited attempt는 결과와
무관하게 budget을 소비해 concurrent retry가 같은 slot을 재사용하지 못한다.

### Break glass와 Case closure

`BREAK_GLASS`는 VerificationLevel/DataAccessGrant activation을 우회하지 않고 별도 Aggregate와 endpoint를 사용한다.
한 request는 active Case, one exact field, structured emergency reason, 2분 expiry와 reveal budget 1을 가진다.
Requester와 다른 `SUPPORT_PII_REVEAL_APPROVE` actor의 사전 승인이 필요하고 reveal 뒤
`PRIVACY_BREAK_GLASS_REVIEW` actor의 사후 검토가 필수다. Request/approval/reveal은 PII-free security notification
intent를 durable하게 만들며 delivery failure는 retry/manual-review 상태로 남긴다. Raw break-glass reveal도 위
Audit-before-reveal commit gate와 owner object authorization을 사용한다.
Notification claim이 `PROCESSING`에서 worker 중단을 만나면 5분 claim timeout 뒤 재회수하며 attempt limit을
계속 적용한다.

SupportCase가 `RESOLVED` 또는 `CLOSED`로 전이하는 transaction은 같은 Case의 active VerificationSession,
DataAccessGrant와 pre-reveal BreakGlassRequest를 revoke한다. 이미 reveal되어 mandatory review를 기다리는
`REVIEW_PENDING` request는 review evidence를 보존한다. `RESOLVED`/`CLOSED` Case에서 activation, approval과
reveal은 DB/service 양쪽에서 거부한다.

## Alternatives Considered

- Support가 OTP hash와 secret delivery payload를 저장: raw-secret persistence와 pepper 운영 경계 때문에 기각.
- existing order NotificationDelivery에 OTP를 넣음: order-shaped schema와 payload retention이 맞지 않아 기각.
- Provider call을 Case/Grant transaction 안에서 실행: long transaction과 lock contention 때문에 기각.
- decrypt 뒤 Audit append: raw 반환 전 성공 Audit 불변식을 위반해 기각.
- break glass를 verification level로 표현: ADR-082와 충돌해 기각.

## Rationale

Secret 수명은 Provider에 두고 Support에는 재생·잠금·binding 증거만 남긴다. Reveal을 audited reservation,
owner decrypt와 result commit으로 분리하면 external Vault call을 long transaction 밖에 두면서 Audit commit 전 raw
응답을 구조적으로 차단할 수 있다.

## Consequences

Reveal 한 번에 두 Support transaction과 owner Vault 호출이 필요하며 failed audited attempt도 budget을 소비한다.
ENHANCED와 sensitive reveal은 상담 지연이 늘어난다. production challenge provider와 security notification
delivery provisioning은 release 전 필수이며 local/test adapter가 fallback이 될 수 없다.

## Verification

- OTP/proof replay, five-failure lock과 30-minute recreation boundary
- subject unlink/relink lockout bypass와 replacement-assignee session reuse denial
- session/challenge/grant/break-glass `-1ns / at / +1ns`
- other Case/Subject/Purpose/action reuse와 BASIC-for-ENHANCED denial
- concurrent verify/reveal single winner와 budget exhaustion
- Audit/commit/Vault failure의 no raw response
- owner decrypt 중 Case/subject/permission 변경의 no raw response와 stale Provider/notification work recovery
- field allowlist, distinct approval, Case closure revoke and activation denial
- break-glass request/pre-review/reveal/post-review/security-notification
- API `Cache-Control: no-store`와 PII-free logs/Audit/snapshots

## Metrics

Level, challenge type, outcome, field risk, access path와 failure class만 label로 사용한다. actor/Case/Subject ID,
reason, proof, raw/ciphertext/digest/provider reference는 label이나 log에 넣지 않는다.

## Revisit Conditions

상용 KYC/identity Provider 선정, fraud/abuse 데이터, verification completion rate, legal/privacy review 또는
multi-region challenge/reveal 요구가 확정될 때.

## Related Decisions

ADR-009, ADR-022, ADR-069, ADR-081, ADR-082, ADR-083, ADR-084, ADR-089.
