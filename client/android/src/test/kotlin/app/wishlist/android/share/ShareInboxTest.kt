package app.wishlist.android.share

import app.wishlist.shared.submission.InboxImportResult
import app.wishlist.shared.submission.InboxRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The Android deferred-share files: written whole, imported once, deleted only when deletable. */
class ShareInboxTest {
    private val directory: File = Files.createTempDirectory("share-inbox").toFile()
    private var now = 1_000_000L
    private val inbox = ShareInbox(directory) { now }

    private fun record(key: String, url: String = "https://shop.example/$key") =
        InboxRecord(clientSubmissionId = key, sourceUrl = url, sharedAtIso = "2026-10-09T08:00:00.000Z", accountBinding = null)

    @After fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test fun written_records_are_offered_unbound_and_deleted_only_when_deletable() = runBlocking {
        assertTrue(inbox.write(record("a")))
        assertTrue(inbox.write(record("b")))
        var offered = emptyList<InboxRecord>()
        val deleted = inbox.importPending { records ->
            offered = records
            InboxImportResult(deletable = listOf("a"), retained = listOf("b"))
        }
        assertEquals(listOf(record("a"), record("b")), offered)
        assertEquals(listOf("a"), deleted)
        assertEquals(listOf("b.share"), directory.list()!!.sorted())
    }

    @Test fun nothing_to_import_does_not_call_the_importer() = runBlocking {
        var called = false
        assertEquals(emptyList<String>(), inbox.importPending { called = true; InboxImportResult(emptyList(), emptyList()) })
        assertEquals(false, called)
    }

    @Test fun unreadable_files_are_removed_and_a_fresh_temporary_is_left_alone() = runBlocking {
        File(directory, "x.share").writeText("garbage")
        val temporary = File(directory, "y.tmp").apply { writeText("1\ny") }
        temporary.setLastModified(now - 10_000)
        inbox.importPending { InboxImportResult(emptyList(), emptyList()) }
        assertEquals(listOf("y.tmp"), directory.list()!!.sorted())

        // A temporary a killed process left behind is incomplete: removed once stale.
        now += 60_000
        inbox.importPending { InboxImportResult(emptyList(), emptyList()) }
        assertEquals(emptyList<String>(), directory.list()!!.sorted())
    }
}
