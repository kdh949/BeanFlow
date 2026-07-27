# Decision Recording Rules

## Purpose

질문과 답변 전문을 보관하는 것이 아니라, 시간이 지난 뒤에도 왜 현재 동작과 구조를 선택했는지 재구성할 수 있게 한다.

## Classification

### Business Policy

기록 위치: `docs/product/business-policy-decisions.md`

제품·운영 규칙을 기록한다.

- 예약 lease 시간
- 취소 가능 상태
- 포인트 적립 기준
- 이의제기 기간
- 매장 수락 timeout

정책이 데이터 모델, 트랜잭션 또는 장기 구조를 크게 바꾸면 ADR도 작성한다.

### ADR

기록 위치: `docs/adr/ADR-NNN-title.md`

다음 중 하나라도 변경하면 ADR 후보로 본다.

- 외부 API 또는 사용자에게 보이는 동작
- Bounded Context와 데이터 소유권
- Aggregate·Repository 경계
- DB 스키마·마이그레이션
- 트랜잭션·일관성 수준
- 멱등성·동시성·재시도·reconciliation
- 실패·fallback 정책
- 보안·인가·개인정보
- 이벤트 계약
- 장기 변경 비용이 큰 dependency·infrastructure
- 정확성·성능·운영 비용의 명시적 trade-off

### Minor Decision

기록 위치: `docs/decisions/minor-decisions.md`

국소적이고 쉽게 되돌릴 수 있으며 공개 계약에 영향이 작은 선택을 기록한다.

- 내부 이름과 fixture 구조
- 한 Query Repository의 작은 구현 방식
- 문서 배치
- 단일 모듈 내부의 제한된 설정

### No record

- formatter 결과
- import 순서
- 지역 변수 이름
- Accepted 문서에 이미 명확한 선택의 단순 적용

## Question protocol

결정이 필요한 에이전트는 한 번에 하나의 질문을 한다.

1. 왜 지금 결정해야 하는가
2. 영향 범위
3. 대안
4. 장단점과 실패 가능성
5. 추천
6. 기록 분류

답변 후 기록을 먼저 갱신하고 구현을 시작한다.

## ADR structure

- Title
- Status
- Context
- Decision
- Alternatives Considered
- Rationale
- Consequences
- Verification
- Metrics
- Revisit Conditions
- Related Decisions

Status:

- Proposed
- Accepted
- Superseded
- Deprecated
- Rejected

## Amendment

기존 결정을 바꿀 때 원문을 조용히 덮어쓰지 않는다.

- 단순 수치 정책 변경: 변경일·이유를 정책 문서에 기록
- 구조적 변경: 새 ADR을 만들고 이전 ADR을 `Superseded` 처리
