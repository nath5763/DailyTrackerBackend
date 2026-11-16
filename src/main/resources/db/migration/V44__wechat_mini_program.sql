create table wechat_identities (
  id uuid primary key,
  app_id varchar(80) not null,
  openid_hash varchar(64) not null,
  user_id uuid not null references app_users(id) on delete cascade,
  provisional boolean not null,
  linked_at timestamptz not null,
  unlinked_at timestamptz
);
create unique index wechat_identity_openid_active on wechat_identities(app_id, openid_hash) where unlinked_at is null;
create unique index wechat_identity_user_active on wechat_identities(app_id, user_id) where unlinked_at is null;

create table wechat_sessions (
  token_hash varchar(64) primary key references auth_sessions(token_hash) on delete cascade,
  session_key_cipher varchar(255) not null,
  key_expires_at timestamptz not null,
  group_claim_key varchar(64),
  group_claim_expires_at timestamptz,
  group_claim_consumed_at timestamptz
);

create table wechat_link_codes (
  token_hash varchar(64) primary key,
  user_id uuid not null references app_users(id) on delete cascade,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  consumed_at timestamptz
);
create index wechat_link_codes_user_created on wechat_link_codes(user_id, created_at desc);

create table wechat_share_refs (
  token_hash varchar(64) primary key,
  leaderboard_id uuid not null references leaderboards(id) on delete cascade,
  created_by_user_id uuid not null references app_users(id) on delete cascade,
  created_at timestamptz not null,
  expires_at timestamptz not null
);
create index wechat_share_refs_expiry on wechat_share_refs(expires_at);

create table wechat_group_links (
  id uuid primary key,
  app_id varchar(80) not null,
  group_key varchar(64) not null,
  leaderboard_id uuid not null references leaderboards(id) on delete cascade,
  linked_by_user_id uuid not null references app_users(id),
  linked_at timestamptz not null,
  disconnected_at timestamptz
);
create unique index wechat_group_link_active on wechat_group_links(app_id, group_key) where disconnected_at is null;
create index wechat_group_links_board_active on wechat_group_links(leaderboard_id) where disconnected_at is null;

create table wechat_rate_events (
  id uuid primary key,
  subject_hash varchar(64) not null,
  created_at timestamptz not null
);
create index wechat_rate_events_subject_created on wechat_rate_events(subject_hash, created_at desc);

create table wechat_audit_events (
  id uuid primary key,
  action varchar(40) not null,
  actor_user_id uuid references app_users(id) on delete set null,
  leaderboard_id uuid references leaderboards(id) on delete set null,
  created_at timestamptz not null
);
