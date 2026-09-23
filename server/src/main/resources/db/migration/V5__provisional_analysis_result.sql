alter table analysis_jobs add column pending_product_name text;
alter table analysis_jobs add column pending_product_description text;
alter table analysis_jobs add column pending_product_image_url text;
alter table analysis_jobs add column pending_canonical_url text;
alter table analysis_jobs add column pending_category_id varchar(128);
alter table analysis_jobs add column pending_purpose_id varchar(128);
alter table analysis_jobs add column pending_failure_code varchar(64);
