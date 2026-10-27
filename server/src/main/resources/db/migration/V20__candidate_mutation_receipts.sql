-- B8a: a candidate move replays the result stored at move time, so the receipt keeps the response body.
-- Kept for the account lifetime like the other mutation receipts; deletion receipts are a separate table (V21).
create table candidate_mutation_receipts (
    owner_id uuid not null references app_users(id),
    operation varchar(64) not null,
    idempotency_key uuid not null,
    request_fingerprint char(64) not null,
    purpose_id uuid not null,
    result_json jsonb not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key(owner_id, operation, idempotency_key),
    foreign key(owner_id, purpose_id) references purposes(owner_id, id)
);
