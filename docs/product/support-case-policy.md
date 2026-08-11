# SupportCase Policy

> **Status:** `ACCEPTED INITIAL POLICY` (2026-08-11). The lightweight hybrid Case boundary is Accepted in ADR-081.
> This policy fixes the initial S20 persisted/API vocabulary and transition matrix; reopen is deliberately outside S20.

## Aggregate and history

`SupportCase`는 외부 상담번호(optional), requester type/reference, inquiry category, priority, assignee, subject links와 `OPEN | IN_PROGRESS | WAITING | RESOLVED | CLOSED` 상태를 소유한다. 대량 interaction, note, assignment/state history와 action은 별도 기록으로 두어 Case 로딩 크기를 제한한다.

## Initial state transition contract

The Aggregate alone may perform these transitions:

```text
OPEN        -> IN_PROGRESS
IN_PROGRESS -> WAITING | RESOLVED
WAITING     -> IN_PROGRESS
RESOLVED    -> CLOSED
```

`CLOSED` is terminal in S20. There is no reopen command or hidden direct-state update. A later reopen capability requires
an explicit product decision covering authorization and Audit before it can add a new transition or endpoint.

## Invariants

1. CLOSED Case는 assignment, interaction, note, subject link와 privileged action을 만들거나 변경할 수 없다.
2. S20에는 `DataAccessGrant` Aggregate나 활성 Grant가 존재하지 않는다. S40이 Grant를 도입할 때
   `RESOLVED` 또는 `CLOSED` Case의 활성 Grant를 같은 Case 경계에서 fail-closed로 철회하고, terminal Case에는
   Grant 활성화·reveal을 허용하지 않는다.
3. privileged action은 활성 담당자가 있어야 하며 다른 상담원에게 실행 권한이 자동 이전되지 않는다.
4. state와 assignment 변경은 append-only history를 남긴다.
5. note에는 password, OTP, token, PAN/CVC, 전체 계좌, 불필요한 주소를 저장하지 않는다.
6. subject link는 식별자만 보유하며 대상 Context의 소유권을 이전하지 않는다.
7. interaction과 제한형 EvidenceReference는 retention class를 명시한다.

## Requester types and categories

Requester: `CUSTOMER`, `STORE_OWNER`, `STORE_MEMBER`, `RIDER`, `THIRD_PARTY`, `INTERNAL_OPERATOR`, `SYSTEM`, `UNKNOWN`.

Category: `ORDER_STATUS`, `PICKUP_RESCHEDULE`, `ORDER_CANCELLATION`, `PAYMENT_OR_REFUND`, `COUPON_OR_POINT`,
`COMPENSATION`, `CUSTOMER_PROFILE`, `STORE_PROFILE`, `DELIVERY_STATUS`, `DELIVERY_INCIDENT`, `SETTLEMENT`, `DISPUTE`,
`ACCOUNT_RECOVERY`, `PRIVACY`, `SAFETY`, `OTHER`. `OTHER`는 구조화된 상세 사유가 필요하다.

## Decision record

- **Decision:** initial S20 Case state vocabulary, transition matrix, requester/category closed vocabulary and no-reopen
  scope are accepted. `DataAccessGrant` 종료 철회는 S40 도입 의무로 보존하며 S20 범위에는 포함하지 않는다.
- **Rationale:** Case persistence constraints, endpoint-specific API schemas and append-only state history need one
  unambiguous contract. Reopen needs its own authorization and Audit design, so S20 does not expose it.
- **Revisit condition:** a product requirement for reopen is approved with its eligible actor, reason, Audit category and
  object-level authorization rule, or S40's detailed Grant plan selects the atomic revocation persistence/transaction
  design.
