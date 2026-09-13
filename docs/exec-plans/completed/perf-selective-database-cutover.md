# 기존 perf DB를 보존하고 새 DB로 선별 이관

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `false`
> **Depends-On:** `docs/exec-plans/completed/performance-observability-and-load-test-foundation.md`, `docs/exec-plans/completed/same-origin-aistor-proxy.md`
> **Completed-At:** `2026-09-13`

이 ExecPlan은 `.agent/PLANS.md`와 [ADR-130](../../adr/ADR-130-perf-selective-database-cutover.md)을 따른다.
새 Flyway SQL을 작성하지 않고 대상 이미지의 migration과 별도 이관 도구를 실행한다.

## Purpose / Big Picture

원본 DB/이미지를 보존하고 현재 코드에 맞는 계정·카탈로그·혜택·미래 슬롯을 새 perf DB에서 사용한다.

## Current State

원본 03910bf/V71 DB와 bucket을 보존하고 503d158/실제 Flyway 89개로 전환했다.
원본 174개 테이블 731,334행 전체의 최종 백업 복원·내용 해시 비교를 통과했다.
새 DB에 28개 테이블 144,254행과 별도 bucket 이미지 6,400개를 이관했다.
Docker 실행 설정을 별도 Compose로 보존해 root host 파일 수정 없이 전환했다.

## Definitions

source는 보존하는 원본 DB/bucket, target은 이번 실행의 새 DB/bucket,
cutover는 원본 writer 정지와 최종 snapshot 확정 뒤 새 앱/프록시 활성화다.

## Scope

### In Scope

백업/복원, 실제 Flyway, 선별 copy, ID/sequence/감사 보존, 이미지 복제, 배포와 smoke.

### Non-goals

원본 삭제, 과거 거래 변환, checksum repair, 앱/API 변경, 운영자 업무 권한 확대, 부하 실행, commit/push.

## Business Rules and Invariants

ADR-130 및 BR-42의 계정/포인트 원자성, 정책 version/head/Audit 결합을 유지한다.
쿠폰 만료·금액·실패 상태를 변경하지 않고 secret/raw row를 로그에 기록하지 않는다.

## Architecture and Transaction Boundaries

원본 READ ONLY snapshot을 다른 DB에 한 transaction으로 COPY한다. 이미지 복제는 DB transaction 밖에서 수행한다.
앱의 기존 Aggregate/transaction/외부 실패 의미는 바꾸지 않는다.

## Alternatives Considered

원본 upgrade/reset/전체 restore 대신 별도 DB와 bucket을 사용한다. 근거는 ADR-130에 기록했다.

## Failure Semantics

source=target, nonempty target, 출처/이미지 SHA 불일치, 제약/내용 오류는 중단한다.
원본을 자동 삭제하지 않는다. 정지 이후 실패는 새 데이터/증적을 보존하고 기록된 복구 절차를 따른다.

## Data and Migration

백업 검증 → 빈 target → packaged Flyway → 명시적 컬럼 copy → sequence → 관계/내용 확인.
기존 Flyway SQL/이력을 수정·복제하지 않는다. 데이터 보존 범위는 ADR-130을 따른다.

## API and Event Contracts

변경 없음. HTTP smoke는 현재 runtime OpenAPI를 사용하고 과거 pending event를 새 앱에 전달하지 않는다.

## Milestones

1. 결정 기록과 원본 backup/복원 검증.
2. 보호 조건이 있는 이관 도구와 실패 경로 검증.
3. target DB/bucket과 데이터/이미지 검증.
4. 권한이 있는 배포 shell에서 최종 cutover, runtime/HTTP/관측성 검증.

## Required Tests

대상 오인/nonempty 차단, copy 오류 rollback, sequence, 제약/내용/seed, 이미지 revision,
backup restore, 객체 검증과 health/HTTP.

## Validation Commands

- `python3 -m unittest discover -s scripts/deploy -p 'test_selective_*.py'`
- `bash scripts/verify-docs.sh`
- `git diff --check`

운영 manifest/state/validation은 저장소 밖 실행 디렉터리에 기록한다.

