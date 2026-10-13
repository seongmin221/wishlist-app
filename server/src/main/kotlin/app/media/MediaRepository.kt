package app.media

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class MediaAsset(
    val id: UUID,
    val ownerId: UUID,
    val objectKey: String,
    val contentType: String,
    val declared: DeclaredImage,
    val status: MediaStatus,
    val byteSize: Int?,
    val width: Int?,
    val height: Int?,
    val failureReason: MediaFailureReason?,
    val uploadExpiresAt: Instant,
    /** PENDING past its upload window, judged with the DB clock when the row was read. */
    val expired: Boolean,
)

data class StoredIntent(val asset: MediaAsset, val fingerprint: String)

class MediaRepository {
    fun findByKey(c: Connection, owner: UUID, key: UUID): StoredIntent? = c.prepareStatement(
        "select $COLUMNS, request_fingerprint from media_assets where owner_id=? and idempotency_key=?",
    ).use { s ->
        s.setObject(1, owner); s.setObject(2, key)
        s.executeQuery().use { r -> if (r.next()) StoredIntent(map(r), r.getString("request_fingerprint")) else null }
    }

    fun find(c: Connection, owner: UUID, id: UUID, lock: Boolean = false): MediaAsset? = c.prepareStatement(
        "select $COLUMNS from media_assets where owner_id=? and id=?${if (lock) " for update" else ""}",
    ).use { s ->
        s.setObject(1, owner); s.setObject(2, id)
        s.executeQuery().use { r -> if (r.next()) map(r) else null }
    }

    /** Live PENDING count and seconds until the earliest one expires (at least 1), in one DB clock reading. */
    fun livePending(c: Connection, owner: UUID): Pair<Int, Int?> = c.prepareStatement("""
        with instant as materialized (select clock_timestamp() as as_of)
        select count(*), greatest(1, ceil(extract(epoch from min(upload_expires_at) - (select as_of from instant))))::integer
        from media_assets where owner_id=? and status='PENDING' and upload_expires_at > (select as_of from instant)
    """).use { s ->
        s.setObject(1, owner)
        s.executeQuery().use { r -> check(r.next()); r.getInt(1) to (r.getObject(2) as Int?) }
    }

    fun insert(c: Connection, owner: UUID, key: UUID, fingerprint: String, request: IntentRequest): MediaAsset {
        val id = UUID.randomUUID()
        c.prepareStatement("""
            insert into media_assets(id,owner_id,idempotency_key,request_fingerprint,object_key,content_type,
                declared_byte_size,declared_width,declared_height,upload_expires_at)
            values (?,?,?,?,?,?,?,?,?,clock_timestamp() + (? * interval '1 second'))
        """).use { s ->
            s.setObject(1, id); s.setObject(2, owner); s.setObject(3, key); s.setString(4, fingerprint)
            s.setString(5, MediaKeys.objectKey(owner, id)); s.setString(6, request.contentType)
            s.setInt(7, request.byteSize); s.setInt(8, request.width); s.setInt(9, request.height)
            s.setLong(10, MediaLimits.UPLOAD_VALIDITY.seconds)
            check(s.executeUpdate() == 1)
        }
        return checkNotNull(find(c, owner, id))
    }

    /** Applies a verdict only to a row still PENDING; the caller holds the row lock. */
    fun markReady(c: Connection, id: UUID, verdict: ImageVerdict.Valid) = c.prepareStatement("""
        update media_assets set status='READY', byte_size=?, width=?, height=?, ready_at=clock_timestamp(), updated_at=clock_timestamp()
        where id=? and status='PENDING'
    """).use { s ->
        s.setInt(1, verdict.byteSize); s.setInt(2, verdict.width); s.setInt(3, verdict.height); s.setObject(4, id)
        check(s.executeUpdate() == 1)
    }

    fun markFailed(c: Connection, id: UUID, reason: MediaFailureReason) = c.prepareStatement("""
        update media_assets set status='FAILED', failure_reason=?, failed_at=clock_timestamp(), updated_at=clock_timestamp()
        where id=? and status='PENDING'
    """).use { s ->
        s.setString(1, reason.name); s.setObject(2, id)
        check(s.executeUpdate() == 1)
    }

    private fun map(r: ResultSet) = MediaAsset(
        id = r.getObject("id", UUID::class.java), ownerId = r.getObject("owner_id", UUID::class.java),
        objectKey = r.getString("object_key"), contentType = r.getString("content_type"),
        declared = DeclaredImage(r.getInt("declared_byte_size"), r.getInt("declared_width"), r.getInt("declared_height")),
        status = MediaStatus.valueOf(r.getString("status")),
        byteSize = r.getObject("byte_size") as Int?, width = r.getObject("width") as Int?, height = r.getObject("height") as Int?,
        failureReason = r.getString("failure_reason")?.let(MediaFailureReason::valueOf),
        uploadExpiresAt = r.getObject("upload_expires_at", java.time.OffsetDateTime::class.java).toInstant(),
        expired = r.getBoolean("expired"),
    )

    private companion object {
        const val COLUMNS = """id,owner_id,object_key,content_type,declared_byte_size,declared_width,declared_height,status,
            byte_size,width,height,failure_reason,upload_expires_at,upload_expires_at <= clock_timestamp() as expired"""
    }
}
