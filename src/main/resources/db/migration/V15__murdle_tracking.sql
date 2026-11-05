insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000011', 'murdle', 'Murdle',
        'https://murdle.com/', 'UTC', 'lower', true, now());

update game_catalog set tracked_game_slug = 'murdle' where slug = 'murdle';
