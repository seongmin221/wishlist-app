-- B9a: shared READY extraction cache and seller-independent identifiers.
-- products is keyed by canonical URL; aliases map normalized share URLs (never raw share URLs) to it.
-- Items copy a cache row once; product_cache_id records provenance only and is never followed for display.
create table products (
    id uuid primary key,
    canonical_url text not null unique,
    product_name text not null,
    product_description text,
    product_image_url text,
    product_brand text,
    product_price numeric(19,4),
    product_currency char(3),
    merchant_name text,
    product_gtin char(14),
    product_mpn_key text,
    extracted_at timestamptz not null,
    updated_at timestamptz not null default clock_timestamp(),
    constraint products_price_currency_pair_check check ((product_price is null) = (product_currency is null)),
    constraint products_gtin_format_check check (product_gtin is null or product_gtin ~ '^[0-9]{14}$')
);

create table product_url_aliases (
    normalized_source_url text primary key,
    product_id uuid not null references products(id),
    updated_at timestamptz not null default clock_timestamp()
);
create index product_url_aliases_product_idx on product_url_aliases (product_id);

alter table wishlist_items
    add column normalized_source_url text,
    add column product_gtin char(14),
    add column product_mpn_key text,
    add column product_cache_id uuid references products(id),
    add constraint wishlist_items_gtin_format_check check (product_gtin is null or product_gtin ~ '^[0-9]{14}$');

alter table analysis_jobs
    add column pending_gtin char(14),
    add column pending_mpn_key text,
    add column pending_checked_at timestamptz,
    add column pending_product_cache_id uuid references products(id);

-- Duplicate detection looks up an owner's active items by each identity value.
create index wishlist_items_active_canonical_idx on wishlist_items (owner_id, canonical_url) where lifecycle_status = 'ACTIVE';
create index wishlist_items_active_gtin_idx on wishlist_items (owner_id, product_gtin) where lifecycle_status = 'ACTIVE' and product_gtin is not null;
create index wishlist_items_active_mpn_idx on wishlist_items (owner_id, product_mpn_key) where lifecycle_status = 'ACTIVE' and product_mpn_key is not null;
create index wishlist_items_active_source_idx on wishlist_items (owner_id, normalized_source_url) where lifecycle_status = 'ACTIVE';
