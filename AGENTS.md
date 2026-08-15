# BeanFlow Agent Guide

## Product

BeanFlow는 다점포 카페의 선주문, 결제, 픽업, 포인트, 정산, 환불 조정과 이의제기를 다루는 거래 플랫폼이다.

## Instruction scope

- 이 파일은 저장소 공통 규칙이다.
- 복잡한 Feature, 스키마 변경, 여러 모듈에 걸친 변경 또는 중요한 리팩터링은 `.agent/PLANS.md`에 따른 ExecPlan을 사용한다.
- 하위 디렉터리에 더 구체적인 `AGENTS.md` 또는 `AGENTS.override.md`가 있으면 해당 범위에서 더 구체적인 규칙을 따른다.

## Read before work

모든 작업에서 다음을 읽는다.

- `docs/product/business-policy-decisions.md`
- `docs/architecture/failure-semantics.md`
- `docs/decisions/README.md`
- `docs/testing/definition-of-done.md`

중요하거나 여러 단계인 작업에서는 추가로 읽는다.

- `.agent/PLANS.md`
- 관련 `docs/adr/ADR-*.md`
- 관련 아키텍처 문서
- `docs/exec-plans/active/`의 해당 ExecPlan
- 관련 OpenAPI 계약

## Frontend UI work

For any task that reads or changes `frontend/**`, read and follow `frontend/AGENTS.md` before planning or acting. UI work requires the BeanFlow Storybook MCP; do not implement UI while that prerequisite is unavailable.

## Repository content boundary

저장소에는 BeanFlow 제품, 아키텍처, 구현, 테스트, 운영과 변경 이력에 직접 필요한 정보만 기록한다.

다음을 저장소, 소스 주석, 테스트 fixture, commit message, PR, issue에 기록하지 않는다.

- 개인 배경과 경력
- 제품과 무관한 외부 평가 자료
- 사적인 프로젝트 선택 동기
- 공개할 필요가 없는 원본 기획·대화
- 실제로 확인되지 않은 사용자·트래픽·성과

외부 또는 개인 문맥에서 제품 요구를 도출해야 한다면 출처를 복사하지 말고 중립적인 제품 요구, 제약, 위험과 수용 조건으로 다시 작성한다.

## Source conflicts

- 코드, 테스트, OpenAPI, Business Policy, ADR, ExecPlan이 충돌하면 임의로 하나를 선택하지 않는다.
- 구현 전에 충돌 파일, 상충 내용, 영향 범위와 추천 해결안을 보고한다.
- 기존 결정을 변경하려면 관련 Business Policy 또는 ADR을 먼저 갱신한다.
- Accepted ADR을 소스 코드만으로 우회하지 않는다.

## Before implementation

코드를 작성하기 전에 다음을 보고한다.

1. 변경 목적
2. 관련 비즈니스 규칙과 도메인 불변식
3. 영향받는 모듈과 Aggregate
4. 변경할 가능성이 높은 파일
5. 트랜잭션 경계
6. 동시성·멱등성·외부 실패 위험
7. 가능한 대안
8. 선택 근거와 예상 부작용
9. 테스트·검증 계획
10. 문서와 ADR 갱신 계획

단순 오탈자나 포매팅 변경에는 위 형식을 축약할 수 있다.

## Architecture rules

- Controller는 Repository를 직접 호출하지 않는다.
- Application Service가 유스케이스와 트랜잭션 경계를 조정한다.
- Aggregate가 자신의 상태 전이와 불변식을 보호한다.
- Repository는 기본적으로 Aggregate Root 단위로 둔다.
- 다른 Aggregate는 기본적으로 식별자로 참조한다.
- 양방향 연관관계와 `@ManyToMany`는 Accepted ADR 없이 추가하지 않는다.
- 외부 Provider 호출을 장시간 DB 트랜잭션 안에서 수행하지 않는다.
- JPA Entity를 API 응답으로 직접 노출하지 않는다.
- 쓰기 모델의 객체 그래프를 조회 편의를 위해 확장하지 않는다.
- 목록·집계 조회는 DTO Projection, Query Repository 또는 별도 Read Model을 검토한다.
- Redis, Kafka, Kubernetes, MSA는 필요성과 장애 정책이 문서화되지 않으면 도입하지 않는다.
- 새 production dependency는 문제, 대안, 운영 비용, 실패 모델과 제거 비용을 보고한 뒤 추가한다.

