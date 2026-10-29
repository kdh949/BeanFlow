# BeanFlow 시스템 아키텍처

```mermaid
flowchart LR
    n_web["Web Client<br/>React · TypeScript"]:::client
    n_waf["Sophos WAF<br/>HTTPS 진입"]:::edge
    n_nginx["Nginx<br/>API · Auth 프록시"]:::edge
    n_api["BeanFlow API<br/>Spring Modulith<br/>Kotlin · Spring Boot"]:::app
    n_db[("PostgreSQL · PostGIS<br/>주문 · 매장 · 결제 데이터")]:::data
    n_pg["PG<br/>Sandbox 결제"]:::external
    n_minio["MinIO / AIStor<br/>이미지 저장"]:::external
    n_vault["Vault Transit<br/>개인정보 암호화"]:::external
    n_keycloak["Keycloak<br/>운영자 OIDC"]:::external

    n_web -->|"HTTPS"| n_waf
    n_waf -->|"HTTP"| n_nginx
    n_nginx -->|"/api"| n_api
    n_nginx -->|"허용된 /auth 경로"| n_keycloak
    n_api -->|"JDBC"| n_db
    n_api -->|"승인 · 조회<br/>· 취소"| n_pg
    n_api -->|"S3 API"| n_minio
    n_api -->|"로컬 Proxy 경유"| n_vault
    n_api -->|"JWK 조회"| n_keycloak

    classDef client fill:#eff6ff,stroke:#60a5fa,color:#17233b
    classDef edge fill:#f8fafc,stroke:#94a3b8,color:#17233b
    classDef app fill:#f0fdfa,stroke:#2bbbad,color:#17233b
    classDef data fill:#fff7ed,stroke:#fdba74,color:#17233b
    classDef external fill:#f5f3ff,stroke:#bda4ee,color:#17233b
    classDef decision fill:#f0fdfa,stroke:#2bbbad,color:#17233b
```

고객·점주·운영 화면의 요청은 WAF와 Nginx를 거쳐 BeanFlow API로 전달됩니다. 거래·위치 정보는 PostgreSQL·PostGIS에, 이미지는 객체 저장소에 보관합니다. 고객·점주는 세션으로 로그인하고 운영자는 Keycloak을 사용합니다.

## 기능별 흐름

- [결제 승인과 결과 조회](payment.md)
- [주문 접수부터 픽업·정산까지](order.md)
- [주문 취소·환불과 정산 조정](refund.md)

## 관련 문서

- [모듈 구성과 데이터 책임](architecture-overview.md)
- [주요 설계 결정](decision-summary.md)

[README](../../README.md#아키텍처)
