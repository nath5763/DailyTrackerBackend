create table discord_group_dm_leaderboards (
  channel_id varchar(20) primary key,
  leaderboard_id uuid not null references leaderboards(id) on delete cascade,
  configured_by_user_id uuid not null references profiles(user_id) on delete restrict,
  configured_by_discord_user_id varchar(20) not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  check (channel_id ~ '^[0-9]{1,20}$'),
  check (configured_by_discord_user_id ~ '^[0-9]{1,20}$')
);

create index discord_group_dm_leaderboards_board_idx
  on discord_group_dm_leaderboards(leaderboard_id);