## Explicit failure semantics

- 실패한 의존성을 local, in-memory, fake, mock, cached, stale 또는 no-op 구현으로 자동 대체하지 않는다.
- 필수 설정이 없거나 유효하지 않으면 애플리케이션 시작을 실패시킨다.
- 외부 작업 결과가 불명확하면 성공 또는 확정 실패로 단정하지 않는다.
- 비동기 부수효과의 실패는 `RETRY_SCHEDULED`, `FAILED`, `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW` 같은 명시적 상태로 남긴다.
- 예외를 잡아 로그만 남기고 성공 흐름을 계속하는 코드는 Accepted failure policy가 없는 한 금지한다.
- fallback은 명시적인 제품 기능일 때만 허용한다. 활성화 조건, degraded 상태, metric, log, 테스트와 ADR이 필요하다.
- 테스트용 fake와 mock은 test 또는 명시적 local profile에서만 활성화한다.
- 운영 profile에서 fake provider가 선택되면 시작을 실패시킨다.

## Decision recording

사용자 질문이 필요한 경우 한 번에 하나의 초점 있는 질문을 제시한다.

질문에는 다음을 포함한다.

1. 지금 결정해야 하는 이유
2. 영향받는 Context, Aggregate, API 또는 데이터
3. 가능한 대안
4. 대안별 장점, 단점과 실패 가능성
5. 추천안
6. 기록 위치 추천:
   - Business Policy
   - ADR
   - Minor Decision
   - 기록 불필요

사용자가 답하면 구현 전에 먼저 결정 기록을 갱신한다.

- 제품 동작·정책 숫자: `docs/product/business-policy-decisions.md`
- 구조적·장기적 결정: `docs/adr/`
- 작고 국소적이며 되돌리기 쉬운 결정: `docs/decisions/minor-decisions.md`

전체 대화 전문을 저장하지 않는다. 결정, 근거, 결과, 검증과 재검토 조건만 기록한다.

## Validation

관련되는 검증을 실제로 실행하고 정확한 결과를 보고한다.

검토 대상:

- 순수 도메인 단위 테스트
- Application Service 테스트
- PostgreSQL Testcontainers Repository 테스트
- API 계약 테스트
- Spring Modulith 구조 검증
- ArchUnit 테스트
- 동시성·멱등성 테스트
- 장애·재시도 테스트
- 정적 분석
- 빌드
- 문서와 OpenAPI 검증
- 성능 영향이 있으면 동일 조건 기준선과 재측정

실행하지 않은 검증은 `Not run`으로 표시한다. 실패한 검증을 숨기지 않는다. 비교 가능한 측정 없이 성능 향상을 주장하지 않는다.

## Code review rules

다음을 우선적으로 탐지한다.

- 예외를 삼키고 성공·빈 값·0·오래된 데이터로 대체
- 자동 in-memory, local, fake, mock, cache 또는 no-op fallback
- 외부 timeout을 확정 실패로 잘못 처리
- 중복 요청·이벤트에 대한 Unique Constraint 또는 멱등성 누락
- 트랜잭션 내부의 외부 API 호출
- Aggregate 경계를 넘는 Cascade 또는 객체 연관관계
- 상태·API·데이터 소유권 변경인데 ADR 또는 정책 갱신 누락
- 테스트 없이 추가된 실패 경로
- 실제 실행하지 않은 검증·측정 주장

## Git behavior

- 명시적 요청 없이 commit, push, PR 생성 또는 원격 변경을 하지 않는다.
- 기존 사용자의 변경을 임의로 되돌리지 않는다.
- 범위를 벗어난 리팩터링을 섞지 않는다.
- 완료 전에 diff를 검토하고 불필요한 파일, secret, 개인 문맥과 생성물을 제거한다.

## Done

변경은 다음 조건을 충족할 때 완료다.

- 비즈니스 불변식이 코드와 DB 제약으로 필요한 만큼 보호됨
- 정상·실패·중복·재시도 경로가 명확함
- 실패가 관측 가능하고 성공으로 위장되지 않음
- 관련 테스트와 검증이 실제로 통과함
- API와 문서가 변경된 동작과 일치함
- 중요한 결정이 적절한 기록에 남음
- 미해결 fallback, placeholder 또는 불명 상태가 숨겨지지 않음
