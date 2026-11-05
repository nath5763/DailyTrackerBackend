insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000019', 'quordle', 'Quordle',
        'https://www.merriam-webster.com/games/quordle/', 'UTC', 'lower', true, now());

update game_catalog set tracked_game_slug = 'quordle' where slug = 'quordle';
