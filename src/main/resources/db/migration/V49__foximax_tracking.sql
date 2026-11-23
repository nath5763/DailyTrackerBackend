insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000049', 'foximax', 'FoxiMax',
        'https://foximax.com/', 'UTC', 'lower', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('foximax', 'FoxiMax',
        'Reveal hidden words by guessing letters; each miss adds another word.',
        'Word and Vocabulary', 'https://foximax.com/', 'foximax', 51);
