insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000074', 'farflung', 'Farflung',
        'https://farflung.game/', 'UTC', 'higher', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('farflung', 'Farflung',
        'Pin countries that fit five daily prompts; rarer answers travel farther.',
        'Geography and Maps', 'https://farflung.game/', 'farflung', 52);
