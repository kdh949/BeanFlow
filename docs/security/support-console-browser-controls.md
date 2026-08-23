# Proposed Support Console Browser Controls

Support Console은 최종 제품 범위지만 별도 app, 기존 React/TypeScript/Vite app 통합, server-rendered UI 중
frontend/trust/deployment boundary는 ADR-090에서 기존 frontend의 격리 `/support` route로 Accepted됐다. 아래 통제는
baseline이며 browser credential, token storage, CORS, CSRF와 origin이 승인됐다는 뜻이 아니다.
Server authorization remains authoritative.

- no localStorage/sessionStorage/IndexedDB/service-worker cache for PII
- no persistent query cache for revealed values
- remove reveal values on expiry, navigation, Case resolution/closure, logout and permission loss
- no hidden DOM copy; clipboard needs separate permission/Audit decision
- sensitive responses use no-store and browser security headers
- no bulk selection/export/download initially
- masked value is the initial and fallback render; error paths do not retain plaintext
- sanitize notes and Provider content; test XSS, CSRF/CORS assumptions and clickjacking headers
