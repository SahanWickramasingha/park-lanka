-- Explicitly linked identities preserve legacy ownership without rewriting history.
alter table cashier_accounts add column legacy_owner_key varchar(160);
alter table cashier_accounts add constraint uq_cashier_legacy_owner unique (legacy_owner_key);
