create table purposes (
    id uuid primary key,
    owner_id uuid not null references app_users(id),
    name text not null check (char_length(name) between 1 and 40),
    description text check (char_length(description) <= 200),
    color_key varchar(16) not null check (color_key in ('CORAL','MUSTARD','PERIWINKLE','CYAN','MINT','PINK')),
    icon_key varchar(16) not null check (icon_key in ('HEART','HOME','PLANE','GIFT','TENT','MUSIC','STAR','BOOK')),
    lifecycle_status varchar(16) not null default 'ACTIVE' check (lifecycle_status in ('ACTIVE','ARCHIVED','DELETED')),
    version integer not null default 1 check (version > 0),
    membership_version integer not null default 1 check (membership_version > 0),
    activity_at timestamptz not null,
    activity_kind varchar(16) not null default 'CREATED' check (activity_kind in ('CREATED','CANDIDATE_ADDED')),
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    unique(owner_id,id)
);
create index purposes_active_activity on purposes(owner_id, activity_at desc, id desc) where lifecycle_status='ACTIVE';

-- No purpose table existed: stored purpose strings referenced nothing. Preserve the original text.
-- A review that was pending only for the cleared AI purpose has nothing left to confirm.
alter table wishlist_items add column legacy_purpose_id varchar(128);
update wishlist_items
set legacy_purpose_id = purpose_id,
    purpose_source = case when purpose_source = 'AI' then 'UNASSIGNED' else purpose_source end,
    review_status = case when purpose_source = 'AI' and review_status = 'PENDING' and category_source is distinct from 'AI'
        then 'NOT_REQUIRED' else review_status end,
    purpose_id = null
where purpose_id is not null;
alter table wishlist_items alter column purpose_id type uuid using null::uuid;
alter table wishlist_items add constraint wishlist_purpose_owner_fk
    foreign key (owner_id, purpose_id) references purposes(owner_id, id);
create index wishlist_active_purpose on wishlist_items(owner_id, purpose_id, created_at desc, id desc) where lifecycle_status='ACTIVE';

alter table mutation_receipts alter column category_id drop not null;
alter table mutation_receipts add column purpose_id uuid;
alter table mutation_receipts add constraint mutation_receipt_purpose_owner_fk
    foreign key (owner_id, purpose_id) references purposes(owner_id, id);
alter table mutation_receipts add constraint mutation_receipt_single_target
    check (num_nonnulls(category_id, purpose_id) = 1);

-- Distinguishes an AI "no purpose" judgement from a call that could not judge purposes.
alter table analysis_jobs add column pending_purpose_judged boolean not null default false;
