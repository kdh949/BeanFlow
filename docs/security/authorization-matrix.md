# Authorization Matrix

| Resource / Action | Customer | Store Owner | Store Staff | Platform Operator |
|---|---:|---:|---:|---:|
| 내 주문 생성·조회 | Own | No | No | Read for support |
| 고객 주문 취소 | Own and allowed state | No | No | Approved operation |
| 매장 메뉴 조회 | Yes | Yes | Yes | Yes |
| 매장 메뉴 변경 | No | Owned store | Assigned store if permitted | Controlled |
| 주문 수락·제조 상태 | No | Owned store | Assigned store | Support only |
| 내 포인트 조회 | Own | No | No | Read with reason |
| 부분 환불 | No | Owned store with policy | Permission required | Approved operation |
| 매장 정산 조회 | No | Owned store | No by default | Yes |
| 이의제기 생성 | No | Owned store | No | No |
| 이의제기 판정 | No | No | No | Settlement permission |
| 재처리 | No | No | No | Explicit permission + reason |
| 권한 변경 | No | Limited | No | Audited |

## Enforcement layers

- Security FilterChain: 인증 객체 구성
- Method Security: 역할 기반 진입점
- Application Service: 객체 소유권·매장 membership
- Aggregate: 상태와 비즈니스 권한에 독립적인 불변식
- Audit: 금액·권한·수동 재처리

인가 실패를 리소스가 없다는 것과 혼동하지 않도록 API 노출 정책을 별도로 정한다.
