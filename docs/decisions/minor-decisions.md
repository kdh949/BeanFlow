# Minor Decisions

| ID | Date | Task | Decision | Rationale | Affected files | Revisit |
|---|---|---|---|---|---|---|
| MD-2026-001 | 2026-07-28 | 주문 생성 예약 lease Milestone 1 | 실제 영속 event producer가 없는 동안 `spring-modulith-starter-jpa`를 활성화하지 않는다 | Hibernate `validate`가 사용하지 않는 Event Publication Entity schema를 요구하지 않게 하고, ADR-010의 구체 publication 선택은 첫 event-driven Feature에서 검증한다 | `build.gradle.kts`, 애플리케이션 context | 영속 cross-module event producer를 구현할 때 |
| MD-2026-002 | 2026-08-01 | PR CI 고속화와 실패 전파 복구 | required `build` job은 모든 PR에서 유지하되 문서·OpenAPI-only 변경은 정적 검증만 실행하고, 그 밖의 변경과 분류 불명은 전체 Gradle build를 실행한다. 모든 검증 로그 pipeline은 원 명령의 실패를 보존한다 | required workflow 자체를 건너뛰면 merge가 Pending에 막힐 수 있다. 문서 계약은 빠르게 검증하되 unknown 변경과 검사 도구 변경은 fail-closed로 전체 build에 보내고, 실패를 성공으로 위장하지 않는다 | `.github/workflows/ci.yml`, `scripts/ci/`, `scripts/verify-docs.sh` | fast path 오분류, OpenAPI 검증 누락 또는 전체 build 회귀가 관측될 때 |
| MD-2026-003 | 2026-08-01 | Signed cursor test context | 공개 test-vector key는 `src/test/resources`의 test application context에만 둔다 | main·local runtime 설정에 secret/fallback을 추가하지 않으면서 통합 테스트가 실제 required key-ring binding을 사용하게 한다 | `src/test/resources/application.yaml`, Cursor tests | test context가 explicit properties로 전환되거나 test vector가 더 이상 필요 없을 때 |
| MD-2026-004 | 2026-08-01 | Signed cursor canonical JSON writer | codec 내부 기본 ObjectMapper와 insertion-ordered map으로 v1 payload를 직렬화한다 | 전역 Jackson customization이 property order나 whitespace 없는 고정 wire bytes를 바꾸지 않게 한다 | `HmacSignedCursorCodec.kt` | versioned wire contract가 새 codec writer 또는 payload type을 필요로 할 때 |

## ID format

`MD-YYYY-NNN`
