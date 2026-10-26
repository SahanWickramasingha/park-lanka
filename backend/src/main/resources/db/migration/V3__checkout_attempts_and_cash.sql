alter table payments add column request_key varchar(36);
alter table payments add column request_fingerprint varchar(64);
alter table payments add column cash_tendered numeric(12,2);
alter table payments add column change_due numeric(12,2);
alter table payments add constraint uq_checkout_request unique(request_key);
