-- Level numbers now identify different puzzles; old move logs and scores cannot be reused.
delete from slipout_level_attempts;
delete from slipout_level_progress;
alter table slipout_level_attempts drop constraint slipout_level_attempts_level_number_check;
alter table slipout_level_attempts add constraint slipout_level_attempts_level_number_check check (level_number between 0 and 15);
alter table slipout_level_progress drop constraint slipout_level_progress_level_number_check;
alter table slipout_level_progress add constraint slipout_level_progress_level_number_check check (level_number between 0 and 15);
