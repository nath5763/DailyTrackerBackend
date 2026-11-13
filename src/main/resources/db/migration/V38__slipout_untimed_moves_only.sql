update game_catalog
set description = 'Slide blocks to clear a path and guide the target through the exit.'
where slug = 'slipout';

update submissions
set metrics = (metrics - 'rankingSecondary' - 'rankingSecondaryDirection'
                       - 'completionTimeMs' - 'remainingTimeMs' - 'displayScore')
              || jsonb_build_object('rankingPrimary', primary_score,
                                    'rankingPrimaryDirection', 'ASCENDING',
                                    'displayScore', primary_score::integer::text ||
                                      case when primary_score = 1 then ' move' else ' moves' end)
where game_id = 'a3aa273b-fdd6-4e91-843a-710c2f69657e';
