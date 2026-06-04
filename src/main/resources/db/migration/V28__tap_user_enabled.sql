ALTER TABLE tap_user
  ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_tap_user_enabled ON tap_user(enabled);
