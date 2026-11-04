create table discord_account_links (
  discord_user_id varchar(20) primary key,
  user_id uuid not null unique references profiles(user_id) on delete cascade,
  linked_at timestamptz not null,
  updated_at timestamptz not null,
  check (discord_user_id ~ '^[0-9]{1,20}$')
);

create table discord_link_codes (
  id uuid primary key,
  user_id uuid not null references profiles(user_id) on delete cascade,
  code_hash varchar(64) not null unique,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  consumed_at timestamptz,
  check (char_length(code_hash) = 64),
  check (expires_at > created_at)
);

create index discord_link_codes_user_created_idx
  on discord_link_codes(user_id, created_at desc);
create index discord_link_codes_expiry_idx
  on discord_link_codes(expires_at);

create table discord_channel_leaderboards (
  guild_id varchar(20) not null,
  channel_id varchar(20) not null,
  leaderboard_id uuid not null references leaderboards(id) on delete cascade,
  configured_by_user_id uuid not null references profiles(user_id) on delete restrict,
  configured_by_discord_user_id varchar(20) not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  primary key (guild_id, channel_id),
  check (guild_id ~ '^[0-9]{1,20}$'),
  check (channel_id ~ '^[0-9]{1,20}$'),
  check (configured_by_discord_user_id ~ '^[0-9]{1,20}$')
);

create index discord_channel_leaderboards_board_idx
  on discord_channel_leaderboards(leaderboard_id);

create table processed_discord_interactions (
  interaction_id varchar(20) primary key,
  discord_user_id varchar(20) not null,
  interaction_type integer not null,
  command_name varchar(32) not null,
  outcome varchar(64) not null,
  processed_at timestamptz not null,
  check (interaction_id ~ '^[0-9]{1,20}$'),
  check (discord_user_id ~ '^[0-9]{1,20}$')
);

create index processed_discord_interactions_user_command_time_idx
  on processed_discord_interactions(discord_user_id, command_name, processed_at desc);
create index processed_discord_interactions_processed_idx
  on processed_discord_interactions(processed_at);
