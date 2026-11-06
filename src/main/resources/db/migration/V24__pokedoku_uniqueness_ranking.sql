update games
set ranking_direction = 'lower'
where slug = 'pokedoku';

with known_uniqueness as (
  select s.id,
         (case
            when coalesce(s.metrics ->> 'uniquenessScore', '') ~ '^[+]?[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$'
              then s.metrics ->> 'uniquenessScore'
            else s.metrics ->> 'rarityScore'
          end)::numeric as uniqueness,
         case when s.metrics ->> 'correctCells' ~ '^[0-9]+$'
              then (s.metrics ->> 'correctCells')::integer end as correct_cells
  from submissions s
  join games g on g.id = s.game_id
  where g.slug = 'pokedoku'
    and (coalesce(s.metrics ->> 'uniquenessScore', '') ~ '^[+]?[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$'
      or coalesce(s.metrics ->> 'rarityScore', '') ~ '^[+]?[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$')
)
update submissions s
set primary_score = known.uniqueness,
    metrics = (s.metrics - array[
        'rarityScore', 'rankingPrimary', 'rankingSecondary', 'rankingPrimaryDirection',
        'rankingSecondaryDirection', 'nativeScore', 'scoreValue', 'scoreRaw', 'scoreUnit',
        'scoreDirection', 'displayScore'
      ]) || jsonb_strip_nulls(jsonb_build_object(
        'uniquenessScore', known.uniqueness,
        'nativeScore', known.uniqueness,
        'scoreValue', known.uniqueness,
        'scoreRaw', case when known.uniqueness = trunc(known.uniqueness) then trunc(known.uniqueness)::text
                         else regexp_replace(known.uniqueness::text, '0+$', '') end,
        'rankingPrimary', known.uniqueness,
        'rankingSecondary', known.correct_cells,
        'rankingPrimaryDirection', 'ASCENDING',
        'rankingSecondaryDirection', 'DESCENDING',
        'scoreUnit', 'uniqueness',
        'scoreDirection', 'LOWER_IS_BETTER',
        'displayScore', 'UNIQ ' ||
          case when known.uniqueness = trunc(known.uniqueness) then trunc(known.uniqueness)::text
               else regexp_replace(known.uniqueness::text, '0+$', '') end ||
          case when known.correct_cells is null then '' else ' · ' || known.correct_cells || '/9' end
      ))
from known_uniqueness known
where s.id = known.id;

update submissions s
set metrics = s.metrics - array[
    'rankingPrimary', 'rankingSecondary', 'rankingPrimaryDirection',
    'rankingSecondaryDirection', 'nativeScore'
  ]
from games g
where g.id = s.game_id
  and g.slug = 'pokedoku'
  and coalesce(s.metrics ->> 'uniquenessScore', '') !~ '^[+]?[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$'
  and coalesce(s.metrics ->> 'rarityScore', '') !~ '^[+]?[0-9]+([.][0-9]+)?([eE][+-]?[0-9]+)?$';
