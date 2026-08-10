# Support Profile Change Policy

> **Status:** R0-R4 classification, R3 approval/actor separation and purpose-specific owner commands are Accepted in
> ADR-087; exact field mapping and endpoint DTOs remain DRAFT until owner models exist.

## Field risk classes

- R0: ID, 원장 합계, 확정 정산, verification/audit/approval/policy version. 직접 수정 금지; adjustment/reconciliation만.
- R1: 표시 이름, 알림 선호, 공개 매장 전화·소개·픽업 안내. BASIC+permission+Audit 후보.
- R2: 실명 오탈자, 보조 연락처, 운영 연락, 일반 배송지. ENHANCED+Specialist; 인증·정산·소유권 영향 시 R3.
- R3: 로그인/복구 채널, 계정 복구·소유권, 대표자·사업자·계약·정산 계좌, 라이더 식별/지급. ENHANCED, Support Manager와 Operations 순차 승인, 세 actor 분리, agent execution return.
- R4: password, MFA secret, OTP, token, PG raw token, PAN/CVC, key. 조회·직접 수정 금지; reset/re-registration만.

범용 profile PATCH를 제공하지 않고 목적별 typed workflow를 사용한다. 고위험 변경 후 가능한 경우 기존·신규 채널에 모두 알리며, notification 실패는 변경을 rollback하지 않고 retry/warning으로 남긴다.
