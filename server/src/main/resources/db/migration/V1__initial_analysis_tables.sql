create table wishlist_items (
    id uuid primary key,
    owner_id uuid not null,
    client_submission_id uuid not null,
    source_url text not null,
    analysis_status varchar(32) not null,
    lifecycle_status varchar(32) not null,
    version integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (owner_id, client_submission_id)
);

create table analysis_jobs (
    id uuid primary key,
    wishlist_item_id uuid not null references wishlist_items(id),
    generation integer not null,
    stage varchar(32) not null,
    attempt_count integer not null default 0,
    first_attempt_at timestamptz,
    browser_attempted boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (wishlist_item_id, generation)
);

create table outbox_events (
    id uuid primary key,
    analysis_job_id uuid not null references analysis_jobs(id),
    event_type varchar(32) not null,
    task_name varchar(255) not null unique,
    published_at timestamptz,
    lease_until timestamptz,
    created_at timestamptz not null default now()
);
create index outbox_events_dispatch_lease_idx on outbox_events (published_at, lease_until);

create table llm_budget_windows (
    id uuid primary key,
    window_type varchar(16) not null,
    window_start timestamptz not null,
    reserved_microusd bigint not null default 0,
    settled_microusd bigint not null default 0,
    ceiling_microusd bigint not null,
    unique (window_type, window_start)
);

create table llm_budget_reservations (
    id uuid primary key,
    request_id uuid not null unique,
    analysis_job_id uuid not null references analysis_jobs(id),
    generation integer not null,
    price_table_version varchar(64) not null,
    state varchar(16) not null,
    maximum_microusd bigint not null,
    actual_microusd bigint,
    lease_until timestamptz not null,
    created_at timestamptz not null default now(),
    settled_at timestamptz
);
create index llm_budget_reservations_expiry_idx on llm_budget_reservations (state, lease_until);
