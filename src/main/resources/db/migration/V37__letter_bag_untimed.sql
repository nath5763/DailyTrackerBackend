update game_catalog
set description = 'Build words from a daily bag of 30 letters with no time limit. Rare letters earn bonus points.'
where slug = 'letter-bag';

drop index if exists letter_bag_attempts_expiry_idx;
