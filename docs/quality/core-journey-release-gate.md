# 코어 사용자 여정 릴리스 게이트

> **상태:** `OPEN — release not approved`
> **소스 기준:** `433ed1990fdded3551d8bc1070200607904a4ac7` (2026-08-18)
> **대상:** 고객 가입·탐색·주문·결제·사후 처리와 점주 주문 처리·환불·정산·이의제기의 한 개 연결 여정

이 gate는 [Core User Journey Contract](../product/core-user-journey.md)의 source inventory를 release
결정으로 바꾼다. 어느 한 feature test 또는 과거 ExecPlan의 통과가 이 gate 전체의 통과를 뜻하지 않는다.

## 증거 상태

| 상태 | 의미 |
|---|---|
| `Passed (current)` | 이 source baseline에서 이번 release cycle에 실제 실행해 통과한 결과다. |
| `Historical evidence` | 완료 ExecPlan 또는 이전 commit에 결과가 남아 있다. 현재 baseline에서 재실행한 결과가 아니다. |
| `Not run` | 필요한 검증이 아직 실행되지 않았다. 통과로 해석하지 않는다. |
| `Pending remote` | 동일 SHA의 원격 required check가 아직 terminal result가 아니다. |
| `Blocked` | 필요한 계약·구현·문서 또는 권한이 없어 검증을 시작할 수 없다. |

`Passed (current)` local result, remote required-check result, 그리고 최종 release 승인은 서로 다르다.
remote가 끝나지 않았거나 required scenario가 `Not run`/`Blocked`이면 이 문서는 release를 승인하지 않는다.

## Current Stage 02 evidence

| Check | Command | Status | Boundary |
|---|---|---|---|
| Documentation integrity | `PATH="$PWD/.venv/bin:$PATH" bash scripts/verify-docs.sh` | Passed (current) | target 162 paths/171 operations, runtime 151 paths/160 operations, 329 schemas; this validates documentation and local contract structure only. |

## 12-item release gate

| # | Required gate | Current evidence / owner | Status | Exit condition |
|---:|---|---|---|---|
| 1 | P0 고객·점주 route에 Access Token 붙여넣기나 임의 UUID 입력이 없다. | productization-80/-90의 frontend source와 완료 ExecPlan evidence; 이번 slice는 UI를 실행하지 않았다. | Historical evidence | current browser smoke에서 customer·merchant route와 navigation을 다시 확인한다. |
| 2 | 고객·점주의 unsafe mutation은 각 Session/CSRF 경계를 지키며 actor 간 token을 교차 수용하지 않는다. | `frontend/src/api/client.test.ts` 및 productization-80/-90 evidence. | Historical evidence | current backend/frontend security tests가 통과한다. |
| 3 | 계정 생성 → 로그인 → 주문 → 점주 완료가 browser E2E로 한 번 연결된다. | 개별 customer/merchant browser evidence는 있으나 하나의 end-to-end run은 없다. | Not run | deterministic demo에서 customer와 merchant actor를 이어 420px browser E2E를 저장한다. |
| 4 | 쿠폰·포인트가 선택 가능하고 주문 서버가 실제 cart 기준으로 다시 계산한다. | points surface와 order-side recalculation은 source-backed; Stage 05 wallet backend/API와 order-side regression은 current evidence가 있으나 customer selector UI는 없다. | Blocked | Storybook MCP가 복구된 뒤 wallet loading/empty/unavailable/selection과 order-side recalculation browser evidence를 통과한다. |
| 5 | scripted provider success와 `UNKNOWN → reconciliation` 자동 수렴을 보인다. | productization-80 demo smoke와 payment core tests에 이전 증거가 있다. | Historical evidence | current scripted-provider integration run을 보관한다. |
| 6 | Toss sandbox는 선택적 실환경 확인이며 CI의 대체물이 아니다. | Toss sandbox plan/adapter 기록은 있으나 이번 release cycle에서 실행하지 않았다. | Not run | 선택적으로 sandbox receipt를 붙이고, 별도로 scripted CI gate를 통과한다. |
| 7 | 부분 환불이 정산 조정과 포인트 복원/복구 상태를 정직하게 남긴다. | productization-90 frontend/backend validation evidence. | Historical evidence | current partial-refund recovery scenario를 다시 실행한다. |
| 8 | OWNER 정산/이의제기와 STAFF의 허용·거부 경로가 모두 검증된다. | productization-90 test/stories evidence. | Historical evidence | current role matrix tests와 browser-visible forbidden state를 실행한다. |
| 9 | fresh clone에서 deterministic seed와 runbook으로 동일한 demo가 재현된다. | `scripts/demo/seed.sh`, local-demo completed plan, productization-80 smoke의 역사적 결과. | Not run | fresh checkout에서 start → seed → smoke를 기록한다; 검색 색인 fixture 제한도 분리 보고한다. |
| 10 | backend/frontend/docs/architecture/E2E required checks가 모두 green이다. | same-SHA push CI `32072235507` (`433ed1990fdded3551d8bc1070200607904a4ac7`)은 2026-08-17T22:00:00Z에 success로 완료됐다. | Passed (current) | 이 결과를 유지하고, gate 3·4·9의 current evidence도 갖춘다. |
| 11 | ExecPlan/load evidence는 실제 측정값과 `Not measured`를 구분한다. | completed ExecPlans는 과거 evidence를 보존하지만, current core-journey load measurement는 없다. | Not run | scenario·dataset·환경·p95/SQL/lock 결과 또는 명시적 `Not measured`를 current release record에 남긴다. |
| 12 | README의 구현 claims가 runtime source evidence와 일치한다. | 현재 README에는 이미 구현된 customer/merchant session route와 맞지 않는 과거 설명이 있다. 이 packet은 README 변경을 허용하지 않는다. | Blocked | source-backed README correction과 docs verification을 별도 owner가 완료한다. |

## Required evidence boundaries

- 이 Stage에서 current로 실행하는 검증은 문서 검증뿐이다. `RuntimeOpenApiParityTest`, Gradle build,
  frontend test, Storybook, demo, browser E2E, migration, and remote CI rerun are **not** run by this slice.
- `32072235507`은 exact source baseline의 remote success evidence다. 이 Stage 02 documentation commit은
  push하지 않았으므로 그 run에 포함되지 않았고, 그 성공은 full user journey의 missing/not-run gate를
  자동으로 해제하지 않는다.
- Gate 4 is intentionally fail-closed. Wallet selector UI가 아직 없더라도 backend query failure를 empty coupon
  response, fake client list 또는 client-side discount decision으로 바꾸지 않는다.

## Release decision

**Current decision: do not release.** Gate 4 and 12 are blocked; gates 3, 6, 9, and 11 are not run. Gate 10의
same-SHA CI success와 historical results remain useful regression context only.

Stage 02 documentation stack은 `main → feature/core-journey-02-contract`에서 시작했고, Stage 05 backend/API는
그 dependency와 lease checks 뒤 별도 Draft slice에서 구현됐다. 이 release gate 문서만으로 후속 stage를
시작해서는 안 된다.
