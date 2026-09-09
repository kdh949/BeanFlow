SET LOCAL lock_timeout = '5s';

-- V69 only needed to allow limited-campaign drafts to carry complete cost terms while inactive.
-- The original V19 constraint already allowed that shape. Restore its fail-closed active guard so
-- a campaign cannot be activated while its settlement burden remains unresolved.
ALTER TABLE promotion_campaign
    DROP CONSTRAINT promotion_campaign_cost_burden_check,
    ADD CONSTRAINT promotion_campaign_cost_burden_check CHECK (
        (
            NOT active
            AND cost_bearer IS NULL
            AND platform_share_bps IS NULL
            AND store_share_bps IS NULL
        ) OR (
            cost_bearer IS NOT NULL
            AND platform_share_bps IS NOT NULL
            AND store_share_bps IS NOT NULL
            AND (
                (cost_bearer = 'PLATFORM' AND platform_share_bps = 10000 AND store_share_bps = 0)
                OR (cost_bearer = 'STORE' AND platform_share_bps = 0 AND store_share_bps = 10000)
                OR (
                    cost_bearer = 'SHARED'
                    AND platform_share_bps BETWEEN 1 AND 9999
                    AND store_share_bps BETWEEN 1 AND 9999
                    AND platform_share_bps + store_share_bps = 10000
                )
            )
        )
    );
