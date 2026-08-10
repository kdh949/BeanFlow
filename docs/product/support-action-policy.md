# Support ActionPolicy

> **Status:** Core ALLOWED/APPROVAL_REQUIRED/DENIED and exact approval binding are Accepted in ADR-084; class/type names
> and typed Kotlin policy structure are `DRAFT IMPLEMENTATION DETAILS`.

## Decision

모든 privileged action은 서버가 `ALLOWED | APPROVAL_REQUIRED | DENIED` 중 하나로 결정한다. 정의되지 않은 조합은 `DENIED`다. 입력은 operator role와 persistent grant, Case/assignment, requester-subject relation, verification, action과 canonical payload, target state/version, 금액·최근 이력, policy version과 시각이다.

결과는 reason codes, required verification/permissions, approval plan, monetary/field limits, policy version과 expiry를 포함한다. UI evaluation은 안내용이며 실행은 최신 row/version과 payload로 다시 평가한다.

## Request and approval binding

SupportActionRequest revision은 action, target, canonical payload hash, verification session, policy version, aggregate version, amount, reason, evidence digest와 expiry에 묶인다. 값이 바뀌면 승인과 decision은 `STALE`이며 새 revision이 필요하다. 모든 변경 명령은 `Idempotency-Key`를 요구하고 같은 key+다른 payload는 409다.

범용 DB JSON/SpEL/Drools DSL은 도입하지 않고 typed Kotlin policy를 계획한다. Owner Context가 최종 불변식을 재검증하며 승인도 owner 거부를 우회하지 않는다.
