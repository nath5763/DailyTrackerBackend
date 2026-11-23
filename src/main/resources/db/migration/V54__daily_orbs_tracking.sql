insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000078', 'daily-orbs', 'Daily Orbs',
        'https://dailyorbs.com/', 'America/Toronto', 'higher', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('daily-orbs', 'Daily Orbs',
        'Solve four daily trivia puzzles and collect at least six of twelve colored orbs.',
        'Trivia and Knowledge', 'https://dailyorbs.com/', 'daily-orbs', 56);
