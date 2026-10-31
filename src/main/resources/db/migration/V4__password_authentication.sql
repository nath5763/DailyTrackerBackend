alter table app_users
  add column password_hash varchar(255),
  add column email_verified_at timestamptz;

alter table app_users alter column last_login_at drop not null;

update app_users
set email_verified_at = created_at;

drop table auth_magic_links;

create table auth_action_tokens (
  id uuid primary key,
  user_id uuid not null references app_users(id) on delete cascade,
  purpose varchar(24) not null check (purpose in ('EMAIL_VERIFICATION','PASSWORD_RESET')),
  token_hash varchar(64) not null unique,
  request_ip_hash varchar(64) not null,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  consumed_at timestamptz
);

create table auth_login_attempts (
  id uuid primary key,
  normalized_email_hash varchar(64) not null,
  request_ip_hash varchar(64) not null,
  created_at timestamptz not null
);

create index auth_action_tokens_user_purpose_created_idx
  on auth_action_tokens(user_id, purpose, created_at desc);
create index auth_action_tokens_ip_created_idx
  on auth_action_tokens(request_ip_hash, created_at desc);
create index auth_action_tokens_expiry_idx
  on auth_action_tokens(expires_at);
create index auth_login_attempts_email_created_idx
  on auth_login_attempts(normalized_email_hash, created_at desc);
create index auth_login_attempts_ip_created_idx
  on auth_login_attempts(request_ip_hash, created_at desc);
