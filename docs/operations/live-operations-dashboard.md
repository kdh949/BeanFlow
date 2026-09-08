# BeanFlow 현재 상태 모니터링

`BeanFlow / Live Operations`는 실행 ID 없이 현재 상태를 확인하는 Grafana 대시보드다.
기본값은 **최근 15분, 10초 자동 갱신**이다. 부하 실행 결과와 비교는 기존 Performance RCA에서 확인한다.

## 화면 사용

1. 환경과 호스트를 고른다. 현재 수집 환경은 `perf`다. HTTP Route는 HTTP 요청·지연·오류 패널에만 적용된다.
2. 상단에서 API 수집, 요청/초, 5xx 비율, p95, connection 대기, 미획득 DB 잠금을 확인한다.
3. 아래에서 HTTP 상태·Route 지연, pool·DB wait, JVM·호스트·컨테이너, 외부 호출을 비교한다.
4. 데이터가 없거나 오래된 것 같으면 맨 아래 target 수집 상태·scrape 경과 시간·exporter 내부 상태를 확인한다.
5. 이상 시간대를 선택하고 상단 `같은 시간의 Performance RCA`를 연다. RCA는 서버 전체 범위를 조회하므로
   환경·호스트가 여러 개면 대상 범위를 다시 확인한다. trace·log·profile 링크는 기존 datasource 설정을 따른다.

기간을 과거로 고정하면 상단 값도 그 종료 시점의 값이 된다. 현재 상태로 돌아오려면 시간 선택에서
`Last 15 minutes`와 `10s` 갱신을 선택한다. Grafana Live streaming이 아닌 Prometheus polling이다.

## 수치 해석

| 표시 | 의미와 한계 |
|---|---|
| API 수집 중 | management endpoint scrape 성공. 외부 WAF, readiness, 로그인·결제 성공의 증거는 아님 |
| 현재 요청/초 0 | 실제로 존재하는 요청 counter가 최근 rate 구간에서 증가하지 않음 |
| 오류율 값 없음 | 요청 분모가 0이거나 수집을 확인할 수 없음. 0% 성공으로 바꾸지 않음 |
| p95 계산할 표본 없음 | 최근 요청이 없어 percentile을 계산할 수 없거나 수집 불가 |
| DB 대기 0 | exporter up, pg_up, query scrape 성공과 신선도가 확인된 빈 결과 |
| 충돌·외부 호출 데이터 없음 | counter 미생성 또는 수집 불가. 호출·충돌 0건으로 추정하지 않음 |
| 컨테이너 실행 중 | Docker state=running. healthcheck·기대 서비스 목록의 완전성은 별도 확인 |
| 마지막 worker 관측값 | worker 마지막 갱신 gauge. 현재 DB 직접 조회 또는 worker 정상의 증거가 아님 |

API/DB/node는 10초, Alloy/container는 15초 scrape다. 각 쿼리의 Min step도 이를 반영하며
rate/histogram은 `$__rate_interval`을 사용한다. 따라서 화면은 10초마다 갱신돼도 요청률·지연은
최근 최소 40초, 화면 기간과 해상도에 따라 더 긴 구간의 분포다. 이는
[Grafana rate interval 계산](https://grafana.com/docs/grafana/latest/datasources/prometheus/template-variables/#use-__rate_interval)을 따른다.

현재 값은 instant query로 읽는다. API/DB/node의 마지막 scrape가 30초, Alloy/container가 45초 이상
되면 관련 수치를 숨긴다. 상태 카드는 `중단 / 지연`, target 자체가 없으면 `데이터 없음`이다.
이 시간은 scrape 주기의 세 배인 표본 유효성 기준이며 서비스 SLO나 새 경보 임계값이 아니다.
DB는 `pg_up=1`, `pg_exporter_last_scrape_error=0`도 확인한다. container는
`beanflow_container_collection_success=1`과 마지막 성공 시각 45초 미만도 확인한다.
그래프의 빈 구간을 선으로 연결하거나 이전 정상값을 현재값으로 유지하지 않는다.

worker gauge에는 자체 마지막 성공 시각이 없다. publication은 기본 10초, 결제 UNKNOWN 나이·멱등
장기 대기·예약 후보 수는 기본 30초 주기로 갱신되나 실패 뒤 이전 값이 남을 수 있다.
예약 후보 수는 기본 최대 100건인 **마지막 batch 후보 수**이고 전체 backlog가 아니다.
미완료 publication에는 예약된 미활성 target도 포함될 수 있다. 이 패널의 0은 정상 판정으로 쓰지 않으며
RCA의 scheduled task 오류 로그와 함께 확인한다.

호스트는 다른 workload도 포함한다. 디스크 I/O는 busy 시간 비중이며 디스크 잔여 용량이 아니다.
컨테이너 CPU는 core 단위이며 제한 0은 무제한이므로 제한선을 그리지 않는다. 선택형 Docker stats
수집기가 없으면 관련 패널을 `데이터 없음`으로 남긴다. cAdvisor를 자동 활성화하지 않는다.

## 설치

JSON: [beanflow-live-operations.json](../../infra/observability/central/grafana/dashboards/beanflow-live-operations.json)

- Grafana UI: Dashboards → New → Import → JSON 업로드 → BeanFlow 폴더 → Import.
- UID: `beanflow-live-operations`. 기존 `beanflow-performance-rca`를 덮어쓰지 않는다.
- 필수 datasource UID: `beanflow-prometheus`. 새 datasource나 공개 endpoint는 만들지 않는다.
- 파일 provisioning: 기존 BeanFlow provider가 읽는 `/var/lib/grafana/dashboards/beanflow`에 JSON을 둔다.
  기존 provider는 30초마다 파일을 읽는다. UI import만 하면 Grafana DB에 저장되며 파일 provisioning과는 별개다.
- 환경/host 변수는 `up{job="beanflow"}`에서 구한다. host는 IPv4 또는 hostname이며 API와 exporter가
  같은 host의 다른 port에 있는 현재 topology를 전제로 한다. instance는 API 18081, PostgreSQL 19187,
  node 19100, Alloy 12345, container 19101의 정확한 주소로 매칭한다. 이 port는 scrape 설정과 함께 유지한다.
  DB/exporter host 분리, port 변경 또는 IPv6에서는
  명시적인 대상 매핑으로 selector를 갱신해야 한다. 대상이 없을 때 다른 host를 자동 선택하지 않는다.

## 검증

```bash
python3 scripts/perf/test-live-dashboard-queries.py
python3 scripts/perf/test-dashboard-queries.py
bash scripts/verify-docs.sh
git diff --check
```

새 테스트는 실제 JSON의 모든 PromQL을 Promtool로 파싱하고 정상/유휴/미수집/오래된 표본,
DB·container 내부 실패, 환경·host 격리를 합성 시계열로 검증한다. 실제 scrape는 별도 조회하며
Grafana에서 변수 선택, 카드 값, 그래프, 갱신과 RCA 시간 링크를 확인한다.
CI preflight는 Live Operations와 기존 RCA의 query suite를 함께 실행한다.

새로운 부하·서비스 SLO·성능 향상·장애 주입 결과를 이 검증에서 주장하지 않는다.
