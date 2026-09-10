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
   전체 stack completion을 구분한다. 전체 완료는 여섯 기능 구현·검증 및 일곱 PR 및 exact ancestry 확인이며 merge/deploy와 다르다.
6. 재고 관리와 UI는 범위 밖이다. 신규 production dependency와 초기 DDL 재작성은 하지 않는다.

### 픽업 슬롯 authoring 경계

Fulfillment의 관리 application service가 Identity public port로 ACTIVE membership shared lock을 획득한 뒤
PickupSlot row를 잠근다. 기존 예약/변경은 같은 row lock과 version을 사용한다. Fulfillment의 명시적 module
의존성에 Identity API를 추가하며 owner 데이터를 Identity로 이동하지 않는다. Controller는 Fulfillment service만
호출한다. 새 슬롯과 시간 변경은 미래의 유효한 구간만 허용하며 시작한 슬롯은 변경하지 않는다. 정원 0은
예약이 없는 미래 슬롯을 닫는 값으로 허용한다. 과거 슬롯·예약 snapshot·예약/확정 count를 관리 DTO로 덮어쓰지 않는다.

### 수수료 계약 추가 경계

ADR-071과 V18의 immutable version을 유지한다. 미래 적용 구간만 추가하고 과거 계약의 종료일을 변경하지 않는다.
새 구간은 반개구간 `[effectiveFrom, effectiveTo)`이며 종료가 없으면 무한대로 해석한다. 겹치면 409다.
Store row의 배타 잠금으로 최종 주문 quote의 shared lock과 직렬화한다. 등록 revision은 해당 매장의 불변
계약 row 수이며 초기 데이터도 포함한다. 명시적 expectedRevision으로 동시 작성 충돌을 확인한다.
현재 구간의 강제 종료, 과거 적용과 주문 snapshot 재계산은 제공하지 않는다.

### 기존 계정의 소속 관리

Identity가 Operations public grant/Audit port와 Merchant Store 존재 확인 port를 호출한다. Operations에서
Identity API로 역의존성을 추가하지 않는다. 추가는 기존 MerchantAccount의 UUID를 대상으로 하고 기존
credential 상태를 변경하지 않는다. 비밀번호 미설정/만료 상태는 소속을 부여해도 매장 접근을 허용하지 않는다.
기존 소속은 expectedVersion을 가진 역할 변경·철회·재활성화만 허용한다. 계정과 소속을 잠근 뒤 변경하여
카탈로그·픽업·이의 철회의 membership shared lock과 직렬화한다. 요청 시작 시만 읽는 기존 조회 경로는
현재 요청을 마칠 수 있지만 철회 이후 새 접근은 거절한다. 마지막 OWNER 자동 승계/계정 삭제는 제공하지 않는다.

### 일반 Delivery와 publication의 명시적 재시도

HTTP는 MANUAL_REVIEW Case와 원본 상태를 검증하고 동일 원본의 추가 시도 한 번을 예약한다. 조회/재시도 grant는
알림과 publication별로 분리한다. Case expectedVersion과 알림 owner version을 확인하며 payload/provider key와
누적 시도 횟수는 보존한다. Notification은 원본의 attemptLimit을 현재 횟수+1로 늘리고 기존 worker에서 실행한다.
이벤트는 명시적 요청 원장의 baseline attempts와 일치하는 exact publication만 기존 registry의 claim lifecycle에
넘긴다. 원장 없는 Case는 자동 후보에서 계속 제외한다. registered transactional listener와 event type을 검증하며
예약 Analytics target와 unmapped target는 재시도를 거절한다.

Case 상태는 접수 때 RUNNING이다. 실제 성공/skip은 RESOLVED, 실제 재실패는 MANUAL_REVIEW로 돌아간다.
PROCESSING/RESUBMITTED처럼 결과 불명 상태는 임의로 성공 또는 실패 처리하지 않고 RUNNING으로 노출한다.
외부 실행 전 process가 종료되어도 DB 요청이 남고, 실행 횟수가 baseline보다 증가하면 추가 자동 재실행하지 않는다.
Notification result는 Case를 같은 트랜잭션으로 갱신하고 publication 결과는 bounded reconciliation으로 반영한다.
기존 완료 command와 달리 RUNNING publication 요청은 90일 cleanup에서 제외한다.

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

### 복구 PR 분할 (2026-09-10)

일반 복구 기능은 Notification Provider 호출과 Modulith publication 실행의 실패 모델이 달라 리뷰 범위를
알림 복구(V79)와 publication 복구(V80)의 두 PR로 나눈다. 여섯 기능의 전체 범위는 유지하며 총 일곱 PR이다.
Notification 소유 실행 변경과 공통 Case lifecycle port를 먼저 제공하고, 다음 PR에서 Ordering 실행 원장과
publication 후보 선택·결과 대사를 추가한다. 각 PR은 자체 Runtime API parity와 독립 마이그레이션을 검증한다.
