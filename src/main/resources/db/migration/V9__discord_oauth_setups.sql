alter table discord_channel_leaderboards add column channel_name varchar(100);

create table discord_setups (
  id uuid primary key,
  user_id uuid not null references profiles(user_id) on delete cascade,
  leaderboard_id uuid not null references leaderboards(id) on delete cascade,
  state_hash varchar(64) not null unique,
  setup_hash varchar(64) unique,
  discord_user_id varchar(20),
  guild_id varchar(20),
  encrypted_token varchar(2048),
  expires_at timestamptz not null,
  consumed_at timestamptz,
  check (char_length(state_hash) = 64),
  check (setup_hash is null or char_length(setup_hash) = 64)
);
create index discord_setups_expiry_idx on discord_setups(expires_at);
