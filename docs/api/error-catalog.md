# Error Catalog

| Code | HTTP | Retryable | Meaning |
|---|---:|---:|---|
| INVALID_REQUEST | 400 | No | 요청 형식 또는 필드 검증 실패. malformed/expired/scope-mismatched cursor와 audited policy read의 invalid access reason을 포함. framework validation detail은 선택적 field와 `INVALID_VALUE`, `MISSING_VALUE`, `INVALID_FORMAT`, `MALFORMED_REQUEST` 중 하나만 반환하며 rejected value와 exception message를 반환하지 않음 |
| AUTHENTICATION_FAILED | 401 | Yes, after correcting credentials or lock expiry | 고객·점주 로그인에서 계정 없음, 비밀번호 불일치, 계정 잠금 또는 임시 비밀번호 만료를 구분하지 않는 동일 응답 |
| AUTHENTICATION_RATE_LIMITED | 429 + Retry-After | Yes, after IP block expiry | actor 종류별 source IP 로그인 실패 30회로 15분 차단됨. 계정 존재 여부를 노출하지 않음 |
| LOGIN_ID_UNAVAILABLE | 409 | No, choose another ID | actor namespace 안에서 canonical 로그인 ID가 이미 사용 중 |
| PASSWORD_POLICY_VIOLATION | 400 | No, correct password | BR-35의 길이·UTF-8 byte·사용자명 동일·common-password·현재와 동일한 self-change 비밀번호 정책 위반 |
| POINT_ACCOUNT_INTEGRITY_FAILURE | 503 | Operator investigation | CustomerAccount에 대응하는 실제 PointAccount가 없어 actor-scoped 포인트 조회를 안전하게 제공할 수 없음. 0원 DTO, lazy-create 또는 404로 대체하지 않음 |
| ACCESS_DENIED | 403 | No | 역할·소유권·매장 소속, actor별 CSRF·Chain 검증 또는 active explicit operator grant 불충족 |
| INITIAL_PASSWORD_CHANGE_REQUIRED | 403 | Yes, after password change | `INITIAL_PASSWORD` 점주 Session이 비밀번호 변경과 `/merchant/me` 외 매장 API를 호출함 |
| RESOURCE_NOT_FOUND | 404 | No | 접근 가능한 리소스 없음 |
| ORDER_REFERENCE_NOT_FOUND | 404 | No | 접근 가능한 범위에서 공개 주문번호에 해당하는 주문이 없음. 다른 고객·매장 주문은 403 정책을 유지 |
| MERCHANT_ACCOUNT_NOT_FOUND | 404 | No | exact canonical login ID 또는 opaque account reference에 해당하는 관리 가능 점주 계정이 없음 |
| ORDER_STATE_CONFLICT | 409 | No | 현재 상태에서 명령 불가 |
| ORDER_ACTION_NOT_ALLOWED | 422 | No, correct action and expectedStatus | 점주 주문보드 action과 client가 본 expectedStatus 조합 자체가 허용되지 않음. 실제 Order 상태가 expectedStatus와 다르면 이 코드가 아니라 ORDER_STATE_CONFLICT |
| REORDER_SOURCE_STATE_INVALID | 409 | No | source Order가 `COMPLETED`, `CANCELLED`, `REJECTED`, `EXPIRED`가 아니어서 빠른 재주문 불가 |
| REORDER_ITEMS_UNAVAILABLE | 409 | Maybe, after source/current catalogue changes | source line 하나 이상을 검증된 option snapshot과 현재 Merchant 구성으로 전부 재구성할 수 없음. item별 stable reason을 반환하고 부분 Order는 만들지 않음 |
| IDEMPOTENCY_KEY_REUSED | 409 | No | 같은 키에 다른 payload |
| IDEMPOTENCY_REQUEST_IN_PROGRESS | 409 + Retry-After | Yes, same key after delay | 같은 key·payload의 최초 명령이 아직 처리 중이며 새 실행은 하지 않음. 사전등록 모델 명령에만 사용 |
| IDEMPOTENCY_MANUAL_REVIEW_REQUIRED | 409, no Retry-After | No automatic retry | stale `PROCESSING`의 자동 처리가 중단되어 운영자 확인이 필요함. 같은 key 재요청은 owner 작업을 실행하지 않으며 현재 공식 자동 해결 API가 없음 |
| MENU_CONFIGURATION_NOT_AVAILABLE | 409 | Maybe | 유효한 메뉴·옵션 구성이 현재 판매 불가 |
| BRAND_NAME_ALREADY_IN_USE | 409 | No, choose another name | 다른 활성 브랜드가 같은 정규화 이름을 이미 쓰고 있음. 보관된 브랜드의 이름은 다시 쓸 수 있음 |
| BRAND_FANOUT_LIMIT_EXCEEDED | 409 | No | 브랜드 소속 매장이 ADR-112 6절의 1000개 상한을 넘김. 이름 변경의 색인 fan-out과 상한을 넘기는 매장 배정 양쪽에 적용하며 비동기 큐로 우회하지 않음 |
| BRAND_STATE_CONFLICT | 409 | Maybe, after reading the brand again | `expectedVersion` 불일치, 보관된 브랜드 배정, 또는 소속 매장이 남은 브랜드의 보관 시도 |
| PICKUP_SLOT_FULL | 409 | Maybe | 슬롯 수용량 없음 |
| STOCK_NOT_AVAILABLE | 409 | Maybe | 판매 재고 부족 |
| COUPON_NOT_AVAILABLE | 409 | No | 쿠폰 만료·사용·조건 불충족 |
| POINT_BALANCE_INSUFFICIENT | 409 | No | 사용 가능 포인트 부족 |
| POINT_ADJUSTMENT_INSUFFICIENT_AVAILABLE | 409 | No | 감사형 음수 포인트 조정에 필요한 미예약 available PointLot 합이 부족함. 부분 차감이나 recovery pending을 만들지 않음 |
| RESERVATION_EXPIRED | 409 | No | 결제 전 예약 lease 만료 |
| PAYMENT_DECLINED | 422 | Depends | Provider가 명시적으로 거절 |
| PAYMENT_RESULT_UNKNOWN | 202 representation | Poll | 승인 결과 불명, reconciliation 중이며 확정 실패가 아님 |
| PAYMENT_METHOD_REGISTRATION_REJECTED | 422 | Yes, new authKey and key | Provider가 side effect 부재를 확인한 등록 거절. 같은 authKey는 재사용하지 않음 |
| PAYMENT_METHOD_REGISTRATION_UNKNOWN | 202 representation | Same-key read only | 등록 결과 불명 또는 수동 조사 중. 같은 authKey로 Provider를 다시 호출하지 않으며 등록 성공이 아님 |
| PAYMENT_METHOD_DEACTIVATION_UNKNOWN | 202 representation | Same-key read only | Provider 폐기 결과 불명. 결제수단은 이미 신규 결제에 사용할 수 없고 DELETE를 재호출하지 않음 |
| PAYMENT_METHOD_STATE_CONFLICT | 409 | No until state changes | ACTIVE가 아닌 결제수단의 default 지정·신규 승인 또는 허용되지 않은 lifecycle 명령 |
| PAYMENT_METHOD_AUTHORIZATION_REUSED | 409 | No | 같은 customer/provider/authKey hash가 다른 Idempotency-Key로 재사용되어 Provider 호출 전에 거부됨 |
| PAYMENT_METHOD_TOKEN_CONFLICT | 409 | Operator | Provider token의 owner/reference/표시 metadata/state가 exact ACTIVE binding과 달라 overwrite·재활성화 없이 수동 검토 필요 |
| PAYMENT_METHOD_PROVIDER_UNAVAILABLE | 503 | Yes, same key after correction | Provider credential·인증·계약·필수 설정 결함. 고객 거절이나 fake fallback이 아님 |
| PAYMENT_REFUND_UNKNOWN | 202 representation | Poll | 환불 결과 불명, reconciliation 중이며 성공 환불액에 아직 포함하지 않음 |
| PAYMENT_REFUND_EXCEEDED | 409 | No | 누적 환불이 승인액 초과 |
| PAYMENT_REFUND_UNRESOLVED | 409 | Yes, after refund reaches a definitive state | 선행 환불이 진행·재시도 대기·결과 불명·수동 검토 상태라 새 고객 취소 환불액을 안전하게 확정할 수 없음 |
| REFUND_PREVIEW_STALE | 409 | Yes, fetch a new preview | preview 이후 Order·Payment·Refund watermark·잔여 unit·복원 policy version 중 하나가 바뀌어 실행 입력을 재검증할 수 없음 |
| REFUND_OUTCOME_UNRESOLVED | 409 | Yes, after reconciliation | 미확정 Refund 때문에 새 점주 preview 또는 실행의 남은 승인액을 확정할 수 없음. 새 Provider 요청을 만들지 않음 |
| REPROCESSING_NOT_SAFE | 409 | No until integrity issue changes | 누락 Refund 제한 복구의 immutable snapshot·source·금액 guard 불충족 또는 S80에서 Payment lookup 외 owner step의 수동 재실행 요청 |
| REPROCESSING_APPROVER_MUST_DIFFER | 409 | Yes, with a different operator | 복구 제안자와 같은 actor가 승인·거절을 시도함 |
| REPROCESSING_PROPOSAL_EXPIRED | 409 | Yes, create a new proposal | 30분 승인 유효 구간 종료 |
| REPROCESSING_PROPOSAL_STALE | 409 | Yes, after reviewing current state | 제안 뒤 case·snapshot·Refund 상태가 바뀌어 fingerprint 재검증 실패 |
| SETTLEMENT_INPUT_UNAVAILABLE | 503 | Yes, after owner source/setup is corrected | 주문 생성 또는 완료 event의 immutable 정산 입력 source·version·금액 tie-out을 검증할 수 없음. fee/cost default나 현재 값 fallback 없음 |
| DEPENDENCY_UNAVAILABLE | 503 | Yes | 필수 외부·DB 의존성 일시 장애 |
| NOTIFICATION_DELIVERY_FAILED | operation-specific | Operator | 주문과 독립된 발송 실패 |
| SETTLEMENT_ALREADY_CONFIRMED | 409 | No | 확정 결과 직접 변경 시도 |
| DISPUTE_WINDOW_CLOSED | 409 | No | 이의제기 기간 종료 |
| DISPUTE_ALREADY_ACTIVE | 409 | No | 같은 SettlementItem에 `FILED` 또는 `UNDER_REVIEW` 이의제기가 이미 존재 |
| DISPUTE_REFILE_NOT_ALLOWED | 409 | No | immediate previous terminal ID, 새 evidence reference 또는 1회 제한을 충족하지 못한 재이의 |
| TEMPORARY_PASSWORD_NOT_REPLAYABLE | 409 | No automatic retry; exact lookup then new reset | 점주 발급·초기화의 같은 key terminal replay에서 1회 secret을 재현할 수 없음. 대상 account reference만 반환 |
| SUPPORT_SEARCH_RATE_LIMITED | 429 + Retry-After | Yes, after the current window | actor별 영속 5분/30회 exact-search budget 소진. 요청은 Vault/owner query 전에 중단되고 응답·Audit에 검색값을 넣지 않음 |
| VERIFICATION_REQUIRED | 403 | Yes, after matching verification | Case·Subject·Purpose·actor에 묶인 충분한 BASIC/ENHANCED verification이 없음 |
| VERIFICATION_LOCKED | 429 + Retry-After | Yes, after lock expiry | invalid proof 5회로 같은 Case+Subject binding이 30분 잠김 |
| DATA_ACCESS_GRANT_REQUIRED | 403 | Yes, after new matching grant | active matching Grant가 없거나 만료·철회·소진됨 |
| DATA_ACCESS_SCOPE_MISMATCH | 403 | No; request an allowed field | 요청 field가 Grant 또는 subject owner allowlist 밖임 |
| SUPPORT_ACTION_POLICY_DENIED | 409 | Yes, after state/policy/input changes | current typed ActionPolicy가 request/revision 생성을 거부함; owner command는 실행되지 않음 |
| SUPPORT_ACTION_REQUEST_STATE_CONFLICT | 409 | Yes, after reviewing current state | request가 해당 revision/approval/reassignment 전이를 허용하지 않음 |
| SUPPORT_ACTION_REQUEST_STALE | 409 | Yes, after reading current versions and creating a new revision when needed | request revision, target/policy/verification/permission 또는 Case/request version binding이 현재 값과 다름 |
| SUPPORT_ACTION_REQUEST_EXPIRED | 409 | Yes, with a new verified revision | exact verification-bound approval expiry에 도달함 (`now >= expiresAt`) |
| SUPPORT_APPROVER_MUST_DIFFER | 409 | Yes, with a distinct eligible actor | requester, executor, Support reviewer, Operations reviewer의 분리 또는 reviewer-as-executor 규칙 위반 |
| SUPPORT_INVESTIGATION_STATE_CONFLICT | 409 | Yes, after reading the current investigation | Operations investigation이 이미 terminal이거나 요청한 decision 전이를 허용하지 않음 |
| SUPPORT_ORDER_CHANGE_AUTHORIZATION_REQUIRED | 403 | Yes, after exact store authorization | ACCEPTED direct change에 exact confirmation 또는 action/policy-bound delegation이 없음 |
| SUPPORT_ORDER_CHANGE_AUTHORIZATION_EXPIRED | 409 | Yes, after new authorization | authorization boundary `now >= expiresAt`; expired authorization은 실행·소비되지 않음 |
| SUPPORT_ORDER_CHANGE_AUTHORIZATION_EXHAUSTED | 409 | Yes, after new authorization | delegation successful-use budget 소진; replay가 아닌 새 owner change는 실행되지 않음 |
| SUPPORT_ORDER_CHANGE_AUTHORIZATION_SCOPE_MISMATCH | 403 | No for this authorization; create exact authorization | store/action/policy 또는 confirmation의 request/revision/digest/target binding 불일치, STORE 책임 미수락이나 Support actor separation 위반 포함 |

