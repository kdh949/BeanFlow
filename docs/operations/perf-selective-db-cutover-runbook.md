# perf DB 선별 이관과 원본 보존

이 실행은 [ADR-130](../adr/ADR-130-perf-selective-database-cutover.md)을 따른다.
503d158 이미지와 당시 89개 Flyway migration을 대상으로 한 일회성 실행이다.
다른 release에 아래 도구의 상수·컬럼 목록을 검토 없이 재사용하지 않는다.

## 전환 결과

2026-09-13 KST, `orbit-runner@172.16.16.22`의 `beanflow-perf`를 전환했다.

| 항목 | 보존한 원본 | 현재 사용 대상 |
| --- | --- | --- |
| DB | `beanflow`, 새 연결의 기본 transaction은 read-only | `beanflow_perf_20260913` |
| AIStor bucket | `beanflow-staging` | `beanflow-perf-20260913` |
| API/web revision | `03910bf75090e5230661aac86ff16a42203d59f8` | `503d15831cc3b775bb073c8bcefb534ad89f36a0` |
| migration | 기존 71개 이력 그대로 보존 | 실제 Flyway 89개 적용·validate |

API digest는 `sha256:8004525749653b0f0b32f613d235c81e373677f429966068749d2eb6385b8b45`,
web digest는 `sha256:ad629b63ccab594e036926b04e61e1725afcea0586f34439f408d8684a51d457`이다.

## 보존·선별 범위

- 원본 전체: 174개 테이블, 731,334행. 최종 dump 복원 뒤 모든 테이블의 건수·내용 해시 일치.
- 새 DB: 28개 테이블, 144,254행. 명시한 원본 컬럼과 ID를 유지하고 새 컬럼에는 migration 기본값 적용.
- 고객 220, 점주 161, 매장 161, 메뉴 2,721, 쿠폰 1,600, 미래 빈 슬롯 120,960개를 포함한다.
- 계정과 PointAccount, 캠페인 claim/counter/명령, 정책 version/head와 감사 6건, 기존 운영 권한 5건을 함께 보존했다.
- migration 자체의 지역·정책 seed 9개 테이블은 원본과 내용이 같음을 확인하고 중복 복사하지 않았다.
- 주문 12,214, 결제 12,092, 환불 12,058, event publication 91,982와 나머지 과거 이력은 원본에 보관한다.
- 이미지 객체 6,400개, 648,253,120 bytes를 별도 bucket에 복제하고 SHA-256·크기·content type·metadata를 비교했다.

원본의 네 `NOT VALID` 감사 제약 상태도 백업에 보존됐다. 새 DB 적재에서는 FK/CHECK/trigger를
비활성화하지 않았다. 적립 정책 sequence는 최대 ID 이후 값을 발급하도록 동기화했다.

## 실행 도구와 검증 순서

1. `pg_dump --format=custom`을 보호된 파일에 저장하고 별도 검증 DB에 `pg_restore --exit-on-error`로 복원한다.
2. 승인한 이미지에서 app.jar/lib/migration을 추출해 `SelectiveRuntime.java`를 JDK 21로 컴파일한다.
   기존 앱 user가 기존 runtime credential로 새 DB에 Flyway를 실행한다. 앱의 read-only rootfs에는 쓰지 않고
   승인된 `/tmp` tmpfs에 도구를 스트리밍한다. host root 소유 파일을 읽기 위한 mount는 만들지 않는다.
3. `selective_copy.py`와 `selective_columns.json`은 source read-only snapshot을 메모리에서 COPY한다.
   target 이름과 migration/seed를 확인하고 단일 transaction 안에서 lock·비어 있음·제약을 검증한다.
   CSV의 CRLF를 보존하며 COPY 종료 제어 행이 있는 데이터는 거부한다. raw row는 로그에 출력하지 않는다.
4. 별도 fixture DB에서 nonempty target 차단과 중복 PK 실패 시 28개 테이블 전체 rollback을 확인한다.
5. `prepare_selective_media.py`는 AIStor 내부의 기존 관리자 인증을 사용한다. 앱 access key의 기존 action과
   원본 resource를 유지하고 새 bucket resource만 추가한다. 이전 policy를 보호된 파일로 보존한다.
   서버가 policy 배열 순서를 정규화하므로 의미를 비교한다. `SelectiveRuntime media-copy`는 이미 존재하는
   대상 객체를 덮어쓰지 않고 내용 일치를 검증한다.
