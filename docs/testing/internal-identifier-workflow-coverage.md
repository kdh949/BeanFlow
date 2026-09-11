# 내부 식별자 직접 입력 개선 결과

2026-09-11 기준으로 기존 감사의 28개 입력 항목과 실제 route·typed API·표시 모델·명령을 재대조했다.
`feature/customer-point-selection`의 고객 선택→PointAccount 연결(#170)은 재구현하지 않았다.
나머지 27개 항목은 아래와 같이 검색·선택·기존 요청 재개로 연결했다. 비용 주체의 추가 입력 두 곳도
같은 선택 컴포넌트를 사용한다. 외부 기관의 업무 코드는 별도 분류한다.

## 기존 28개 항목 대조

| 번호 | 기존 입력 | 현재 업무 흐름 | 구현 PR |
|---|---|---|---|
| 1 | 주문 보상 조회의 주문 ID | BF 공개 주문번호로 현재 주문과 후속 처리 조회 | [#172](https://github.com/kdh949/BeanFlow/pull/172) |
| 2 | 점주 동의의 상담 주문 변경 요청 ID | 현재 소속 매장의 동의 대기 주문·승인안 목록에서 선택 | [#175](https://github.com/kdh949/BeanFlow/pull/175) |
| 3 | 포인트 계정 ID | 고객 로그인 이름 정확 검색→마스킹 후보→계정 연결, 이번 변경에서 제외 | [#170](https://github.com/kdh949/BeanFlow/pull/170) |
| 4 | 매장 소속 추가의 기존 계정 ID | 로그인 이름으로 계정 확인 후 소속 추가 | [#171](https://github.com/kdh949/BeanFlow/pull/171) |
| 5 | 운영 조사 요청 ID | 현재 권한·승인안의 운영 검토 목록에서 선택 | [#175](https://github.com/kdh949/BeanFlow/pull/175) |
| 6 | 주문 후속 처리의 주문 ID | 공개 주문번호로 조회하고 내부 대상 자동 연결 | [#172](https://github.com/kdh949/BeanFlow/pull/172) |
| 7 | 공통 포인트 정책의 발행 주체 ID | 현재 매장·브랜드 명부 또는 명명된 플랫폼 비용 주체 선택 | [#177](https://github.com/kdh949/BeanFlow/pull/177) |
| 8 | 환불 설정 복구 건 ID | 주문의 현재 복구 건 목록에서 선택 | [#172](https://github.com/kdh949/BeanFlow/pull/172) |
| 9 | 환불 복구 제안 ID | 복구 건의 실제 제안 목록과 검토 주소 | [#172](https://github.com/kdh949/BeanFlow/pull/172) |
| 10 | 이의제기 매장 ID | 매장 이름 검색·선택 후 이의 목록 | [#171](https://github.com/kdh949/BeanFlow/pull/171) |
| 11 | 운영 환불 대상 매장 ID | 매장 이름 선택과 공개 주문번호 | [#171](https://github.com/kdh949/BeanFlow/pull/171) |
| 12 | 점주 발급의 첫 매장 ID | 현재 매장 이름 선택 | [#171](https://github.com/kdh949/BeanFlow/pull/171) |
| 13 | 포인트를 관리할 매장 ID | 이름 검색 또는 이름이 표시된 정책 목록 | [#171](https://github.com/kdh949/BeanFlow/pull/171) |
| 14 | 기존 주문 변경 요청 ID | 접근 가능한 상담 주문 변경 요청 목록과 검토 주소 | [#174](https://github.com/kdh949/BeanFlow/pull/174) |
| 15 | 매장 동의·위임 ID | 현재 요청·승인안·주문에 유효한 매장 동의 선택 | [#175](https://github.com/kdh949/BeanFlow/pull/175) |
| 16 | 주문 변경 새 실행 담당자 ID | 현재 실행 권한을 가진 조직 로그인 이름 선택 | [#173](https://github.com/kdh949/BeanFlow/pull/173) |
| 17 | 기존 정보 정정 ID | 접근 가능한 정정 요청 목록과 검토 주소 | [#174](https://github.com/kdh949/BeanFlow/pull/174) |
| 18 | 정보 정정 새 담당자 ID | 현재 정정 실행 권한의 담당자 선택 | [#173](https://github.com/kdh949/BeanFlow/pull/173) |
| 19 | 기존 개인정보 열람 요청 ID | 현재 권한으로 열람 승인 요청 탐색·검토 | [#174](https://github.com/kdh949/BeanFlow/pull/174) |
| 20 | 상담 담당자 ID 필터 | 조직 로그인 이름 선택으로 담당자 필터 | [#173](https://github.com/kdh949/BeanFlow/pull/173) |
| 21 | 기존 긴급 열람 요청 ID | 현재 접근 가능한 긴급 요청 탐색·검토 | [#174](https://github.com/kdh949/BeanFlow/pull/174) |
| 22 | 기존 보상 요청 ID | 현재 권한으로 보상 요청 목록·검토 주소 | [#174](https://github.com/kdh949/BeanFlow/pull/174) |
| 23 | 신규 보상의 사고 ID | 같은 고객·주문의 기존 사고 선택 또는 발생 시각·별개 사고 확인 후 등록 | [#176](https://github.com/kdh949/BeanFlow/pull/176) |
| 24 | 보상 새 실행 담당자 ID | 현재 보상 실행 권한의 담당자 선택 | [#173](https://github.com/kdh949/BeanFlow/pull/173) |
| 25 | 기존 본인확인 ID | 상담의 현재 접근 가능한 본인확인 요청 선택 | [#174](https://github.com/kdh949/BeanFlow/pull/174) |
| 26 | 기존 상담 건 ID | 문의 분류·상태·담당자·접수 시각이 있는 상담 목록 | [#174](https://github.com/kdh949/BeanFlow/pull/174) |
| 27 | 상담 새 담당자 ID | 현재 상담 담당 권한을 가진 조직 로그인 이름 선택 | [#173](https://github.com/kdh949/BeanFlow/pull/173) |
| 28 | 상담 연결 대상 ID | 정확 검색의 마스킹 후보·매장명·공개 주문번호를 확인한 후 연결 | [#173](https://github.com/kdh949/BeanFlow/pull/173) |

## 부가 입력과 표시

- 포인트 조정 및 매장별 정책의 `issuerReference` 입력도 #177의 비용 주체 선택을 사용한다.
  플랫폼 명부는 정책 관리에서 이름·사유로 등록하며 지급·정책 변경과는 별도 명령이다.
  과거 비용 스냅샷을 재분류하지 않고 현재 정책에 명시된 기존 참조를 출처·버전과 함께 유지할 수 있다.
- 고객·매장·주문·배송 대상은 소유 모듈이 제공한 현재 표시 정보만 사용한다. 운영 담당자는 서명된 조직
  로그인 이름을 사용하며 이름 미등록·현재 권한 부족을 명시한다. UUID에서 이름을 추정하지 않는다.
- 매장 정산 등록 코드, 배달업체 배달원 등록 코드, 배달원 정산 등록 코드는 외부 기관이 발급한
  업무 코드다. 입력 출처와 허용 문자·길이를 설명하며 실제 계좌번호·카드정보·비밀키 입력을 금지한다.
  [ADR-087](../adr/ADR-087-field-risk-and-purpose-specific-profile-change.md)의 원문 재입력·해시·별도 승인 경계를 유지한다.
- 계약·증빙 참조는 업무 문서의 위치다. 로그인 ID는 사용자가 알고 있는 로그인 이름이다.
  API/주소의 ID와 진단용 읽기 전용 ID 표시는 직접 조회 입력으로 세지 않는다.

## 상태 보존과 검증 범위

모든 후보 선택은 기존 명령의 현재 권한·상태·버전·본인확인·별도 승인 검사를 우회하지 않는다.
신규 목록에는 범위가 결합된 커서와 상한을 적용했다. 등록과 감사는 같은 짧은 transaction에서 처리한다.
관련 결정은 [ADR-128](../adr/ADR-128-operation-target-selection-and-workflow-discovery.md),
[ADR-066](../adr/ADR-066-audited-loyalty-point-adjustment.md),
[ADR-086](../adr/ADR-086-versioned-goodwill-compensation.md)에 기록했다.

불명 명령의 대상·본문·멱등키를 보존한다. 나중 재시도의 403/409는 최초 요청의 rollback을 증명하지
않으므로 그 요청을 해제하지 않는다. 매장 정책의 상위 탭, 개인정보 열람·보상 중 상담/본인확인 선택,
상담 접수 또는 대상 연결의 부분 실패에도 같은 규칙을 적용한다. 개인정보 원문은 재시도용으로 보관하지 않는다.

최종 정적 검사는 production TSX 102개 파일의 입력 선언 230개와 동적 목적별 필드 정의를 대조했다.
`ID/식별값/식별자` 입력 라벨은 로그인 이름 세 곳만 남았다. 이 검색만으로 완결성을 주장하지 않고 위
28개 항목의 실제 route·API·선택 결과·명령 연결 및 Storybook interaction을 함께 확인했다.

로컬 API/실제 PostgreSQL·동시성·감사 rollback·권한·계약·Modulith와 UI interaction/a11y,
정적 문서·타입·단위 테스트·디자인 검사·빌드를 실행한다. PR별 결과는 해당 ExecPlan과 원격 CI가 근거다.
실제 운영 환경의 로그인·금융/개인정보 업무 E2E, 배포와 migration 적용은 실행하지 않았다.
