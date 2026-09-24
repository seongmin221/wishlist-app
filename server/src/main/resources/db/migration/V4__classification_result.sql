alter table wishlist_items add column predicted_category_id varchar(128);
alter table wishlist_items add column predicted_purpose_id varchar(128);
alter table wishlist_items add column analysis_failure_code varchar(64);
alter table wishlist_items add column classified_at timestamptz;
alter table analysis_jobs add column model_snapshot varchar(128);
alter table analysis_jobs add column price_table_version varchar(64);
