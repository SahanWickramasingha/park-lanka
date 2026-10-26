-- A committed receipt makes retries explicit, including imports of an empty source.
create table data_import_receipts (
 source_fingerprint varchar(64) primary key,
 source_version varchar(32) not null,
 imported_at timestamp(6) with time zone not null,
 row_count bigint not null
);
