# Perf 배포 환경 부하 및 원인 분석 — 2026-09-07

공개 HTTPS 경로에서 9회 실행, 809 workflow와 2,686 HTTP 요청을 측정했다. 정상 결제 5 workflow/s의 실패 48건은 모두 주문 견적 충돌이었다. 낮은 지연과 충분한 CPU 여유만으로 주문 처리 용량을 판단하면 안 된다.

## 측정 조건

- 발생기: 별도 Mac의 k6 2.2.0, 공개 `https://beanflow.dhkim.cloud` 경유. TLS 검증 활성. 기본 curl의 CA 체인 오류는 기존 Homebrew OpenSSL CA bundle을 명시해 해결했고, k6 기본 TLS 검증도 통과했다.
- API: `beanflow-api:perf-observability-20260907`, image `sha256:94979cf47cb2354f7152f4330725b324188090977bf6a81be89eb7c01ae9ddd6`, 2 GiB 제한, JVM MaxRAMPercentage 70. PostgreSQL 1.5 GiB, Hikari max 10.
- `perf`, trace sampling 1.0, wall profile 10ms. 앱은 Doppler, 모니터링은 기존 env-file 설정을 사용한다.
- 데이터: 정상 가입·로그인한 합성 고객 20명, 합성 점주 1명, 매장 1개, 메뉴/공유 재고 1개, 미래 슬롯 4개. 재고 100,000개, 슬롯별 용량 100,000. 잠금 실험만 슬롯 1개로 제한했다. GLOBAL 정책은 기존 값을 사용했다.
- 매장 fixture는 기존 엔티티와 DB 제약을 확인한 단일 트랜잭션으로 추가했다. 최초 지역 코드 FK 오류는 전체 rollback됐고, 실제 지역 코드로 재실행했다. 인증 세션은 정상 로그인 API로 발급했다.
- 정상 결제는 견적→주문→결제 준비→승인까지다. 점주 접수·제조·픽업은 하지 않았으므로 기존 접수 만료·자동 거절·환불 worker도 시간 경과에 따라 실행됐다. 이후 실행은 이 배경 작업과 누적 데이터의 영향을 포함한다.
- Toss는 내부 계약 드라이버이며 실제 결제망 성능이 아니다. AIStor/Vault는 실제 배포 의존성을 사용하지만 이번 시나리오는 이미지 업로드나 암호화 처리량 측정이 아니다.
- 원본 manifest/summary/Prometheus/trace/span-profile 증거는 로컬 `/private/tmp/beanflow-load-20260907/runs/`에 있다. credential/fixture는 같은 상위 임시 디렉터리의 비공개 파일에만 있다. 임시 디렉터리는 장기 보관소가 아니다.

## 실행 결과

workflow는 HTTP 요청 수와 다르다. 숫자는 실행 종료의 native `summary.json` 기준이다. `Failed`는 현재 실행기의 검증 기준을 넘었다는 뜻이며, 승인된 서비스 SLO 판정은 아니다. 기본 HTTP p95 1초·실패율 1% 기준을 사용했고, 의도한 지연 실험은 timeout 10초, DB lock 25초로 따로 기록했다.

