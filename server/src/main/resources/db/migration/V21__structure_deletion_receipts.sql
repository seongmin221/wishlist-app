-- B8b/B8c: one successful deletion per owner resource. A resend with the same version and impact token replays 204
-- before the live resource is looked up; preview tokens themselves are never stored. Kept for the account lifetime.
create table structure_deletion_receipts (
    owner_id uuid not null references app_users(id),
    kind varchar(16) not null check (kind in ('CATEGORY','PURPOSE')),
    resource_id uuid not null,
    expected_version integer not null check (expected_version > 0),
    impact_token char(64) not null check (impact_token ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null default clock_timestamp(),
    primary key(owner_id, kind, resource_id)
);
