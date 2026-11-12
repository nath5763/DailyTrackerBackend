insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('b7dbe59a-17c4-4f08-9d2c-756702291ece', 'letter-bag', 'Daily Letter Bag', '/daily/letter-bag', 'UTC', 'higher', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('letter-bag', 'Daily Letter Bag', 'Build words from a daily bag of letters in 90 seconds.', 'Word and Vocabulary', '/daily/letter-bag', 'letter-bag', 0);

create table letter_bag_attempts (
  user_id uuid not null references profiles(user_id) on delete cascade,
  puzzle_date date not null,
  started_at timestamptz not null,
  ended_at timestamptz,
  state jsonb not null,
  primary key (user_id, puzzle_date)
);
create index letter_bag_attempts_expiry_idx on letter_bag_attempts(started_at) where ended_at is null;

alter table submissions drop constraint submissions_submission_method_check;
alter table submissions add constraint submissions_submission_method_check
  check (submission_method in ('SHARE_TEXT', 'MANUAL', 'IN_APP'));