| 실행 ID | 부하 / 투입 시간 | 사전 VU / 상한 | 완료 / 실패 | HTTP p95 / p99 | 판정 |
|---|---|---:|---:|---:|---|
| [bf-0907-warmup-quote](http://172.16.16.18:3000/d/beanflow-performance-rca/beanflow-performance-rca?from=1788786166688&to=1788786257074&var-test_id=bf-0907-warmup-quote) | 1 workflow/s / 30s | 4 / 10 | 31 / 2 | 158.2 / 1656.8 ms | Failed |
| [bf-0907-normal-r1](http://172.16.16.18:3000/d/beanflow-performance-rca/beanflow-performance-rca?from=1788786248263&to=1788786398507&var-test_id=bf-0907-normal-r1) | 1 workflow/s / 90s | 4 / 10 | 90 / 2 | 84.3 / 207.1 ms | Failed |
| [bf-0907-contention-r5](http://172.16.16.18:3000/d/beanflow-performance-rca/beanflow-performance-rca?from=1788786398617&to=1788786548873&var-test_id=bf-0907-contention-r5) | 5 workflow/s / 90s | 10 / 20 | 450 / 48 | 71.4 / 126.6 ms | Failed |
| [bf-0907-board-v4](http://172.16.16.18:3000/d/beanflow-performance-rca/beanflow-performance-rca?from=1788786557744&to=1788786679803&var-test_id=bf-0907-board-v4) | 4 VU, 3초 polling / 60s | 4 / 4 | 80 / 0 | 126.3 / 872.8 ms | Passed |
| [bf-0907-decline-r1](http://172.16.16.18:3000/d/beanflow-performance-rca/beanflow-performance-rca?from=1788786642686&to=1788786733141&var-test_id=bf-0907-decline-r1) | 1 workflow/s / 30s | 4 / 10 | 31 / 3 | 173.6 / 478.1 ms | Failed |
| [bf-0907-timeout-r1](http://172.16.16.18:3000/d/beanflow-performance-rca/beanflow-performance-rca?from=1788786693773&to=1788786778160&var-test_id=bf-0907-timeout-r1) | 1 workflow/s / 15s | 12 / 20 | 16 / 0 | 8037.5 / 8053.0 ms | Passed |
| [bf-0907-unknown-r1](http://172.16.16.18:3000/d/beanflow-performance-rca/beanflow-performance-rca?from=1788786739001&to=1788786805381&var-test_id=bf-0907-unknown-r1) | 1 workflow/s / 5s | 4 / 10 | 6 / 0 | 56.1 / 123.8 ms | Passed |
| [bf-0907-slot-lock-r1](http://172.16.16.18:3000/d/beanflow-performance-rca/beanflow-performance-rca?from=1788786827791&to=1788786948056&var-test_id=bf-0907-slot-lock-r1) | 1 workflow/s / 60s | 16 / 20 | 59 / 15 | 11827.7 / 16510.2 ms | Failed |
| [bf-0907-recovery-idempotency](http://172.16.16.18:3000/d/beanflow-performance-rca/beanflow-performance-rca?from=1788786975193&to=1788787080579&var-test_id=bf-0907-recovery-idempotency) | 1 workflow/s / 45s | 4 / 10 | 46 / 0 | 146.2 / 282.3 ms | Passed |

- 정상 1/s: 88/90 승인, 2건 주문 409. 정상 5/s: 402/450 승인, 48건 주문 409. 5/s 실행의 실제 승인량은 약 4.47/s이다. 10/s 증량은 충돌률 10.7% 때문에 수행하지 않았다.
- 점주 board: 80건 전부 성공, HTTP 200 56건과 304 24건. workflow p95 3,131ms에는 의도한 3초 sleep이 들어 있다.
- decline: 결제까지 도달한 28건은 모두 기대한 422/PAYMENT_DECLINED. 전체 실패 3건은 결제 전 주문 409다. 정상 거절을 승인 실패나 HTTP 장애와 혼동하지 않는다.
- timeout: 16건 모두 확인 시 202/UNKNOWN 계열, 실행 내 follow-up까지 기대 상태를 만족했다. 실행 중 승인 counter는 0이지만, 이후 DB 조회에서 16건 모두 APPROVED로 reconciliation된 것을 확인했다.
- unknown: 6건 모두 기대한 결과불명 응답. 후속 DB 조회 시 6건 UNKNOWN이며 최종 MANUAL_REVIEW 수렴은 아직 검증하지 않았다. 드라이버가 계속 IN_PROGRESS를 반환하도록 의도한 실험이다.
- slot-lock: 18초 동안 합성 슬롯 한 행을 SELECT FOR UPDATE로 잠근 후 ROLLBACK했다. 59개 workflow 중 15개 stale, 1개 dropped iteration. 그 외 실행의 dropped는 0이다.
- 회복/멱등성: 46건 모두 정상 생성 및 동일 key replay 성공. DB의 완료 멱등 레코드 46개, distinct order 46개를 확인했다.

## 원인과 대응

### 1. 공유 재고 변경이 견적을 무효화한다 — 직접 재현

같은 메뉴·슬롯의 견적 두 개를 먼저 받은 뒤 주문을 순서대로 제출했다. 첫 요청은 201, 다음 요청은 409 ORDER_QUOTE_STALE이었다. 오류의 currentQuote와 원래 quote는 표시 금액과 메뉴 구성이 같았다. `OrderQuoteCoordinator.kt`의 fingerprint는 슬롯 reserved/confirmed count 및 version, 재고 available/reserved/confirmed quantity 및 version을 포함한다. 다른 주문이나 만료·거절에 따른 자원 회수도 이를 바꾼다. 슬롯을 나눠도 재고 하나를 공유하면 충돌이 남을 수 있다.

5/s 구간 API CPU 최대 0.82 core, working set 최대 약 1.16 GiB/2 GiB, Hikari active 최대 3, pending 0, 관측된 미승인 DB lock 0이었다. 10초/15초 scrape에서 관측된 값이므로 짧은 미관측 대기까지 없었다는 주장은 하지 않는다. 그럼에도 이번 48건 실패가 견적 충돌이라는 응답·metric 증거는 명확하다.

**우선 대응:** [ADR-116](../adr/ADR-116-non-reserving-order-quote.md)을 먼저 검토해 고객이 확인한 거래 의미와 순간적인 자원 점유 변화를 구분하는 fingerprint 계약을 설계한다. 가격·메뉴·benefit provenance·픽업 window 검증과 최종 트랜잭션의 원자적 재고/용량 검증은 유지해야 한다. 변경한다면 fingerprint version, 동시 주문/마지막 재고/정책 변경 테스트를 함께 갱신해야 한다. 현재 ADR을 유지하는 동안에는 재조회·고객 재확인·새 멱등 키가 필요하며 무조건 자동 재시도로 성공 처리하면 안 된다. 이번 작업에서는 제품 정책을 변경하지 않았다.

### 2. 외부 결제 지연은 연결 풀 포화와 다른 현상이다 — exact trace/profile

`d30aaae857cd9d0a49bcb9c7d36cccc3`: 전체 8,069.79ms 중 `beanflow.toss.confirm` 8,013.26ms. 같은 root span의 `pyroscope.profile.id=2d5dbe8a563a96ac`로 조회한 wall profile은 8.05초이며, 7.98초가 `TossOneTimePaymentGateway` → `CompletableFuture.get` → `LockSupport.park` 경로에 있다. DB pending은 0이고 active 최대 1이었다. 드라이버의 9초 응답 지연에 대해 앱이 약 8초에 결과불명으로 응답하는 의도된 현상이다.

**대응:** timeout을 승인 실패로 단정하지 않고 기존 UNKNOWN/reconciliation을 유지한다. 연결 풀 증설로 해결할 문제가 아니다. 실제 결제망의 지연 분포와 사용자 대기 예산을 따로 측정한 뒤 deadline·화면 안내를 검토한다. UNKNOWN의 age와 최종 해소 상태를 계속 관측해야 한다.

### 3. DB 행 잠금은 낮은 CPU에서도 서비스 대기를 만든다 — 제어 실험

잠금 주입은 KST 22:14:04 부근 시작, 22:14:23 부근 rollback 완료했다. Prometheus에서 Hikari active 10/max 10, pending 5, PostgreSQL tuple wait 8 및 transactionid wait 1이 관측됐다. 직접 DB 표본에서는 tuple wait가 9까지 보였다. `8a99e7a9557568369e87af8c3a727169` trace의 17,638.01ms 중 17,557.22ms가 `PickupSlotJpaRepository.findLockedById`였다. 같은 span profile `413376c5e0b7019a`도 17.55초의 DB 응답 대기를 보여 준다.

**대응:** 실제 사고에서는 blocker와 잠금 보유 트랜잭션을 먼저 찾고 길이를 줄인다. PK 조회 인덱스나 pool 크기만 바꾸어 이미 보유된 행 잠금을 없앨 수는 없다. lock/transaction timeout을 도입한다면 typed failure·rollback·멱등성 정책을 함께 검증해야 한다. 이번 장시간 잠금은 인위적으로 주입한 것이며 정상 구현에서 18초 잠금이 발생했다는 주장은 아니다.

발생기의 1개 dropped는 이 실행에서 목표 부하 하나를 시작하지 못했다는 의미다. 긴 workflow에 필요한 동시 VU가 늘었으며 사전 16/상한 20 설정의 동적 할당 여유도 고려해야 한다. 재실험에서는 18초×1/s에 여유를 둔 사전 24 VU 정도를 검토할 수 있지만, 이 증설 효과는 아직 측정하지 않았다. VU 증설로 DB 잠금이나 stale 정책이 해결되지는 않는다.

### 4. 첫 board 응답은 JSON serializer 초기화 비용이 컸다 — 표본 근거

board 첫 네 요청은 약 0.85초이고 나머지는 대부분 짧았다. trace `8448a8eedeb8809a7fa312158b8795f5`의 주요 개별 DB span은 수 ms다. 같은 span profile `ca82c5a87e4dce54`에서 약 0.46초가 Jackson 직렬화, 그 중 약 0.42초가 serializer 탐색/생성 경로이며 Kotlin creator introspection이 포함된다. 이는 이 표본의 초기 직렬화 비용 근거이며 지속적인 DB 병목 증거는 아니다.

**대응:** 배포 후 대표 읽기 응답을 포함한 예열 단계와 정상 구간을 나눠 측정한다. 실제 매장별 backlog와 동시 state transition을 포함한 더 큰 데이터셋에서 재검증한 뒤 직렬화나 query 최적화를 선택한다. 이번 3초 polling 주기는 변경하지 않았다.

## Grafana 보완과 검증

- 추가: 주문 예약 충돌 사유 패널(149), 선택 실행의 200ms 이상 trace 목록(150). 기존 전체 slow trace도 유지한다.
- 수정: Tempo 검색 결과는 table frame이므로 기존 traces 시각화 대신 table을 사용했다. 브라우저에서 8초 confirmation trace들이 실제 목록으로 보이는 것을 확인했다.
- 수정: Hikari 패널(4)은 구간 최대값과 10초 최소 간격을 사용한다. 기존 15초 query step은 10초 scrape의 짧은 포화를 놓쳤다. 수정 후 브라우저에서 active 10/pending 5 피크와 회복을 확인했다. 두 최대값은 구간 내 서로 다른 순간일 수 있음을 설명에 명시했다.
- PromQL 84개를 실제 서버에서 조회해 모두 성공했고, 잠금 구간에서 82개에 데이터가 있었다. 나머지를 0/성공으로 대체하지 않는다. dashboard contract와 DB wait의 7개 가용성 사례 검증도 통과했다.
- 9개 실행 모두 k6 remote-write series ingest와 Grafana annotation 게시를 확인했다. 짧은 실행은 마지막 표본과 native summary의 합계가 다를 수 있어 최종 건수는 summary를 사용했다.
- 정확한 span ID로 Pyroscope profile을 조회했으며, 단순히 같은 시간의 전체 JVM profile과 구분했다. exemplar 클릭부터 동일 trace log까지 UI 전체 이동은 이번 검증 범위에 포함하지 않았다.
- 전체 wall flame graph는 모든 스레드의 대기 포함 시간을 합한다. 브라우저에서 약 2분 구간에 누적 3.07시간이 표시된 것은 이 집계 의미이며 CPU를 3시간 소비했다는 뜻이 아니다. 상단 libc/park 항목만으로 병목을 단정하지 않고 위 exact span profile로 요청 경로를 확인했다.
- 중앙 dashboard provisioning 파일을 백업 후 반영했고 datasource 설정은 재적용하지 않았다. 백업: 모니터링 서버 `/home/kdh949/beanflow-dashboard-before-load-analysis-1788786582.json`.

## 종료 상태와 해석 한계

부하 발생과 잠금 주입은 종료했다. API/DB/frontend/driver는 healthy이고 BeanFlow 수집 대상 5개는 UP이다. DB lock wait 0, 회복 실행 pending 0, 멱등성 46건 성공을 확인했다. 앱 파일시스템은 23 GiB 중 81% 사용, 잔여 약 4.3 GiB였다. 장시간 soak 전에는 디스크 용량 수집·경보와 로그/trace 보관량을 점검해야 한다.

합성 데이터와 결과불명 결제 6건은 진단 증거로 남겨 두었다. 실제 트래픽, 전체 사용자 경험, 여러 매장·대규모 backlog, WAF 한계, 장시간 안정성, 실결제망 capacity 또는 성능 개선을 증명한 결과가 아니다. 1/s와 5/s 실행은 데이터 누적·예열·배경 worker 조건이 달라 엄격한 성능 개선 비교로 사용할 수 없다.

- Passed: 부하 실행/증거 수집, 정상 거절·UNKNOWN 의미 검증, timeout 후 APPROVED 16건, 회복/멱등성, dashboard 실제 배포 및 브라우저 검증.
- Failed: 정상 주문의 stale 허용률 기준, 의도한 DB lock 실행의 오류율·dropped 기준. 숨기거나 threshold를 낮춰 정상 처리량으로 재분류하지 않았다.
- Pending: 지속 UNKNOWN 6건의 최종 MANUAL_REVIEW 경로 확인, ADR-116 개선 결정 및 동일 조건 재측정.
- Not run: 10/s 이상 증량, stress/soak/capacity, 실제 PG 결제, 여러 매장 workload, 정책 변경 후 성능 재측정.

참고: [k6 arrival-rate VU allocation](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/arrival-rate-vu-allocation/)은 iteration 소요 시간과 목표 rate에 따른 VU 산정을 설명한다. [Pyroscope Server API](https://grafana.com/docs/pyroscope/latest/reference-server-api/)의 spanSelector를 사용해 해당 span의 profile을 별도로 검증했다.
