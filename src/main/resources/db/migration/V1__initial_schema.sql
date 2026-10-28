create table app_users (
  id uuid primary key,
  normalized_email varchar(320) not null unique,
  created_at timestamptz not null,
  last_login_at timestamptz not null
);

create table profiles (
  user_id uuid primary key references app_users(id) on delete cascade,
  share_id uuid not null unique,
  display_name varchar(32) not null check (char_length(display_name) between 2 and 32),
  avatar_color varchar(7) not null check (avatar_color in ('#dfff4f','#ff8db6','#a8c7ff','#ffba6b','#bba7ff')),
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create table games (
  id uuid primary key,
  slug varchar(80) not null unique,
  name varchar(120) not null,
  source_url varchar(500) not null,
  release_timezone varchar(80) not null,
  ranking_direction varchar(8) not null check (ranking_direction in ('lower','higher')),
  active boolean not null,
  created_at timestamptz not null
);

create table leaderboards (
  id uuid primary key,
  owner_id uuid not null references profiles(user_id) on delete cascade,
  name varchar(48) not null check (char_length(name) between 2 and 48),
  created_at timestamptz not null
);

create table leaderboard_members (
  leaderboard_id uuid not null references leaderboards(id) on delete cascade,
  user_id uuid not null references profiles(user_id) on delete cascade,
  added_by uuid not null references profiles(user_id) on delete cascade,
  created_at timestamptz not null,
  primary key (leaderboard_id, user_id)
);

create table submissions (
  id uuid primary key,
  user_id uuid not null references profiles(user_id) on delete cascade,
  game_id uuid not null references games(id) on delete restrict,
  puzzle_key varchar(80) not null,
  played_on date not null,
  primary_score numeric(12,4) not null check (primary_score >= 1),
  metrics jsonb not null default '{}'::jsonb,
  raw_text text not null check (char_length(raw_text) between 1 and 10000),
  parser_version integer not null check (parser_version > 0),
  created_at timestamptz not null,
  unique (user_id, game_id, puzzle_key)
);

create table auth_magic_links (
  id uuid primary key,
  normalized_email varchar(320) not null,
  token_hash varchar(64) not null unique,
  request_ip_hash varchar(64) not null,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  consumed_at timestamptz
);

create table auth_sessions (
  id uuid primary key,
  user_id uuid not null references app_users(id) on delete cascade,
  token_hash varchar(64) not null unique,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  last_seen_at timestamptz not null
);

create index submissions_user_game_date_idx on submissions(user_id, game_id, played_on desc);
create index submissions_game_date_score_idx on submissions(game_id, played_on desc, primary_score);
create index leaderboard_members_user_idx on leaderboard_members(user_id, leaderboard_id);
create index auth_magic_links_email_created_idx on auth_magic_links(normalized_email, created_at desc);
create index auth_magic_links_ip_created_idx on auth_magic_links(request_ip_hash, created_at desc);
create index auth_sessions_user_idx on auth_sessions(user_id);
create index auth_sessions_expiry_idx on auth_sessions(expires_at);

insert into games(id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000001', 'fermi', 'Fermi.gg', 'https://fermi.gg', 'America/New_York', 'lower', true, now());
