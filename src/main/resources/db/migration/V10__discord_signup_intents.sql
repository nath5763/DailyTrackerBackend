create table discord_signup_intents (
  id uuid primary key,
  token_hash varchar(64) not null unique,
  oauth_state_hash varchar(64) unique,
  discord_user_id varchar(20) not null,
  guild_id varchar(20) not null,
  channel_id varchar(20) not null,
  leaderboard_id uuid,
  user_id uuid,
  expires_at timestamptz not null,
  consumed_at timestamptz
);
create index discord_signup_intents_expiry_idx on discord_signup_intents(expires_at);
