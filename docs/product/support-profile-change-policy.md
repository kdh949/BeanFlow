# Support Profile Change Policy

> **Status:** R0-R4 classification, S100 initial field mapping, R3 approval/actor separation and purpose-specific owner
> commands are Accepted in ADR-087/SP-22. Endpoint DTOs become runtime contracts only with S100 implementation evidence.

## Field risk classes

- R0: ID, 원장 합계, 확정 정산, verification/audit/approval/policy version. 직접 수정 금지; adjustment/reconciliation만.
- R1: 표시 이름, 알림 선호, 공개 매장 전화·소개·픽업 안내. BASIC+permission+Audit 후보.
- R2: 실명 오탈자, 보조 연락처, 운영 연락, 일반 배송지. ENHANCED+Specialist; 인증·정산·소유권 영향 시 R3.
- R3: 로그인/복구 채널, 계정 복구·소유권, 대표자·사업자·계약·정산 계좌, 라이더 식별/지급. ENHANCED, Support Manager와 Operations 순차 승인, 세 actor 분리, agent execution return.
- R4: password, MFA secret, OTP, token, PG raw token, PAN/CVC, key. 조회·직접 수정 금지; reset/re-registration만.

범용 profile PATCH를 제공하지 않고 목적별 typed workflow를 사용한다. 고위험 변경 후 가능한 경우 기존·신규 채널에 모두 알리며, notification 실패는 변경을 rollback하지 않고 retry/warning으로 남긴다.

## S100 initial owner mapping

- Customer: R1 display name, R2 verified legal-name typo, R3 primary login/recovery phone, R4 credential reset intent.
- Store: R1 public display/phone/description/pickup instructions, R2 operations phone/email, R3 legal representative and
  opaque settlement-account reference, R4 access reset/re-registration intent.
- External courier: R1 display name, R2 relay phone/email, R3 provider identity and opaque payout reference, R4 provider
  re-registration intent. This is not a first-party Rider workforce model.

R1은 BASIC+`SUPPORT_PROFILE_R1_CHANGE`, R2는 ENHANCED+`SUPPORT_PROFILE_R2_CHANGE`다. R3/R4 intent는 ENHANCED+
`SUPPORT_PROFILE_R3_REQUEST`, S60 exact revision의 Support Manager→Operations 승인과 assigned agent execution을
요구한다. Requester, 두 reviewer는 pairwise distinct하고 reviewer는 실행할 수 없다.

Support는 raw change payload를 저장하지 않는다. R3/R4 request에는 canonical digest만 남고 execution이 같은 typed
payload를 다시 제출한다. Owner가 latest version, digest, encryption/index/history를 최종 검증한다. Primary-phone은
기존 등록 채널에 귀속된 ENHANCED session 없이는 처리하지 않으며 새 전화번호만으로는 계정 소유를 입증할 수 없다.
R4 API는 password, OTP, MFA secret, token 또는 key를 입력·반환하지 않고 reset/re-registration intent만 만든다.
