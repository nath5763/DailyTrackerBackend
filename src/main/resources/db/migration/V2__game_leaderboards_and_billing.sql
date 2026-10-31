alter table profiles add column free_leaderboard_claimed_at timestamptz;

alter table leaderboards add column game_id uuid references games(id) on delete restrict;
alter table leaderboards add column creation_basis varchar(16);

update leaderboards
set game_id = (select id from games where slug = 'fermi');

with ranked as (
  select id, row_number() over (partition by owner_id order by created_at, id) as position
  from leaderboards
)
update leaderboards board
set creation_basis = case when ranked.position = 1 then 'FREE' else 'GRANDFATHERED' end
from ranked
where board.id = ranked.id;

update profiles profile
set free_leaderboard_claimed_at = owners.first_created_at
from (
  select owner_id, min(created_at) as first_created_at
  from leaderboards
  group by owner_id
) owners
where profile.user_id = owners.owner_id;

alter table leaderboards alter column game_id set not null;
alter table leaderboards alter column creation_basis set not null;
alter table leaderboards add constraint leaderboards_creation_basis_check
  check (creation_basis in ('FREE', 'SUBSCRIPTION', 'GRANDFATHERED'));

create index leaderboards_game_idx on leaderboards(game_id, created_at);
create index leaderboards_owner_idx on leaderboards(owner_id, created_at);

create table billing_accounts (
  user_id uuid primary key references app_users(id) on delete cascade,
  stripe_customer_id varchar(255) unique,
  stripe_subscription_id varchar(255) unique,
  subscription_status varchar(32),
  current_period_end timestamptz,
  cancel_at_period_end boolean not null default false,
  grace_until timestamptz,
  ever_subscribed_at timestamptz,
  last_event_created_at timestamptz,
  pending_checkout_session_id varchar(255),
  pending_checkout_url text,
  pending_checkout_expires_at timestamptz,
  updated_at timestamptz not null
);

create table processed_stripe_events (
  event_id varchar(255) primary key,
  event_type varchar(120) not null,
  event_created_at timestamptz not null,
  processed_at timestamptz not null
);
