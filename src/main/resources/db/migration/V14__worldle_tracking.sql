insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000010', 'worldle', 'Worldle',
        'https://worldle.teuteuf.fr/', 'UTC', 'lower', true, now());

update game_catalog set tracked_game_slug = 'worldle' where slug = 'worldle';
