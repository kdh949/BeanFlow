# HTTP 실패를 문의 코드로 추적한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-09-30`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객 오류 화면의 문의 코드로 HTTP 실패와 안전한 원인 진단을 찾아야 한다.

## Current State

API 오류 응답에는 correlationId가 있지만 ApiExceptionHandler는 진단을 기록하지 않는다.
Nginx는 별도 request_id만 기록하고 CorrelationIdFilter는 MDC 생성·제거만 수행한다.

## Definitions

문의 코드는 오류 응답 correlationId이며 X-Correlation-Id와 같다. route는 MVC path template다.
SQLSTATE는 DB가 반환하는 5자리 오류 분류이며 SQL이나 오류 원문과 다르다.

## Scope

### In Scope

공통 요청 필터, 5xx API 예외 처리, Nginx 로그, 개인정보 비노출 검증과 조회 runbook.

### Non-goals

거래 정책, 오류 응답 계약, DB 스키마, UI, 운영 배포와 기존 장애 데이터 수정.

## Business Rules and Invariants

실패를 성공으로 바꾸지 않으며 BR-28 및 ADR-121의 secret/PII 비노출을 유지한다.
correlation ID는 검색 field이며 metric/index label이 아니다.

## Architecture and Transaction Boundaries

CorrelationIdFilter가 보안 필터보다 먼저 요청 문맥을 설치하고 실패 status를 기록한다.
ApiExceptionHandler가 서버 의존성 실패 진단을 기록한다. Aggregate/DB transaction은 변경하지 않는다.
Nginx는 upstream response header로 request_id와 correlation_id를 연결한다.

## Alternatives Considered

원문 Throwable 로그는 SQL·secret 누출 위험 때문에 제외한다. status-only 로그는 원인 식별이
어려워 안전한 class/SQLSTATE/frame 진단을 추가한다. 새로운 관측 dependency는 도입하지 않는다.

## Failure Semantics

4xx INFO, 5xx ERROR이며 전파 예외는 기록 후 원래 예외를 재전파한다.
원문 URL/body/headers/message와 suppressed cause를 수집하지 않는다.

## Data and Migration

DB 변경 없음. 진단은 최대 8개 cause, cause별 12개 frame으로 제한한다.

## API and Event Contracts

기존 status, error body 및 X-Correlation-Id 계약을 보존한다. 로그 field만 추가한다.

## Milestones

1. ADR에 안전한 HTTP 진단 정책 기록.
2. 요청/예외/Nginx 로그 연결과 테스트 구현.
3. 관련 테스트·format·build·문서 검증, diff 검토와 PR 생성.

## Required Tests

MockMvc 실패 요청의 문의 코드/응답 header/log 일치, security-style 조기 응답,
원문 민감정보 비노출, cause chain bounded/cycle, SQLSTATE, MDC 정리와 trace 연결.
Nginx 실제 proxy의 upstream correlation header와 접근 로그 연결.
기존 AuthenticationSecurityIntegrationTest와 NearbyCoordinatePrivacyIntegrationTest도 실행한다.

## Validation Commands

- ./gradlew test --tests '*ApiFailureLoggingTest' --tests '*CorrelationIdFilterTest'
- ./gradlew spotlessCheck bootJar
- bash scripts/verify-docs.sh
- python3 scripts/test-http-failure-nginx.py
- bash scripts/deploy/test-frontend-image-contract.sh
- git diff --check

## Observability

http_request_failed: correlationId/method/route/status. api_dependency_failed:
correlationId/error_code/exception_types/exception_frames/sql_state.
활성 trace에는 beanflow.correlation_id를 추가한다.

## Documentation Updates

ADR-121 amendment와 docs/operations/http-failure-support-code-runbook.md를 추가한다.

## Progress

- [x] 현재 코드·정책 검토와 격리 checkout 준비.
- [x] 로그 정책 결정 기록.
- [x] 구현과 검증.

## Surprises & Discoveries

기존 checkout에 관련 없는 미완료 변경이 있어 별도 local clone에서 작업한다.
Nginx 배포 계약 테스트가 raw URI를 요구해 ADR-121과 상충한다. HTTP 실패 amendment에
따라 method/status/request_id/correlation_id를 검사하고 raw URI·IP·사용자 정보를 금지한다.
JVM compiler의 기본 캐시/daemon 쓰기 경로가 격리 환경 밖이므로 임시 Gradle cache와
in-process compiler로 검증한다.

## Decision Log

- 2026-09-30: 원문 Throwable 대신 bounded 진단 metadata를 사용한다. ADR-121에 기록.

## Outcomes & Retrospective

Passed: JVM 27 tests (ApiFailureLoggingTest 6, CorrelationIdFilterTest 4,
PerformanceTelemetryContextFilterTest 3, ModularityTests 1,
AuthenticationSecurityIntegrationTest 10, NearbyCoordinatePrivacyIntegrationTest 3),
spotlessCheck, bootJar, 실제 Nginx config/proxy/header/log/privacy 검증,
frontend image contract, CI script tests, 문서/OpenAPI 검증과 diff check.

JVM 검증은 임시 GRADLE_USER_HOME, JDK 21, `-Pkotlin.compiler.execution.strategy=in-process`,
`-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m`과 `--max-workers=2`로 실행했다.
초기 환경/컴파일 오류를 수정한 뒤 최종 검증은 실패·skip 없이 통과했다.

Not run: 전체 JVM/프론트엔드 UI suite, 운영 배포와 실제 수집 로그 조회.
거래 데이터·정책을 수정하거나 현재 장애를 복구했다고 주장하지 않는다.

## Revision Notes

- 2026-09-30: 초기 계획.
