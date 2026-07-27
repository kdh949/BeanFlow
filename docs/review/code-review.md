# Code Review Guide

## Correctness

- 도메인 불변식이 Controller 또는 단순 사전 조회에만 의존하지 않는가
- 중복·동시 요청에서 DB 최종 방어가 있는가
- 허용되지 않은 상태 전이가 가능한가
- 환불·포인트·정산 합계가 재현 가능한가

## Failure

다음 패턴은 지적한다.

- catch 후 success/empty/zero/null 반환
- 자동 in-memory/local/fake/mock/no-op fallback
- timeout을 확정 실패로 처리
- 비동기 부수효과를 영속 상태 없이 로그만 남김
- 필수 설정에 default secret/credential
- 운영 profile에서 fake provider
- 알림 실패를 성공 처리하거나 원본 주문을 롤백

## Transactions and JPA

- 외부 호출이 DB 트랜잭션 안에 있는가
- Aggregate 경계를 넘는 Cascade·orphanRemoval이 있는가
- 목록 API가 Entity graph를 순회하는가
- Fetch Join과 pagination이 충돌하는가
- bulk update 후 persistence context 정리가 필요한가
- index와 constraint가 실제 query·invariant를 지원하는가

## Decisions

다음 변화에 관련 문서가 갱신됐는가.

- 공개 API·error contract
- 상태 머신
- 데이터 소유권·schema
- transaction boundary
- consistency, retry, fallback
- security/privacy
- event contract

## Verification

- 테스트가 정상 경로만 다루지 않는가
- 실행했다고 주장한 명령의 실제 결과가 있는가
- 성능 수치의 조건이 비교 가능한가
- 실제 PostgreSQL을 검증했는가
