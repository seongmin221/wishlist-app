@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.wishlist.shared.presentation

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Instant

internal fun purposeDetail(name: String = "출퇴근 헤드폰", version: Int = 1) = PurposeDetail(
    id = "11111111-1111-4111-8111-111111111111", name = name, description = "지하철",
    colorKey = "coral", iconKey = "music", candidateCount = 0, membershipVersion = 1, version = version,
    activity = PurposeActivity(Instant.parse("2026-10-01T00:00:00Z"), "CREATED"),
    createdAt = Instant.parse("2026-10-01T00:00:00Z"), updatedAt = Instant.parse("2026-10-01T00:00:00Z"),
    allowedActions = setOf("EDIT", "DELETE", "ADD_CANDIDATES"),
)
internal fun purposeSummaryFixture(detail: PurposeDetail = purposeDetail()) = PurposeSummary(
    detail.id, detail.name, detail.description, detail.colorKey, detail.iconKey, detail.version,
    detail.candidateCount, detail.activity, emptyList(),
)
internal fun purposeCatalog(vararg rows: PurposeSummary) = PurposeCatalog(rows.toList(), rows.size, 30, ArchiveSummary(0, emptyList()))
internal class ScriptedPurposes : PurposeRepository {
    val summaries = Script<PurposeCatalog> { ok(purposeCatalog(purposeSummaryFixture())) }
    val details = Script<PurposeDetail> { ok(purposeDetail()) }
    val creates = Script<PurposeCreated> { ok(PurposeCreated(purposeDetail(), 1, 30)) }
    val updates = Script<PurposeDetail> { ok(purposeDetail(version = 2)) }
    val keys = mutableListOf<String>()
    val commands = mutableListOf<PurposeCommand>()
    val patches = mutableListOf<Triple<String, Int, PurposePatch>>()
    override suspend fun summary() = summaries.next(Unit)
    override suspend fun detail(id: String) = details.next(id)
    override suspend fun create(command: PurposeCommand, idempotencyKey: String): ClientResult<PurposeCreated> {
        commands += command; keys += idempotencyKey
        return creates.next(command)
    }
    override suspend fun update(id: String, expectedVersion: Int, patch: PurposePatch): ClientResult<PurposeDetail> {
        patches += Triple(id, expectedVersion, patch)
        return updates.next(patch)
    }
}

