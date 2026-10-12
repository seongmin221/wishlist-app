-- B5: extracted product metadata, provisional metadata per job, and recovery bookkeeping.
-- Pre-B11 work tables carry no production data yet, so plain transactional index creation is used.
alter table wishlist_items
    add column product_brand text,
    add column product_price numeric(19,4),
    add column product_currency char(3),
    add column merchant_name text,
    add column metadata_checked_at timestamptz,
    add constraint wishlist_items_price_currency_pair_check check ((product_price is null) = (product_currency is null)),
    add constraint wishlist_items_price_nonnegative_check check (product_price is null or product_price >= 0),
    add constraint wishlist_items_currency_format_check check (product_currency is null or product_currency ~ '^[A-Z]{3}$');

alter table analysis_jobs
    add column pending_brand text,
    add column pending_price numeric(19,4),
    add column pending_currency char(3),
    add column pending_merchant text,
    add column recovery_seq integer not null default 0,
    add column recovery_check_at timestamptz,
    add constraint analysis_jobs_pending_price_currency_pair_check check ((pending_price is null) = (pending_currency is null)),
    add constraint analysis_jobs_recovery_seq_check check (recovery_seq >= 0);

create index analysis_jobs_pending_recovery_idx on analysis_jobs (recovery_check_at nulls first, updated_at, id)
    where stage in ('GENERAL_PENDING', 'BROWSER_PENDING');
create index analysis_jobs_running_recovery_idx on analysis_jobs (recovery_check_at nulls first, lease_until, id)
    where stage in ('GENERAL_RUNNING', 'BROWSER_RUNNING');

alter table outbox_events add column not_before timestamptz;
create index outbox_events_job_created_idx on outbox_events (analysis_job_id, created_at desc, id desc);
