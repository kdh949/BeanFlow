# ADR-125: publication 결과 불명 조사와 시도별 복구

- **Status:** Accepted
- **Date:** 2026-09-10
- **Implementation owner:** [관리 API 리뷰 수정](../exec-plans/completed/management-api-review-remediation.md)

## Context

ADR-124는 실행 결과 불명을 RUNNING으로 보존하지만 claim 뒤 프로세스가 종료되면 운영자가
다시 복구할 수 없다. 후보 조회만의 예산 검사는 오래된 후보를 읽은 다른 워커를 막지 못한다.
Modulith 2.1 JPA의 NULL 상태 비교와 publication ID만 사용하는 결과 갱신도 별도 보완이 필요하다.

## Decision

이 결정은 ADR-124의 publication 결과 불명 RUNNING 영구 유지와 ADR-010의 수동 복구 claim/result
무조건 위임 부분을 대체한다. 그 밖의 관리 API, 자동 재시도 스케줄과 event 계약은 유지한다.

1. 후보의 publication ID, 수동 request ID와 baseline attempts를 실제 claim까지 전달한다.
   publication row lock 아래 동일 요청 RUNNING, Case RUNNING, baseline 일치를 재검증한다.
   NULL/FAILED를 명시적으로 처리하고 추가 claim 한 번의 시도 횟수와 claim 시각을 함께 commit한다.
2. 실행 시작은 startedAt, dispatch 대기는 claimedAt을 사용한다. 마지막 실제 claim 뒤 초기 5분을
   넘기면 MANUAL_REVIEW와 EXECUTION_OUTCOME_UNKNOWN으로 조사하며 원본 상태는 보존한다.
   5분은 실측 SLA가 아닌 운영 감지 초기값이다. DB 중심 listener와 수 초 단위 HTTP timeout 설정을
   확인했지만 전체 처리 상한은 보장하지 않는다. 설정은 양수여야 하며 시간 경과 자체는 재실행 근거가 아니다.
3. 결과 불명 재실행은 exact event type/listener allowlist로 제한한다. 초기 검증 대상은 OrderReadyV1의
   고객 알림 접수 listener다. 하나의 owner transaction과 logical source unique constraint로
   Inbox/Delivery 한 벌을 보존하며 Provider 발송은 별도 worker다. 실제 동시 호출/commit 후 ACK 유실
   테스트가 통과해야 허용한다. 나머지 등록 listener는 확정 실패 복구만 제공하며 불명 재실행은 거절한다.
4. 수동 요청 원장에 Case version, claim/start 시각, 시도 결과와 불명 전환 시각을 저장한다.
   전용 registry decorator는 기존 Modulith dispatch를 유지하면서 실제 event 객체와 request ID를 연결해
   callback에 전달한다. 이 메모리는 호출 식별자 전달용이며 상태/예산의 원천 또는 DB 장애 fallback이 아니다.
5. 결과는 publication lock 아래 자신의 request에 기록한다. 현재 publication attempts가 해당 시도의
   ordinal과 같은 경우에만 기존 repository의 결과 갱신을 호출한다. 오래된 성공/실패는 자기 요청에만
   남고 새 시도의 publication과 Case를 덮지 않는다. Case 전이도 저장된 Case version으로 보호한다.
6. UNKNOWN 요청도 결과 대사에 계속 포함한다. 실제 listener 완료를 확인하거나 검증된 동일 source replay로
   수렴하며 Order/Refund terminal만으로 publication을 성공 처리하지 않는다. UNKNOWN은 90일 cleanup에서 제외한다.
7. 감사 실패는 Case/요청 전이를 rollback한다. API는 현재 권한, 사유, expected Case version, 멱등 키를 유지한다.
   저장된 접수 응답 replay는 상태 변경이나 추가 실행 예산을 만들지 않는다.

## Alternatives Considered

시간 초과 후 FAILED 강제 변경이나 전체 listener 재실행은 중복 부수효과를 만들 수 있다.
새 broker/범용 workflow 대신 기존 adapter와 Case/result worker를 확장한다.
원본을 복제한 새 publication은 원본 identity와 실행 이력을 갈라놓으므로 만들지 않는다.

## Rationale

실행 중단과 단순 지연을 구분할 수 없을 때는 불확실성을 보존하고 동시 실행 안전성이 확인된 대상만 재개한다.
요청 ID/ordinal로 늦은 결과를 구분해야 재시도 예산과 운영 상태가 함께 신뢰할 수 있다.

## Consequences

V81이 원장 metadata를 추가한다. legacy 실행의 시작 시각을 복원할 수 없으면 결과 불명으로 조사한다.
불명 재실행 허용은 listener별 검증을 거쳐 확장해야 한다. 이는 일반 FAILED 복구와 별도 capability다.
실제 알림 발송이나 환불 성공은 publication 완료와 동일하지 않으며 각 owner 상태를 확인한다.

V81 전후 writer의 혼합 실행은 지원하지 않는다. 기존 버전은 새 원장 필드를 쓰거나 시도별 callback를
보호하지 못하므로 배포 시 이전 애플리케이션 프로세스를 종료한 뒤 migration과 새 버전을 시작한다.
종료된 실행의 업무 결과는 추정하지 않고 backfill 시각과 UNKNOWN 조사 정책으로 확인한다.

## Verification

PostgreSQL stale candidate 경쟁, NULL claim, 실제 listener/registry 경로, claim 뒤 중단,
commit 뒤 ACK 유실, 겹친 실행의 늦은 성공/실패, 미검증 target 차단, 감사 rollback,
unknown retention과 API 계약/Modulith/전체 CI를 검증한다.

## Metrics

Case 조회와 감사에 불명 사유, request ID와 시각을 남긴다. 원본 payload는 공개하지 않는다.
기존 미완료/수동 검토 backlog 지표를 유지하며 실측 없이 처리시간/복구시간 개선을 주장하지 않는다.

## Revisit Conditions

새 listener의 불명 재실행 허용, 외부 Provider replay contract 변경, 처리시간 실측 또는 Modulith SPI 변경 시 재검토한다.

## Related Decisions

- [ADR-010](ADR-010-initial-event-publication.md)
- [ADR-124](ADR-124-management-api-vertical-slices.md)
- [BR-54](../product/business-policy-decisions.md)
