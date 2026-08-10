# ADR-091: migration queue priority를 direct dependency로 표현

- **Status:** Rejected
- **Date:** 2026-08-10

## Context

ADR-072는 migration writer를 repository-wide 직렬화하지만 queue priority를 `Depends-On`에 넣지 말라고
한다. 초안은 `ACTIVE + Implementation-Ready=true + Writes-Migration=true`인 Analytics ExecPlan을 실제
lease holder로 오인하고 S10의 `Depends-On`에 넣었다. Analytics plan의 `Migration lane released` 문구는
그 plan이 시작할 수 있다는 뜻이지 branch/worktree/PR/task와 명시적 획득 기록이 있다는 뜻이 아니다.

## Decision

이 ADR이 제안한 queue dependency를 채택하지 않는다. `Depends-On`은 current phase가 직접 소비하는
completed plan output만 기록한다. S10은 Analytics schema/event/projection output을 소비하지 않으므로
Analytics plan을 dependency로 두지 않는다.

어느 ready migration plan을 먼저 실행할지는 기술 dependency가 아닌 scheduling decision이다. 선택된
plan만 latest main에서 전용 branch/worktree/task를 만든 뒤 repository inventory를 재확인하고 명시적으로
lease를 획득한다. 실제 holder 증거는 그 실행 identity와 획득 시각/기준 main/마지막 Flyway 번호를 plan
Progress 또는 release evidence에 기록한 것이다. 단순 active metadata, ready flag, 빈 PR 목록이나 lane
released 문구는 lease 증거가 아니다. 별도 queue metadata가 실제 자동화 요구로 생기면 ADR-072를
개정하는 새 Proposed ADR로 다루며 `Depends-On`을 재사용하지 않는다.

## Alternatives Considered

- active/ready metadata를 곧 lease로 간주: 실행 identity와 획득 기록이 없어 거짓 positive다.
- queue priority를 `Depends-On`으로 표현: direct-input graph를 오염시키고 completion path를 가짜로 만든다.
- 지금 canonical queue metadata 추가: 실제 executor 요구와 검증 설계가 없어 불필요하다.

## Rationale

Accepted ADR-072의 direct-input 의미와 실행 시점 lease preflight를 그대로 유지한다.

## Consequences

S10과 Analytics 중 제품 우선순위는 별도 scheduling 선택으로 남는다. 두 plan이 동시에 lease를 얻을 수
없다는 제약은 유지되지만, 어느 plan도 상대의 output을 소비하지 않는 한 dependency edge는 없다.

## Verification

ExecPlan graph에 queue-only dependency가 없는지, 실행 시작 시 explicit lease evidence가 기록되는지,
simultaneous migration writer가 거부되는지 검증한다.

## Metrics

Queue age와 migration-writer lease duration; delivery 성능 주장은 하지 않는다.

## Revisit Conditions

실제 executor가 durable queue ordering을 요구할 때 새 ADR을 제안한다.

## Related Decisions

ADR-072.
