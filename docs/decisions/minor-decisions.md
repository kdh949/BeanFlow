# Minor Decisions

| ID | Date | Task | Decision | Rationale | Affected files | Revisit |
|---|---|---|---|---|---|---|
| MD-2026-001 | 2026-07-28 | 주문 생성 예약 lease Milestone 1 | 실제 영속 event producer가 없는 동안 `spring-modulith-starter-jpa`를 활성화하지 않는다 | Hibernate `validate`가 사용하지 않는 Event Publication Entity schema를 요구하지 않게 하고, ADR-010의 구체 publication 선택은 첫 event-driven Feature에서 검증한다 | `build.gradle.kts`, 애플리케이션 context | 영속 cross-module event producer를 구현할 때 |

## ID format

`MD-YYYY-NNN`
