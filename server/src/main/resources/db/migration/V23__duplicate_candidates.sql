-- B9b: duplicate pairs per owner. A row stores only the identity relation (bases) found at analysis finish;
-- the pair kind is derived from both items' live analysis state when read. Decided rows are never recomputed.
create table duplicate_candidates (
    id uuid primary key,
    owner_id uuid not null references app_users(id),
    new_item_id uuid not null,
    existing_item_id uuid not null,
    bases text[] not null,
    status varchar(16) not null default 'OPEN',
    decision varchar(16),
    decided_at timestamptz,
    version integer not null default 1,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    unique (owner_id, id),
    unique (new_item_id, existing_item_id),
    foreign key (owner_id, new_item_id) references wishlist_items(owner_id, id),
    foreign key (owner_id, existing_item_id) references wishlist_items(owner_id, id),
    constraint duplicate_candidates_distinct_items_check check (new_item_id <> existing_item_id),
    constraint duplicate_candidates_status_check check (status in ('OPEN', 'DECIDED', 'WITHDRAWN')),
    constraint duplicate_candidates_decision_check check (decision in ('KEEP_BOTH', 'DELETE_NEW', 'DELETE_EXISTING')),
    constraint duplicate_candidates_decided_check check ((status = 'DECIDED') = (decision is not null) and (decision is null) = (decided_at is null)),
    constraint duplicate_candidates_bases_check check (cardinality(bases) > 0
        and bases <@ array['CANONICAL_URL', 'GTIN', 'BRAND_MPN', 'SOURCE_URL']::text[]),
    constraint duplicate_candidates_version_check check (version > 0)
);
create index duplicate_candidates_open_new_idx on duplicate_candidates (new_item_id) where status = 'OPEN';
create index duplicate_candidates_existing_idx on duplicate_candidates (existing_item_id);

-- DUP-02 replays the result stored at decision time; kept for the account lifetime like the B8 receipts.
create table duplicate_decision_receipts (
    owner_id uuid not null references app_users(id),
    idempotency_key uuid not null,
    request_fingerprint char(64) not null,
    candidate_id uuid not null,
    result_json jsonb not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key (owner_id, idempotency_key),
    foreign key (owner_id, candidate_id) references duplicate_candidates(owner_id, id)
);
