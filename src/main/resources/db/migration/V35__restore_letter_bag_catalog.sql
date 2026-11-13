insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order)
values ('letter-bag', 'Letter Bag', 'Build words from a daily bag of letters in 90 seconds.',
        'Word and Vocabulary', '/daily/letter-bag', 'letter-bag', 0)
on conflict (slug) do update set name = excluded.name;
