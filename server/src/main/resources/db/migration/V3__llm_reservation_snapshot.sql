alter table llm_budget_reservations add column model_snapshot varchar(128) not null default 'pre-release';
alter table llm_budget_windows add constraint llm_budget_windows_nonnegative check (reserved_microusd >= 0 and settled_microusd >= 0 and ceiling_microusd >= 0);
