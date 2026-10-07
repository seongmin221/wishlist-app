-- Input cap 2,000 -> 2,500 raises the per-call maximum 496 -> 596 micro USD.
-- Ceilings scale proportionally. Reservation uses the lower of the stored and configured ceiling,
-- so current windows created with the previous defaults are raised here; other rows keep their values.
update llm_budget_windows set ceiling_microusd = 721000 where window_type = 'DAILY' and ceiling_microusd = 600000;
update llm_budget_windows set ceiling_microusd = 7210000 where window_type = 'MONTHLY' and ceiling_microusd = 6000000;
