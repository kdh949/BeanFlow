# 결제 승인과 결과 조회

```mermaid
flowchart LR
    n_request["결제 승인 요청"]:::client
    n_tx1[("Tx1<br/>요청·멱등성 정보 저장")]:::data
    n_pg["PG 승인 API 호출"]:::external
    n_result{"승인 결과"}:::decision
    n_tx2[("Tx2<br/>주문·결제·혜택 확정")]:::data
    n_unknown["UNKNOWN<br/>PG 상태 조회"]:::app
    n_review["5회 조회 후 미확정<br/>MANUAL_REVIEW"]:::app
    n_failed["승인 거절<br/>실패 결과 저장"]:::app

    n_request --> n_tx1
    n_tx1 -->|"커밋 후 호출"| n_pg
    n_pg --> n_result
    n_result -->|"승인"| n_tx2
    n_result -->|"응답 유실"| n_unknown
    n_result -->|"거절"| n_failed
    n_unknown -->|"승인 확인"| n_tx2
    n_unknown -->|"결과 미확정"| n_review

    classDef client fill:#eff6ff,stroke:#60a5fa,color:#17233b
    classDef edge fill:#f8fafc,stroke:#94a3b8,color:#17233b
    classDef app fill:#f0fdfa,stroke:#2bbbad,color:#17233b
    classDef data fill:#fff7ed,stroke:#fdba74,color:#17233b
    classDef external fill:#f5f3ff,stroke:#bda4ee,color:#17233b
    classDef decision fill:#f0fdfa,stroke:#2bbbad,color:#17233b
```

결제 요청을 먼저 저장하고 PG를 호출한 뒤 승인 결과를 별도 트랜잭션에 반영합니다. 응답이 끊기면 `UNKNOWN`으로 기록하고 PG에서 승인 상태를 조회합니다.

## 관련 문서

- [PG 호출 전후 트랜잭션 분리 · ADR-006](../adr/ADR-006-external-payment-transaction-boundary.md)
- [멱등성과 승인 결과 조회 · ADR-007](../adr/ADR-007-payment-idempotency-reconciliation.md)

[시스템 구조](overview.md) · [README](../../README.md#아키텍처)
