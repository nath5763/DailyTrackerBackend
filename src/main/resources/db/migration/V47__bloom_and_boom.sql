insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('d9e37311-4ad4-48c6-a892-efce99a1c3c2', 'bloom-and-boom', 'Bloom & Boom',
        '/daily/bloom-and-boom', 'UTC', 'lower', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('bloom-and-boom', 'Bloom & Boom',
        'Use row and column clues to uncover every bloom without waking a storm-bug.',
        'Logic and Puzzle', '/daily/bloom-and-boom', 'bloom-and-boom', 0);

create table bloom_and_boom_attempts (
  id uuid primary key,
  user_id uuid references profiles(user_id) on delete cascade,
  guest_token_hash varchar(64),
  puzzle_date date not null,
  puzzle_id varchar(80) not null,
  started_at timestamptz not null,
  ended_at timestamptz,
  outcome varchar(8) check (outcome in ('WON', 'LOST')),
  elapsed_ms bigint check (elapsed_ms is null or elapsed_ms >= 0),
  revealed jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  check ((user_id is not null) <> (guest_token_hash is not null))
);

create unique index bloom_and_boom_user_day_idx on bloom_and_boom_attempts(user_id, puzzle_date)
  where user_id is not null;
create unique index bloom_and_boom_guest_day_idx on bloom_and_boom_attempts(guest_token_hash, puzzle_date)
  where guest_token_hash is not null;
