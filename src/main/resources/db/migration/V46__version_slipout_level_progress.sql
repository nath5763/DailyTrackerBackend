-- Scores remain attached to the exact board on which they were earned.
-- Existing rows keep their original catalog IDs; revised boards use v6 IDs.
alter table slipout_level_progress add column catalog_id text;
update slipout_level_progress set catalog_id = case
  when level_number = 0 then 'slipout-tutorial-v3-00'
  when level_number <= 15 then 'slipout-level-v3-' || lpad(level_number::text, 2, '0')
  when level_number <= 60 then 'slipout-level-v4-' || lpad(level_number::text, 2, '0')
  else 'slipout-level-v5-' || lpad(level_number::text, 2, '0') end;
alter table slipout_level_progress alter column catalog_id set not null;
alter table slipout_level_progress drop constraint slipout_level_progress_pkey;
alter table slipout_level_progress add primary key (user_id, level_number, catalog_id);

alter table slipout_level_attempts add column catalog_id text;
update slipout_level_attempts set catalog_id = case
  when level_number = 0 then 'slipout-tutorial-v3-00'
  when level_number <= 15 then 'slipout-level-v3-' || lpad(level_number::text, 2, '0')
  when level_number <= 60 then 'slipout-level-v4-' || lpad(level_number::text, 2, '0')
  else 'slipout-level-v5-' || lpad(level_number::text, 2, '0') end;
alter table slipout_level_attempts alter column catalog_id set not null;
