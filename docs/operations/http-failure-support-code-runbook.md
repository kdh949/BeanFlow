# HTTP 실패 문의 코드 조회

화면의 문의 코드는 오류 응답 `correlationId`와 `X-Correlation-Id` header 값이다.
로그는 [ADR-121](../adr/ADR-121-performance-observability-and-trace-profile-correlation.md)의
HTTP failure amendment를 따른다.

## 조회 순서

1. 장애가 발생한 시간 범위를 고정하고 API stdout 또는 Loki의 log body에서 문의 코드를 검색한다.
   Loki 예: `{service_name="beanflow"} |= "<문의 코드>"`. 설치별 label 이름은 실제 labels에서 확인한다.
2. `http_request_failed`의 status와 route template을 확인한다. 이 기록은 보안 필터의 조기 거부도
   포함하며 MVC route가 결정되지 않았다면 `UNMAPPED`다. 4xx는 INFO, 5xx는 ERROR다.
3. 5xx 의존성 오류의 `api_dependency_failed`를 같은 코드로 조회한다. `error_code`,
   `exception_types`, `sql_state`, `exception_frames`로 DB 접속·SQL 오류·Projection 검증을 구분한다.
   class와 line은 실제 배포 revision의 소스에서 확인한다. cause가 8개보다 깊거나 순환하면
   `exception_chain_truncated=true`이며 기록하지 않은 root cause를 추정하지 않는다.
4. ECS/OTLP log의 native trace ID로 trace를 조회한다. 활성 trace의 `beanflow.correlation_id`도
   같은 코드다. agent 또는 ingest가 비활성이라 trace가 없으면 stdout 기록으로 조사한다.
5. Nginx 접근 로그의 `correlation_id`에서 같은 코드를 검색하면 `request_id`를 함께 찾을 수 있다.
   backend에 도달하기 전의 502/504나 Nginx 자체 거부는 correlation_id가 `-`일 수 있으므로
   request_id, status와 시간으로 조사한다.

## 진단 필드 경계

원문 URL, query, body, 인증 header, cookie, SQL, exception message와 suppressed exception은
새 진단 로그에 기록하지 않는다. 호출 frame은 cause별 최대 12개, cause는 최대 8개다.
SQLSTATE는 검증된 5자리 `[A-Z0-9]` 값만 기록한다. 문의 코드를 metric/Loki index label로 만들지 않는다.
`http_request_failed`는 응답 실패이고 `api_dependency_failed`는 해당 실패의 추가 진단이므로
둘을 각각의 거래 실패로 중복 집계하지 않는다.

## 배포 후 수용 확인

- 안전한 테스트 요청에서 503을 재현하고 response body/header/stdout의 문의 코드가 같은지 확인한다.
- Nginx 접근 로그의 correlation_id와 request_id 연결을 확인한다.
- 사용 중인 수집 경로에서 같은 문의 코드와 native trace ID로 조회한다.
- 배포 revision과 log frame의 소스 revision을 일치시킨다.

로컬 MockMvc와 실제 Nginx proxy 검증은 운영 배포·Loki ingest 확인을 대신하지 않는다.
