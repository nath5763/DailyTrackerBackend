update submissions s
set metrics = jsonb_set(s.metrics, '{completionStatus}', '"WIN"'::jsonb, true)
from games g
where g.id = s.game_id
  and g.slug in ('fermi', 'krillion', 'pinpoint', 'queens', 'crossclimb', 'tango', 'zip')
  and (not (s.metrics ? 'completionStatus') or s.metrics ->> 'completionStatus' = 'UNKNOWN');

update submissions s
set metrics = jsonb_set(
    s.metrics,
    '{completionStatus}',
    to_jsonb(case
      when upper(coalesce(s.metrics ->> 'displayScore', '')) like 'X/%'
        or lower(coalesce(s.metrics ->> 'failed', 'false')) in ('true', '1')
        or s.primary_score = 7
      then 'LOSS'
      else 'WIN'
    end),
    true)
from games g
where g.id = s.game_id
  and g.slug = 'wordle'
  and (not (s.metrics ? 'completionStatus') or s.metrics ->> 'completionStatus' = 'UNKNOWN');
