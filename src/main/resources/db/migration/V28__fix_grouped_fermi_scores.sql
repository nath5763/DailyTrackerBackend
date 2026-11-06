with parsed as (
  select s.id,
         (regexp_match(s.raw_text,
           '([0-9]{1,3}(,[0-9]{3})+(\.[0-9]+)?)[[:space:]]*[×x][[:space:]]*score', 'i'))[1] as score_token
  from submissions s
  join games g on g.id = s.game_id
  where g.slug = 'fermi'
), corrected as (
  select id, score_token, replace(score_token, ',', '')::numeric as score_value
  from parsed
  where score_token is not null
)
update submissions s
set primary_score = c.score_value,
    metrics = s.metrics || jsonb_build_object(
      'scoreRaw', c.score_value::text,
      'scoreValue', c.score_value,
      'nativeScore', c.score_value,
      'rankingPrimary', c.score_value,
      'displayScore', c.score_token || '×'
    )
from corrected c
where s.id = c.id;
