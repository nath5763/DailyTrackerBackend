insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000075', 'perceptle', 'Perceptle',
        'https://perceptle.io/', 'UTC', 'higher', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('perceptle', 'Perceptle',
        'Test five perception skills in quick daily challenges and compare the rounded score.',
        'Logic, Grid, and Number Puzzles', 'https://perceptle.io/', 'perceptle', 53);
