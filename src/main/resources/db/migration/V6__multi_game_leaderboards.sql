alter table leaderboards add column board_type varchar(16);

update leaderboards set board_type = 'SINGLE_GAME';

alter table leaderboards alter column board_type set not null;
alter table leaderboards alter column game_id drop not null;

alter table leaderboards add constraint leaderboards_board_type_check
  check (board_type in ('SINGLE_GAME', 'MULTI_GAME'));

alter table leaderboards add constraint leaderboards_type_game_basis_check
  check (
    (board_type = 'SINGLE_GAME' and game_id is not null)
    or
    (board_type = 'MULTI_GAME' and game_id is null and creation_basis = 'SUBSCRIPTION')
  );
