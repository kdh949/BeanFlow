# Support Verification and Step-Up Controls

UNVERIFIED, BASIC and ENHANCED are ordered authorization evidence, not identity-certification claims. Session is Case+Subject+Purpose+action-bound, short-lived and revocable. BASIC cannot authorize ENHANCED work. New contact control alone cannot replace an old login/recovery channel.

Challenge provider calls occur outside long transactions. Intent/attempt is durable before sending. Timeout or malformed response remains pending/unknown, never success. Attempts are append-only with bounded retries, replay protection, lockout and redacted device/network hashes where justified.

Verification success does not grant PII or action access; DataAccessGrant and ActionPolicy remain separate checks.
