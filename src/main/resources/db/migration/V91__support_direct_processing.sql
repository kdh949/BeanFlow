SET LOCAL lock_timeout = '5s';


ALTER TABLE support_action_revision ADD COLUMN authorization_basis varchar(24) NOT NULL DEFAULT 'LEGACY' CHECK (authorization_basis IN ('LEGACY', 'SUPPORT_DIRECT'));

ALTER TABLE support_action_revision ALTER COLUMN verification_session_id DROP NOT NULL;

ALTER TABLE support_action_revision ADD COLUMN subject_link_id uuid REFERENCES support_case_subject_link(id);

ALTER TABLE support_compensation_request ADD COLUMN authorization_basis varchar(24) NOT NULL DEFAULT 'LEGACY' CHECK (authorization_basis IN ('LEGACY', 'SUPPORT_DIRECT'));

ALTER TABLE support_compensation_request ALTER COLUMN verification_session_id DROP NOT NULL;

ALTER TABLE support_compensation_request ADD COLUMN subject_link_id uuid REFERENCES support_case_subject_link(id);

ALTER TABLE support_profile_change ADD COLUMN authorization_basis varchar(24) NOT NULL DEFAULT 'LEGACY' CHECK (authorization_basis IN ('LEGACY', 'SUPPORT_DIRECT'));

ALTER TABLE support_profile_change ALTER COLUMN verification_session_id DROP NOT NULL;

ALTER TABLE support_profile_change ADD COLUMN subject_link_id uuid REFERENCES support_case_subject_link(id);

ALTER TABLE support_data_access_grant ADD COLUMN authorization_basis varchar(24) NOT NULL DEFAULT 'LEGACY' CHECK (authorization_basis IN ('LEGACY', 'SUPPORT_DIRECT'));

ALTER TABLE support_data_access_grant ALTER COLUMN verification_session_id DROP NOT NULL;

ALTER TABLE support_data_access_grant ADD COLUMN direct_authorized_at timestamptz;

ALTER TABLE support_break_glass_request ADD COLUMN authorization_basis varchar(24) NOT NULL DEFAULT 'LEGACY' CHECK (authorization_basis IN ('LEGACY', 'SUPPORT_DIRECT'));

ALTER TABLE support_break_glass_request ADD COLUMN direct_authorized_at timestamptz;

ALTER TABLE support_post_acceptance_resolution ADD COLUMN authorization_basis varchar(24) NOT NULL DEFAULT 'LEGACY' CHECK (authorization_basis IN ('LEGACY', 'SUPPORT_DIRECT'));

ALTER TABLE support_action_revision ADD CONSTRAINT chk_support_action_revision_direct_binding CHECK (
    (authorization_basis = 'LEGACY' AND verification_session_id IS NOT NULL)
    OR (authorization_basis = 'SUPPORT_DIRECT' AND verification_session_id IS NULL AND subject_link_id IS NOT NULL AND expires_at = created_at + INTERVAL '15 minutes')
);

ALTER TABLE support_compensation_request ADD CONSTRAINT chk_support_compensation_request_direct_binding CHECK (
    (authorization_basis = 'LEGACY' AND verification_session_id IS NOT NULL)
    OR (authorization_basis = 'SUPPORT_DIRECT' AND verification_session_id IS NULL AND subject_link_id IS NOT NULL)
);

ALTER TABLE support_profile_change ADD CONSTRAINT chk_support_profile_change_direct_binding CHECK (
    (authorization_basis = 'LEGACY' AND verification_session_id IS NOT NULL)
    OR (authorization_basis = 'SUPPORT_DIRECT' AND verification_session_id IS NULL AND subject_link_id IS NOT NULL)
);

ALTER TABLE support_compensation_request ADD COLUMN execution_expires_at timestamptz,
    ADD CONSTRAINT chk_support_compensation_direct_expiry CHECK (
        authorization_basis = 'LEGACY' OR (execution_expires_at IS NOT NULL AND execution_expires_at = created_at + INTERVAL '15 minutes' AND approval_route = 'NONE')
    );
ALTER TABLE support_compensation_request DROP CONSTRAINT support_compensation_request_evidence_basis_check,
    ADD CONSTRAINT support_compensation_request_evidence_basis_check CHECK (evidence_basis IN ('STORE_CONSENT', 'OPERATIONS_FINDING', 'CONTRACTUAL_RULE', 'SUPPORT_DECISION'));
ALTER TABLE support_post_acceptance_resolution DROP CONSTRAINT chk_support_resolution_actor,
    ADD CONSTRAINT chk_support_resolution_actor CHECK (authorization_basis = 'SUPPORT_DIRECT' OR requester_actor_id <> executor_actor_id);


