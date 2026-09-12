# ADR-122: 외부 Keycloak을 사용하는 배포 모드

- **Status:** Accepted
- **Date:** 2026-09-06
- **Implementation owner:** [외부 Keycloak 배포 지원](../exec-plans/completed/external-keycloak-deployment.md)

## Context

ADR-119의 portfolio 배포는 Keycloak 서비스, 전용 DB 초기화와 관리자 credential을 같은
Compose에 포함한다. 별도로 운영하는 Keycloak을 사용하는 환경에도 이 credential을 요구하면
불필요한 DB 접근 권한을 애플리케이션 서버에 전달하게 된다. 비밀번호 검사만 제거하면 내부
Keycloak 주소와 Nginx upstream이 남아 정상적으로 기동하거나 로그인할 수 없다.

## Decision

- `BEANFLOW_KEYCLOAK_MODE=bundled|external`로 배포 모드를 명시한다. 생략한 기존 배포는
  `bundled`를 유지하며 알 수 없는 값은 거부한다.
- `external`에서는 base와 staging/prod overlay 뒤에 `compose.external-keycloak.yml`을 적용한다.
  Keycloak 서비스와 의존성, Keycloak DB 초기화 mount, DB·관리자 secret 정의와 mount를 제거한다.
  기존 PostgreSQL volume이나 외부 Keycloak 데이터는 삭제하지 않는다.
- 외부 issuer, authorization server base URL, realm, public client ID와 JWKS URL은 필수 배포
  입력이다. HTTPS와 issuer/base/realm/JWKS 일치를 검사한다. credential, query와 fragment가
  포함된 주소는 허용하지 않는다. callback과 logout은 기존 BeanFlow 공개 origin을 유지한다.
- 브라우저는 외부 Keycloak에 직접 Authorization Code + PKCE S256 요청을 한다. 외부 client의
  정확한 redirect URI, Web Origins, access-token audience와 `roles` mapper는 Keycloak 운영자가
  설정한다. SPA client secret, Keycloak 관리자 또는 DB credential을 BeanFlow에 주입하지 않는다.
- 외부 모드의 Nginx는 BeanFlow `/api/`와 정적 파일을 제공하고 `/auth/`는 404로 닫는다.
  외부 Keycloak은 자신의 origin/TLS를 소유한다. HTTP redirect proxy나 내부 Keycloak fallback을
  추가하지 않는다.
- 2026-09-09 보완: AIStor public signing endpoint가 BeanFlow origin과 같으면 설정된 private
  bucket의 `stores/`, `menus/`, `campaigns/` GET/HEAD도 저장소로 전달한다. ADR-115와 ADR-120의
  presigned URL·비공개 bucket 계약은 유지하며 Keycloak 프록시나 업로드 권한은 추가하지 않는다.
  기존 배포 입력에서 Nginx 파일을 생성해 읽기 전용 mount하고 preflight에서 내용 일치를 검증한다.
  별도 이미지 origin은 해당 host가 routing을 소유한다. 구현과 로컬 검증 범위는
  [같은 출처 AIStor 프록시 계획](../exec-plans/completed/same-origin-aistor-proxy.md)에 기록한다.
- 2026-09-11 담당자 선택 보완: access token에 `preferred_username`을 전달하는 사용자명 mapper를
  설정한다. BeanFlow는 서명된 최근 로그인 식별명을 표시용으로 관측하며 권한은 영속 grant로 판단한다.
  claim이 없는 기존 토큰은 이름 미등록으로 표시하고 관리자 API나 임의 이름으로 대체하지 않는다.
- Doppler는 배포 시 필요한 값을 공급하고 Compose config tree가 요구하는 secret 파일은 저장소
  밖에 0700 directory/0600 file로 준비한다. 비밀값을 출력하지 않고 모든 필수 입력을 확인한 뒤
  기록한다. 기존 DB/HMAC 값의 변경은 자동 credential rotation으로 처리하지 않는다.

## Alternatives Considered

- Keycloak DB 비밀번호를 임의로 채우거나 필수 검사만 제거: 내장 서비스를 계속 생성하고
  내부 주소를 사용하므로 외부 인증 서버 선택을 구현하지 못한다.
- 기본 Compose를 외부 전용으로 변경: 기존 bundled 배포를 깨뜨리므로 채택하지 않는다.
- 외부 Keycloak을 BeanFlow `/auth`로 재프록시: issuer, cookie, hostname과 TLS 경계를 중복 관리하게
  되므로 외부 origin을 직접 사용한다.

## Rationale

명시적 overlay는 기존 배포 동작을 보존하면서 외부 인증 서버의 소유권을 분리한다. 런타임 OIDC
config API와 JWT issuer/audience 검증을 그대로 사용하므로 제품 API, 화면과 권한 계약 변경이 없다.

## Consequences

- `!reset`과 `!override`를 지원하는 Docker Compose 2.24.4 이상이 필요하다.
- 외부 Keycloak의 가용성·TLS·realm·client 설정은 별도 운영 책임이다. discovery 404나 인증 실패를
  로컬 로그인 성공으로 대체하지 않는다.
- 정적 Compose 검사 통과와 실제 discovery/JWKS 도달성, 브라우저 로그인 성공을 구분한다.
- 이전 bundled 배포에서 전환하더라도 기존 컨테이너·DB를 자동 삭제하지 않는다. 중지·정리는
  소유권을 확인한 별도 운영 작업이다.

## Verification

- 외부 모드에서 Keycloak secret 파일 없이 preflight가 통과하고 최종 서비스가 세 개임을 검증한다.
- 누락·잘못된 모드, issuer/base/realm/JWKS 불일치와 HTTP 주소를 거부한다.
- bundled staging/prod 회귀 검사와 실제 Nginx syntax/HTTP smoke를 수행한다.
- 같은 출처 이미지의 path/query/signing Host 보존, GET/HEAD 제한, 앱 credential 제거,
  로그 비노출, upstream 실패 시 HTML 성공 응답 방지와 health 독립성을 검증한다.
- Doppler 준비는 secret 누락, multiline PEM 보존, 0600 권한과 비밀값 비출력을 검사한다.
- 실제 외부 로그인·로그아웃과 JWT issuer/audience/role은 배포된 환경에서 별도로 검증한다.

## Metrics

기존 API health, 인증 실패와 배포 preflight 결과를 사용한다. 새로운 metric이나 측정되지 않은
가용성·성능 주장은 추가하지 않는다.

## Revisit Conditions

외부 인증 서버를 같은 origin으로 proxy해야 하거나 confidential client/BFF, 자동 realm provisioning이
제품 요구가 될 때 재검토한다.

## Related Decisions

- [ADR-119](ADR-119-portfolio-deployment-runtime.md)
- [ADR-092](ADR-092-hybrid-authentication.md)
- [BR-41](../product/business-policy-decisions.md#br-41-운영자-웹-keycloak-로그인)
