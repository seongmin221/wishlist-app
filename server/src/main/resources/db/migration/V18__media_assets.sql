-- B6: user-uploaded images and the item reference to them.
-- Rows are kept as DELETED tombstones so idempotency keys stay meaningful for the account lifetime.
-- Production is not deployed before B11 and the new item column is all null, so plain transactional index creation is used.
create table media_assets (
    id uuid primary key,
    owner_id uuid not null references app_users(id),
    idempotency_key uuid not null,
    request_fingerprint char(64) not null,
    object_key text not null unique,
    content_type varchar(32) not null,
    declared_byte_size integer not null,
    declared_width integer not null,
    declared_height integer not null,
    status varchar(16) not null default 'PENDING',
    byte_size integer,
    width integer,
    height integer,
    failure_reason varchar(32),
    upload_expires_at timestamptz not null,
    ready_at timestamptz,
    failed_at timestamptz,
    released_at timestamptz,
    deleted_at timestamptz,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    constraint media_assets_owner_key_unique unique (owner_id, idempotency_key),
    constraint media_assets_owner_id_unique unique (owner_id, id),
    constraint media_assets_status_check check (status in ('PENDING', 'READY', 'FAILED', 'DELETING', 'DELETED')),
    constraint media_assets_content_type_check check (content_type = 'image/jpeg'),
    constraint media_assets_declared_check check (declared_byte_size between 1 and 5242880
        and declared_width between 1 and 2048 and declared_height between 1 and 2048),
    constraint media_assets_ready_check check (status <> 'READY'
        or (byte_size is not null and width is not null and height is not null and ready_at is not null)),
    constraint media_assets_failed_check check (status <> 'FAILED' or (failure_reason is not null and failed_at is not null)),
    constraint media_assets_failure_reason_check check (failure_reason is null or failure_reason in ('SIZE_MISMATCH',
        'CONTENT_TYPE_MISMATCH', 'NOT_JPEG', 'UNSUPPORTED_JPEG', 'METADATA_PRESENT', 'DIMENSIONS_EXCEEDED',
        'DIMENSIONS_MISMATCH', 'DECODE_FAILED')),
    constraint media_assets_deleted_check check (status <> 'DELETED' or deleted_at is not null)
);

create index media_assets_pending_owner_idx on media_assets (owner_id, upload_expires_at) where status = 'PENDING';
-- One partial index per cleanup discovery condition.
create index media_assets_cleanup_pending_idx on media_assets (upload_expires_at, id) where status = 'PENDING';
create index media_assets_cleanup_failed_idx on media_assets (failed_at, id) where status = 'FAILED';
create index media_assets_cleanup_ready_idx on media_assets ((coalesce(released_at, ready_at)), id) where status = 'READY';
create index media_assets_cleanup_deleting_idx on media_assets (updated_at, id) where status = 'DELETING';

alter table wishlist_items
    add column media_asset_id uuid,
    add constraint wishlist_items_media_owner_fk foreign key (owner_id, media_asset_id) references media_assets (owner_id, id);
create unique index wishlist_items_media_asset_unique on wishlist_items (media_asset_id) where media_asset_id is not null;
