insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at) values
('f0000000-0000-4000-8000-000000000060', 'loldle-classic', 'LoLdle Classic', 'https://loldle.net/', 'UTC', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000061', 'loldle-quote', 'LoLdle Quote', 'https://loldle.net/', 'UTC', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000062', 'loldle-ability', 'LoLdle Ability', 'https://loldle.net/', 'UTC', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000063', 'loldle-emoji', 'LoLdle Emoji', 'https://loldle.net/', 'UTC', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000064', 'loldle-splash', 'LoLdle Splash', 'https://loldle.net/', 'UTC', 'lower', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order) values
('loldle-classic', 'LoLdle Classic', 'Guess a League of Legends champion from attribute clues.', 'Fandom — Video Games', 'https://loldle.net/', 'loldle-classic', 160),
('loldle-quote', 'LoLdle Quote', 'Guess a League of Legends champion from an in-game quote.', 'Fandom — Video Games', 'https://loldle.net/', 'loldle-quote', 161),
('loldle-ability', 'LoLdle Ability', 'Guess a League of Legends champion from an ability icon.', 'Fandom — Video Games', 'https://loldle.net/', 'loldle-ability', 162),
('loldle-emoji', 'LoLdle Emoji', 'Guess a League of Legends champion from emojis.', 'Fandom — Video Games', 'https://loldle.net/', 'loldle-emoji', 163),
('loldle-splash', 'LoLdle Splash', 'Guess a League of Legends champion from splash art.', 'Fandom — Video Games', 'https://loldle.net/', 'loldle-splash', 164);
