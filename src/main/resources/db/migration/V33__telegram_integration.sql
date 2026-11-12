create table telegram_account_links (
  telegram_user_id bigint primary key,
  user_id uuid not null unique references profiles(user_id) on delete cascade,
  linked_at timestamptz not null
);

create table telegram_link_codes (
  token_hash varchar(64) primary key,
  user_id uuid not null references profiles(user_id) on delete cascade,
  expires_at timestamptz not null,
  consumed_at timestamptz
);
create index telegram_link_codes_expiry_idx on telegram_link_codes(expires_at);

create table telegram_group_leaderboards (
  chat_id bigint primary key,
  leaderboard_id uuid not null references leaderboards(id) on delete cascade,
  title varchar(255),
  configured_by_user_id uuid not null references profiles(user_id),
  configured_at timestamptz not null
);
create index telegram_group_leaderboards_board_idx on telegram_group_leaderboards(leaderboard_id);

create table telegram_signup_intents (
  token_hash varchar(64) primary key,
  telegram_user_id bigint not null,
  chat_id bigint,
  leaderboard_id uuid references leaderboards(id) on delete set null,
  user_id uuid references profiles(user_id) on delete set null,
  expires_at timestamptz not null,
  challenge_at timestamptz,
  confirmed_at timestamptz,
  consumed_at timestamptz
);
create index telegram_signup_intents_expiry_idx on telegram_signup_intents(expires_at);

create table telegram_pending_flows (
  telegram_user_id bigint primary key,
  origin_chat_id bigint,
  flow varchar(32) not null,
  step varchar(32) not null,
  leaderboard_id uuid,
  game_slug varchar(80),
  range_key varchar(8),
  share_text text,
  callback_token varchar(32) not null,
  expires_at timestamptz not null
);

create table processed_telegram_updates (
  update_id bigint primary key,
  processed_at timestamptz not null
);
