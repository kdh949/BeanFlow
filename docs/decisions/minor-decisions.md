# Minor Decisions

| ID | Date | Task | Decision | Rationale | Affected files | Revisit |
|---|---|---|---|---|---|---|
| MD-2026-001 | 2026-07-28 | 주문 생성 예약 lease Milestone 1 | 실제 영속 event producer가 없는 동안 `spring-modulith-starter-jpa`를 활성화하지 않는다 | Hibernate `validate`가 사용하지 않는 Event Publication Entity schema를 요구하지 않게 하고, ADR-010의 구체 publication 선택은 첫 event-driven Feature에서 검증한다 | `build.gradle.kts`, 애플리케이션 context | 영속 cross-module event producer를 구현할 때 |
| MD-2026-002 | 2026-08-01 | PR CI 고속화와 실패 전파 복구 | required `build` job은 모든 PR에서 유지하되 문서·OpenAPI-only 변경은 정적 검증만 실행하고, 그 밖의 변경과 분류 불명은 전체 Gradle build를 실행한다. 모든 검증 로그 pipeline은 원 명령의 실패를 보존한다 | required workflow 자체를 건너뛰면 merge가 Pending에 막힐 수 있다. 문서 계약은 빠르게 검증하되 unknown 변경과 검사 도구 변경은 fail-closed로 전체 build에 보내고, 실패를 성공으로 위장하지 않는다 | `.github/workflows/ci.yml`, `scripts/ci/`, `scripts/verify-docs.sh` | fast path 오분류, OpenAPI 검증 누락 또는 전체 build 회귀가 관측될 때 |

## ID format

`MD-YYYY-NNN`
