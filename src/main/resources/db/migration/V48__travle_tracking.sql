insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('a12ce501-9a1a-462a-8784-cf3148bb9bc3', 'travle', 'Travle',
        'https://travle.earth/', 'UTC', 'lower', true, now());

update game_catalog
set tracked_game_slug = 'travle', source_url = 'https://travle.earth/'
where slug = 'travle';
