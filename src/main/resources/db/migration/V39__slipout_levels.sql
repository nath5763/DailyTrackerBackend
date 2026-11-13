create table slipout_level_attempts (
  id uuid primary key,
  user_id uuid not null references profiles(user_id) on delete cascade,
  level_number integer not null check (level_number between 1 and 15),
  started_at timestamptz not null,
  ended_at timestamptz,
  completed boolean,
  moves integer,
  history jsonb
);
create index slipout_level_attempts_user_idx on slipout_level_attempts(user_id, level_number, started_at desc);

create table slipout_level_progress (
  user_id uuid not null references profiles(user_id) on delete cascade,
  level_number integer not null check (level_number between 1 and 15),
  best_moves integer not null check (best_moves > 0),
  best_history jsonb not null,
  completed_at timestamptz not null,
  primary key (user_id, level_number)
);
