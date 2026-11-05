create table leaderboard_invites (
  leaderboard_id uuid primary key references leaderboards(id) on delete cascade,
  invite_id uuid not null unique,
  active boolean not null,
  created_at timestamptz not null
);
