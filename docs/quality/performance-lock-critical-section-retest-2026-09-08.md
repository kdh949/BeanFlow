# 주문 잠금 구간 SQL 및 환불 드라이버 개선 검증

## 상태와 범위

주요 구현 commit은 `ad449db28df64c8c9c51e897ca1524964a464f01`, bootstrap/보관 한도 보완을 포함한
배포 후보는 `686ba07885ac0f7fece39da6a3569208c6bf1094`이다. 현재 로컬 회귀와 SQL 실험을
완료했고 전체 CI 및 이미지 workflow가 통과했다. API/driver 배포와 재측정 및 Grafana 캡처를
완료했다. 카운터 비용은 감소했지만 통합 HTTP 처리량 개선은 입증되지 않았다.
실행 계획: [잠금 구간 SQL 개선](../exec-plans/completed/order-lock-critical-section-sql.md).

## 원인과 변경

견적 v3의 사용량 무효화 해결 뒤 관측한 stock/slot lock wait는 실제 DB 대기였다. 다만 대기 요청의
trace만으로 특정 SQL이 유일한 원인이라고 할 수 없다. 추가 실험에서 다음을 구분했다.

1. **잠금 보유 중 불필요한 SQL:** 픽업번호 발급이 매 주문마다 과거 주문/slot의 count/max를
   집계했다. 정상 발급은 기존 counter의 원자 UPDATE RETURNING으로 바꾸고, missing counter에만
   기존 baseline UPSERT를 실행한다. 새 AuditRecord의 UUID는 이미 정해져 있어 merge가 존재 SELECT를
   실행했다. 새 record 전용 persist/flush repository로 바꾸고 같은 transaction과 실패 변환을 유지한다.
2. **자동 거절 및 환불 background:** 승인 후 점주 접수가 없으면 3분 뒤 자동 거절·환불·자원 복구가
   시작된다. 앞선 테스트가 끝났어도 다음 테스트의 자원 사용량에 영향을 준다.
3. **perf driver의 환불 계약 오류:** 앱의 `refund:rejection:<eventId>`를 driver의 key 정규식이
   거절해 `TOSS_CANCEL_INVALID_REQUEST`를 반환했다. 이후 cancels가 없어 조회도
   `TOSS_REFUND_LOOKUP_AMBIGUOUS`로 남았다. driver는 최대 300자 printable key를 수용하고,
   같은 취소 key/payload의 최초 응답 재생, 다른 payload 충돌, 확인 재시도 시 취소 이력 보존,
   남은 금액 검증과 결제별 유일한 환불 reference를 구현했다.

