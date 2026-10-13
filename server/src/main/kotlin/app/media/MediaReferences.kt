package app.media

/**
 * The one place that decides whether a media row is still in use. B10 adds archive snapshot references here.
 * A DELETED item keeps protecting its image for the grace period after its last change, so a missed release time in an
 * item deletion still leaves 24 hours before the object goes.
 */
object MediaReferences {
    fun referenced(alias: String) = """exists (select 1 from wishlist_items ref
        where ref.owner_id=$alias.owner_id and ref.media_asset_id=$alias.id
          and (ref.lifecycle_status in ('ACTIVE','ARCHIVED') or ref.updated_at > clock_timestamp() - interval '24 hours'))"""

    /**
     * Discovery: one branch per status so each uses its partial index and stops at the batch size, instead of one OR
     * predicate that re-evaluates every old linked READY row each run.
     */
    fun discovery(alias: String) = """select id from (
        (select $alias.id, $alias.updated_at from media_assets $alias where $alias.status='PENDING'
            and $alias.upload_expires_at <= clock_timestamp() - interval '24 hours' order by $alias.upload_expires_at, $alias.id limit ?)
        union all (select $alias.id, $alias.updated_at from media_assets $alias where $alias.status='FAILED'
            and $alias.failed_at <= clock_timestamp() - interval '24 hours' order by $alias.failed_at, $alias.id limit ?)
        union all (select $alias.id, $alias.updated_at from media_assets $alias where $alias.status='READY'
            and coalesce($alias.released_at, $alias.ready_at) <= clock_timestamp() - interval '24 hours' and not ${referenced(alias)}
            order by coalesce($alias.released_at, $alias.ready_at), $alias.id limit ?)
        union all (select $alias.id, $alias.updated_at from media_assets $alias where $alias.status='DELETING'
            and $alias.updated_at <= clock_timestamp() - interval '2 minutes' order by $alias.updated_at, $alias.id limit ?)
    ) candidates order by updated_at, id limit ?"""

    /** Rows the cleanup may delete now, including DELETING rows whose lease expired. */
    fun cleanupCandidate(alias: String) = """(
        ($alias.status='PENDING' and $alias.upload_expires_at <= clock_timestamp() - interval '24 hours')
        or ($alias.status='FAILED' and $alias.failed_at <= clock_timestamp() - interval '24 hours')
        or ($alias.status='READY' and coalesce($alias.released_at, $alias.ready_at) <= clock_timestamp() - interval '24 hours'
            and not ${referenced(alias)})
        or ($alias.status='DELETING' and $alias.updated_at <= clock_timestamp() - interval '2 minutes'))"""
}