class PurposePresenterTest {
    private class H(scope: TestScope) {
        val session = MutableAuthSession()
        val repo = ScriptedPurposes()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        var seq = 0
        val home = PurposeHomePresenter(repo, session, dispatcher)
        val form = PurposeFormPresenter(repo, IdGenerator { "00000000-0000-4000-8000-${(++seq).toString().padStart(12, '0')}" }, session, dispatcher)
        val pages = Script<ItemPage> { ok(ItemPage(emptyList(), 0, null)) }
        val items = object : ItemListRepository {
            override suspend fun byCategory(categoryId: String, cursor: String?, limit: Int) = error("wrong scope")
            override suspend fun byPurpose(purposeId: String, cursor: String?, limit: Int) = pages.next(purposeId to cursor)
        }
        val detail = PurposeDetailPresenter(repo, items, session, dispatcher)
        fun close() { home.close(); form.close(); detail.close() }
    }
    private fun scenario(block: suspend TestScope.(H) -> Unit) = runTest {
        val h = H(this)
        h.session.changeAccount("A")
        try { block(h) } finally { h.close() }
    }
    @Test fun anonymousMakesNoRequest() = scenario { h ->
        h.session.changeAccount(null)
        h.home.load(); advanceUntilIdle()
        assertFalse(h.home.state.value.signedIn)
        assertTrue(h.repo.summaries.calls.isEmpty())
    }
    @Test fun homeRefreshReplacesAllAndPreservesServerOrder() = scenario { h ->
        h.home.load(); advanceUntilIdle()
        val second = purposeSummaryFixture(purposeDetail("새 목적").copy(id = "22222222-2222-4222-8222-222222222222"))
        h.repo.summaries.answer(ok(purposeCatalog(second)))
        h.home.refresh(); advanceUntilIdle()
        assertEquals(listOf(second), h.home.state.value.purposes)
        assertEquals(2, h.repo.summaries.calls.size)
    }
    @Test fun homeRefreshFailureKeepsShownSnapshot() = scenario { h ->
        h.home.load(); advanceUntilIdle()
        h.repo.summaries.answer(fail(ErrorKind.NETWORK))
        h.home.refresh(); advanceUntilIdle()
        assertEquals(1, h.home.state.value.purposes.size)
        assertTrue(h.home.state.value.refreshFailed)
    }
    @Test fun homeSessionChangeDropsOldSnapshotAndLoadsNewAccount() = scenario { h ->
        h.home.load(); advanceUntilIdle()
        val pending = h.repo.summaries.hold()
        h.home.refresh(); advanceUntilIdle()
        h.session.changeAccount(null); advanceUntilIdle()
        pending.complete(ok(purposeCatalog(purposeSummaryFixture())))
        advanceUntilIdle()
        assertTrue(h.home.state.value.purposes.isEmpty())
        assertFalse(h.home.state.value.signedIn)
    }
    @Test fun createDefaultsAndInputValidation() = scenario { h ->
        h.form.startCreate(); advanceUntilIdle()
        assertEquals("coral", h.form.state.value.colorKey)
        assertEquals("heart", h.form.state.value.iconKey)
        assertFalse(h.form.state.value.canSubmit)
        h.form.changeName("🎁".repeat(40)); advanceUntilIdle()
        assertTrue(h.form.state.value.canSubmit)
        h.form.changeName("a".repeat(41)); advanceUntilIdle()
        assertFalse(h.form.state.value.canSubmit)
        h.form.changeName("x\u200B"); advanceUntilIdle()
        assertFalse(h.form.state.value.canSubmit)
    }
    @Test fun lostCreateResponseReusesKeyAndPayload() = scenario { h ->
        h.form.startCreate(); h.form.changeName("  책상  "); advanceUntilIdle()
        h.repo.creates.answer(fail(ErrorKind.NETWORK))
        h.form.submit(); advanceUntilIdle()
        assertEquals("  책상  ", h.form.state.value.name)
        assertNull(h.form.state.value.result)
        h.form.submit(); advanceUntilIdle()
        assertEquals(h.repo.keys[0], h.repo.keys[1])
        assertEquals("  책상  ", h.repo.commands.last().name)
        assertNotNull(h.form.state.value.result)
    }
    @Test fun changedCreatePayloadUsesNewKey() = scenario { h ->
        h.form.startCreate(); h.form.changeName("책상"); advanceUntilIdle()
        h.repo.creates.answer(fail(ErrorKind.TIMEOUT))
        h.form.submit(); advanceUntilIdle()
        h.form.chooseIcon("book"); h.form.submit(); advanceUntilIdle()
        assertNotEquals(h.repo.keys[0], h.repo.keys[1])
    }
    @Test fun explicitCreateRejectionClearsKey() = scenario { h ->
        h.form.startCreate(); h.form.changeName("책상"); advanceUntilIdle()
        h.repo.creates.answer(fail(ErrorKind.RATE_LIMITED, "PURPOSE_CREATE_RATE_LIMITED"))
        h.form.submit(); advanceUntilIdle()
        assertEquals(FormMessage.RATE_LIMITED, h.form.state.value.message)
        h.form.submit(); advanceUntilIdle()
        assertNotEquals(h.repo.keys[0], h.repo.keys[1])
    }
    @Test fun pendingSubmitLocksInputsAndDeduplicates() = scenario { h ->
        h.form.startCreate(); h.form.changeName("책상"); advanceUntilIdle()
        val pending = h.repo.creates.hold()
        h.form.submit(); advanceUntilIdle()
        h.form.submit(); h.form.changeName("다른 값"); h.form.chooseColor("mint"); advanceUntilIdle()
        assertTrue(h.form.state.value.submitting)
        assertEquals("책상", h.form.state.value.name)
        assertEquals("coral", h.form.state.value.colorKey)
        assertEquals(1, h.repo.keys.size)
        pending.complete(ok(PurposeCreated(purposeDetail(), 1, 30))); advanceUntilIdle()
    }
    @Test fun editSendsOnlyChangedFieldsAndExplicitDescriptionNull() = scenario { h ->
        h.form.startEdit(purposeDetail()); advanceUntilIdle()
        assertFalse(h.form.state.value.dirty)
        h.form.changeDescription(""); h.form.chooseColor("mint"); h.form.submit(); advanceUntilIdle()
        val patch = h.repo.patches.single()
        assertEquals(1, patch.second)
        assertNull(patch.third.name)
        assertEquals(DescriptionChange.Set(null), patch.third.description)
        assertEquals("mint", patch.third.colorKey)
        assertNull(patch.third.iconKey)
    }
    @Test fun invalidStyleSelectionCannotLeakToWire() = scenario { h ->
        h.form.startCreate(); h.form.chooseColor("CORAL"); h.form.chooseIcon("unknown"); advanceUntilIdle()
        assertEquals("coral", h.form.state.value.colorKey)
        assertEquals("heart", h.form.state.value.iconKey)
    }
    @Test fun conflictReadsLatestBeforeAllowingNewSubmit() = scenario { h ->
        h.form.startEdit(purposeDetail()); h.form.changeName("초안"); advanceUntilIdle()
        h.repo.updates.answer(fail(ErrorKind.CONFLICT, "PURPOSE_VERSION_CONFLICT", 7))
        h.repo.details.answer(ok(purposeDetail("다른 기기", 7)))
        h.form.submit(); advanceUntilIdle()
        assertEquals("다른 기기", h.form.state.value.name)
        assertEquals(FormMessage.CONFLICT_LOADED, h.form.state.value.message)
        assertFalse(h.form.state.value.canSubmit)
    }
    @Test fun failedConflictReadKeepsDraftAndBlocksSaveUntilRetry() = scenario { h ->
        h.form.startEdit(purposeDetail()); h.form.changeName("초안"); advanceUntilIdle()
        h.repo.updates.answer(fail(ErrorKind.CONFLICT, "PURPOSE_VERSION_CONFLICT", 7))
        h.repo.details.answer(fail(ErrorKind.NETWORK))
        h.form.submit(); advanceUntilIdle()
        assertEquals("초안", h.form.state.value.name)
        assertEquals(FormPhase.CONFLICT_UNRESOLVED, h.form.state.value.phase)
        assertFalse(h.form.state.value.canSubmit)
        h.repo.details.answer(ok(purposeDetail("최신", 7)))
        h.form.retryLoad(); advanceUntilIdle()
        assertEquals("최신", h.form.state.value.name)
    }
    @Test fun conflictAlreadyAppliedIsSuccess() = scenario { h ->
        h.form.startEdit(purposeDetail()); h.form.changeName("초안"); advanceUntilIdle()
        h.repo.updates.answer(fail(ErrorKind.CONFLICT, "PURPOSE_VERSION_CONFLICT", 2))
        h.repo.details.answer(ok(purposeDetail("초안", 2)))
        h.form.submit(); advanceUntilIdle()
        assertNotNull(h.form.state.value.result)
    }
    @Test fun goneEditAndSessionReset() = scenario { h ->
        h.form.startEdit(purposeDetail()); h.form.changeName("초안"); advanceUntilIdle()
        h.repo.updates.answer(fail(ErrorKind.NOT_FOUND, "PURPOSE_NOT_FOUND"))
        h.form.submit(); advanceUntilIdle()
        assertTrue(h.form.state.value.gone)
        h.session.changeAccount(null); advanceUntilIdle()
        assertEquals("", h.form.state.value.name)
        assertFalse(h.form.state.value.canSubmit)
    }
    @Test fun detailRequestsPurposeScopeAndRetriesIndependentFailure() = scenario { h ->
        h.pages.answer(fail(ErrorKind.NETWORK))
        h.detail.load(purposeDetail().id); advanceUntilIdle()
        assertNotNull(h.detail.state.value.detail)
        assertNotNull(h.detail.state.value.itemsError)
        h.detail.retry(); advanceUntilIdle()
        assertNull(h.detail.state.value.itemsError)
        assertEquals(1, h.repo.details.calls.size)
        assertEquals(2, h.pages.calls.size)
    }
    @Test fun detailGoneDoesNotDisplayEmptyCandidatesAsSuccess() = scenario { h ->
        h.repo.details.answer(fail(ErrorKind.NOT_FOUND, "PURPOSE_NOT_FOUND"))
        h.detail.load(purposeDetail().id); advanceUntilIdle()
        assertTrue(h.detail.state.value.gone)
    }
    @Test fun detailApplyEditedAndAccountLeave() = scenario { h ->
        h.detail.load(purposeDetail().id); advanceUntilIdle()
        h.detail.applyEdited(purposeDetail("바뀐 이름", 2)); advanceUntilIdle()
        assertEquals("바뀐 이름", h.detail.state.value.detail?.name)
        h.session.changeAccount(null); advanceUntilIdle()
        assertNull(h.detail.state.value.detail)
        assertTrue(h.detail.state.value.items.isEmpty())
    }
    @Test fun createUsageFailureKeepsDraftAndRetryLoadsUsage() = scenario { h ->
        h.repo.summaries.answer(fail(ErrorKind.NETWORK))
        h.form.startCreate(); h.form.changeName("초안"); advanceUntilIdle()
        assertEquals(FormPhase.LOAD_FAILED, h.form.state.value.phase)
        assertFalse(h.form.state.value.canSubmit)
        h.form.retryLoad(); advanceUntilIdle()
        assertEquals("초안", h.form.state.value.name)
        assertTrue(h.form.state.value.canSubmit)
    }
    @Test fun limitBlocksCreateButNotEditingExistingPurpose() = scenario { h ->
        h.repo.summaries.answer(ok(purposeCatalog().copy(activeCount = 30)))
        h.form.startCreate(); h.form.changeName("초안"); advanceUntilIdle()
        assertFalse(h.form.state.value.canSubmit)
        assertEquals(FormMessage.LIMIT_REACHED, h.form.state.value.message)
        h.form.startEdit(purposeDetail()); h.form.changeName("수정"); advanceUntilIdle()
        assertTrue(h.form.state.value.canSubmit)
    }
    @Test fun notAllowedEditCannotSubmit() = scenario { h ->
        h.form.startEdit(purposeDetail().copy(allowedActions = emptySet()))
        h.form.changeName("수정"); advanceUntilIdle()
        assertFalse(h.form.state.value.canSubmit)
        h.form.submit(); advanceUntilIdle()
        assertTrue(h.repo.patches.isEmpty())
    }
    @Test fun detailAppendDeduplicatesAndRefreshDropsEarlierPages() = scenario { h ->
        val one = itemFixture(id = "00000000-0000-4000-8000-000000000001")
        val two = itemFixture(id = "00000000-0000-4000-8000-000000000002")
        h.pages.answer(ok(ItemPage(listOf(one), 2, "next")))
        h.detail.load(purposeDetail().id); advanceUntilIdle()
        h.pages.answer(ok(ItemPage(listOf(one, two), 2, null)))
        h.detail.loadMore(); advanceUntilIdle()
        assertEquals(listOf(one.id, two.id), h.detail.state.value.items.map { it.id })
        assertEquals(purposeDetail().id to "next", h.pages.calls.last())
        h.detail.refresh(); advanceUntilIdle()
        assertTrue(h.detail.state.value.items.isEmpty())
        assertFalse(h.detail.state.value.hasMore)
    }
    @Test fun detailAppendFailureKeepsCardsAndRetryUsesSameCursor() = scenario { h ->
        val one = itemFixture()
        h.pages.answer(ok(ItemPage(listOf(one), 2, "next")))
        h.detail.load(purposeDetail().id); advanceUntilIdle()
        h.pages.answer(fail(ErrorKind.NETWORK))
        h.detail.loadMore(); advanceUntilIdle()
        assertTrue(h.detail.state.value.appendFailed)
        assertEquals(listOf(one), h.detail.state.value.items)
        h.detail.retry(); advanceUntilIdle()
        assertFalse(h.detail.state.value.appendFailed)
        assertEquals(purposeDetail().id to "next", h.pages.calls.last())
    }
    @Test fun applyEditedKeepsMetadataWhenOlderHeaderResponseArrives() = scenario { h ->
        h.detail.load(purposeDetail().id); advanceUntilIdle()
        val old = h.repo.details.hold()
        h.detail.refresh(); advanceUntilIdle()
        h.detail.applyEdited(purposeDetail("최신", 3)); advanceUntilIdle()
        old.complete(ok(purposeDetail())); advanceUntilIdle()
        assertEquals("최신", h.detail.state.value.detail?.name)
    }
    @Test fun descriptionAllowsLineBreaksButRejectsForbiddenControls() = scenario { h ->
        h.form.startCreate(); h.form.changeName("이름"); h.form.changeDescription("줄1\r\n줄2"); advanceUntilIdle()
        assertTrue(h.form.state.value.canSubmit)
        h.form.changeDescription("a".repeat(201)); advanceUntilIdle()
        assertFalse(h.form.state.value.canSubmit)
        h.form.changeDescription("a\r"); advanceUntilIdle()
        assertFalse(h.form.state.value.canSubmit)
    }
    @Test fun closeStopsPendingSaveAndLaterIntents() = scenario { h ->
        h.form.startCreate(); h.form.changeName("초안"); advanceUntilIdle()
        val save = h.repo.creates.hold()
        h.form.submit(); advanceUntilIdle()
        h.form.close()
        save.complete(ok(PurposeCreated(purposeDetail(), 1, 30)))
        h.form.changeName("나중"); h.form.submit(); advanceUntilIdle()
        assertNull(h.form.state.value.result)
        assertEquals("초안", h.form.state.value.name)
        assertEquals(1, h.repo.keys.size)
    }
    @Test fun accountChangeCancelsHeldFormSave() = scenario { h ->
        h.form.startCreate(); h.form.changeName("A 초안"); advanceUntilIdle()
        val save = h.repo.creates.hold()
        h.form.submit(); advanceUntilIdle()
        h.session.changeAccount("B"); advanceUntilIdle()
        save.complete(ok(PurposeCreated(purposeDetail(), 1, 30))); advanceUntilIdle()
        assertNull(h.form.state.value.result)
        assertEquals("", h.form.state.value.name)
    }

