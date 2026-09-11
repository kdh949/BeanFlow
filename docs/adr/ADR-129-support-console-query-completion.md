# ADR-129: 상담 현황·주문 요약·통합 승인함의 조회 경계

- **Status:** Accepted
- **Date:** 2026-09-11

## Context

상담의 작성·승인·실행 화면과 종류별 요청 목록이 구현됐으나 담당자 현황, 연결 주문의 품목/금액과 통합 승인 진입점이 없다.

## Decision

현재 공용 ConsoleFrame과 업무별 상세를 유지하면서 필요한 조회만 추가한다. 상담 현황은 현재 actor에게 배정된 OPEN/IN_PROGRESS/WAITING만 진행 중으로 집계한다. 기존 상담 목록 권한을 넓히거나 좁히지 않고 optional 분류·우선순위·내 담당 필터를 추가한다.
연결 주문은 Support가 활성 링크와 SUPPORT_ORDER_READ를 확인한 뒤 Ordering 공개 DTO를 조회한다. 주문 당시 품목과 금액을 표시하되 주문 상태에서 현재 결제·환불 상태를 추정하지 않는다.
통합 승인함은 기존 권한 검증과 업무 상세를 사용한다. 조회 결과가 승인·실행 권한을 부여하지 않으며 실제 명령은 기존 상세에서 최신 상태와 분리 원칙을 재검증한다.
목록 실패를 빈 값으로 바꾸지 않고 개인 정보 원문을 추가 노출·보관하지 않는다.

## Alternatives Considered

별도 고객센터 셸과 중복 조회 API는 현행 업무/권한과 두 구현을 유지하게 되므로 기각한다. 화면에서 전체 데이터를 내려받아 집계하는 방식은 bounded 조회와 의미가 달라 기각한다.

## Rationale

기존 처리 경로를 유지하며 누락된 업무 진입점만 제공하고 데이터 소유 Context를 보존한다.

## Consequences

새 조회 계약과 검증이 필요하다. 집계·목록·명령은 서로 다른 관측 시점이며 단일 snapshot을 보장하지 않는다. 새 schema, production dependency와 write command는 없다.

## Verification

권한·actor·활성 링크·cursor·오류 계약, projection 금액/품목, Runtime parity, Modulith, UI 상태·a11y 및 실제 상세 링크.

## Metrics

기존 API error/correlation과 latency. 개인정보를 기록하지 않고 비교 측정 없이 성능을 주장하지 않는다.

## Revisit Conditions

조회량 측정이 현재 방식의 한계를 보이거나 새 업무가 기존 상세와 다른 승인 정책을 요구할 때.

## Related Decisions

ADR-081, ADR-082, ADR-084, ADR-090, ADR-128.
[ExecPlan](../exec-plans/active/support-console-query-completion.md).
