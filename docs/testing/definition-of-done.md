# Definition of Done

Feature는 다음 조건을 충족해야 완료다.

## Product and domain

- [ ] 사용자 목적과 범위가 명시됐다.
- [ ] 비즈니스 규칙과 불변식이 문서·코드에 일치한다.
- [ ] 정상, 실패, 중복, timeout과 복구 경로가 정의됐다.
- [ ] Aggregate와 트랜잭션 경계가 명확하다.
- [ ] 미결정 정책을 임의로 구현하지 않았다.

## Failure semantics

- [ ] 실패가 성공, 빈 값, 0 또는 stale data로 위장되지 않는다.
- [ ] 필수 설정 누락 시 fail-fast한다.
- [ ] unknown 외부 결과를 명시적 상태로 보존한다.
- [ ] 비동기 실패가 영속 상태와 재처리 경로를 가진다.
- [ ] 암묵적 local/in-memory/fake/mock/no-op fallback이 없다.
- [ ] 허용 fallback은 ADR, metric, log와 테스트가 있다.

## Persistence

- [ ] Aggregate 경계를 넘는 불필요한 객체 연관관계가 없다.
- [ ] DB Unique/Check/FK/Index가 불변식을 필요한 만큼 보강한다.
- [ ] 실제 PostgreSQL Testcontainers 테스트가 있다.
- [ ] 대량 변경에 Dirty Checking이 적합한지 검토했다.
- [ ] 쿼리 수와 Fetch 전략을 유스케이스 기준으로 검토했다.

## API and security

- [ ] OpenAPI 또는 계약 테스트가 갱신됐다.
- [ ] 상태 코드와 stable error code가 정의됐다.
- [ ] 역할과 객체 수준 인가가 검증됐다.
- [ ] 민감 정보가 응답·로그·감사 데이터에 노출되지 않는다.

## Verification

- [ ] 관련 단위·통합·구조 테스트가 실제 실행됐다.
- [ ] 동시성·멱등성 위험이 있으면 해당 테스트가 있다.
- [ ] Provider 장애·재시도 위험이 있으면 장애 테스트가 있다.
- [ ] 빌드와 정적 분석이 통과했다.
- [ ] 실행하지 않은 검증은 `Not run`으로 보고됐다.
- [ ] 성능 주장이 있으면 동일 조건 측정이 있다.

## Decisions and documentation

- [ ] 질문으로 바뀐 중요한 동작이 Business Policy 또는 ADR에 기록됐다.
- [ ] 국소 결정은 필요 시 Minor Decision에 기록됐다.
- [ ] ExecPlan의 Progress, Decision Log와 Outcomes가 최신이다.
- [ ] README·운영 문서·Runbook이 필요한 만큼 갱신됐다.
- [ ] 개인·외부 문맥과 불필요한 원본 자료가 diff에 없다.

## Review

- [ ] 최종 diff를 검토했다.
- [ ] 범위를 벗어난 리팩터링과 생성물이 제거됐다.
- [ ] commit/push는 사용자가 명시적으로 요청한 경우에만 수행한다.
