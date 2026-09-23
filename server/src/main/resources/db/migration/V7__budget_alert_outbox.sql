create table llm_budget_alerts (
    id uuid primary key,
    window_type varchar(16) not null,
    window_start timestamptz not null,
    threshold_percent integer not null,
    delivered_at timestamptz,
    lease_until timestamptz,
    claim_token uuid,
    created_at timestamptz not null default now(),
    unique (window_type, window_start, threshold_percent)
);
create index llm_budget_alerts_pending_idx on llm_budget_alerts (delivered_at, lease_until);
