# ADR-090: Support Console frontend boundary와 sensitive browser state

- **Status:** Accepted
- **Date:** 2026-08-23

## Context

Support Console이 최종 제품 범위라는 결정은 확정됐다. 최신 main에는 React 19+TypeScript+Vite 앱과
`/app`, `/store`, `/ops` surface가 있지만, 그 사실만으로 privileged Support UI를 같은 trust/deployment
boundary에 둘 수는 없다. Browser credential, token storage, CORS, CSRF, origin, operator identity와
deployment isolation이 아직 확정되지 않았다.

## Decision

Support Console은 기존 React/TypeScript/Vite `frontend/` 안의 격리된 `/support` route로 구현한다.
`/ops`와 같은 Keycloak Authorization Code + PKCE `S256` public-client 세션과 메모리 access token을
재사용하며, same-origin `/api/v1/support/**`만 호출한다. 고객·점주 Session을 해석하거나 impersonation하지
않고 Support 서버의 persistent permission과 object authorization이 모든 작업의 권위다.

PII는 localStorage/sessionStorage/IndexedDB/service-worker/persistent query cache에 저장하지 않는다.
exact 검색 criterion은 POST body로만 보내고 검색 후보는 masked DTO만 유지한다. Reveal 원문은 route-local
메모리와 현재 DOM에만 존재하며 navigation, grant expiry, Case terminal 전이, logout, permission loss 또는
사용자의 명시적 닫기 때 제거한다. Sensitive response는 `no-store`, bulk export/download/copy helper는 초기
비목표다. 사용자 입력, reveal 값, token과 opaque provider reference를 client log나 telemetry에 넣지 않는다.

Support route는 고객·점주 navigation에서 노출하지 않고 운영자 셸 안에 별도 업무 surface로 둔다. 같은
origin과 release bundle을 공유하는 coupling은 수용하되, 인증 gate와 Support 전용 route-local state로
credential/PII 경계를 분리한다. 별도 app/origin이 실제 조직·배포 격리를 요구할 때 재검토한다.

## Alternatives Considered

- 별도 operator app/origin: trust, credential과 deployment isolation이 명확하지만 현재 단일 운영자 OIDC
  client와 build를 중복하고 별도 배포 운영 근거가 없어 보류한다.
- 기존 `frontend/`의 격리 `/support` route: build, OIDC session과 디자인 시스템을 재사용할 수 있어 채택한다.
  customer/store/ops와 같은 origin 및 bundle의 credential·XSS·release coupling을 수용한다.
- server-rendered operator UI: browser state와 bundle을 줄일 수 있지만 별도 rendering/session/CSRF 운영
  모델과 UX 구현이 필요해 채택하지 않는다.

## Rationale

ADR-092와 BR-41이 운영자·고객지원의 Keycloak JWT chain, PKCE와 token 비저장 경계를 이미 확정한다.
같은 frontend 안에서 그 인증과 디자인 시스템을 재사용하면 새 credential model을 만들지 않으면서 현재
구현된 Support API를 사용자 여정으로 연결할 수 있다.

## Consequences

Support와 Operations가 build, OIDC client와 release cadence를 공유한다. 같은-origin XSS 영향 범위가 넓어지고
Support 변경도 frontend 전체 배포를 요구한다. 반대로 별도 client secret, CORS 또는 Support 전용 token
저장소는 생기지 않는다. Reveal expiry/navigation 제거와 browser residue E2E 검증이 release gate가 된다.

## Verification

선택 대안의 credential/CORS/CSRF contract test, route/origin authorization, storage/cache/DOM inspection,
reveal expiry/navigation, CSP/XSS/no-store와 해당 production build.

## Metrics

UI error/expiry clearing과 server authorization outcomes만; PII/session identifiers를 client telemetry에 넣지 않는다.

## Revisit Conditions

조직 규정이 Support의 별도 origin/배포 소유권을 요구하거나, shared-origin XSS incident, 독립 deploy
cadence 또는 bundle/performance evidence가 현재 경계를 정당화하지 못할 때.

## Related Decisions

ADR-070, ADR-081, ADR-082, ADR-092, BR-41.
