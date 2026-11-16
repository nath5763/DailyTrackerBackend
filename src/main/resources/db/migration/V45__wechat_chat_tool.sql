create table wechat_chat_tool_contexts (
  token_hash varchar(64) primary key references wechat_sessions(token_hash) on delete cascade,
  group_key varchar(64) not null,
  member_cipher varchar(512) not null,
  expires_at timestamptz not null
);

create table wechat_chat_tool_cards (
  id uuid primary key,
  leaderboard_id uuid not null references leaderboards(id) on delete cascade,
  group_key varchar(64) not null,
  activity_id varchar(256) not null unique,
  created_by_user_id uuid not null references app_users(id),
  created_at timestamptz not null,
  expires_at timestamptz not null,
  shared_at timestamptz
);
create index wechat_chat_tool_cards_active on wechat_chat_tool_cards(group_key, leaderboard_id, expires_at)
  where shared_at is not null;

create table wechat_chat_tool_updates (
  id uuid primary key,
  card_id uuid not null references wechat_chat_tool_cards(id) on delete cascade,
  submission_id uuid not null references submissions(id) on delete cascade,
  member_cipher varchar(512) not null,
  created_at timestamptz not null,
  processed_at timestamptz,
  attempts integer not null default 0,
  last_error_code varchar(40),
  unique (card_id, submission_id)
);
create index wechat_chat_tool_updates_pending on wechat_chat_tool_updates(created_at)
  where processed_at is null and attempts < 5;
