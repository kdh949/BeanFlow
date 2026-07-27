# BeanFlow ExecPlan Standard

ExecPlan은 복잡한 Feature 또는 시스템 변경을 다른 대화나 기억 없이 구현할 수 있게 하는 실행 가능한 설계 문서다.

## 사용 조건

다음 중 하나에 해당하면 ExecPlan을 작성하거나 기존 계획을 갱신한다.

- 둘 이상의 Bounded Context를 변경
- DB 스키마 또는 데이터 마이그레이션 변경
- 외부 Provider 연동
- 멱등성, 동시성, 재시도 또는 reconciliation 도입
- 공개 API 또는 이벤트 계약 변경
- 중요한 성능 최적화
- 여러 단계에 걸친 리팩터링
- 구현과 검증이 한 세션을 넘을 가능성이 큼

## 비협상 요구사항

- ExecPlan은 현재 파일만 읽어도 작업을 재개할 수 있을 만큼 self-contained해야 한다.
- 문서는 작업 진행, 발견, 결정에 맞춰 계속 갱신하는 living document다.
- 목적은 코드 파일 생성이 아니라 관찰 가능한 동작의 완성이다.
- 제품 용어와 기술 용어를 처음 등장할 때 정의한다.
- 측정하지 않은 결과를 쓰지 않는다.
- 질문으로 결정된 중요 사항은 Decision Log와 ADR 또는 Business Policy에 함께 반영한다.
- 실패 경로와 운영 복구를 정상 경로와 같은 수준으로 작성한다.

## 필수 구조

```markdown
# <행동 중심 제목>

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

## Current State

## Definitions

## Scope

### In Scope
### Non-goals

## Business Rules and Invariants

## Architecture and Transaction Boundaries

## Alternatives Considered

## Failure Semantics

## Data and Migration

## API and Event Contracts

## Milestones

## Required Tests

## Validation Commands

## Observability

## Documentation Updates

## Progress

## Surprises & Discoveries

## Decision Log

## Outcomes & Retrospective

## Revision Notes
```

## 작성 원칙

- 경로와 이름은 가능한 한 구체적으로 적는다.
- “적절히 구현한다”, “필요한 테스트를 한다” 같은 모호한 표현을 피한다.
- 구현 도중 계획과 현실이 달라지면 계획을 먼저 갱신한다.
- 사용자에게 다음 단계를 다시 물으며 멈추지 않는다. 단, 제품 동작·아키텍처·보안·정합성에 중요한 미결정 사항은 `AGENTS.md`의 질문 절차를 따른다.
- 실험이나 proof of concept가 필요하면 제거 조건과 최종 구현 반영 여부를 명시한다.
