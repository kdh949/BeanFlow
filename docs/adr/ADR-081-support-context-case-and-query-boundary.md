# ADR-081: Support Context, Case 중심 privileged action과 query boundary

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

상담 Case와 교차 Context 조회·작업이 필요하지만 이를 Operations에 흡수하거나 Order 객체 그래프를 확장하면 수명·권한·소유권이 섞인다.

## Decision

Support Context는 `SupportCase`, 상담 이력과 대상 연결, 본인 확인·작업·승인 흐름과 처리 결과를 관리한다.
Operations는 저장된 권한 부여 내역, 조사, reconciliation과 LegalHold를 관리한다.

Support가 다른 모듈의 데이터를 읽거나 변경할 때는 공개 Application API와 ID를 사용한다.
해당 모듈의 Repository나 테이블에는 직접 접근하지 않는다. 검색·타임라인은 각 모듈이 제공하는
DTO와 범위가 제한된 조회를 조합해 구성한다.

저장 방식과 Projection, API별 필터·정렬·커서 구성은 각 구현 단계에서 담당 모듈의 모델에 맞춰 결정하고 검증한다.

## Alternatives Considered

- Operations 하위 기능: 권한/복구와 상담 생명주기가 결합되어 기각.
- customer API impersonation: actor/audit 의미가 손상되어 기각.
- write Aggregate 연관관계/Elasticsearch 선도입: 경계·운영비가 증명되지 않아 기각.
- S00에서 공통 Support cursor tuple 고정: endpoint별 DTO와 모델이 없어 기각.

## Rationale

Case 감사 가능성과 owner 불변식을 보존하면서 modular monolith의 공개 API 경계를 따른다.

## Consequences

새 모듈과 조회 API가 필요하고, 여러 모듈의 결과를 조합하는 비용도 측정해야 한다.
원본 데이터의 상태는 각 담당 모듈에서 관리하며 Support Console이 별도의 원본 상태를 만들지 않는다.

## Verification

Spring Modulith와 ArchUnit으로 모듈 의존성과 Controller의 Repository 직접 호출 여부를 검사한다.
API별로 검색·타임라인의 마스킹 규칙을 검증하고, 의존성 실패가 빈 목록으로 바뀌지 않는지 테스트한다.

## Metrics

Case·검색·타임라인의 응답시간과 오류, SQL 조회 횟수와 Projection 갱신 지연을 측정 대상으로 둔다.
개선 전후를 비교할 때는 같은 조건의 기준선과 실행 환경을 함께 기록한다.

## Revisit Conditions

독립 배포 요구, 구현된 query model의 측정된 한계 또는 fuzzy search 필요.

## Related Decisions

ADR-001, ADR-002, ADR-003, ADR-070.
