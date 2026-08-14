# Actors and Goals

| Actor | Goal | Main actions | Authorization boundary |
|---|---|---|---|
| Customer | 원하는 시간에 신뢰할 수 있는 주문과 픽업 | 매장 검색, 주문, 혜택 적용, 결제, 취소, 내역 조회 | 자신의 주문·포인트·결제수단만 접근 |
| Store Owner | 매장 설정과 거래·수익 통제 | 매장·메뉴·정책 관리, 정산 조회, 이의제기 | 소유·관리 매장만 접근 |
| Store Staff | 주문을 정확히 접수·제조·인도 | 수락·거절, 제조중, 준비완료, 수령완료 | 소속 매장 운영 기능만 접근 |
| Platform Operator | 실패 거래와 운영 예외 복구 | 조회, reconciliation, 재처리, 감사 확인 | 직접 금액 수정 대신 승인된 명령 사용 |
| Settlement Operator | 정산 검증과 이의제기 판정 | 배치 확인, 조정 승인·거절 | 사유·감사 로그 없이 확정 결과 변경 금지 |

## Accounts and authentication

각 Actor가 어떤 계정으로 어떻게 인증하는지는 [ADR-092](../adr/ADR-092-hybrid-authentication.md)를 따른다.

| Actor | 계정 소유 | 인증 방식 | 계정 생성 |
|---|---|---|---|
| Customer | BeanFlow | 아이디·비밀번호 → Session Cookie | 자체 가입 |
| Store Owner | BeanFlow | 아이디·비밀번호 → Session Cookie | P0 운영 콘솔 발급·최초 membership 원자 생성, 최초 비밀번호 강제 변경 |
| Store Staff | BeanFlow | 아이디·비밀번호 → Session Cookie | P0 운영 콘솔 발급·최초 membership 원자 생성, 점주 초대·권한 편집은 P1 |
| Platform Operator | 외부 IdP | Keycloak OIDC/JWT | 조직 IdP |
| Settlement Operator | 외부 IdP | Keycloak OIDC/JWT | 조직 IdP |

- 고객 인증은 P0에서 이메일·전화번호가 아닌 사용자명 ID와 비밀번호를 사용한다. 사용자명 정규화와
  중복·로그인 실패 응답은 [BR-34](business-policy-decisions.md)를 따른다. 휴대전화 OTP는 P1이며
  실제 SMS Provider 계약이 확정된 뒤에 도입한다([C-1](design-contract-conflicts.md)).
- 점주 계정은 `INITIAL_PASSWORD` 상태에서 비밀번호 변경 외 모든 매장 기능이 차단된다
  ([ADR-093](../adr/ADR-093-merchant-credential-lifecycle.md)).
- 운영자는 점주 계정과 최초 `OWNER | STAFF` membership을 함께 발급한다. 임시 비밀번호는 성공
  응답에서 한 번만 보이고 이후에는 조회가 아니라 초기화로 다시 발급한다([BR-46](business-policy-decisions.md)).
- 인증 방식과 무관하게 Application 계층은 `CurrentActor`만 사용한다
  ([ADR-095](../adr/ADR-095-unified-current-actor.md)).

## Identifiers each actor uses

| Actor | 1차 식별 수단 | 사용하지 않는 것 |
|---|---|---|
| Customer | 주문번호(`BF-XXXX-XXXX`), 픽업번호 | 주문 UUID 입력 |
| Store Owner / Staff | 픽업번호, 주문번호, 상태, 픽업 예정 시각 | 주문 UUID 입력 |
| Platform Operator | 주문번호, 마스킹 전화번호, Correlation ID | UUID를 첫 검색 수단으로 사용 |

내부 UUID는 계속 존재하지만 사람의 입력 수단이 아니다
([ADR-096](../adr/ADR-096-public-order-reference.md), [ADR-097](../adr/ADR-097-store-pickup-number.md)).

## Authorization principles

- 인증은 사용자가 누구인지 확인한다.
- 역할 권한은 어떤 종류의 기능에 진입 가능한지 확인한다.
- 객체 수준 인가는 해당 사용자가 특정 매장·주문·정산 항목에 접근할 수 있는지 확인한다.
- `STORE_OWNER` 역할만으로 모든 매장의 정산을 조회할 수 없다.
- 운영자도 Aggregate 상태를 우회하여 DB 값을 직접 수정하지 않는다.
