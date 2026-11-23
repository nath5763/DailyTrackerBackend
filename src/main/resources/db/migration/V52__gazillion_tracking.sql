insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000076', 'gazillion', 'Gazillion',
        'https://gazillion.world/', 'UTC', 'higher', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('gazillion', 'Gazillion',
        'Trap five historical moments on a timeline and travel back through history with your score.',
        'Trivia and Knowledge', 'https://gazillion.world/', 'gazillion', 54);
