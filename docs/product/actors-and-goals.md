# Actors and Goals

| Actor | Goal | Main actions | Authorization boundary |
|---|---|---|---|
| Customer | 원하는 시간에 신뢰할 수 있는 주문과 픽업 | 매장 검색, 주문, 혜택 적용, 결제, 취소, 내역 조회 | 자신의 주문·포인트·결제수단만 접근 |
| Store Owner | 매장 설정과 거래·수익 통제 | 매장·메뉴·정책 관리, 정산 조회, 이의제기 | 소유·관리 매장만 접근 |
| Store Staff | 주문을 정확히 접수·제조·인도 | 수락·거절, 제조중, 준비완료, 수령완료 | 소속 매장 운영 기능만 접근 |
| Platform Operator | 실패 거래와 운영 예외 복구 | 조회, reconciliation, 재처리, 감사 확인 | 직접 금액 수정 대신 승인된 명령 사용 |
| Settlement Operator | 정산 검증과 이의제기 판정 | 배치 확인, 조정 승인·거절 | 사유·감사 로그 없이 확정 결과 변경 금지 |

## Authorization principles

- 인증은 사용자가 누구인지 확인한다.
- 역할 권한은 어떤 종류의 기능에 진입 가능한지 확인한다.
- 객체 수준 인가는 해당 사용자가 특정 매장·주문·정산 항목에 접근할 수 있는지 확인한다.
- `STORE_OWNER` 역할만으로 모든 매장의 정산을 조회할 수 없다.
- 운영자도 Aggregate 상태를 우회하여 DB 값을 직접 수정하지 않는다.