    @Test fun conflictSnapshotSurvivesDraftChangesForCancelAndReopen() = scenario { h ->
        val latest = purposeDetail("다른 기기", 7).copy(colorKey = "mint", iconKey = "book")
        h.detail.load(latest.id); advanceUntilIdle()
        h.form.startEdit(purposeDetail()); h.form.changeName("저장 시도"); advanceUntilIdle()
        h.repo.updates.answer(fail(ErrorKind.CONFLICT, "PURPOSE_VERSION_CONFLICT", 7))
        h.repo.details.answer(ok(latest))
        h.form.submit(); advanceUntilIdle()
        h.form.changeName("버릴 초안"); h.form.chooseColor("cyan"); advanceUntilIdle()
        assertEquals(latest, h.form.state.value.recoveredDetail)
        // Both platform cancel paths apply the known server snapshot, never the discarded draft.
        h.detail.applyEdited(assertNotNull(h.form.state.value.recoveredDetail)); advanceUntilIdle()
        assertEquals(latest, h.detail.state.value.detail)
        h.form.startEdit(assertNotNull(h.detail.state.value.detail)); h.form.changeName("새 편집"); advanceUntilIdle()
        assertNull(h.form.state.value.recoveredDetail)
        h.form.submit(); advanceUntilIdle()
        assertEquals(7, h.repo.patches.last().second)
    }
    @Test fun conflictReadRetryPublishesSnapshotAndAccountChangeClearsIt() = scenario { h ->
        h.form.startEdit(purposeDetail()); h.form.changeName("초안"); advanceUntilIdle()
        h.repo.updates.answer(fail(ErrorKind.CONFLICT, "PURPOSE_VERSION_CONFLICT", 7))
        h.repo.details.answer(fail(ErrorKind.NETWORK))
        h.form.submit(); advanceUntilIdle()
        assertNull(h.form.state.value.recoveredDetail)
        val latest = purposeDetail("서버", 7)
        h.repo.details.answer(ok(latest))
        h.form.retryLoad(); advanceUntilIdle()
        assertEquals(latest, h.form.state.value.recoveredDetail)
        h.session.changeAccount("B"); advanceUntilIdle()
        assertNull(h.form.state.value.recoveredDetail)
    }
    @Test fun applyingRecoveredDetailCannotReplaceNewerSaveResult() = scenario { h ->
        h.detail.load(purposeDetail().id); advanceUntilIdle()
        val saved = purposeDetail("저장 성공", 8).copy(colorKey = "pink")
        h.detail.applyEdited(saved); advanceUntilIdle()
        h.detail.applyEdited(purposeDetail("충돌 복구", 7)); advanceUntilIdle()
        assertEquals(saved, h.detail.state.value.detail)
    }

