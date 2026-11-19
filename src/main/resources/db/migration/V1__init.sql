-- MS-TRANSFER-LIMITS initial schema (see PROMPT section 6 and ADR-002).

CREATE TABLE account_channel_limit (
  account_id            VARCHAR(34)    NOT NULL,
  channel               VARCHAR(16)    NOT NULL CHECK (channel IN ('MOBILE_APP','WEB','BRANCH')),
  daily_limit           NUMERIC(19,2)  NOT NULL CHECK (daily_limit >= 0),
  per_operation_limit   NUMERIC(19,2)  NOT NULL CHECK (per_operation_limit >= 0),
  currency              CHAR(3)        NOT NULL DEFAULT 'MXN',
  version               BIGINT         NOT NULL DEFAULT 0,
  updated_at            TIMESTAMPTZ    NOT NULL,
  PRIMARY KEY (account_id, channel),
  CHECK (per_operation_limit <= daily_limit)
);

CREATE TABLE daily_consumption (
  account_id     VARCHAR(34)   NOT NULL,
  channel        VARCHAR(16)   NOT NULL,
  business_date  DATE          NOT NULL,
  consumed       NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (consumed >= 0),
  PRIMARY KEY (account_id, channel, business_date)
);

-- currency, daily_limit, per_operation_limit and consumed_today extend the base design: they keep a
-- snapshot of the original decision so an idempotent replay returns exactly the original result.
CREATE TABLE transfer_validation (
  transfer_id          UUID PRIMARY KEY,
  account_id           VARCHAR(34)   NOT NULL,
  channel              VARCHAR(16)   NOT NULL,
  amount               NUMERIC(19,2) NOT NULL CHECK (amount > 0),
  currency             CHAR(3)       NOT NULL DEFAULT 'MXN',
  status               VARCHAR(16)   NOT NULL CHECK (status IN ('APPROVED','REJECTED')),
  reason_code          VARCHAR(64),
  daily_limit          NUMERIC(19,2) NOT NULL,
  per_operation_limit  NUMERIC(19,2) NOT NULL,
  consumed_today       NUMERIC(19,2) NOT NULL,
  business_date        DATE          NOT NULL,
  created_at           TIMESTAMPTZ   NOT NULL,
  CHECK ((status = 'APPROVED') = (reason_code IS NULL))
);

CREATE INDEX ix_transfer_validation_account_date
  ON transfer_validation (account_id, business_date);
