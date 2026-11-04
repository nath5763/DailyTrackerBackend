alter table billing_accounts add column stripe_price_id varchar(255);
alter table billing_accounts add column pending_checkout_price_id varchar(255);