HTTP와 retry 정책의 초기 계약은 `openapi/beanflow-v1.yaml`을 따른다.

주문 생성과 빠른 재주문의 `MANUAL_REVIEW`는 아직 처리 중이라는 뜻이 아니다. 해당
Idempotency-Key에는 `IDEMPOTENCY_MANUAL_REVIEW_REQUIRED`를 반환하고 `Retry-After`를 넣지 않는다.
클라이언트는 같은 key를 polling하지 않으며, 운영자는
[Fast Reorder Runbook](../operations/fast-reorder-runbook.md)의 읽기 전용 조사 절차를 따른다.
현재는 감사 가능한 해결 command가 없으므로 DB row를 직접 `COMPLETED`/`FAILED`로 바꾸거나
terminal response를 추정하지 않는다.

S70의 `RESOLUTION_REQUIRED`는 오류 envelope가 아니다. execution endpoint가 latest owner state를 잠근 뒤
`PREPARING`, `READY` 또는 `COMPLETED`를 확인했음을 나타내는 terminal 200 representation이며 Order와 store
authorization successful-use budget은 바뀌지 않는다. 실제 refund/benefit/settlement resolution 생성은 S80이
소유한다. S80의 `PARTIALLY_RESOLVED`, `RECONCILING`, `MANUAL_REVIEW`도 확정 성공으로 축약하지 않는 200
representation state다. Payment Provider timeout은 step `UNKNOWN`으로 남고, 안전한 lookup은 reconcile operation이
수행한다. `UNDETERMINED`의 Settlement `BLOCKED`는 503이 아니라 비용 귀속이 정해지지 않았다는 durable state다.

