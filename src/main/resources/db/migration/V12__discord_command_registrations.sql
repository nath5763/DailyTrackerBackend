create table discord_command_registrations (
  application_id varchar(20) not null,
  scope_id varchar(20) not null,
  command_name varchar(32) not null,
  definition_hash varchar(64) not null,
  registered_at timestamptz not null,
  primary key (application_id, scope_id, command_name)
);
