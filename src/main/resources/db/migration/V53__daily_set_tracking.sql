insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000077', 'daily-set', 'The Daily Set',
        'https://setpuzzle.com/', 'America/Los_Angeles', 'lower', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('daily-set', 'The Daily Set',
        'Find six valid Sets from twelve cards, in Regular or shuffling Diablo mode.',
        'Logic, Grid, and Number Puzzles', 'https://setpuzzle.com/', 'daily-set', 55);
