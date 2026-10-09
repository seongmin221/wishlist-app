package app.wishlist.android.share

import app.wishlist.shared.submission.InboxImportResult
import app.wishlist.shared.submission.InboxRecord
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shares the runtime could not store in time (a slow cold start, or a store failure): one file per
 * share, `<key>.share`, imported through the same `importInbox` as iOS once the runtime is ready
 * (the share card said "저장했어요" and must not lose it). Written as `<key>.tmp` and then renamed,
 * so a reader sees whole records only; a `.tmp` left by a killed process is incomplete and removed
 * once stale. Lines: format `1`, key, sharedAt ISO, URL (a parsed link has no line breaks). The
 * record is unbound (the account is not restored yet): the next flush binds it to whoever is signed
 * in, as for any share made while signed out.
 */
class ShareInbox(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()

    /** Writes [record]; false when the file could not be written (the share is then lost). */
    fun write(record: InboxRecord): Boolean = try {
        directory.mkdirs()
        val temporary = File(directory, "${record.clientSubmissionId}$TEMPORARY")
        temporary.writeText(listOf(FORMAT, record.clientSubmissionId, record.sharedAtIso, record.sourceUrl).joinToString("\n"))
        temporary.renameTo(File(directory, "${record.clientSubmissionId}$RECORD")) || false.also { temporary.delete() }
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /**
     * Offers every record to [importer] (`SubmissionCoordinator.importInbox`, which waits for ready)
     * and deletes what it reported deletable (a crash in between re-imports the same keys: a no-op).
     * Unreadable files are deleted. One pass at a time. Returns the deleted keys.
     */
    suspend fun importPending(importer: suspend (List<InboxRecord>) -> InboxImportResult): List<String> = lock.withLock {
        val files = withContext(Dispatchers.IO) { readAll() }
        if (files.isEmpty()) return@withLock emptyList()
        val result = importer(files.map { it.first })
        val deletable = result.deletable.toSet()
        withContext(Dispatchers.IO) { files.filter { it.first.clientSubmissionId in deletable }.forEach { it.second.delete() } }
        result.deletable
    }

    private fun readAll(): List<Pair<InboxRecord, File>> {
        val records = mutableListOf<Pair<InboxRecord, File>>()
        for (file in directory.listFiles().orEmpty().sortedBy { it.name }) {
            if (file.name.endsWith(TEMPORARY)) {
                if (now() - file.lastModified() >= STALE_TEMPORARY_MS) file.delete()
            } else if (file.name.endsWith(RECORD)) {
                val record = parse(file)
                if (record == null) file.delete() else records += record to file
            }
        }
        return records
    }

    private fun parse(file: File): InboxRecord? {
        val lines = runCatching { file.readText().split("\n") }.getOrNull() ?: return null
        if (lines.size != 4 || lines[0] != FORMAT) return null
        return InboxRecord(clientSubmissionId = lines[1], sourceUrl = lines[3], sharedAtIso = lines[2], accountBinding = null)
    }

    private companion object {
        const val FORMAT = "1"
        const val RECORD = ".share"
        const val TEMPORARY = ".tmp"
        const val STALE_TEMPORARY_MS = 60_000L
    }
}