ALTER TABLE support_data_access_grant DROP CONSTRAINT chk_support_data_access_grant_approval, ADD CONSTRAINT chk_support_data_access_grant_approval CHECK (
    (authorization_basis = 'LEGACY' AND (
        (risk = 'BASIC' AND approver_id IS NULL AND decided_at IS NULL)
        OR (risk = 'SENSITIVE' AND (
            (state IN ('REQUESTED', 'APPROVAL_PENDING', 'REVOKED') AND approver_id IS NULL AND decided_at IS NULL)
            OR (state IN ('ACTIVE', 'CONSUMED', 'EXPIRED', 'DENIED', 'REVOKED') AND approver_id IS NOT NULL
                AND approver_id <> requester_id AND decided_at IS NOT NULL)
        ))
    )) OR (authorization_basis = 'SUPPORT_DIRECT' AND (approver_id IS NULL AND decided_at IS NULL AND direct_authorized_at IS NOT NULL AND verification_session_id IS NULL AND state IN ('ACTIVE', 'CONSUMED', 'EXPIRED', 'REVOKED') AND expires_at IS NOT NULL AND expires_at = direct_authorized_at + CASE WHEN risk = 'BASIC' THEN INTERVAL '10 minutes' ELSE INTERVAL '5 minutes' END))
);

ALTER TABLE support_break_glass_request DROP CONSTRAINT chk_support_break_glass_approval, ADD CONSTRAINT chk_support_break_glass_approval CHECK (
    (authorization_basis = 'LEGACY' AND (
        (state IN ('APPROVAL_PENDING', 'REVOKED') AND approver_id IS NULL AND approved_at IS NULL AND expires_at IS NULL)
        OR (state = 'DENIED' AND approver_id IS NOT NULL AND approver_id <> requester_id AND approved_at IS NULL)
        OR (state IN ('ACTIVE', 'REVIEW_PENDING', 'REVIEWED', 'EXPIRED', 'REVOKED') AND approver_id IS NOT NULL
            AND approver_id <> requester_id AND approved_at IS NOT NULL AND expires_at IS NOT NULL)
    )) OR (authorization_basis = 'SUPPORT_DIRECT' AND (approver_id IS NULL AND approved_at IS NULL AND direct_authorized_at IS NOT NULL AND expires_at IS NOT NULL AND state IN ('ACTIVE', 'REVIEW_PENDING', 'REVIEWED', 'EXPIRED', 'REVOKED')))
);

ALTER TABLE support_break_glass_request DROP CONSTRAINT chk_support_break_glass_ttl, ADD CONSTRAINT chk_support_break_glass_ttl CHECK (
    (authorization_basis = 'LEGACY' AND (
        expires_at IS NULL OR expires_at = approved_at + INTERVAL '2 minutes'
    )) OR (authorization_basis = 'SUPPORT_DIRECT' AND (expires_at = direct_authorized_at + INTERVAL '2 minutes'))
);

ALTER TABLE support_data_access_grant ADD CONSTRAINT chk_support_grant_legacy_verification CHECK (
    authorization_basis <> 'LEGACY' OR verification_session_id IS NOT NULL
);
ALTER TABLE support_compensation_policy_version ADD COLUMN authorization_basis varchar(24) NOT NULL DEFAULT 'LEGACY' CHECK (authorization_basis IN ('LEGACY', 'SUPPORT_DIRECT'));

-- New immutable version copies the current numerical policy; historical semantics remain v1.
INSERT INTO support_compensation_policy_version (id, code, effective_at, low_amount_maximum_krw, high_amount_maximum_krw, supported_amount_maximum_krw, low_order_ratio_maximum_bps, created_at, authorization_basis)
SELECT '90000000-0000-0000-0000-000000000002', 'GOODWILL_SUPPORT_DIRECT_V2', CURRENT_TIMESTAMP,
    low_amount_maximum_krw, high_amount_maximum_krw, supported_amount_maximum_krw,
    low_order_ratio_maximum_bps, CURRENT_TIMESTAMP, 'SUPPORT_DIRECT'
FROM support_compensation_policy_version WHERE id = (SELECT current_version_id FROM support_compensation_policy_head WHERE name = 'GOODWILL');
INSERT INTO support_compensation_limit_rule (id, policy_version_id, scope, window_seconds, maximum_krw)
SELECT gen_random_uuid(), '90000000-0000-0000-0000-000000000002', scope, window_seconds, maximum_krw
FROM support_compensation_limit_rule WHERE policy_version_id = (SELECT current_version_id FROM support_compensation_policy_head WHERE name = 'GOODWILL');
UPDATE support_compensation_policy_head SET current_version_id = '90000000-0000-0000-0000-000000000002', updated_at = CURRENT_TIMESTAMP, version = version + 1 WHERE name = 'GOODWILL';
