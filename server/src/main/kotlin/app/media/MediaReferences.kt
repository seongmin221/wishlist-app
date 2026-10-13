package app.media

import java.time.Duration

/**
 * The one place that decides whether a media row is still in use and whether cleanup may take it. B10 adds archive
 * snapshot references to [referenced].
 */
object MediaReferences {
    private val grace = seconds(MediaLimits.CLEANUP_GRACE)
    private val lease = seconds(MediaLimits.DELETE_LEASE)

    /**
     * A DELETED item keeps protecting its image for the grace period after its last change, so a missed release in an
     * item deletion still leaves 24 hours before the object goes.
     */
    fun referenced(alias: String) = """exists (select 1 from wishlist_items ref
        where ref.owner_id=$alias.owner_id and ref.media_asset_id=$alias.id
          and (ref.lifecycle_status in ('ACTIVE','ARCHIVED') or ref.updated_at > clock_timestamp() - $grace))"""

    /** Per-status condition and the column its partial index orders by. */
    private fun branches(alias: String) = listOf(
        "$alias.status='PENDING' and $alias.upload_expires_at <= clock_timestamp() - $grace" to "$alias.upload_expires_at",
        "$alias.status='FAILED' and $alias.failed_at <= clock_timestamp() - $grace" to "$alias.failed_at",
        // Linked READY rows have no unreferenced_since and are not in the partial index at all.
        "$alias.status='READY' and $alias.unreferenced_since is not null and $alias.unreferenced_since <= clock_timestamp() - $grace " +
            "and not ${referenced(alias)}" to "$alias.unreferenced_since",
        "$alias.status='DELETING' and $alias.updated_at <= clock_timestamp() - $lease" to "$alias.updated_at",
    )

    /** Discovery SQL and how many `limit ?` it binds (one per branch plus the outer limit). */
    data class DiscoveryQuery(val sql: String, val limits: Int)

    fun discovery(alias: String) = DiscoveryQuery(discoverySql(alias), branches(alias).size + 1)
    /** One limited branch per status so each uses its own partial index. */
    private fun discoverySql(alias: String) = branches(alias).joinToString("\n        union all ", "select id from (\n        ",
        "\n    ) candidates order by updated_at, id limit ?") { (condition, order) ->
        "(select $alias.id, $alias.updated_at from media_assets $alias where $condition order by $order, $alias.id limit ?)"
    }

    /** The same rule for one row, re-checked under the lease lock. */
    fun cleanupCandidate(alias: String) = branches(alias).joinToString(" or ", "(", ")") { "(${it.first})" }

    private fun seconds(duration: Duration) = "interval '${duration.seconds} seconds'"
}