    @Test fun detailRefreshReadsLatestHeaderAndCompletedCandidates() = scenario { h ->
        val processing = itemFixture(analysis = AnalysisStatus.PROCESSING, name = null)
        h.repo.details.answer(ok(purposeDetail().copy(candidateCount = 1)))
        h.pages.answer(ok(ItemPage(listOf(processing), 1, null)))
        h.detail.load(purposeDetail().id); advanceUntilIdle()
        val completed = processing.copy(analysis = ItemAnalysis(AnalysisStatus.READY), product = ProductSnapshot(name = "분석 완료"), version = 2)
        val added = itemFixture(id = "00000000-0000-4000-8000-000000000003")
        h.repo.details.answer(ok(purposeDetail("최신 목적", 2).copy(candidateCount = 2)))
        h.pages.answer(ok(ItemPage(listOf(completed, added), 2, null)))
        h.detail.refreshNow(); advanceUntilIdle()
        assertEquals("최신 목적", h.detail.state.value.detail?.name)
        assertEquals(2, h.detail.state.value.detail?.candidateCount)
        assertEquals(AnalysisStatus.READY, h.detail.state.value.items.first().analysis.status)
        assertEquals("분석 완료", h.detail.state.value.items.first().product.name)
        assertEquals(listOf(completed.id, added.id), h.detail.state.value.items.map { it.id })
        assertEquals(2, h.repo.details.calls.size)
        assertEquals(2, h.pages.calls.size)
    }

