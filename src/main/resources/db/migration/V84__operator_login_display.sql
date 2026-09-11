CREATE TABLE operations_operator_login_display (
    actor_id uuid PRIMARY KEY,
    login_name varchar(200) NOT NULL,
    token_issued_at timestamptz NOT NULL,
    observed_at timestamptz NOT NULL,
    CONSTRAINT ck_operator_login_display_name CHECK (length(btrim(login_name)) BETWEEN 1 AND 200 AND login_name !~ '[[:cntrl:]]')
);

CREATE INDEX ix_operator_login_display_name ON operations_operator_login_display (lower(login_name), actor_id);