## Observability

Passed/Pending/Not run/Blocked를 단계별로 구분한다. readiness, SQL, 이미지, HTTP와 ingest는 별도 결과다.

## Documentation Updates

ADR-130, 본 계획, runbook을 추가하고 Progress/Outcomes에 실제 결과와 미완료 조건을 기록한다.

## Progress

- [x] 선별 이관과 원본 보존 결정, 로컬 평가와 승인된 실제 적재 검증.
- [x] 원본 backup/복원 및 174개 테이블 전체 내용 해시 검증.
- [x] 이관 도구/원본 오인·SQL 입력 보호·nonempty target·전체 rollback 검증.
- [x] target DB/Flyway/28개 테이블/seed/sequence와 별도 bucket 6,400개 객체 검증.
- [x] 최종 전환과 health/HTTP 14항목/exporter 검증. 장시간 telemetry ingest·부하 측정은 범위 밖 Not run.

## Surprises & Discoveries

정책 sequence 동기화와 원본 이미지 cleanup 격리가 필요했다. root 배포 파일은 sudo 인증이 필요해
Docker가 제공하는 설정을 별도 Compose로 보존했고 기존 root 파일은 읽거나 바꾸지 않았다.
AIStor 앱 access key는 원본 bucket에만 접근할 수 있었다. AIStor 내부의 기존 관리자 인증으로
새 bucket을 만들고 기존 action/source resource를 유지하면서 대상 resource만 추가했다.
정책 배열 순서의 정규화는 의미 비교로 검증했다. COPY 전송은 embedded CRLF를 보존한다.
초기 HTTP 검증 스크립트에서 최신 CSRF 토큰과 `order.orderId` 응답 구조를 반영한 후 재검증했다.

## Decision Log

- 2026-09-13: 원본을 삭제하지 않는 선별 이관과 최종 배포 전환 승인. ADR-130 적용.
- 2026-09-13: 기존 container 설정과 AIStor 관리자 인증을 이용해 host root 파일 변경 없이 완료.

## Outcomes & Retrospective

서버 `beanflow_perf_20260913`, `beanflow-perf-20260913`, API/web 503d158 전환 완료.
원본 `beanflow`는 새 연결의 기본 transaction을 read-only로 설정했고 원본 bucket과 두 유효 dump를 보존했다.
최종 writer 정지 이후 source/target 비교를 통과했다. 복원 검증 전용 DB만 검증 후 제거했다.

실제 PostgreSQL fixture에서 nonempty target의 무변경 거부와 중복 COPY 실패 시 28개 테이블 rollback을 확인했다.
새 DB에 과거 주문/결제/환불/event를 가져오지 않았으며 API 검증 주문 1건만 생성·재실행·취소했다.
결제 0건, 남은 슬롯 예약 0건, FAILED event 0건이다. 검증 후 원본 컬럼 해시는 27개 테이블에서 동일하며
나머지 슬롯 테이블은 검증 주문의 예약·취소에 따른 version 변경이 있다.

고객·점주 로그인/로그아웃, 포인트·즐겨찾기, 매장·메뉴·이미지·슬롯, 견적,
주문 생성/멱등 재실행/조회/취소 HTTP 14항목 통과. API/web health와 `pg_up=1`, scrape error 0 확인.
Pyroscope 시작 직후 수집 제한 429를 관측했고 이후 90초 같은 오류 0건을 확인했다.
장시간 telemetry ingest, 운영자 SSO 전체 흐름, browser E2E, 부하·성능 측정은 Not run이다.

운영 파일과 증적은 서버 `/home/orbit-runner/beanflow-cutover-503d158/`에 보관한다.
현재 Compose와 복구 절차는 [운영 runbook](../../operations/perf-selective-db-cutover-runbook.md)에 기록했다.
commit/push는 실행하지 않았다.

## Revision Notes

- 2026-09-13: 최초 실행 계획과 보존 범위 기록.
- 2026-09-13: 실제 서버 이관, 배포, 검증과 복구 자료 기록 후 완료.
