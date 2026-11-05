alter table submissions
    add column submission_method varchar(16) not null default 'SHARE_TEXT';

alter table submissions
    add constraint submissions_submission_method_check
    check (submission_method in ('SHARE_TEXT', 'MANUAL'));
