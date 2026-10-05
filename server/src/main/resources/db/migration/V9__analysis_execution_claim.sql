alter table analysis_jobs
    add column execution_token uuid,
    add column lease_until timestamptz,
    add column claimed_item_version integer,
    add constraint analysis_jobs_generation_check check (generation > 0);

-- Legacy RUNNING jobs have no execution identity. Recovery must reclaim them.
update analysis_jobs set lease_until = clock_timestamp()
where stage in ('GENERAL_RUNNING', 'BROWSER_RUNNING');

create index analysis_jobs_recovery_idx on analysis_jobs (stage, lease_until, id);
