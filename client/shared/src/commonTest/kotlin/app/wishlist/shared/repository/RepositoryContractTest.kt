package app.wishlist.shared.repository

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

/** Test-only owner switching retains one shared backend/store for all owners in a scenario. */
interface RepositoryContractFixture {
    suspend fun repositoriesFor(ownerId: String): ContractRepositories
    /** Drives the backend's analysis completion without adding a production repository operation. */
    suspend fun completeAnalysis(ownerId: String, itemId: String)
    suspend fun deleteItem(ownerId: String, itemId: String)
    suspend fun close() {}
}

data class ContractRepositories(val create: CreateItemRepository, val get: GetItemRepository)

/**
 * Task 5 and Task 6b instantiate this identical scenario harness for Fake and Remote.
 * Abstract in Task 4: no contract scenario has run yet. HTTP 201/200 is checked in Remote tests.
 */
abstract class RepositoryContractTest {
    protected abstract suspend fun createFixture(): RepositoryContractFixture

    private val time = Instant.parse("2026-10-07T00:00:00Z")
    private val ownerA = "00000000-0000-4000-8000-000000000001"
    private val ownerB = "00000000-0000-4000-8000-000000000002"
    private val key = "00000000-0000-4000-8000-000000000003"
    private fun command(submissionId: String = key, url: String = "https://shop.example/headphone") =
        CreateItemCommand(submissionId, url, time)

    private suspend fun scenario(block: suspend (RepositoryContractFixture) -> Unit) {
        val fixture = createFixture()
        try { block(fixture) } finally { fixture.close() }
    }

