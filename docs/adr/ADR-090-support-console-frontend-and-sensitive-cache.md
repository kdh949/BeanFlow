# ADR-090: Support Console frontend boundary와 sensitive browser state

- **Status:** Proposed
- **Date:** 2026-08-10

## Context

Support Console이 최종 제품 범위라는 결정은 확정됐다. 최신 main에는 React 19+TypeScript+Vite 앱과
`/app`, `/store`, `/ops` surface가 있지만, 그 사실만으로 privileged Support UI를 같은 trust/deployment
boundary에 둘 수는 없다. Browser credential, token storage, CORS, CSRF, origin, operator identity와
deployment isolation이 아직 확정되지 않았다.

## Decision

**Open decision:** 다음 세 boundary 중 하나를 credential/trust model과 함께 승인하기 전에는 구현 위치를
선택하지 않는다. 어느 대안이든 PII는 localStorage/sessionStorage/IndexedDB/service-worker/persistent
query cache에 저장하지 않고 navigation/expiry/Case close/logout/permission loss 때 plaintext state와
DOM에서 제거한다. Sensitive response는 no-store이고 bulk export는 초기 비목표이며 서버 권한이
authoritative다.

## Alternatives Considered

- 별도 operator app/origin: trust, credential과 deployment isolation이 명확하지만 build, client와 운영 표면이 늘어난다.
- 기존 `frontend/`의 격리 `/support` route: build/client 재사용이 쉽지만 customer/store/ops와 같은 origin 및 bundle의 credential·XSS·release coupling을 수용해야 한다.
- server-rendered operator UI: browser state와 bundle을 줄일 수 있지만 별도 rendering/session/CSRF 운영 모델과 UX 구현이 필요하다.

## Rationale

제품 범위와 browser-side 비저장 통제는 고정하되, repository topology만으로 trust boundary를 추론하지 않는다.
결정에는 credential transport/storage, CORS/CSRF, origin, deployment owner와 failure behavior가 함께 필요하다.

## Consequences

S130은 이 ADR이 Accepted되기 전 `Implementation-Ready`가 될 수 없다. 선택한 대안에 따라 build/client
재사용, CSP, CSRF/CORS, session/token handling과 E2E gate가 달라진다. 이 Proposed ADR은 기존
`frontend/` 통합을 승인하거나 별도 app/server-rendering을 배제하지 않는다.

## Verification

선택 대안의 credential/CORS/CSRF contract test, route/origin authorization, storage/cache/DOM inspection,
reveal expiry/navigation, CSP/XSS/no-store와 해당 production build.

## Metrics

UI error/expiry clearing과 server authorization outcomes만; PII/session identifiers를 client telemetry에 넣지 않는다.

## Revisit Conditions

Browser credential과 trust/deployment boundary가 승인될 때. 구현 뒤에는 incident, deploy cadence와
bundle/performance evidence가 재검토 조건이다.

## Related Decisions

ADR-070, ADR-081, ADR-082.
