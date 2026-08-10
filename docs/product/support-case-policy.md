# SupportCase Policy

> **Status:** `DRAFT IMPLEMENTATION POLICY`; the lightweight hybrid Case boundary is Accepted in ADR-081, while exact
> states, requester/category vocabulary and reopen rules require S20 contract/model validation.

## Aggregate and history

`SupportCase`는 외부 상담번호(optional), requester type/reference, inquiry category, priority, assignee, subject links와 `OPEN | IN_PROGRESS | WAITING | RESOLVED | CLOSED` 상태를 소유한다. 대량 interaction, note, assignment/state history와 action은 별도 기록으로 두어 Case 로딩 크기를 제한한다.

## Invariants

1. CLOSED Case는 일반 작업을 만들 수 없고 explicit reopen과 Audit이 필요하다.
2. RESOLVED/CLOSED 전환은 활성 DataAccessGrant를 철회한다.
3. privileged action은 활성 담당자가 있어야 하며 다른 상담원에게 실행 권한이 자동 이전되지 않는다.
4. state와 assignment 변경은 append-only history를 남긴다.
5. note에는 password, OTP, token, PAN/CVC, 전체 계좌, 불필요한 주소를 저장하지 않는다.
6. subject link는 식별자만 보유하며 대상 Context의 소유권을 이전하지 않는다.
7. interaction과 제한형 EvidenceReference는 retention class를 명시한다.

## Requester types and categories

Requester: `CUSTOMER`, `STORE_OWNER`, `STORE_MEMBER`, `RIDER`, `THIRD_PARTY`, `INTERNAL_OPERATOR`, `SYSTEM`, `UNKNOWN`.

Category: order status/reschedule/cancellation, payment/refund, coupon/point, compensation, customer/store profile, delivery status/incident, settlement, dispute, account recovery, privacy, safety, other. `OTHER`는 구조화된 상세 사유가 필요하다.
