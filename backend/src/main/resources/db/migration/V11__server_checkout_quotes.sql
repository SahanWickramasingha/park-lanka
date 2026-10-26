create table checkout_quotes (
 id varchar(36) primary key,
 session_id bigint not null references parking_sessions(id),
 branch_id bigint not null references branches(id),
 cashier_key varchar(160) not null,
 billing_cutoff timestamp(6) with time zone not null,
 expires_at timestamp(6) with time zone not null,
 amount numeric(12,2) not null,
 billable_minutes bigint not null,
 constraint ck_quote_expiry check (expires_at > billing_cutoff),
 constraint ck_quote_amount check (amount >= 0)
);
create index ix_checkout_quote_session on checkout_quotes(session_id);
alter table payments add column billing_cutoff timestamp(6) with time zone;
alter table payments add column quote_id varchar(36) references checkout_quotes(id);
