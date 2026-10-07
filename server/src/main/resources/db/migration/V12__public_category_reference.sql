-- Existing legacy values are preserved. New or changed references must be registry IDs.
-- Validate the constraint after auditing legacy references during rollout.
alter table wishlist_items add constraint wishlist_public_category_fk
    foreign key (category_id) references public_categories(id) not valid;
