-- Sharing time is optional and independent of the server's ordering time.
alter table wishlist_items add column client_created_at timestamptz;
