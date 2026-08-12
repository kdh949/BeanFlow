# ADR-087: R0-R4 field risk와 목적별 profile change

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

Customer/Store/Rider profile 모델이 불완전하고 범용 PATCH는 필드마다 다른 인증·승인·소유권·통지를 표현하지 못한다.

## Decision

R0 system fact는 adjustment/reconciliation만, R1은 BASIC+permission+Audit 후보, R2는 ENHANCED+Specialist, identity/financial/ownership 영향은 R3로 승격한다. R3는 Support Manager+Operations 순차 승인과 agent execution return, R4 secret은 조회/직접 변경 없이 reset/re-registration만 허용한다. Identity/Merchant/Delivery owner가 목적별 typed commands와 history를 소유하며 old/new channel notification을 시도한다.

### S100 initial field and workflow amendment (2026-08-13)

S100은 다음 closed mapping을 initial policy로 사용한다.

| Owner | R0 | R1 | R2 | R3 | R4 |
|---|---|---|---|---|---|
| Identity customer | customer/profile ID, version, Audit/approval fact | display name | verified legal-name typo correction | primary login/recovery phone | credential reset intent only |
| Merchant store | store/profile ID, version, settlement fact | public display name, public phone, public description, pickup instructions | operations phone/email | legal representative, opaque settlement-account reference | access reset/re-registration intent only |
| Delivery external courier | courier/profile ID, version | display name | relay phone/email | provider identity reference, opaque payout reference | provider re-registration intent only |

`RIDER` Support subject는 ADR-088/SP-17의 Delivery-owned external courier를 뜻한다. S100은 first-party Rider workforce,
dispatch 또는 provider credential storage를 만들지 않는다. Opaque settlement/payout reference도 raw bank account,
PAN/CVC 또는 Provider token이 아니다.

R1은 current Case assignment/subject link, BASIC verification, persistent `SUPPORT_PROFILE_R1_CHANGE`와 PII-free Audit가
필수다. R2는 ENHANCED verification과 specialist `SUPPORT_PROFILE_R2_CHANGE`가 필요하다. R3와 R4 reset intent는
ENHANCED verification, persistent `SUPPORT_PROFILE_R3_REQUEST`, exact owner version과 canonical payload digest에 묶인
S60 `SUPPORT_MANAGER_THEN_OPERATIONS` revision을 사용한다. Support Manager와 Operations reviewer는 requester 및 서로와
달라야 하고 reviewer는 실행하지 않는다. Approved work는 current assigned agent에게 돌아오며 권한 상실은 explicit
reassignment를 요구한다.

Support는 R3/R4 raw typed payload를 저장하지 않는다. Request 시 canonical SHA-256 digest만 저장하고 실행자는 같은
typed payload를 다시 제출한다. Owner command가 execution 시 digest와 current owner version을 다시 확인하고 owner-local
Vault ciphertext, masked derivative, exact index와 append-only history를 commit한다. 고객 primary-phone은 current subject의
기존 등록 채널에 귀속된 ENHANCED VerificationSession이 필수다. 새 전화번호만 소유했다는 증거는 기존 계정 소유 증거가
아니며 요청·승인·실행을 만족시키지 않는다. 최종 owner-write transaction은 requester의 현재 request 권한, active Case
subject link, session의 requester/Case/subject/link/purpose/scope/ENHANCED binding과 primary-phone registered-channel
challenge를 다시 잠그고 검증한다. 승인 뒤 권한 회수·link 해제·challenge 무효화는 fail closed다.

변경된 field가 notification channel이면 owner history에 저장된 old/new encrypted snapshot을 정확히 대상으로 두 개의
durable notification intent를 만든다. 그 밖의 변경은 가능한 current registered channel에 알린다. Notification owner가
provider call 직전에 owner snapshot을 transient하게 resolve/decrypt하며 raw destination을 delivery table, log, metric,
Audit 또는 Support response에 저장하지 않는다. Profile/Audit commit 뒤 notification 실패는 change를 rollback하지 않고
`RETRY_SCHEDULED` 또는 `MANUAL_REVIEW`로 남긴다. Support notification line은 immutable source timestamp와 최초
correlation을 보존하고 `PROCESSING` claim lease를 사용한다. delivery owner commit 뒤 acknowledgement 전 장애는 만료
claim reconciliation이 같은 logical source의 기존 delivery id에 재결합하며 owner profile write를 반복하지 않는다.

## Alternatives Considered

- Support-owned JSON profile: source-of-truth divergence로 기각.
- generic PATCH와 field allowlist: workflow/approval 차이를 숨겨 기각.
- R3를 Operations가 직접 실행: 상담 lineage와 separation을 깨서 기각.
- Support에 raw change payload를 저장: owner-local PII와 digest-only S60 boundary를 깨서 기각.
- approval 전에 owner pending payload를 저장: Support request 실패 시 고아 PII와 별도 보상 workflow를 만들므로 기각.

## Rationale

Field sensitivity와 business ownership을 API와 approval granularity에 일치시킨다.

## Consequences

S30의 최소 owner profile에 legal/operations/payout history와 reset intent를 추가해야 한다. notification 실패는 change
rollback이 아닌 retry/warning이다. Initial field set 외 새 field는 default deny이며 새 policy/owner/OpenAPI amendment
없이는 generic extension으로 받아들이지 않는다.

## Verification

R-class matrix, purpose endpoints, new-contact-only denial, three-actor separation, owner version race와 notification failure.

## Metrics

Class/action별 request/approval/outcome와 post-change notification failure.

## Revisit Conditions

새 profile field, auth method, payout/legal requirement 또는 rider ownership model 확정.

## Related Decisions

ADR-027, ADR-069, ADR-084.
