# Post-Acceptance Resolution Policy

> **Status:** No lifecycle rollback, explicit partial/unknown results and no cost-owner fallback are Accepted in ADR-085;
> exact state and outcome vocabulary is DRAFT until S80.

`PostAcceptanceResolutionCase`는 제조·준비·완료 사실을 되돌리지 않고 환불, 혜택, 보상과 정산 조정을 조정한다.

책임은 `CUSTOMER | STORE | PLATFORM | SHARED | UNDETERMINED`, 결과는 full/partial refund, customer/store/platform compensation, no-monetary resolution, manual settlement review 중 하나다. `PARTIALLY_RESOLVED`를 완료로 표시하지 않는다.

책임이 `UNDETERMINED`이면 Store 차감이나 Platform 부담으로 fallback하지 않는다. Payment timeout은 `UNKNOWN/RECONCILING`, Settlement는 확정 결과 overwrite 대신 immutable adjustment를 사용한다. Order는 PREPARING/READY/COMPLETED에서 과거 상태로 rollback하지 않는다.