    @Test fun cancellingRecoveredEditKeepsCandidateAdditionAtSameVersion() = scenario { h ->
        val recovered = purposeDetail("복구한 목적", 7).copy(candidateCount = 1, membershipVersion = 4)
        h.repo.details.answer(ok(recovered))
        h.detail.load(recovered.id); advanceUntilIdle()
        h.form.startEdit(purposeDetail()); h.form.changeName("충돌할 초안"); advanceUntilIdle()
        h.repo.updates.answer(fail(ErrorKind.CONFLICT, "PURPOSE_VERSION_CONFLICT", 7))
        h.repo.details.answer(ok(recovered))
        h.form.submit(); advanceUntilIdle()
        val latest = recovered.copy(candidateCount = 2, membershipVersion = 5,
            activity = PurposeActivity(Instant.parse("2026-10-02T00:00:00Z"), "CANDIDATE_ADDED"),
            allowedActions = recovered.allowedActions + "ARCHIVE")
        h.repo.details.answer(ok(latest))
        h.detail.refreshNow(); advanceUntilIdle()
        h.detail.applyEdited(assertNotNull(h.form.state.value.recoveredDetail)); advanceUntilIdle()
        assertEquals(latest, h.detail.state.value.detail)
    }
    @Test fun cancellingRecoveredEditKeepsRemovalAndArchiveAvailability() = scenario { h ->
        val recovered = purposeDetail(version = 7).copy(candidateCount = 1, membershipVersion = 4,
            allowedActions = setOf("EDIT", "DELETE", "ADD_CANDIDATES", "ARCHIVE"))
        h.repo.details.answer(ok(recovered)); h.detail.load(recovered.id); advanceUntilIdle()
        val empty = recovered.copy(candidateCount = 0, membershipVersion = 5, allowedActions = recovered.allowedActions - "ARCHIVE")
        h.repo.details.answer(ok(empty)); h.detail.refreshNow(); advanceUntilIdle()
        h.detail.applyEdited(recovered); advanceUntilIdle()
        assertEquals(empty, h.detail.state.value.detail)
    }
    @Test fun newerEditWithOlderMembershipKeepsCurrentCandidates() = scenario { h ->
        val current = purposeDetail(version = 7).copy(candidateCount = 2, membershipVersion = 5,
            activity = PurposeActivity(Instant.parse("2026-10-02T00:00:00Z"), "CANDIDATE_ADDED"),
            allowedActions = setOf("EDIT", "DELETE", "ADD_CANDIDATES", "ARCHIVE"))
        h.repo.details.answer(ok(current)); h.detail.load(current.id); advanceUntilIdle()
        val saved = purposeDetail("최신 이름", 8).copy(description = "최신 설명", colorKey = "mint", iconKey = "book",
            candidateCount = 1, membershipVersion = 4, updatedAt = Instant.parse("2026-10-03T00:00:00Z"))
        h.detail.applyEdited(saved); advanceUntilIdle()
        assertEquals(current.copy(name = saved.name, description = saved.description, colorKey = saved.colorKey,
            iconKey = saved.iconKey, version = 8, updatedAt = saved.updatedAt), h.detail.state.value.detail)
    }
    @Test fun olderEditWithNewerMembershipUpdatesCandidatesWithoutRevertingName() = scenario { h ->
        val saved = purposeDetail("저장한 이름", 8).copy(colorKey = "mint", membershipVersion = 4, candidateCount = 1)
        h.repo.details.answer(ok(saved)); h.detail.load(saved.id); advanceUntilIdle()
        val candidates = purposeDetail("과거 이름", 7).copy(membershipVersion = 5, candidateCount = 2,
            activity = PurposeActivity(Instant.parse("2026-10-02T00:00:00Z"), "CANDIDATE_ADDED"),
            allowedActions = saved.allowedActions + "ARCHIVE")
        h.repo.details.answer(ok(candidates)); h.detail.refreshNow(); advanceUntilIdle()
        assertEquals(saved.copy(membershipVersion = 5, candidateCount = 2, activity = candidates.activity,
            allowedActions = candidates.allowedActions), h.detail.state.value.detail)
    }
    @Test fun recoveredSnapshotDoesNotCancelRefreshThatCanHaveNewerMembership() = scenario { h ->
        val recovered = purposeDetail(version = 7).copy(membershipVersion = 4, candidateCount = 1)
        h.repo.details.answer(ok(recovered)); h.detail.load(recovered.id); advanceUntilIdle()
        val pending = h.repo.details.hold()
        h.detail.refresh(); advanceUntilIdle()
        h.detail.applyEdited(recovered); advanceUntilIdle()
        val latest = recovered.copy(membershipVersion = 5, candidateCount = 2)
        pending.complete(ok(latest)); advanceUntilIdle()
        assertEquals(latest, h.detail.state.value.detail)
        assertFalse(h.detail.state.value.loading)
    }

}