`REORDER_ITEMS_UNAVAILABLE.details`는 source line 순서로 정렬하고 같은 line에서는 reason
우선순위와 `optionId` 오름차순으로 정렬한다. stable reason은 다음과 같다.

| Item reason | Meaning |
|---|---|
| `SOURCE_OPTION_SELECTION_UNAVAILABLE` | legacy source line에 검증된 정규화 option ID snapshot이 없음 |
| `MENU_REMOVED` | source `menuId`가 현재 Merchant에 존재하지 않음 |
| `MENU_NOT_AVAILABLE` | 현재 Menu가 주문 가능 상태가 아님 |
| `OPTION_REMOVED` | snapshot의 `optionId`가 현재 Menu에 존재하지 않음 |
| `OPTION_NOT_AVAILABLE` | 현재 Option이 주문 가능 상태가 아님 |
| `MENU_CONFIGURATION_NOT_AVAILABLE` | 현재 정규화 메뉴·옵션 조합의 판매 가능한 MenuConfiguration이 없음 |

menu 자체가 사라지거나 판매 중지된 line은 그 상위 원인 하나만 반환한다. 이 오류와 기존
`PICKUP_SLOT_FULL`, `STOCK_NOT_AVAILABLE`, `COUPON_NOT_AVAILABLE`,
`POINT_BALANCE_INSUFFICIENT`는 DB/owner 조회 장애를 의미하지 않는다. owner 조회나 저장 실패는
`503 DEPENDENCY_UNAVAILABLE`이며 빈 item 목록, 현재값 추정 또는 부분 재주문으로 바꾸지 않는다.

