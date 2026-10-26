alter table parking_sessions add column ticket_token varchar(36);
alter table parking_sessions add constraint uq_ticket_token unique (ticket_token);
