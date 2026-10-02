ALTER TABLE sys_user ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_auth_version CHECK (auth_version >= 0);
