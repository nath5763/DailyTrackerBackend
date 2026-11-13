insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('a3aa273b-fdd6-4e91-843a-710c2f69657e', 'slipout', 'SlipOut', '/daily/slipout', 'UTC', 'lower', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('slipout', 'SlipOut',
        'Slide wooden blocks to free the target in 90 seconds.', 'Logic and Puzzle', '/daily/slipout', 'slipout', 0);

create table slipout_attempts (
  id uuid primary key,
  user_id uuid not null references profiles(user_id) on delete cascade,
  puzzle_date date not null,
  puzzle_id varchar(80) not null,
  started_at timestamptz not null,
  ended_at timestamptz,
  completed boolean,
  moves integer,
  completion_time_ms bigint,
  remaining_time_ms bigint,
  history jsonb,
  created_at timestamptz not null default now()
);
create index slipout_attempts_user_date_idx on slipout_attempts(user_id, puzzle_date, started_at desc);
create unique index slipout_attempts_one_active_idx on slipout_attempts(user_id, puzzle_date) where ended_at is null;
