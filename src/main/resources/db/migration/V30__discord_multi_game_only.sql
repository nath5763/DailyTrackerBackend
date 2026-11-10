delete from discord_channel_leaderboards mapping
using leaderboards board
where mapping.leaderboard_id = board.id and board.board_type = 'SINGLE_GAME';

delete from discord_group_dm_leaderboards mapping
using leaderboards board
where mapping.leaderboard_id = board.id and board.board_type = 'SINGLE_GAME';
