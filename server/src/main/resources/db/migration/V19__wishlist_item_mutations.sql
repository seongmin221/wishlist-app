-- B7: reanalysis receipts point at the owner's wishlist item.
-- Pre-B11 work tables carry no production data yet, so plain transactional constraint creation is used.
alter table wishlist_items add constraint wishlist_items_owner_id_unique unique (owner_id, id);

alter table mutation_receipts add column wishlist_item_id uuid;
alter table mutation_receipts add constraint mutation_receipt_item_owner_fk
    foreign key (owner_id, wishlist_item_id) references wishlist_items(owner_id, id);
alter table mutation_receipts drop constraint mutation_receipt_single_target;
alter table mutation_receipts add constraint mutation_receipt_single_target
    check (num_nonnulls(category_id, purpose_id, wishlist_item_id) = 1);
