alter table wishlist_items
    add column review_status varchar(16) not null default 'NOT_REQUIRED',
    add column manual_completion_at timestamptz,
    add column category_id varchar(128),
    add column category_source varchar(16),
    add column category_missing_reason varchar(32),
    add column purpose_id varchar(128),
    add column purpose_source varchar(16) not null default 'UNASSIGNED',
    add column name_source varchar(16),
    add column image_source varchar(16),
    add column user_override_fields text[] not null default '{}',
    add column current_generation integer not null default 1;

-- Keep legacy predictions as diagnostics. Only READY categories become assignments.
update wishlist_items i
set current_generation = coalesce((select max(j.generation) from analysis_jobs j where j.wishlist_item_id = i.id), 1),
    category_id = case when analysis_status = 'READY' then predicted_category_id end,
    category_source = case when analysis_status = 'READY' and predicted_category_id is not null then 'AI' end,
    category_missing_reason = case
        when analysis_status = 'READY' and predicted_category_id is not null then null
        when analysis_failure_code = 'AI_ABSTAINED' then 'AI_ABSTAINED'
        when analysis_failure_code in ('AI_UNUSABLE_RESPONSE', 'AI_INVALID_CANDIDATE', 'AI_USAGE_OUT_OF_RANGE') then 'AI_RESPONSE_UNUSABLE'
        else 'EXTRACTION_UNRESOLVED'
    end,
    review_status = case
        when analysis_status = 'READY' and nullif(btrim(product_name), '') is not null and predicted_category_id is not null then 'PENDING'
        else 'NOT_REQUIRED'
    end,
    name_source = case when product_name is not null then 'AI' end,
    image_source = case when product_image_url is not null then 'AI' end;

alter table wishlist_items
    add constraint wishlist_items_analysis_status_check check (analysis_status in ('PROCESSING', 'READY', 'PARTIAL', 'FAILED_RETRYABLE', 'FAILED_TERMINAL')),
    add constraint wishlist_items_review_status_check check (review_status in ('NOT_REQUIRED', 'PENDING', 'CONFIRMED', 'DEFERRED')),
    add constraint wishlist_items_lifecycle_status_check check (lifecycle_status in ('ACTIVE', 'ARCHIVED', 'DELETED')),
    add constraint wishlist_items_version_check check (version > 0),
    add constraint wishlist_items_current_generation_check check (current_generation > 0),
    add constraint wishlist_items_category_source_check check (category_source in ('AI', 'USER', 'UNASSIGNED')),
    add constraint wishlist_items_category_missing_reason_check check (category_missing_reason in ('EXTRACTION_UNRESOLVED', 'AI_ABSTAINED', 'AI_RESPONSE_UNUSABLE', 'CUSTOM_CATEGORY_DELETED')),
    add constraint wishlist_items_category_assignment_check check (category_id is null or (category_source is not null and category_source in ('AI', 'USER') and category_missing_reason is null)),
    add constraint wishlist_items_purpose_source_check check (purpose_source in ('AI', 'USER', 'UNASSIGNED')),
    add constraint wishlist_items_ai_purpose_check check (purpose_source <> 'AI' or purpose_id is not null),
    add constraint wishlist_items_name_source_check check (name_source in ('AI', 'USER', 'UNASSIGNED')),
    add constraint wishlist_items_image_source_check check (image_source in ('AI', 'USER', 'UNASSIGNED')),
    add constraint wishlist_items_user_override_fields_check check (user_override_fields <@ array['NAME', 'BRAND', 'IMAGE', 'CATEGORY', 'PURPOSE']::text[]);
