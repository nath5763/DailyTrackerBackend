insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000079', 'minute-cryptic', 'Minute Cryptic',
        'https://www.minutecryptic.com/', 'UTC', 'lower', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('minute-cryptic', 'Minute Cryptic',
        'Solve one daily cryptic clue while using as few hints as possible.',
        'Word and Vocabulary', 'https://www.minutecryptic.com/', 'minute-cryptic', 57);
