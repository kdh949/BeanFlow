# 주문 접수부터 픽업·정산까지

```mermaid
flowchart LR
    n_paid["결제 완료<br/>PAID"]:::client
    n_accept["매장 접수<br/>ACCEPTED"]:::app
    n_prepare["제조<br/>PREPARING"]:::app
    n_ready["픽업 준비<br/>READY"]:::app
    n_done["픽업 완료<br/>COMPLETED"]:::app
    n_event["주문 완료 이벤트"]:::app
    n_point[("포인트 적립")]:::data
    n_settle[("정산 항목 생성")]:::data

    n_paid --> n_accept
    n_accept --> n_prepare
    n_prepare --> n_ready
    n_ready --> n_done
    n_done -->|"커밋 후"| n_event
    n_event --> n_point
    n_event --> n_settle

    classDef client fill:#eff6ff,stroke:#60a5fa,color:#17233b
    classDef edge fill:#f8fafc,stroke:#94a3b8,color:#17233b
    classDef app fill:#f0fdfa,stroke:#2bbbad,color:#17233b
    classDef data fill:#fff7ed,stroke:#fdba74,color:#17233b
    classDef external fill:#f5f3ff,stroke:#bda4ee,color:#17233b
    classDef decision fill:#f0fdfa,stroke:#2bbbad,color:#17233b
```

매장이 결제된 주문을 접수하면 제조·준비 완료·픽업 순서로 진행합니다. 주문 완료 이벤트는 포인트 적립과 정산 항목 생성으로 연결됩니다.

## 관련 문서

- [주문 가격 스냅샷 · ADR-004](../adr/ADR-004-order-price-snapshot.md)
- [정산 조정 내역 · ADR-008](../adr/ADR-008-settlement-adjustment-ledger.md)

[시스템 구조](overview.md) · [README](../../README.md#아키텍처)