`SETTLEMENT_INPUT_UNAVAILABLE`은 일시 DB 장애만 뜻하는 `DEPENDENCY_UNAVAILABLE`과
구분한다. Merchant 계약, Campaign burden, PointLot issuer/allocation 또는 immutable snapshot
자체가 누락·모순인 setup/source 실패다. 주문 생성에서는 Order와 모든 예약을 rollback하며,
완료 mapping에서는 V2 publication 성공으로 진행하지 않는다.

고객 주문 취소는 명령 트랜잭션 멱등성 모델이라 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를
반환하지 않는다. 같은 key에 다른 `orderId`·`reasonCode`·`detail`이 오면
`IDEMPOTENCY_KEY_REUSED`, 다른 key로 이미 취소된 주문을 다시 취소하면
`ORDER_STATE_CONFLICT`, Order 잠금 대기가 요청 timeout을 넘기면
`DEPENDENCY_UNAVAILABLE`이다.

선행 Refund가 `REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`,
`RECONCILING`, `MANUAL_REVIEW`이면 고객 취소는 Order 전이 전에
`PAYMENT_REFUND_UNRESOLVED`를 반환한다. 이 응답은 취소 성공이나 Refund 실패를 뜻하지
않는다. 선행 Refund가 `SUCCEEDED` 또는 명시적 `FAILED`로 확정된 뒤 같은
Idempotency-Key로 다시 요청할 수 있다.