6. `prepare_selective_release.py`는 Docker가 제공하는 실제 설정을 별도 Compose로 고정한다.
   root 배포 파일을 수정하지 않고 secret bind, 자원 제한, network, port, tmpfs, 보안 설정을 보존한다.
   변경은 API/web 이미지, API DB/bucket, web의 bucket proxy와 exporter DB뿐이다.
7. `apply_selective_cutover.py`는 기존 web/API를 정지하고 최종 backup·source/target 내용 비교를 실행한다.
   원본 DB에 기본 read-only를 설정한 뒤 새 API/exporter, readiness, 새 web 순서로 전환한다.
   새 앱 시작 전 실패는 원본을 재시작한다. 새 앱 시작 후 실패는 양쪽 데이터를 보존하고 명시적 복구를 요구한다.

## 실행 증적과 운영 설정

서버의 `/home/orbit-runner/beanflow-cutover-503d158/`는 0700 실행 디렉터리다.
백업·manifest·Compose에는 0600 권한을 사용한다. 이 파일들의 원문은 저장소에 복사하지 않는다.

| 파일 | 용도 |
| --- | --- |
| `source-preparation.dump` | 서비스 실행 중 일관된 snapshot 백업, 복원 검증 완료 |
| `source-final.dump` | 기존 writer 정지 후 최종 백업 |
| `final-backup-full-content.json` | 최종 백업 복원과 원본 전체 내용 비교 |
| `transfer-manifest.json`, `selection-cutoff.txt` | 28개 테이블 건수·내용 해시와 슬롯 선택 기준 시각 |
| `negative-tests.json`, `target-invariants.json` | 실패 rollback·참조·상태·sequence 검증 |
| `original-media-policy.json`, `media-copy.log` | 정책 복구 자료와 객체 검증 |
| `deployment-compose.json`, `rollback-compose.json` | 실제 전환 설정과 원본 서비스 복구 설정 |
| `cutover-state.json`, `http-smoke.json`, `final-validation.json` | 단계별 결과와 HTTP/운영 확인 |

최종 dump SHA-256은 `c53d1ee837c3ee9cc88218088f3a879ab36510e296e637ee69b43837daff4169`다.
기존 `/srv/beanflow`와 `/etc/beanflow/perf` 파일은 현재 전환 결과를 반영하지 않는다.
다음 운영 작업은 위 `deployment-compose.json`을 기준으로 시작해야 한다.
부분 Compose의 orphan 안내는 별도 보존한 PostgreSQL/driver/Alloy/node-exporter를 뜻한다.
`--remove-orphans`, `down -v`, volume 삭제, 원본 DB drop을 실행하지 않는다.

## 검증 결과와 한계

- Passed: 백업 전체 복원/해시, 실제 Flyway, 선별 내용/seed, 실패 rollback, sequence, 이미지 6,400개.
- Passed: API/web health/readiness, 외부 HTTPS, 고객·점주 로그인과 로그아웃, 포인트·즐겨찾기,
  매장·메뉴·이미지·슬롯, 견적, 주문 생성/동일 key 재실행/조회/취소의 HTTP 14항목.
- 검증 주문 1건은 `CANCELLED`로 남는다. 결제는 요청하지 않았고 슬롯 예약은 해제했다.
- 쓰기 요청 전 actor CSRF endpoint에서 최신 토큰을 받아야 한다. 초기 검증 스크립트의 토큰 누락 403을
  현재 계약에 맞게 수정한 뒤 성공했다. 주문 생성 응답은 `order.orderId` 구조를 사용한다.
- Passed: 새 DB exporter `pg_up=1`, scrape error 0. API/web 외 PostgreSQL·AIStor·Vault·driver·Alloy·node-exporter 유지.
- 시작 직후 Pyroscope에 429 수집 용량 제한이 관측됐다. 이후 90초 관찰에서는 같은 오류가 없었다.
  장시간 ingest, 운영자 SSO 전체 흐름, 브라우저 E2E와 부하·용량 측정은 Not run이다.

## 복구

자동으로 원본을 덮어쓰거나 과거 작업을 재실행하지 않는다. 원복 필요 시 먼저 새 web/API를 정지하고
새 DB도 별도 dump로 보존한다. 전환 후 신규 쓰기가 있으면 이를 원본에 통합하는 판단이 먼저 필요하다.
검토한 원복은 `postgres` DB에 연결해 `ALTER DATABASE beanflow RESET default_transaction_read_only`를
실행한 뒤 보존한 `rollback-compose.json`으로 API/web/exporter를 `up --no-deps`하는 순서다.
새 DB/bucket은 원복 후에도 삭제하지 않는다. 원본 bucket 권한은 이번 전환에서 제거하지 않았다.
