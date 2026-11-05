insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at) values
('f0000000-0000-4000-8000-000000000016', 'strands', 'Strands', 'https://www.nytimes.com/games/strands', 'America/New_York', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000017', 'nyt-mini-crossword', 'NYT Mini Crossword', 'https://www.nytimes.com/crosswords/game/mini', 'America/New_York', 'lower', true, now());

update game_catalog set tracked_game_slug = slug where slug in ('strands', 'nyt-mini-crossword');