`PAYMENT_RESULT_UNKNOWN`과 `PAYMENT_REFUND_UNKNOWN`은 Error envelope로 확정 실패를
반환하는 경우가 아니다. command가 접수됐지만 외부 결과가 불명확한 경우 202 body의
상태와 correlation ID로 표현한다. 같은 idempotency key/payload의 polling 성격 재시도는
새 Provider 부작용을 만들지 않는다.

`PAYMENT_METHOD_REGISTRATION_UNKNOWN`과 `PAYMENT_METHOD_DEACTIVATION_UNKNOWN`도 Error
envelope의 확정 실패가 아니다. 각각 target OpenAPI의 202 진행 representation으로 반환한다.
등록 202는 사용할 수 있는 PaymentMethod가 생겼다는 뜻이 아니며, 폐기 202는 Provider token 폐기
확정을 뜻하지 않지만 Tx D1 뒤 신규 결제에는 이미 사용할 수 없다. notice는 내부 attempt·failure·
manual-review 상태를 노출하지 않는다. `PAYMENT_METHOD_PROVIDER_UNAVAILABLE`은 raw Provider
code/message를 details에 포함하지 않으며 설정이 고쳐진 뒤 같은 key로만 재시도한다.

## S90 goodwill compensation error mapping

S90은 새 coarse 오류명을 추가하지 않고 existing stable envelope를 endpoint semantics에 맞게 사용한다.