Toss 멱등키의 길이 계약은 [공식 헤더 문서](https://docs.tosspayments.com/reference/using-api/authorization)에
근거한다. Entity 상태 판단과 persist/merge의 차이는
[Spring Data JPA 문서](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html)를 참고했다.
실제 외부 Toss에 결제나 환불을 요청한 결과가 아니라 명시적 perf driver에서 검증한 결과다.

## SQL 비용 검증

실제 테스트 DB에서 동일 매장/영업일의 기존 SQL과 새 SQL을 번갈아 5회씩
`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 실행했다. 각 실행은 transaction에서 rollback해
번호 증가를 남기지 않았다. 첫 실행은 캐시 준비 효과가 있어 별도 보존하고 나머지 4회 중앙값을 비교했다.

| 항목 | 기존 이력 집계 UPSERT | 새 counter UPDATE |
|---|---:|---:|
| 첫 실행 시간 | 57.161ms | 1.266ms |
| 나머지 4회 실행시간 중앙값 | 4.834ms | 0.036ms |
| 이후 실행의 root plan shared hit blocks | 345 | 3 |

이는 카운터 SQL 한 문장의 실행시간 비교다. HTTP latency, 전체 transaction 또는 처리 용량의
동일 비율 개선을 의미하지 않는다. 새 audit 3개를 더 append하는 회귀 테스트에서는 SQL 증가량이
기존 6개에서 3개로 줄었다. 실제 저장 수, 중복 batch rollback 및 retention/PII 규칙도 검증했다.

## 개선 전 실제 부하

모든 실행은 public HTTPS → WAF → API → 동일 PostgreSQL/내부 Toss driver 경로다. 합성 고객 20명,
같은 매장·메뉴·공유 stock 하나와 미래 pickup slot 네 개, 수량 1, 포인트/쿠폰 없음,
quote → order → payment attempt → confirmation의 HTTP 4회 workflow를 사용한다.
부하 fixture/dataset과 API 2 GiB, PostgreSQL 1.5 GiB, Hikari 10, trace sampling 1.0,
wall profile 10ms를 고정했다. local compile/test와 실제 부하를 겹치지 않았다.

| 실행 ID | 목표/기간 | pre/max VU | 승인/완료 | dropped | HTTP p95/p99 | workflow p95 |
|---|---|---|---|---:|---|---:|
| `bf-0908-locks-before-warm` | 5/s, 30s | 20/20 | 146/146 | 5 | 1026.1/1904.0ms | 3996.2ms |
| `bf-0908-locks-before-r5` | 5/s, 60s | 40/40 | 301/301 | 0 | 129.1/1194.7ms | 372.0ms |
| `bf-0908-locks-before-r20` | 20/s, 90s | 80/80 | 1659/1659 | 142 | 1744.4/2575.5ms | 6362.9ms |
| `bf-0908-locks-isolated-before-r20` | 20/s, 90s | 80/80 | 1718/1718 | 83 | 1338.6/2418.1ms | 5625.8ms |

실행한 workflow의 실패율은 모두 0이지만 warmup 및 두 20/s 실행은 dropped 또는 latency 기준에
실패했다. 성공한 결제만으로 목표 부하가 처리됐다고 주장하지 않는다.

`isolated`라는 실행 이름은 당시 PAID/event backlog가 비었음을 보고 붙인 이름이다.
뒤이어 refund UNKNOWN이 남아 계속 재시도됨을 확인했으므로 완전한 격리 실행이 아니다.
이 이름을 비교 가능성의 증거로 사용하지 않는다.

20/s 첫 실행의 Hikari pending 관측 최대 82, 미완료 event 최대 137, ungranted lock 최대 5,
host CPU 최대 82.0%, iowait 최대 20.1%였다. 두 번째 20/s는 pending 76, event backlog 0,
lock 최대 5였다. event backlog 0이 모든 비동기 작업의 완료를 뜻하지 않는 사례다.
이 최대값은 해당 실행 범위의 Prometheus 표본이며 모든 순간의 정확한 peak 보장은 아니다.

예시 trace `cf41c1a71e47eaeba646dfa7bb73b75`의 총 시간은 2171.8ms다.
첫 DB span이 요청 시작 851.8ms 뒤 나타나며, 실제 pickup/stock SELECT는 각각 133.4/45.8ms다.
repository span의 시간 전체를 행 잠금 대기로 간주하지 않는다. SQL 사이의 pool 획득 대기와
DB 잠금 대기를 함께 봐야 한다.

2026-09-07 18:07:31 UTC의 read-only repeatable-read 검증에서 위 본 실행 세 개의 native 생성·승인
수와 DB distinct order 수가 일치했다. stock/slot counter 불일치, 정원 초과, 중복 pickup 번호,
당시 lock waiter는 모두 0이었다. 세 실행의 3,678건은 자동 거절 후 환불 UNKNOWN으로 남아 있었으며,
이것을 환불 완료로 기록하지 않는다.

## Grafana

기존 63개 패널을 그대로 둔 채 다음 두 패널을 durable provisioning에 추가하고 브라우저에서 확인했다.

- 151 `환불 요청 / 조회 결과`: mode/outcome별 시도율. 누적 상태 건수와 구분한다.
- 152 `픽업번호 할당 지연`: allocation timer의 평균/p95. 전체 잠금 보유 시간과 구분한다.

모니터링 설정 백업: `/home/kdh949/beanflow-locks-dashboard-backup-20260907T175841Z.json`.
대시보드는 [BeanFlow Performance RCA](http://172.16.16.18:3000/d/beanflow-performance-rca/beanflow-performance-rca)다.

## 검증과 배포

- Passed: 기존 코드에서 성능 경로 회귀 2건 실패를 확인한 뒤 수정 후 14개 class/110개 test 통과,
  failure/error/skipped 0. 최초 동시 20건, missing counter legacy count/max, rollback,
  감사 duplicate batch rollback, 견적/주문/결제/구조 관련 검증을 포함한다.
- Passed: perf driver 10개 test, bootstrap/audit 관련 추가 23개 test, observability contract,
  bootJar, 문서/OpenAPI 검증.
- 초기 환경 실패: 공유 build 산출물에서 JPA managed type 인식 오류와 동시 생성으로 clean 실패.
  별도 checkout의 Java 21/PostgreSQL Testcontainers 실행으로 실제 회귀와 수정 결과를 확인했다.
- 초기 CI/이미지 실패: 두 독립 bootstrap에 새 감사 writer import가 누락되어 일반 API와 달리
  시작하지 못했다. 패키지 검증에서 발견했으며 실패 이미지는 배포하지 않았다. 두 구성과 실제
  bootstrap append 테스트를 보완했다.
- Passed: `686ba078`의 [전체 CI](https://github.com/kdh949/BeanFlow/actions/runs/34151795027) 및
  [이미지 build](https://github.com/kdh949/BeanFlow/actions/runs/34151796891). 패키지 portfolio/perf 기동,
  필수 설정 누락 실패, Vault TLS/AppRole, cursor HMAC와 native profiler 초기화를 포함한다.
- Passed: 서버 pull 이미지 revision/digest와 8-file 배포 bundle checksum 검증.
  digest: `sha256:46f2167d5885596cf358bd1e962e6d22edc13660415ffedd5e1f8d7cffb2f576`.
- Passed: 사용자 sudo 적용 뒤 API immutable revision/health와 DB 및 다른 의존성 identity 유지 확인.
  배포 백업은 `/var/backups/beanflow-locks-20260907T185412Z`다. 신규 3,788건의 환불/보상/자원 복구를 확인했다.

## 해석의 한계

- 누적 DB 이력과 오래된 UNKNOWN/MANUAL_REVIEW, background 재시도는 보존한다. 이를 SQL로 성공
  상태로 바꾸지 않는다. 전후 background 양이 다르면 순수 코드의 latency 개선율로 해석하지 않는다.
- 기존 driver는 10,000개를 넘으면 오래된 합성 결제 사실을 삭제했다. 보완 후 최대 50,000개에서
  신규 승인을 503으로 거절하며 기존 사실을 보존한다. 교체 전 API를 정지하고 기존 driver 응답을
  백업·복원·조회 검증하는 절차를 준비했다. process 재시작은 여전히 명시적 상태 보존이 필요하며
  합성 payment의 lookup을 실제 외부 Provider 사실로 취급하지 않는다.
- 긴 steady-state 시험과 실물 Toss·다중 매장·쿠폰/포인트 조합의 용량은 별도 검증 대상이다.

## 배포 후 측정과 판정

| 실행 ID | 조건 | 승인 | dropped | HTTP p95/p99(ms) | workflow p95(ms) |
|---|---|---:|---:|---:|---:|
| bf-0908-locks-after-r5 | 5/s,60s,40/40VU | 301 | 0 | 529.6/1516.0 | 2695.0 |
| bf-0908-locks-after-r20 | 20/s,90s,80/80VU | 1698 | 102 | 1482.0/2424.1 | 5366.8 |
| bf-0908-locks-after-r20-repeat | 20/s,90s,80/80VU | 1648 | 152 | 1772.4/2812.5 | 6856.4 |

시작한 workflow의 실패율은 모두 0이다. 5/s는 threshold Passed, 두 20/s는 Failed다.
추가 예열 5/s30s40/40VU는 승인141, dropped10, HTTP p95 2756.0ms로 Failed였다.
5/s 본 실행은 예열의 자동 거절/환불이 끝부분에 겹쳤고, 반복20/s는 앞선20/s 환불과 겹쳤다.
이전 UNKNOWN8270건이 MANUAL_REVIEW로 이행했고 신규 환불 이후 금융 publication이 발생해
수정 하나만 교체한 A/B로 해석하지 않는다. 전체 HTTP 성능/처리량 향상은 입증되지 않았다.

같은 90s 카운터 timer sum/count 증가량 평균은 5.142 → 0.388ms였다. 이 구간 개선은
개별 SQL 비교와 일치하지만 Hikari pending77, ungranted lock5가 여전히 관측됐다.
대표 승인 trace `ed587e1bae7562e6a80a1218b2db45e`는 1582ms 중 pickup SELECT가 341ms였다.

2026-09-07 19:26:48 UTC 신규3788건의 환불·보상·정원 복구가 SUCCEEDED였다.
별도 금융 event publication7576건은 미완료다. 드라이버 전체12058건 GET 검증에서
존재12058, 누락0, DONE8270/CANCELED3788, 취소기록3788을 확인했다. 50000건 실제 생성은 Not run이다.

관련 PR은 [기반134](https://github.com/kdh949/BeanFlow/pull/134),
[카운터135](https://github.com/kdh949/BeanFlow/pull/135),
[견적136](https://github.com/kdh949/BeanFlow/pull/136),
[감사137](https://github.com/kdh949/BeanFlow/pull/137),
[환불138](https://github.com/kdh949/BeanFlow/pull/138),
[패널139](https://github.com/kdh949/BeanFlow/pull/139),
[이력보존140](https://github.com/kdh949/BeanFlow/pull/140)이다.
각 PR에 관련 코드/테스트/ADR/실제 Grafana 캡처 및 측정 한계를 포함했다. 이 측정 당시에는 원격 병합을 수행하지 않았다.

## 환불 이후 남은 이벤트의 상세 관측

2026-09-07 19:41:33 UTC read-only repeatable-read 집계에서 미완료 publication은 다음과 같다.

| listener | disposition | attempts | 건수 |
|---|---|---:|---:|
| beanflow.analytics.payment-refunded-v1 | PRE_ACCEPTANCE_CANCELLATION | 0 | 3788 |
| beanflow.settlement.payment-refunded-v1 | PRE_ACCEPTANCE_CANCELLATION | 0 | 3738 |
| beanflow.settlement.payment-refunded-v1 | PRE_ACCEPTANCE_CANCELLATION | 6 | 50 |

같은 시점 Prometheus의 `beanflow_settlement_refund_exclusion_conflict_count_total`은
`reason=ORDER_NOT_CANCELLED` 누적 300회였다. 고유 고객/주문 수가 아니라 충돌 시도 횟수다.
`RejectionRefundService`가 호출하는 `PaymentRefundEventProducer.publishPreAcceptance`는
`PRE_ACCEPTANCE_CANCELLATION`을 발행하지만 정산의 `CustomerCancellationRefundExclusionService`는
고객 요청으로 `CANCELLED`된 주문만 수용한다. 자동 거절 `REJECTED`가 이 분기로 들어가는 계약
차이가 정산 충돌을 설명한다. 고객 취소 검증을 완화하지 않고 자동 거절의 정산 disposition을
별도 정책·회귀 테스트와 함께 정리해야 한다. analytics target은 현재 recovery filter의 예약 제외 대상이다.

정산의 3738건이 아직 0회인 점은 batch/filter 및 재시도 소진 publication 처리 순서 검증 대상이다.
이 snapshot만으로 starvation을 확정하지 않는다. 상세 집계와 소스 근거는 PR138에 기록했다.

## PR 캡처의 저장소 검증

초기 PR CI는 PNG가 기존 바이너리 허용 목록에 없어 `LocalDemoRepositorySafetyTest`에서 실패했다.
검토한 캡처14개의 정확한 경로만 등록하고 비밀 패턴 검사 및 다른 NUL 파일 차단을 유지했다.
모든 PR을 통합한 임시 트리의 safety test5개와 문서/OpenAPI 검증이 통과했다.

PR140의 첫 CI `test (2/6)`은 296개 중 기존 `StoreOrderLifecycleIntegrationTest` 1건이
`EXCLUDED_BEFORE_ACCRUAL` 대신 `PENDING`을 읽어 실패했다. 해당 PR은 JVM 구현/테스트를 바꾸지 않는다.
테스트가 기다리는 loyalty 결과는 `REQUIRES_NEW`에서 먼저 commit되지만 payment 복구 상태는
바깥 coordinator transaction에 있어 두 완료 시점 사이에 대기 공백이 있다. 같은 head의 해당
클래스는 로컬 Java21/PostgreSQL Testcontainers에서 13개 통과, failure/error/skipped0이었다.
실패한 원격 shard와 종속 gate를 같은 SHA로 한 번 재실행했으며 첫 실패와 진단을 PR140에 기록했다.

## 병합 전 원격 검증 기록

2026-09-08 05:35 KST 이후 각 PR의 head SHA와 CI workflow head SHA를 대조했다.
PR134~140 모두 최신 CI가 terminal SUCCESS이며 현재 base에서 MERGEABLE/CLEAN이다.
PR140의 두 번째 실행은 같은 SHA의 실패 shard와 종속 gate만 재실행한 결과다.
부모 PR이 main에 병합되면 자식 PR의 base를 main으로 변경하고 CI/충돌을 다시 확인한다.
원격 main은 `958339054d7c5badb2b12731143333e84541605a`이며 실제 PR 병합은 수행하지 않았다.

| PR | head | CI |
|---|---|---|
| 134 | d01fa5aab5ced2660f38d66d90dbc410b8a02ec6 | [SUCCESS](https://github.com/kdh949/BeanFlow/actions/runs/34154913276) |
| 135 | 1caddb41c29fbd5a1412a8a62912acddaaced34f | [SUCCESS](https://github.com/kdh949/BeanFlow/actions/runs/34156934534) |
| 136 | f48ef19d23ab33f3cb02b666d3dec73b83f2bffe | [SUCCESS](https://github.com/kdh949/BeanFlow/actions/runs/34156937912) |
| 137 | 444875da26f69a7a3c3c2bb77d376bad6fd79514 | [SUCCESS](https://github.com/kdh949/BeanFlow/actions/runs/34156938985) |
| 138 | e523c34293bd960e6e6535690f2dc1e15f918d8a | [SUCCESS](https://github.com/kdh949/BeanFlow/actions/runs/34156941096) |
| 139 | b944133a81cb3c91258f019debec0545a830b9b0 | [SUCCESS](https://github.com/kdh949/BeanFlow/actions/runs/34156941949) |
| 140 | 7df008ae3059c4b141127a34c652e9f0d8f02eb4 | [SUCCESS, attempt 2](https://github.com/kdh949/BeanFlow/actions/runs/34156943781) |
통합 runtime source는 배포한686ba078과 일치하며, Grafana112의0~100% 고정 축만 추가 변경됐다.

## 후속 main 반영 확인

2026-09-08 후속 확인에서 PR134~140은 모두 병합됐다. main `8038e71`의
[CI](https://github.com/kdh949/BeanFlow/actions/runs/34217739616),
[CodeQL](https://github.com/kdh949/BeanFlow/actions/runs/34217738828),
[이미지 build](https://github.com/kdh949/BeanFlow/actions/runs/34217739663)가 모두 SUCCESS다.
main의 `src/main`과 배포 기준 `686ba078`의 제품 소스는 동일하다.
RCA 무관측 지연 쿼리와 드라이버 멱등키 테스트의 후속 보강, PR별 캡처·검증 문서는 main의 값을 유지한다.
이 확인에서 서버를 다시 배포하거나 부하·복구를 다시 측정하지 않았다. 위 금융 publication snapshot과
실패한 20/s 실행을 해소된 것으로 해석하지 않는다.
