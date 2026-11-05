insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000009', 'connections', 'Connections',
        'https://www.nytimes.com/games/connections', 'America/New_York', 'lower', true, now());

update game_catalog set tracked_game_slug = 'connections' where slug = 'connections';
