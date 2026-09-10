# ADR-124: 관리 API의 소유권과 순차 수직 슬라이스

- **Status:** Accepted
- **Date:** 2026-09-10
- **Implementation owner:** [관리 API 실행 계획](../exec-plans/active/management-api-vertical-slices.md)

## Context

내부 모델과 함수만 있는 여섯 기능을 HTTP로 제공하려면 권한·감사·동시성·실패 복구를 함께 완성해야 한다.
승인 Adjustment는 별도 transaction에서 먼저 확정되므로 이의 판정 실패 후 반대 판정을 허용할 수 없다.
선행 카탈로그 PR #150은 재고 제거와 V73까지 schema 변경을 포함한다.

## Decision

1. BR-54의 권한 분리를 따른다. Controller는 application service만 호출하며 Context 간 호출은 public port를 쓴다.
2. 판정 command는 의도와 원래 actor/reason/time을 먼저 commit한 뒤 동일 source의 Adjustment 및 판정을 재개한다.
   의도 저장 후 실패하면 UNDER_REVIEW와 pending intent를 노출하며 상충 판정은 409다.
3. 모든 쓰기는 명시적 멱등 키와 사유를 받고 최초 response를 저장한다. 현재 권한을 검증한 뒤 replay한다.
   business state·response·Audit는 같은 owner transaction에 저장한다. 외부 실행은 요청 transaction 밖이다.
4. 사용자가 승인한 수동 stack은 #150 head `8e77826a7e85635988752306c807af9f84ec8d69`에서 시작한다.
   순서는 이의 판정 → 매장 식별 정보 → 픽업 슬롯 → 수수료 계약 → 소속 → 일반 복구다.
   첫 predecessor branch는 `feature/merchant-menu-catalog-lifecycle`이고 이후 각 child는 직전 feature head다.
   원본 dirty checkout은 보존하고 한 isolated worktree에서 한 writer만 V74부터 순차 DDL을 작성한다.
   ADR-072의 독립 writer 병렬 실행을 허용하지 않는다. 선행 branch 변경은 자동 합치지 않고 다시 검증한다.
5. 각 PR은 직전 branch를 base로 하고 증분 테스트·계약·문서를 포함한다. Draft 동안에도 개별 slice의 검증과
   전체 stack completion을 구분한다. 전체 완료는 여섯 구현·검증·PR 및 exact ancestry 확인이며 merge/deploy와 다르다.
6. 재고 관리와 UI는 범위 밖이다. 신규 production dependency와 초기 DDL 재작성은 하지 않는다.

## Alternatives Considered

단순 Controller wrapper는 권한·감사 actor·부분 commit 실패를 해결하지 못한다. 독립 sibling migration은
선행 schema와 충돌한다. 모든 관리 기능을 하나의 CRUD PR로 묶는 대신 typed 기능별 stack을 선택한다.

## Consequences

목적별 command와 replay 저장소가 추가된다. 권한 철회 후 재개는 현재 권한이 있는 운영자에게만 허용된다.
복구 접수는 완료로 표시하지 않으며 운영자가 실제 owner 상태를 확인해야 한다.

## Verification

PostgreSQL commit/rollback·replay·동시성·권한·Audit 장애, API parity, 인증 registry, Modulith와 문서 검증을 수행한다.
실행 결과는 실행 계획과 개별 PR에 기록하며 미실행을 통과로 표시하지 않는다.

## Revisit Conditions

별도 승인 workflow, 분산 배포, writer 병렬화 또는 판정 비용 분담 정책 변경이 필요할 때 재검토한다.

## Related Decisions

- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
- [Business Policy](../product/business-policy-decisions.md)
