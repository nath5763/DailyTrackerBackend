-- Letter Bag has its own playable feature card, so it is not an external catalog entry.
delete from game_catalog where slug = 'letter-bag';

-- Keep the tracked game and all existing submissions and leaderboards intact.
update games set name = 'Letter Bag' where slug = 'letter-bag';
