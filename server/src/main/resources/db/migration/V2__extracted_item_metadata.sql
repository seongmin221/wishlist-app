alter table wishlist_items add column product_name text;
alter table wishlist_items add column product_description text;
alter table wishlist_items add column product_image_url text;
alter table wishlist_items add column canonical_url text;

alter table analysis_jobs add column browser_attempt_count integer not null default 0;
alter table analysis_jobs add column first_browser_attempt_at timestamptz;
