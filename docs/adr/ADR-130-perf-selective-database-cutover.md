# ADR-130: 기존 perf 데이터를 보존하는 새 DB 선별 이관

- **Status:** Accepted
- **Date:** 2026-09-13
- **Implementation owner:** [perf DB 선별 이관](../exec-plans/completed/perf-selective-database-cutover.md)

## Context

기존 perf DB에 적용된 V1/V3/V6/V8/V9/V39/V45와 새 배포 소스가 다르다.
재고 step과 감사 action이 제거된 새 스키마에 과거 거래를 단순 복원할 수도 없다.
기존 데이터 보존과 현재 스키마의 부하 테스트 준비를 함께 충족해야 한다.

## Decision

- 기존 DB/volume/이미지 bucket과 검증한 백업을 보존하고 다른 이름의 빈 DB와 bucket을 만든다.
  원본을 삭제하거나 Flyway checksum을 repair하지 않는다.
- 대상 이미지의 실제 Flyway와 migration으로 새 DB를 만든다. 원본 migration 이력을 복사하거나
  성공 이력을 직접 INSERT하지 않는다. 기존 migration SQL은 변경하지 않는다.
- 계정/PointAccount, 카탈로그/소속/계약, 사용 가능한 미연결 쿠폰과 claim/counter/명령,
  즐겨찾기/색인, 예약 없는 미래 슬롯, 필수 적립 정책/권한을 명시한 컬럼으로 선별 이관한다.
- 정책/권한의 기존 immutable version/head와 정확히 연결된 Audit를 함께 복원한다.
  값·주체·source·시각·금액을 추정하거나 신규 권한을 부여하지 않는다. 새 생성/변경은 기존
  bootstrap/API 계약을 따른다. 계정과 PointAccount는 하나의 적재 transaction에 포함한다.
- 과거 주문/결제/환불/보상/알림/세션/이벤트와 선택하지 않은 감사는 원본 DB에 보관한다.
  MANUAL_REVIEW/FAILED를 성공으로 바꾸거나 새 DB에서 과거 부수효과를 재발송하지 않는다.
- 이미지 객체는 별도 대상 bucket에 같은 key로 복제하고 검증한다. 원본 bucket을 새 DB의
  orphan cleanup 대상과 공유하지 않는다. AIStor의 정식 관리자 인증으로 새 bucket을 만들고
  앱 access key의 기존 action 범위를 유지한 채 새 bucket resource만 추가한다.
  기존 policy는 복구용으로 보존하며 관리자 인증 실패는 명시적 차단이다.
- 최종 원본 writer 정지 뒤 snapshot의 변경 여부를 확인한다. FK/CHECK/trigger를 유지한
  단일 transaction으로 적재하고 사용한 sequence를 실제 최대 ID에 맞춘다.
- 데이터/이미지/앱/HTTP 확인 뒤 전환한다. 쓰기 이후 원복은 신규 데이터도 보존하고 별도 처리한다.
- 백업·credential·원본 row·배포 환경값은 저장소 밖 제한된 권한으로 보관한다.
  root 소유 배포 파일은 sudo/Doppler 경로로만 다루고 host 권한을 우회하지 않는다.
  Docker가 제공하는 현재 container 설정을 별도 사용자 소유 Compose로 보존하는 배포는 허용한다.
  이 경로는 기존 root 파일을 읽거나 수정하지 않고 기존 secret bind source를 재사용한다.

## Alternatives Considered

- 원본 forward migration: 과거 재고/event 호환 코드가 별도로 필요하다.
- 원본 초기화: 보존 요구를 충족하지 못한다.
- 전체 dump 복원: 과거 schema와 실패 작업을 그대로 가져온다.
- 이미지 bucket 공유: 다른 DB의 orphan 판단이 원본 이미지를 삭제할 수 있다.

## Rationale

실패 이력의 의미를 바꾸지 않고 현재 계약으로 새 부하 실행을 시작한다.
검증된 계정/카탈로그/혜택은 재사용하고 과거 거래 이행은 별도 범위로 유지한다.

## Consequences

별도 DB/bucket 및 권한이 필요하다. 새 DB에서는 과거 거래를 조회할 수 없고 원본 보존 책임은 유지된다.
SQL 적재 성공은 앱/로그인/이미지/주문 또는 실제 전환 성공을 뜻하지 않는다.

## Verification

백업 복원, 실제 Flyway, 대상 오인 방지, 실패 rollback, 제약/내용/sequence,
미해결 작업 제외, 이미지 보존, packaged runtime와 HTTP smoke를 단계별 확인한다.

## Metrics

단계별 상태·건수·해시를 기록한다. 측정하지 않은 성능 향상을 주장하지 않는다.

## Revisit Conditions

과거 거래 통합, 실제 자금 운영, 다중 writer, 무중단 전환 또는 원본 폐기가 필요할 때 재설계한다.

## Related Decisions

- [ADR-059](ADR-059-pre-release-compensation-clean-cutover.md)
- [ADR-109](ADR-109-customer-point-account-provisioning.md)
- [ADR-115](ADR-115-store-and-menu-image-storage.md)
- [ADR-119](ADR-119-portfolio-deployment-runtime.md)
- [ADR-122](ADR-122-external-keycloak-deployment.md)
