# 주문 취소·환불과 정산 조정

```mermaid
flowchart LR
    n_cancel["매장 거절 / 고객 취소"]:::client
    n_record[("주문 종료·후속 작업 저장")]:::data
    n_refund["PG 취소·환불"]:::external
    n_benefit["쿠폰·포인트 반환"]:::app
    n_notify["고객 알림"]:::app
    n_retry["실패 작업 재실행"]:::app
    n_settled{"확정 정산이 있는 환불"}:::decision
    n_adjust[("정산 조정 기록<br/>다음 정산에 상계")]:::data

    n_cancel --> n_record
    n_record --> n_refund
    n_record --> n_benefit
    n_record --> n_notify
    n_refund -->|"실패 / 결과 미확정"| n_retry
    n_benefit -->|"실패"| n_retry
    n_notify -->|"실패"| n_retry
    n_retry -->|"작업별 재처리"| n_record
    n_refund -->|"환불 확정"| n_settled
    n_settled -->|"정산 조정 대상"| n_adjust

    classDef client fill:#eff6ff,stroke:#60a5fa,color:#17233b
    classDef edge fill:#f8fafc,stroke:#94a3b8,color:#17233b
    classDef app fill:#f0fdfa,stroke:#2bbbad,color:#17233b
    classDef data fill:#fff7ed,stroke:#fdba74,color:#17233b
    classDef external fill:#f5f3ff,stroke:#bda4ee,color:#17233b
    classDef decision fill:#f0fdfa,stroke:#2bbbad,color:#17233b
```

매장 거절이나 고객 취소 후 환불·쿠폰·포인트 반환 작업의 처리 상태를 저장합니다. 실패한 작업은 다시 실행하고, 확정 정산에 반영할 변경은 조정 내역으로 남깁니다.

## 관련 문서

- [주문·결제·환불의 실패 처리](failure-semantics.md)
- [정산 조정 내역 · ADR-008](../adr/ADR-008-settlement-adjustment-ledger.md)

[시스템 구조](overview.md) · [README](../../README.md#아키텍처)
