# Support Verification Policy

> **Status:** Verification levels, binding and separate BREAK_GLASS path are Accepted in ADR-082. S40 initial
> challenge, attempt, expiry, Grant and break-glass values are Accepted in SP-18 and ADR-106.

## Levels

- `UNVERIFIED`: Case 생성, exact search, 마스킹 후보, 공개 매장정보, challenge 생성만 허용한다.
- `BASIC_VERIFIED`: 등록 앱·in-app approval·등록 전화 OTP·등록 이메일 링크 등 등록 채널 통제 확인이다. 제한된 자기 주문 조회와 저위험 작업 후보일 뿐 최종 권한이 아니다.
- `ENHANCED_VERIFIED`: 독립 요소, 기존 채널과 최근 로그인, 전문 검토 등 고위험 작업용 step-up이다. 새 전화 OTP만으로 기존 기본 전화번호를 변경하지 않는다.
- `BREAK_GLASS`: verification level이 아니라 별도 `AccessPath`; 긴급 안전 목적의 최소 필드 접근이다.

## Binding and lifecycle

VerificationSession은 Case, Subject, Purpose, action scope에 묶이며 다른 Case·대상·목적으로 재사용하지 않는다. BASIC은 ENHANCED 작업을 허용하지 않는다. challenge/attempt는 replay·시도 제한·LOCKED·expiry를 명시하며 OTP, 링크, password 원문은 저장하지 않는다. Provider timeout은 성공이 아닌 pending/unknown이다.

Verification은 DataAccessGrant나 domain action 권한을 자동 부여하지 않는다. 실행 시 ActionPolicy가 persistent permission, 관계, 최신 상태와 함께 재평가한다.

## S40 initial policy

- Session 15분, challenge 5분, invalid proof 5회와 같은 Case+Subject의 30분 lockout을 사용한다.
- BASIC은 등록 채널 한 종류, ENHANCED는 서로 다른 등록 채널 두 종류가 필요하다.
- display-name 계열 BASIC Grant는 10분/3회, phone/email/provider-reference SENSITIVE Grant는 ENHANCED,
  distinct approval과 5분/1회다.
- BREAK_GLASS는 한 field, 2분/1회, distinct pre-approval, durable security notification과 mandatory post-review다.
- Provider가 secret 생성·전송·검증을 소유하고 Support는 opaque reference와 outcome만 보존한다.
- Audit가 commit된 reveal attempt는 downstream 실패에도 budget을 소비하며 raw response를 반환하지 않는다.
