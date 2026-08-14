# ADR-105: 실제 정산 지급을 Non-goal로 두고 sandbox 범위를 명시한다

- **Status:** Accepted
- **Date:** 2026-08-11
- **Implementation owner:** [Design and capability contract](../exec-plans/completed/productization-00-design-capability-contract.md)

## Context

운영자 콘솔 디자인에는 두 화면이 있다.

- `운영자 4b 가맹점 심사`: 사업자등록 진위 확인, 대표자 신분 확인, 정산 계좌 실명 검증
- `운영자 4c 지급 실행`: 이체 파일 내보내기, 지급 실행, 은행 마감 시각, 요청자·승인자 분리

두 화면 모두 실제로 동작하려면 외부 규제 대응과 계약이 필요하다. 실명 확인은 금융기관 연동,
이체 실행은 펌뱅킹 계약과 전자금융 관련 의무가 따른다.

[Non-goals](../product/non-goals.md)는 이미 "실제 가맹점 계좌 지급", "실제 고객 자금 보관",
"전자금융 또는 카드 보안 규제 준수 완료 주장"을 범위 밖으로 두고 있다.

반면 정산 계산, 조정 원장, 이의제기, 멱등 재실행은 이미 구현돼 있고
([ADR-008](ADR-008-settlement-adjustment-ledger.md),
[ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md),
[ADR-018](ADR-018-settlement-dispute-hold-and-refile.md)) 이 부분은 실제로 동작한다.

## Decision

### 범위

| 항목 | 범위 |
|---|---|
| 정산 계산과 배치 | 구현됨 (유지) |
| 조정 원장과 이의제기 | 구현됨 (유지) |
| 대사와 멱등 재실행 | 구현됨 (유지) |
| 지급 대상 산출과 지급 파일 생성 | P1, **sandbox** |
| 요청자·승인자 분리와 2인 승인 | P1, sandbox |
| 실제 계좌 이체 실행 | **Non-goal** |
| 계좌 실명 확인 | **Non-goal** |
| 사업자등록 진위 확인 | **Non-goal** |
| 가맹점 입점 심사 워크플로 | **Non-goal** |

### 표기 규칙

- 지급 파일 생성 기능을 만들 때, 생성된 파일은 **모의 형식**이며 실제 은행에 제출할 수 없음을
  파일 헤더, API 응답, 화면, 문서에 명시한다.
- "지급 완료", "이체 성공", "은행 마감" 같은 표현을 사용하지 않는다. 상태는
  `PAYOUT_FILE_GENERATED`처럼 실제로 일어난 일을 그대로 표현한다.
- README, 제품·운영 설명, ExecPlan 어디에서도 정산 지급을 구현했다고 쓰지 않는다.
- `운영자 4b` 화면은 P0/P1 라우트에서 제외한다. 매장은 운영 데이터로 등록한다.

### 2인 승인의 위치

지급 실행 자체가 Non-goal이므로 2인 승인은 **지급 파일 생성**에 적용한다. 승인 모델은 기존
[ADR-053](ADR-053-two-person-setup-repair-approval.md)의 제안자·결정자 분리 규칙을 재사용하고
새 모델을 만들지 않는다.

## Alternatives Considered

### 1. 모의 이체 Provider를 만들어 "지급 실행"까지 구현

- 장점: 디자인을 그대로 구현할 수 있다.
- 단점: 성공 응답을 반환하는 fake가 운영 경로에 들어간다. `AGENTS.md`는 운영 profile에서 fake
  provider 선택 시 시작 실패를 요구한다. 무엇보다 "지급했다"는 상태가 DB에 남으면 그 데이터를
  근거로 다른 판단이 이뤄진다.

### 2. 화면과 기능을 모두 제거

- 장점: 오해의 여지가 없다.
- 단점: 정산 계산 결과를 어떤 형태로 내보내는지가 사라진다. 지급 파일 생성은 계산 결과의 검증
  가능한 산출물이고 실제 이체 없이도 의미가 있다.

### 3. 실제 펌뱅킹 연동

- 장점: 완전한 제품이 된다.
- 단점: 계약, 규제 대응, 자금 관리 책임이 따른다. 현재 범위와 목적을 크게 벗어난다.

## Rationale

정산의 어려운 부분은 이체가 아니라 **계산의 정확성, 조정의 추적 가능성, 재실행의 멱등성**이다.
그 부분은 이미 구현돼 있다. 이체는 계약이 있으면 연결되는 마지막 단계다.

기능이 없다는 사실을 명확히 표기하는 것과, 없는 기능을 있는 것처럼 보이게 하는 것은 다르다.
후자는 제품이 아니라 데모다.

## Consequences

- 디자인 두 화면이 수정되거나 제외된다.
- 지급 파일 형식을 정의해야 한다. 실제 은행 포맷을 흉내내지 않고 내부 검증용 형식을 쓴다.
- 정산 흐름의 마지막이 "지급 파일 생성"에서 끝난다. 이후 상태는 시스템 밖이다.
- 매장 등록이 운영 데이터 절차가 되므로 그 절차와 감사 기록이 필요하다.

## Verification

- 지급 파일 생성 API가 실제 이체를 수행하지 않고, 응답과 파일에 sandbox 표기가 있는지 검증한다.
- 승인자와 요청자가 같으면 파일 생성이 거부되는지 검증한다.
- 같은 기간·매장에 대한 반복 생성이 중복 부수효과를 만들지 않는지 검증한다.
- 정산 계산·조정·이의제기의 기존 테스트가 회귀 없이 통과하는지 확인한다.
- 문서 검증에서 "지급 완료" 계열 표현이 사용되지 않았는지 확인한다.

## Metrics

- 지급 파일 생성 수와 대상 매장 수
- 대사 미해소로 생성이 차단된 건수
- 2인 승인 거부 수

## Revisit Conditions

- 실제 펌뱅킹 계약과 규제 대응이 확정될 때
- 매장 입점 심사가 제품 범위에 들어올 때
- 정산 결과를 외부 회계 시스템에 연동해야 할 때

## Related Decisions

- [ADR-008](ADR-008-settlement-adjustment-ledger.md)
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-018](ADR-018-settlement-dispute-hold-and-refile.md)
- [ADR-053](ADR-053-two-person-setup-repair-approval.md)
- [Design Contract Conflicts C-8](../product/design-contract-conflicts.md)
