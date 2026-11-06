update games
set ranking_direction = 'lower'
where slug = 'fermi';

update submissions
set metrics = jsonb_set(
    jsonb_set(metrics, '{scoreDirection}', '"LOWER_IS_BETTER"'::jsonb, true),
    '{rankingPrimaryDirection}', '"ASCENDING"'::jsonb, true)
where game_id = (select id from games where slug = 'fermi');
