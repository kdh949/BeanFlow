# 운영형 선착순 쿠폰과 고객 이벤트 페이지를 수직 슬라이스로 제공한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `—`

## Purpose / Big Picture

운영자는 쿠폰 Campaign을 초안으로 만들고 배너를 등록해 발행·중단할 수 있다. 로그인 고객은 현재 열려
있는 이벤트를 보고 선착순 쿠폰을 즉시 내려받으며, 성공한 쿠폰은 기존 쿠폰함과 주문 경로에서 사용한다.
한도 초과·중복·외부 저장 실패는 성공이나 빈 화면으로 위장하지 않는다.

## Progress

- [x] 2026-09-02: latest `origin/main` `3ee8ffd`, Flyway V68, 다른 active migration writer 부재를 확인하고
  `feature/limited-coupon-events`가 repository-wide migration writer lease를 획득했다.
- [x] 2026-09-02: BR-53과 ADR-120을 기록하고 ADR-107을 supersede했다.
- [x] 2026-09-02: Slice 1의 V69 DB foundation, 운영 Campaign draft create/list/detail, 매장·메뉴
  picker, OpenAPI와 운영 화면을 구현했다. Testcontainers API·멱등성, Runtime OpenAPI parity,
  Spring Modulith, frontend unit/typecheck/product-copy, Storybook interaction/a11y를 통과했다.
- [x] 2026-09-02: Slice 2의 1200x450 Campaign banner 정규화·저장, immutable pointer commit,
  멱등 응답 저장(V70), publication, OpenAPI, 운영 화면과 canonical FileField를 구현했다. 외부 저장은 DB
  트랜잭션 밖에서 수행하고 한국어 변경 사유는 multipart 본문으로 전달한다.
- [ ] Slice 3: 로그인 고객 event campaign list.
- [ ] Slice 4: atomic limited coupon claim과 기존 CouponIssuance 연계.
- [ ] Slice 5: STOP, observability, full regression과 plan completion.

## Context and Orientation

Promotion은 `promotion_campaign`과 `promotion_coupon_issuance` 및 운영 Campaign 유스케이스를 소유한다.
Operations는 persistent grant와 Audit API를 제공하고 Promotion이 이를 사용한다. Merchant는 운영 picker에
매장·메뉴 read port를 제공한다. Media는 ADR-115 구현을 범용화하지만 Campaign pointer는 Promotion이 소유한다. 프런트엔드는
`CustomerShell`과 `ConsoleShell` 및 canonical Storybook component를 확장한다.

## Plan of Work

1. V69에 permission vocabulary, limited Campaign extension, counter, claim과 command tables를 additive하게
   추가하고 V70에서 command 최초 응답을 저장한다. 일반 Campaign은 backfill하지 않는다.
2. Operations draft API와 UI를 먼저 연결해 Campaign 조건을 생성·조회할 수 있게 한다.
3. Media API를 추출하고 Campaign banner PUT과 publish command를 연결한다.
4. 고객 event list와 banner presign을 추가한다.
5. Campaign root lock 아래 atomic claim과 STOP을 구현하고 concurrency/failure paths를 고정한다.

## Validation and Acceptance

각 slice는 새 테스트의 RED를 확인하고 focused test, Spotless, OpenAPI parity, frontend typecheck와 Storybook
MCP interaction/a11y를 통과한 뒤 독립 commit한다. 마지막에는 `./gradlew test`, `./gradlew build`,
`npm test`, `npm run check:design`, `npm run build`, docs verifier를 실행한다. 실행하지 못한 external AIStor
smoke와 부하는 Not run으로 분리한다.

## Idempotence and Recovery

모든 migration은 additive이며 적용된 migration을 수정하지 않는다. command replay는 최초 terminal response를
반환한다. 외부 image PUT 뒤 pointer commit이 실패하면 immutable object를 orphan sweep이 회수하며 pointer를
추정하지 않는다. claim transaction rollback은 counter·claim·issuance·response 전체를 원상 복구한다.

## Interfaces and Dependencies

새 production dependency는 없다. 공개 interface는 Operations campaign 관리 API, customer event list와 claim
API, Promotion Campaign command/query ports, Media storage/presign port다. API/DB 시각은 Instant, 화면은
Asia/Seoul을 사용한다.
