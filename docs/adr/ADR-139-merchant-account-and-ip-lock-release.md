# ADR-139: 점주 계정과 연결된 IP 제한의 원자 해제

- **Status:** Accepted
- **Date:** 2026-10-01
- **Supersedes:** [ADR-093](ADR-093-merchant-credential-lifecycle.md)의 조기 잠금 해제 범위
- **Implementation owner:** [점주 잠금 통합 해제](../exec-plans/completed/merchant-account-and-ip-lock-release.md)

## Context

점주 계정 잠금 해제는 계정과 LOGIN_ID 제한만 지운다. IP 제한이 남으면 운영자가 해제한 뒤에도
올바른 비밀번호로 429를 받는다. 현재 actor·scope HMAC별 집계는 ID와 IP의 연결을 복원할 수 없다.
운영자 IP, 모든 IP 또는 오래된 연결을 근거로 IP 제한을 지우면 대상과 무관한 제한을 해제한다.

## Decision

- 실패한 기존 점주 로그인과 올바른 비밀번호의 IP 차단 응답에서 ID·IP HMAC과 마지막 시도 시각을
  기록한다. actor는 MERCHANT, 참조 scope는 IP로 DB 제약하며 원문은 저장하지 않는다.
- 조기 해제는 계정, LOGIN_ID와 연결된 현재 IP 제한을 같은 Operations transaction에서 해제한다.
  연결 시각이 IP `window_start` 이상이고 해당 창 또는 차단 기한이 아직 유효한 행만 선택한다.
  공유 IP의 다른 점주 제한도 함께 해제되며 CUSTOMER actor와 무관한 IP는 유지한다.
- 같은 점주의 로그인 완료는 account row를 먼저 잠그고 IP·LOGIN_ID를 잠근다. 조기 해제도 account
  row 이후 선택한 IP를 HMAC 순으로 잠그므로 새로운 시도와 해제는 직렬화되고 잠금 순서가 역전되지 않는다.
  hash 검증은 transaction 밖에서 유지하고 snapshot 변경은 기존 인증 실패 응답으로 처리한다.
- 해제 count만 Audit afterSummary에 기록한다. 권한·사유·terminal idempotency를 유지하고 동일 key
  replay는 새로 발생한 제한을 다시 해제하지 않는다. Audit 실패는 모든 해제와 terminal 기록을 rollback한다.
- source 연결은 마지막 시도 24시간 뒤 bounded retention으로 삭제한다. IP 제한 삭제 시 연결도
  cascade 삭제한다. 저장·정리 실패는 기존 503 또는 재시도·metric·log로 드러나고 fallback하지 않는다.
- migration은 연결 table만 추가한다. 기존 연결은 backfill할 근거가 없어 만들지 않는다. 배포 후
  새 로그인 시도로 IP를 연결하거나 기존 차단 기한이 만료해야 한다.
- 정상 로그인, 비밀번호 초기화, 고객 인증, 계정 lifecycle과 credentialVersion 규칙은 유지한다.

## Alternatives Considered

- 모든 MERCHANT IP 초기화: 구현은 작지만 대상과 무관한 brute-force 제한을 제거한다.
- 운영자에게 IP 입력 요구: 계정과 실제 source IP를 확인하기 어렵고 기존 한 번의 해제 흐름을 바꾼다.
- 최근 IP 하나만 저장: 여러 장소에서 발생한 현재 제한을 복구하지 못한다.
- ID·IP별 차단으로 변경: 공유 IP 누적을 없애 기존 30회 방어 정책을 약화한다.

## Rationale

현재 제한 창에 관측된 연결만 사용하면 실제 로그인 장애를 한 번에 복구하면서 원문 저장과
전역 해제를 피할 수 있다. account row 직렬화는 추가 lock service나 production dependency 없이
새 로그인과 해제의 경계를 분명히 한다.

## Consequences

- NAT 등 공유 IP를 해제하면 다른 점주도 그 IP에서 다시 시도할 수 있다. 권한·사유·count Audit로 추적한다.
- 연결 table에 로그인 시 추가 write가 생긴다. 24시간 보존과 IP 삭제 cascade로 수명을 제한한다.
- 기존 차단은 자동 연결하지 않는다. 배포 후 한 번 로그인 시도하고 잠금 해제를 다시 실행할 수 있다.
- API request/204 응답 형식은 유지한다. OpenAPI description은 확장된 범위를 명시한다.

## Verification

해제 후 정상 로그인, 두 연결 IP·공유 IP, 고객·무관한 IP·이전 창 보존, 올바른 비밀번호의 차단
연결, legacy 연결 없음, Audit rollback, 같은 key replay, 동시 로그인/해제, bounded retention과
IP 삭제 cascade를 PostgreSQL HTTP 통합 테스트로 검증한다. 기존 고객·점주 인증 회귀와 구조 검증도 실행한다.

## Metrics

기존 해제 success/failure/replay metric과 Audit의 `releasedIpRestrictionCount`를 사용한다.
source 정리 건수·실패는 retention metric에 남기며 HMAC을 metric label로 사용하지 않는다.

## Revisit Conditions

공유 IP 해제로 반복 공격이 확인되거나 특정 IP만 선택할 복구 정책·검증된 채널이 필요할 때 재검토한다.

## Related Decisions

- [BR-35](../product/business-policy-decisions.md#br-35-고객점주-비밀번호와-로그인-제한)
- [ADR-093](ADR-093-merchant-credential-lifecycle.md)
- [ADR-094](ADR-094-browser-session-security.md)
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