    @Test fun creation_and_replay_preserve_id_and_client_share_time() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            val first = repos.create.create(command()).value()
            val replay = repos.create.create(command()).value()
            assertEquals(first.id, replay.id)
            assertEquals(key, replay.clientSubmissionId)
            assertEquals(time, replay.clientCreatedAt)
            val laterShareTime = command().copy(clientCreatedAt = Instant.parse("2026-10-08T00:00:00Z"))
            assertEquals(time, repos.create.create(laterShareTime).value().clientCreatedAt)
            assertEquals("https://shop.example/headphone", replay.sourceUrl)
            assertEquals(first, repos.get.get(first.id).value())
        }
    }

    @Test fun replay_returns_the_latest_analysis_snapshot() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            val first = repos.create.create(command()).value()
            assertEquals(AnalysisStatus.PROCESSING, first.analysis.status)
            fixture.completeAnalysis(ownerA, first.id)
            val latest = repos.get.get(first.id).value()
            assertNotEquals(AnalysisStatus.PROCESSING, latest.analysis.status)
            assertTrue(latest.version > first.version)
            assertEquals(latest, repos.create.create(command()).value())
        }
    }

    @Test fun same_url_with_a_new_submission_key_creates_a_new_item() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            val first = repos.create.create(command()).value()
            val second = repos.create.create(command("00000000-0000-4000-8000-000000000004")).value()
            assertNotEquals(first.id, second.id)
            assertEquals(first.sourceUrl, second.sourceUrl)
        }
    }

    @Test fun same_key_with_different_original_url_conflicts() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            repos.create.create(command(url = "https://shop.example/headphone?variant=A")).value()
            // Both URLs are valid; replay identity still compares their exact original strings.
            val result = repos.create.create(command(url = "https://shop.example/headphone?variant=B"))
            assertEquals(ErrorKind.CONFLICT, result.failureKind())
        }
    }

    @Test fun owner_context_isolates_reads_and_submission_keys() = runTest {
        scenario { fixture ->
            val first = fixture.repositoriesFor(ownerA).create.create(command()).value()
            val reposB = fixture.repositoriesFor(ownerB)
            assertEquals(ErrorKind.NOT_FOUND, reposB.get.get(first.id).failureKind())
            val second = reposB.create.create(command()).value()
            assertNotEquals(first.id, second.id)
            val reposA = fixture.repositoriesFor(ownerA)
            assertEquals(first.id, reposA.create.create(command()).value().id)
            assertEquals(ErrorKind.NOT_FOUND, reposA.get.get(second.id).failureKind())
        }
    }

    @Test fun deleted_item_get_is_not_found_but_same_key_replays_the_tombstone() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            val first = repos.create.create(command()).value()
            fixture.deleteItem(ownerA, first.id)
            assertEquals(ErrorKind.NOT_FOUND, repos.get.get(first.id).failureKind())
            val replay = repos.create.create(command()).value()
            assertEquals(first.id, replay.id)
            assertEquals(LifecycleStatus.DELETED, replay.lifecycleStatus)
            assertEquals(time, replay.clientCreatedAt)
        }
    }

    @Test fun unknown_item_is_not_found() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            assertEquals(ErrorKind.NOT_FOUND,
                repos.get.get("00000000-0000-4000-8000-000000000099").failureKind())
        }
    }

    @Test fun invalid_original_urls_are_rejected_without_consuming_the_key() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            val base = "https://example.com/"
            for (url in listOf("not-a-url", "ftp://example.com/item", " https://example.com/item ",
                "https://example.com/a b", "https://example.com/%GG", "http://localhost/p", "http://shop.localhost/p",
                "http://127.0.0.2/p", "http://0.0.0.0/p", "http://[::1]/p", "http://[::]/p",
                "http://[::ffff:127.0.0.1]/p", base + "x".repeat(2049 - base.length), base + "\uD800")) {
                val error = assertIs<ClientResult.Failure>(repos.create.create(command(url = url))).error
                assertEquals(ErrorKind.VALIDATION, error.kind, url)
                assertEquals("INVALID_URL", error.code, url)
            }
            assertEquals(key, repos.create.create(command()).value().clientSubmissionId)
        }
    }

    @Test fun uppercase_schemes_preserve_the_original_url_and_replay_identity() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            val original = "HTTPS://A.EXAMPLE/Path?variant=%2f"
            val first = repos.create.create(command(url = original)).value()
            assertEquals(original, first.sourceUrl)
            assertEquals(first, repos.create.create(command(url = original)).value())
            assertEquals(ErrorKind.CONFLICT, repos.create.create(command(url = original.lowercase())).failureKind())
        }
    }

    @Test fun valid_uri_boundaries_are_preserved_without_applying_extraction_policy() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            val base = "https://example.com/"
            val urls = listOf("https://example.com:99999/x", "https://example.com:/x", "https://EXAMPLE.com./x",
                "https://u:p@example.com/x", "http://128.0.0.1/x", "http://127.example.com/x",
                "http://[2001:db8::1]/x", "https://example.com/😀", base + "a".repeat(2048 - base.length))
            for ((index, url) in urls.withIndex()) {
                val cmd = command(submissionId = "00000000-0000-4000-8000-${(index + 100).toString().padStart(12, '0')}", url = url)
                val created = repos.create.create(cmd).value()
                assertEquals(url, created.sourceUrl)
                assertEquals(created, repos.create.create(cmd).value())
            }
        }
    }

    @Test fun invalid_raw_userinfo_is_not_repaired_by_the_platform_url_parser() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            for (url in listOf("https://[foo]@example.com/", "https://user[foo@example.com/", "https://example.com/a\u0080b")) {
                val error = assertIs<ClientResult.Failure>(repos.create.create(command(url = url))).error
                assertEquals("INVALID_URL", error.code, url)
            }
        }
    }

    @Test fun dotted_numeric_hosts_are_invalid_rather_than_public_dns_names() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            for (url in listOf("https://1.2.3.4./", "http://127.0.0.1./")) {
                val error = assertIs<ClientResult.Failure>(repos.create.create(command(url = url))).error
                assertEquals("INVALID_URL", error.code, url)
            }
        }
    }

    @Test fun ipv6_numeric_zone_is_preserved_when_it_is_not_local() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            val url = "http://[2001:db8::1%1]/"
            assertEquals(url, repos.create.create(command(url = url)).value().sourceUrl)
        }
    }

    @Test fun uuid_case_does_not_create_a_second_submission_or_hide_the_item() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            val lower = "abcdefab-cdef-4abc-8def-abcdefabcdef"
            val first = repos.create.create(command(submissionId = lower.uppercase())).value()
            assertEquals(lower, first.clientSubmissionId)
            assertEquals(first, repos.create.create(command(submissionId = lower)).value())
            assertEquals(first, repos.get.get(first.id.uppercase()).value())
        }
    }

    @Test fun malformed_uuid_inputs_are_validation_errors() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            for (invalid in listOf("bad", "1-1-1-1-1", "abcdefabcdef4abc8defabcdefabcdef")) {
                val createError = assertIs<ClientResult.Failure>(repos.create.create(command(submissionId = invalid))).error
                assertEquals(ErrorKind.VALIDATION, createError.kind)
                assertEquals("INVALID_IDEMPOTENCY_KEY", createError.code)
                val getError = assertIs<ClientResult.Failure>(repos.get.get(invalid)).error
                assertEquals(ErrorKind.VALIDATION, getError.kind)
                assertEquals("INVALID_WISHLIST_ITEM_ID", getError.code)
            }
        }
    }

    @Test fun sharing_time_is_truncated_to_microseconds_and_replay_keeps_it() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            val submitted = command().copy(clientCreatedAt = Instant.parse("1500-01-02T10:00:00.123456789Z"))
            val first = repos.create.create(submitted).value()
            assertEquals(Instant.parse("1500-01-02T10:00:00.123456Z"), first.clientCreatedAt)
            assertEquals(first, repos.create.create(submitted.copy(clientCreatedAt = time)).value())
        }
    }

    @Test fun sharing_time_outside_server_year_range_is_rejected_without_consuming_the_key() = runTest {
        scenario { fixture ->
            val repos = fixture.repositoriesFor(ownerA)
            for (at in listOf("0000-12-31T23:59:59Z", "+10000-01-01T00:00:00Z")) {
                val error = assertIs<ClientResult.Failure>(repos.create.create(command().copy(clientCreatedAt = Instant.parse(at)))).error
                assertEquals(ErrorKind.VALIDATION, error.kind)
                assertEquals("INVALID_CLIENT_CREATED_AT", error.code)
            }
            val last = repos.create.create(command().copy(clientCreatedAt = Instant.parse("9999-12-31T23:59:59.999999999Z"))).value()
            assertEquals(Instant.parse("9999-12-31T23:59:59.999999Z"), last.clientCreatedAt)
        }
    }
}

private fun ClientResult<WishlistItem>.value(): WishlistItem = when (this) {
    is ClientResult.Success -> value
    is ClientResult.Failure -> fail("Expected success, received $error")
}

private fun ClientResult<WishlistItem>.failureKind(): ErrorKind = when (this) {
    is ClientResult.Success -> fail("Expected failure, received $value")
    is ClientResult.Failure -> error.kind
}