| Code | HTTP | Retry | S90 meaning |
|---|---:|---|---|
| INVALID_REQUEST | 400 | after correction | benefit/template/share/digest/idempotency shape가 유효하지 않음 |
| ACCESS_DENIED | 403 | after authorization change | Case assignment/object scope 또는 executor separation 불일치 |
| VERIFICATION_REQUIRED | 403 | after step-up | action-bound BASIC/ENHANCED session이 없거나 만료됨 |
| RESOURCE_NOT_FOUND | 404 | no for same ID | Case/request/template 또는 owner fact 부재 |
| SUPPORT_ACTION_POLICY_DENIED | 409 | only after policy/input change | duplicate incident, rolling cap, `UNDETERMINED`, template/amount 등 current policy가 발급 거부 |
| SUPPORT_ACTION_REQUEST_STALE | 409 | refresh exact binding | request/payload/target/approval revision이 달라짐; policy head 변경은 기존 request에 소급하지 않음 |
| SUPPORT_ACTION_REQUEST_STATE_CONFLICT | 409 | after valid state transition | manager/Operations 승인이 준비되지 않았거나 notification retry 대상이 아님 |
| IDEMPOTENCY_KEY_REUSED | 409 | new key | 같은 actor/operation key를 다른 canonical payload에 재사용 |
| COMPENSATION_SOURCE_CONFLICT | 409 | reconcile owner fact | owner issuance source가 다른 payload에 이미 귀속됨 |
| DEPENDENCY_UNAVAILABLE | 503 | yes, exact command | DB/Audit/owner persistence failure; financial transaction이면 전체 rollback, post-commit Notification이면 terminal benefit 유지와 retry state |

`COMPENSATION_LIMIT_EXCEEDED`, `DUPLICATE_COMPENSATION`, `COMPENSATION_INVESTIGATION_REQUIRED` 후보는 S90 runtime
code로 승격하지 않았다. band/route는 성공 evaluation/request representation이고, execution denial은
`SUPPORT_ACTION_POLICY_DENIED`의 closed reason으로 처리한다.

## Proposed Support error-code candidates

| Code | HTTP/representation | Retry | Meaning |
|---|---:|---|---|
| SUPPORT_CASE_NOT_ACTIVE | 409 | future specialized mapping | resolved/closed Case에서 일반 작업 시도; S20은 `ORDER_STATE_CONFLICT`를 사용 |
| SUPPORT_CASE_NOT_ASSIGNED | 403 | future specialized mapping | privileged action의 current assignee 불일치; S20은 `ACCESS_DENIED`를 사용 |
| SUPPORT_SUBJECT_NOT_LINKED | 403 | after link | Case와 target Subject 관계 없음 |
| INSUFFICIENT_VERIFICATION | 403 | after step-up | purpose/action에 필요한 level 미달 |
| VERIFICATION_EXPIRED | 409 | new session | 만료된 session |
| DATA_ACCESS_GRANT_EXPIRED | 403 | new grant | 만료/철회/소진 grant |
| FIELD_SCOPE_NOT_ALLOWED | 403 | no | grant보다 넓은 필드 또는 R4 |
| AUDIT_WRITE_FAILED | 503 | yes | pre-reveal/high-risk Audit commit 실패; data/body 없음 |
| SUPPORT_ACTION_APPROVAL_REQUIRED | 409 representation | after approval | 실행 전 approval 필요 |
| PICKUP_SLOT_UNAVAILABLE | 409 | choose slot | new slot capacity 불가; old slot 유지 |
| PROFILE_FIELD_IMMUTABLE | 422 | adjustment workflow | R0/R4 direct change 금지 |
| DELIVERY_PROVIDER_OUTCOME_UNKNOWN | 202 representation | reconcile/poll | Provider 결과 불명; 새 dispatch 금지 |
| DELIVERY_STATE_CONFLICT | 409 | reconcile | 역순/terminal conflict |
| LEGAL_HOLD_ACTIVE | 409 | after release | retention deletion이 scoped hold에 차단됨 |
| RETENTION_DELETE_PARTIALLY_FAILED | 202/503 representation | worker/operator | 일부 component 삭제 실패 |

These codes are planning candidates, not stable runtime codes and not present in target/runtime OpenAPI. Each owning
Stage must validate whether the code, HTTP status and representation are endpoint-specific and promote only the typed
subset it implements. Similar-looking provider/DB failures must not be collapsed into a business conflict.

정산 이의제기 409는 DB 장애를 business conflict로 바꾼 결과가 아니다. 같은 Item 접수는
Item advisory lock으로 직렬화하고, 같은 actor·operation·Idempotency-Key는 별도 advisory
lock과 terminal 응답으로 수렴한다. persistence, Audit 또는 persistent publication 실패는
접수 전체를 rollback하고 `DEPENDENCY_UNAVAILABLE` 503으로 반환한다. 같은 key의 다른
canonical payload만 `IDEMPOTENCY_KEY_REUSED`다.
