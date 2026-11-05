insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at) values
('f0000000-0000-4000-8000-000000000013', 'semantle', 'Semantle', 'https://semantle.com/', 'UTC', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000014', 'contexto', 'Contexto', 'https://contexto.me/', 'UTC', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000015', 'redactle', 'Redactle', 'https://redactle.net/', 'UTC', 'lower', true, now());

update game_catalog set tracked_game_slug = slug where slug in ('semantle', 'contexto', 'redactle');
