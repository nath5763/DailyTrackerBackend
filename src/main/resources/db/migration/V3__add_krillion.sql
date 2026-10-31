alter table submissions drop constraint submissions_primary_score_check;

insert into games(id, slug, name, source_url, release_timezone, ranking_direction, active, created_at)
values ('f0000000-0000-4000-8000-000000000002', 'krillion', 'Krillion', 'https://krillion.io',
        'America/New_York', 'higher', true, now());
