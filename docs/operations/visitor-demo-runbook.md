# 방문자 데모 실행 방법

기능 정책은 [BR-58](../product/business-policy-decisions.md#br-58-방문자별-주문-체험)과 [ADR-132](../adr/ADR-132-visitor-demo-workspace.md), API 명세는 [demo.yaml](../../openapi/demo.yaml)에 정리했다.

## 활성화

방문자 데모는 기본적으로 꺼져 있다(`beanflow.demo.enabled=false`). 실행할 때 `true`로 바꾸며, 설정을 꺼도 이전 데모 계정의 만료 작업은 계속 실행된다. 기존 [local-demo](local-demo-runbook.md) 또는 Toss sandbox 실행 환경의 DB, GLOBAL 포인트 적립 정책, 인증, 외부 의존성을 먼저 준비한다. 기능 활성화와 배포·운영 DB 마이그레이션은 별도 절차다.

전용 비운영 환경에서 기존 실행 명령에 `--beanflow.demo.enabled=true`를 추가한다. 허용 profile은 `local`과 `local-demo` 또는 `toss-sandbox` 조합이다. `prod`, `perf`, `toss-perf`와의 조합은 기동 실패한다. 기존 포트폴리오 prod 서버에서는 이 기능을 켤 수 없다.

- `local,local-demo`: 포인트 전액 사용 샘플 주문과 주문 처리 여정.
- `local,toss-sandbox`: 샘플 여정과 직접 메뉴 선택·장바구니·Toss 테스트 결제.
- Toss 결제를 사용하려면 테스트용 client/secret 키를 넣고 SDK 결제·콜백을 실행한다.
- 비밀 값은 기존 Doppler 또는 승인된 환경 주입 방식을 사용하며 프런트엔드에 넣지 않는다.
- 동일 origin의 HTTPS 프런트엔드에서 `/demo`로 진입한다. 로컬 검증은 Secure cookie를 지원하는 localhost를 사용한다.
- V93은 빈 체험 테이블과 nullable 계정 컬럼을 추가한다. 기존 계정과 주문을 변환하지 않는다. 최신 main의 V92 결제 멱등성 인덱스 다음 번호이며, 아직 적용되지 않은 demo migration만 재번호했다. 적용된 migration의 재번호나 out-of-order 설정은 허용하지 않는다.

데모 기능과 테스트 결제 사용 여부는 `GET /api/v1/demo/config`의 `enabled`, `testPaymentEnabled`로 내려준다. UI는 발급 API의 성공 결과를 받은 뒤 기존 고객·점주 Session을 다시 조회한다. 기존 일반 로그인이 있으면 발급을 거절하며 해당 계정을 덮어쓰지 않는다.

## 데모 사용 순서

1. `/demo`에서 주문 처리 체험 시작. 고객·점주 계정, 샘플 매장·메뉴·영업시간·포인트·주문을 한 트랜잭션으로 만듭니다.
2. 점주 주문 보드에서 주문 접수 → 제조 시작 → 준비 완료.
3. 고객 화면에서 동일 주문의 준비 완료와 픽업 번호 확인.
4. 점주 화면으로 돌아가 픽업 완료. Toss sandbox에서는 직접 메뉴 주문으로 이어갈 수 있다.
5. 접수 제한 3분이 지나면 기존 timeout worker가 주문을 거절한다. 안내에서 새 샘플 주문을 발급해 재시작한다. 기존 주문 상태를 되돌리지 않는다.
6. 30분 경과 또는 체험 종료 시 양쪽 계정 접근을 끝낸다. 만료 worker는 매장 주문 접수를 닫고 Session을 제거한다. 주문·결제·포인트·감사 증거는 보존한다.

체험 매장은 일반 가까운 매장, 통합 검색, 즐겨찾기, 최근 주문과 추천 결과에 노출되지 않는다. 체험 화면은 발급 응답의 store ID로 기존 매장 상세와 메뉴 경로를 직접 연다.

데모 안내 화면은 3초마다 서버에서 주문 상태를 가져와 갱신한다. 새로고침 후 같은 공간을 이어가며, 발급 응답을 잃으면 sessionStorage의 동일 요청 키로 재시도한다. localStorage에는 체험 여부 표시만 저장하고 인증 식별자는 HttpOnly cookie로 보관한다.

## 사용량 제한과 로그

상한은 `beanflow.demo.max-active=20`, `max-daily=200`, `max-browser-daily=5`이며 더 낮출 수 있다. 하루 기준은 Asia/Seoul이다. 한 공간의 샘플은 최초 주문 포함 최대 5개다. 변경 시 active 공간 잠금, 발급 시 전역 admission 잠금으로 중복/상한 초과 경쟁을 제어한다.

`beanflow.demo.workspace` counter의 outcome은 `created`, `ended`, `quota`, `expiry_failed`다. 생성·종료 성공은 commit 후 집계한다. 감사 작업은 `DEMO_WORKSPACE_CREATED`, `DEMO_SAMPLE_CREATED`, `DEMO_WORKSPACE_ENDED`다. Session/cookie/password/raw IP는 기록하지 않는다. 만료 worker 실패는 다음 주기 재시도하며 계정의 정확한 만료 경계는 매 요청의 actor loader가 차단한다.

429는 공간/일일/샘플 상한, 409는 기존 로그인 충돌·진행 중인 주문·요청 키 재사용, 410은 만료다. 거래 종료를 환불·혜택 복원 완료로 표시하지 않는다. 기존 복구/정산 정책과 담당자 인가를 그대로 따른다.

## Storybook 화면

`frontend`에서 `npm run storybook`을 실행한다. 기존 제품 화면에 MSW 테스트 데이터를 연결해 표시한다.

| 단계 | Storybook |
| --- | --- |
| 시작 | [Entry](http://localhost:6006/?path=/story/pages-demo-journey--entry) |
| 접수 대기 | [Accept Order](http://localhost:6006/?path=/story/pages-demo-journey--accept-order) |
| 접수 완료 | [Start Preparing](http://localhost:6006/?path=/story/pages-demo-journey--start-preparing) |
| 제조 중 | [Preparing](http://localhost:6006/?path=/story/pages-demo-journey--preparing) |
| 고객 준비 완료 | [Customer Ready](http://localhost:6006/?path=/story/pages-demo-journey--customer-ready) |
| 픽업 완료 처리 전 | [Pickup Ready](http://localhost:6006/?path=/story/pages-demo-journey--pickup-ready) |
| 완료 | [Completed](http://localhost:6006/?path=/story/pages-demo-journey--completed) |
| 메뉴 선택 | [Menu](http://localhost:6006/?path=/story/pages-demo-journey--menu) |
| 장바구니 | [Cart](http://localhost:6006/?path=/story/pages-demo-journey--cart) |
| 테스트 결제 | [Checkout](http://localhost:6006/?path=/story/pages-demo-journey--checkout) |
| 접수 시간 초과 | [Timed Out](http://localhost:6006/?path=/story/pages-demo-journey--timed-out) |

전체 여정, 발급·주문 추적 재시도, 종료, 만료는 추가 play 테스트로 제공한다. 화면 테스트는 점주 1440px, 고객 390px 크기로 실행한다. 모바일 고객 화면은 내용 전체를 스크롤할 수 있고 안내 접기로 주문 내용을 먼저 확인할 수 있다.

## 테스트

PostGIS Testcontainers 통합 테스트는 실제 Flyway 마이그레이션, 발급 원자성, 두 방문자의 접근 격리, 일반 탐색 비노출, 기능 비활성 재시작 뒤 만료 정리, quota, 같은 키 동시 발급, timeout 다음 새 주문, Spring MVC 쿠키/CSRF와 기존 주문 전환을 검증한다. 비활성 주문 접근의 단위 테스트는 Identity repository 호출이 없음을 검증한다. 통합 테스트에는 테스트용 결제·알림 어댑터를 사용한다.

통합 테스트는 Testcontainers와 테스트용 결제·알림 어댑터로 실행한다. 원격 배포와 실제 Toss SDK·외부 알림 연동은 별도로 진행한다.
