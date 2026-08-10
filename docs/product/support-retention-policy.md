# Support Retention Policy

> **Policy status:** Initial policy. Legal review required before production.

| Category | Initial period | Expiry treatment |
|---|---:|---|
| transaction/withdrawal/payment/supply minimum record | 5 years | 법적 최소 필드만 분리 유지 |
| SupportCase content | close + 3 years | 원문 파기/비가역 처리 |
| compensation/approval/refund/settlement adjustment | transaction + 5 years | PII 최소화 |
| PII access audit | 2 years | 파기/비가역 subject reference |
| verification metadata | case close + 3 years | 파기/비식별화 |
| delivery contact/address active copy | terminal + 90 days | 원문 파기 |
| current courier location | terminal + 24 hours | 원문 파기 |
| raw provider webhook | 7 days encrypted | 원문 파기 |
| PII-free delivery state history | up to 5 years | 파기/통계화 |
| limited evidence | case close + 3 years | object와 index 삭제 |
| OTP/token/password/link secret | never persist | 해당 없음 |

기존 financial Audit 5년을 PII Audit 2년으로 대체하지 않는다. `AuditCategory`, `RetentionClass`, immutable PolicyVersion을 함께 저장하고, active PII와 legal-minimum records를 분리한다.

LegalHold는 사건·범주 범위, 분리된 요청/승인자, next review, expiry를 반드시 가지며 무기한일 수 없다. DB/Object/Index/Projection 삭제를 component별 상태로 기록하고 부분 실패를 `RETRY_SCHEDULED/FAILED/MANUAL_REVIEW`로 보존한다. Backup restore는 deletion ledger watermark를 재적용하고 검증 전 traffic을 차단한다.
