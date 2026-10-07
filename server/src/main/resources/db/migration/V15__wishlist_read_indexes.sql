-- Ordered ACTIVE category reads also cover V11's owner/category prefixes.
create index wishlist_active_public_category_order
    on wishlist_items(owner_id, category_id, created_at desc, id desc)
    where lifecycle_status = 'ACTIVE';
create index wishlist_active_custom_category_order
    on wishlist_items(owner_id, custom_category_id, created_at desc, id desc)
    where lifecycle_status = 'ACTIVE';

-- HOME-02 uses owner order while computing the action group as a filter.
create index wishlist_active_owner_order
    on wishlist_items(owner_id, created_at desc, id desc)
    where lifecycle_status = 'ACTIVE';

drop index wishlist_active_public_category;
drop index wishlist_active_custom_category;
