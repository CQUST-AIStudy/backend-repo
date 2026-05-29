ALTER TABLE tap_user DROP CHECK chk_tap_user_role;

ALTER TABLE tap_user
  ADD CONSTRAINT chk_tap_user_role
  CHECK (role IN ('TEACHER', 'ADMIN', 'STUDENT'));
