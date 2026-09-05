SET LOCAL lock_timeout = '5s';

ALTER TABLE promotion_limited_campaign_command
    ADD COLUMN response_json text NOT NULL DEFAULT '{}';

ALTER TABLE promotion_limited_campaign_command
    ALTER COLUMN response_json DROP DEFAULT;
