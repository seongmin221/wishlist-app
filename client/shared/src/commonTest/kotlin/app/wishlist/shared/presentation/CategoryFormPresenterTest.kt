@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.presentation

import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.model.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CategoryFormPresenterTest {
    private val customId = "11111111-1111-4111-8111-111111111111"
    private val select = catalog(fashion, digital, group("G007", "취미·문화·컬렉터블"), scope = CategoryScope.SELECT, used = 3)
    private val detail = CustomCategoryDetail(customId, "오디오 케이블·DAC", "G003", "DAC와 케이블", listOf("포터블 DAC", "이어폰 케이블"), 2, 4)
    private fun created(name: String = "책상 장비", parent: String = "G003") =
        ok(CategoryCreated(CustomCategoryDetail(customId, name, parent, null, emptyList(), 0, 1), 4, 20))

    private class H(scope: TestScope) {
        var keySeq = 0
        val session = MutableAuthSession()
        val categories = ScriptedCategories()
        val presenter = CategoryFormPresenter(categories, IdGenerator { "00000000-0000-4000-9000-" + (++keySeq).toString().padStart(12, '0') },
            session, StandardTestDispatcher(scope.testScheduler))
        val state get() = presenter.state.value
    }

    private fun scenario(block: suspend TestScope.(H) -> Unit) = runTest {
        val h = H(this)
        h.session.changeAccount("A")
        try { block(h) } finally { h.presenter.close() }
    }

    private suspend fun TestScope.create(h: H, parent: String? = "G003") {
        h.categories.catalog.answer(ok(select))
        h.presenter.startCreate(parent, FormSource.TAB, null); advanceUntilIdle()
    }

    private suspend fun TestScope.edit(h: H) {
        h.categories.custom.answer(ok(detail))
        h.presenter.startEdit(customId, "디지털·IT"); advanceUntilIdle()
    }

    // ---- create: data ----

    @Test fun createLoadsSelectForEveryParentAndTheUsage() = scenario { h ->
        val held = h.categories.catalog.hold()
        h.presenter.startCreate("G003", FormSource.TAB, null); advanceUntilIdle()
        assertEquals(FormPhase.LOADING, h.state.phase)
        h.presenter.setName("책상 장비"); advanceUntilIdle()
        assertEquals("책상 장비", h.state.name) // inputs work while the parents load
        assertFalse(h.state.canSubmit)
        held.complete(ok(select)); advanceUntilIdle()
        assertEquals(listOf<Any?>(CategoryScope.SELECT to null), h.categories.catalog.calls)
        assertEquals(listOf("G001", "G003", "G007"), h.state.parents.map { it.id })
        assertEquals("G003", h.state.parentId)
        assertEquals(3, h.state.usedCount)
        assertTrue(h.state.canSubmit)
    }

    @Test fun createWithoutAParentPicksTheFirstAndAPassedSelectIsNotRefetched() = scenario { h ->
        h.presenter.startCreate(null, FormSource.PICKER, select); advanceUntilIdle()
        assertTrue(h.categories.catalog.calls.isEmpty())
        assertEquals("G001", h.state.parentId)
        assertEquals(FormSource.PICKER, h.state.source)
    }

    @Test fun selectFailureKeepsInputAndRetryReadsOnlySelect() = scenario { h ->
        h.categories.catalog.answer(fail(ErrorKind.NETWORK))
        h.presenter.startCreate("G003", FormSource.TAB, null); advanceUntilIdle()
        h.presenter.setName("책상"); advanceUntilIdle()
        assertEquals(FormPhase.LOAD_FAILED, h.state.phase)
        assertEquals(FormMessage.LOAD_FAILED, h.state.message)
        h.categories.catalog.answer(ok(select))
        h.presenter.retryLoad(); advanceUntilIdle()
        assertEquals(FormPhase.READY, h.state.phase)
        assertEquals("책상", h.state.name)
        assertTrue(h.state.canSubmit)
    }

    // ---- inputs ----

    @Test fun examplesFollowD12() = scenario { h ->
        create(h)
        h.presenter.setPendingExample("키보드"); h.presenter.commitExample()
        h.presenter.setPendingExample("  "); h.presenter.commitExample()
        h.presenter.setPendingExample("키보드"); h.presenter.commitExample()
        advanceUntilIdle()
        assertEquals(listOf("키보드"), h.state.examples)
        assertEquals("", h.state.pendingExample)
        repeat(5) { h.presenter.setPendingExample("e$it"); h.presenter.commitExample() }
        advanceUntilIdle()
        assertEquals(5, h.state.examples.size)
        h.presenter.removeExample(0); advanceUntilIdle()
        assertEquals(listOf("e0", "e1", "e2", "e3"), h.state.examples)
    }

    @Test fun pendingExampleIsSentToo() = scenario { h ->
        create(h)
        h.presenter.setName("책상 장비")
        h.presenter.setPendingExample("키보드"); h.presenter.commitExample()
        h.presenter.setPendingExample("헤드폰")
        h.categories.create.answer(created())
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(listOf("키보드", "헤드폰"), h.categories.createCommands.single().examples)
    }

    @Test fun createDirtyAndSubmitRules() = scenario { h ->
        create(h)
        assertFalse(h.state.dirty)
        assertFalse(h.state.canSubmit)
        h.presenter.setName("   "); advanceUntilIdle()
        assertTrue(h.state.dirty)
        assertFalse(h.state.canSubmit) // blank name
        h.presenter.setName(""); advanceUntilIdle()
        assertFalse(h.state.dirty)
        h.presenter.setName("두\n줄"); advanceUntilIdle()
        assertFalse(h.state.canSubmit) // a forbidden character
    }

    @Test fun limitReachedBlocksANewCreate() = scenario { h ->
        h.categories.catalog.answer(ok(select.copy(customUsedCount = 20)))
        h.presenter.startCreate("G003", FormSource.TAB, null); advanceUntilIdle()
        h.presenter.setName("하나 더"); advanceUntilIdle()
        assertTrue(h.state.limitReached)
        assertEquals(FormMessage.LIMIT_REACHED, h.state.message)
        assertFalse(h.state.canSubmit)
    }

    // ---- create: submit ----

    @Test fun createSucceeds() = scenario { h ->
        create(h)
        h.presenter.setName("책상 장비"); h.presenter.setDescription("업무 공간")
        h.categories.create.answer(created())
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(CategoryCommand("G003", "책상 장비", "업무 공간", emptyList()), h.categories.createCommands.single())
        assertEquals(customId, (h.state.result as FormResult.Created).detail.id)
        assertFalse(h.state.submitting)
        h.presenter.consumeResult(); advanceUntilIdle()
        assertNull(h.state.result)
    }

    @Test fun doubleSubmitIsIgnoredAndInputsAreLocked() = scenario { h ->
        create(h)
        h.presenter.setName("책상 장비")
        val held = h.categories.create.hold()
        h.presenter.submit(); h.presenter.submit(); advanceUntilIdle()
        assertTrue(h.state.submitting)
        h.presenter.setName("바뀜"); advanceUntilIdle()
        assertEquals("책상 장비", h.state.name)
        assertEquals(1, h.categories.createKeys.size)
        held.complete(created()); advanceUntilIdle()
        assertIs<FormResult.Created>(h.state.result)
    }

    @Test fun lostResponseRetryReusesKeyAndReplays() = scenario { h ->
        create(h)
        h.presenter.setName("책상 장비")
        h.categories.create.answer(fail(ErrorKind.TIMEOUT))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(FormMessage.SAVE_FAILED, h.state.message)
        assertEquals("책상 장비", h.state.name)
        h.categories.create.answer(created())
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(1, h.categories.createKeys.toSet().size)
        assertIs<FormResult.Created>(h.state.result)
    }

    @Test fun changedPayloadGetsNewKey() = scenario { h ->
        create(h)
        h.presenter.setName("책상")
        h.categories.create.answer(fail(ErrorKind.NETWORK))
        h.presenter.submit(); advanceUntilIdle()
        h.presenter.setName("책상 장비")
        h.categories.create.answer(created())
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(2, h.categories.createKeys.toSet().size)
    }

    @Test fun definiteRefusalNeedsNoReplayKey() = scenario { h ->
        create(h)
        h.presenter.setName("책상")
        h.categories.create.answer(fail(ErrorKind.CONFLICT, "CATEGORY_NAME_DUPLICATE", fields = setOf("name")))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(mapOf("name" to FieldError.DUPLICATE), h.state.fieldErrors)
        h.presenter.setName("책상2"); advanceUntilIdle()
        assertEquals(emptyMap(), h.state.fieldErrors) // fixing the field clears its error
        h.presenter.setName("책상"); advanceUntilIdle()
        h.categories.create.answer(created())
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(2, h.categories.createKeys.toSet().size)
    }

    @Test fun replayIsNotBlockedByLimitPrecheck() = scenario { h ->
        // The 20th create: the server made it but the answer was lost. The resend must go (same key, replay).
        h.categories.catalog.answer(ok(select.copy(customUsedCount = 19)))
        h.presenter.startCreate("G003", FormSource.TAB, null); advanceUntilIdle()
        h.presenter.setName("스무 번째")
        h.categories.create.answer(fail(ErrorKind.NETWORK))
        h.presenter.submit(); advanceUntilIdle()
        assertTrue(h.state.canSubmit)
        h.categories.create.answer(ok(CategoryCreated(CustomCategoryDetail(customId, "스무 번째", "G003", null, emptyList(), 0, 1), 20, 20)))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(1, h.categories.createKeys.toSet().size)
        assertIs<FormResult.Created>(h.state.result)
        assertEquals(20, h.state.usedCount)
    }

    @Test fun limitRefusalBlocksFurtherCreates() = scenario { h ->
        create(h)
        h.presenter.setName("하나")
        h.categories.create.answer(fail(ErrorKind.CONFLICT, "CATEGORY_LIMIT_REACHED"))
        h.presenter.submit(); advanceUntilIdle()
        assertTrue(h.state.limitReached)
        assertFalse(h.state.canSubmit)
    }

    @Test fun errorMapping() = scenario { h ->
        create(h)
        h.presenter.setName("책상")
        h.categories.create.answer(fail(ErrorKind.RATE_LIMITED, "CATEGORY_CREATE_RATE_LIMITED"))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(FormMessage.RATE_LIMITED, h.state.message)
        h.categories.create.answer(fail(ErrorKind.VALIDATION, "INVALID_CATEGORY_INPUT", fields = setOf("description", "examples", "x")))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(mapOf("description" to FieldError.INVALID_CHARS, "examples" to FieldError.INVALID_CHARS), h.state.fieldErrors)
        h.categories.create.answer(fail(ErrorKind.VALIDATION, "INVALID_CATEGORY_PARENT", fields = setOf("parentId")))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(mapOf("parentId" to FieldError.PARENT), h.state.fieldErrors)
        h.categories.create.answer(fail(ErrorKind.VALIDATION, "INVALID_CATEGORY_INPUT"))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(FormMessage.SAVE_FAILED, h.state.message)
    }

    // ---- edit ----

    @Test fun editLoadsTheDetail() = scenario { h ->
        edit(h)
        assertEquals(FormPhase.READY, h.state.phase)
        assertEquals("오디오 케이블·DAC", h.state.name)
        assertEquals(listOf("포터블 DAC", "이어폰 케이블"), h.state.examples)
        assertEquals(2, h.state.itemCount)
        assertEquals("디지털·IT", h.state.parentName)
        assertFalse(h.state.dirty)
        assertFalse(h.state.canSubmit)
    }

    @Test fun editFirstLoadFailureAndGone() = scenario { h ->
        h.categories.custom.answer(fail(ErrorKind.NETWORK))
        h.presenter.startEdit(customId, "디지털·IT"); advanceUntilIdle()
        assertEquals(FormPhase.LOAD_FAILED, h.state.phase)
        h.presenter.setName("x"); advanceUntilIdle()
        assertEquals("", h.state.name) // no input before the baseline
        h.categories.custom.answer(fail(ErrorKind.NOT_FOUND, "CATEGORY_NOT_FOUND"))
        h.presenter.retryLoad(); advanceUntilIdle()
        assertEquals(FormResult.Gone, h.state.result)
    }

    @Test fun editDirtyComparesWithTheBaseline() = scenario { h ->
        edit(h)
        h.presenter.setName("케이블"); advanceUntilIdle()
        assertTrue(h.state.canSubmit)
        h.presenter.setName("오디오 케이블·DAC"); advanceUntilIdle()
        assertFalse(h.state.dirty)
        h.presenter.setDescription(""); advanceUntilIdle()
        assertTrue(h.state.dirty)
    }

    @Test fun editSendsOnlyTheChanges() = scenario { h ->
        edit(h)
        h.presenter.setDescription("")
        h.presenter.removeExample(1)
        h.categories.update.answer(ok(detail.copy(description = null, examples = listOf("포터블 DAC"), version = 5)))
        h.presenter.submit(); advanceUntilIdle()
        val (id, version, patch) = h.categories.patches.single()
        assertEquals(customId, id)
        assertEquals(4, version)
        assertEquals(CategoryPatch(name = null, description = DescriptionChange.Set(null), examples = listOf("포터블 DAC")), patch)
        assertEquals(5, (h.state.result as FormResult.Saved).detail.version)
    }

    @Test fun conflictReplacesForm() = scenario { h ->
        edit(h)
        h.presenter.setName("내 이름")
        h.categories.update.answer(fail(ErrorKind.CONFLICT, "CATEGORY_VERSION_CONFLICT", currentVersion = 5))
        val latest = detail.copy(name = "다른 기기 이름", version = 5)
        val held = h.categories.custom.hold()
        h.presenter.submit(); advanceUntilIdle()
        assertTrue(h.state.submitting) // locked while the latest detail loads
        h.presenter.setName("중간 입력"); advanceUntilIdle()
        assertEquals("내 이름", h.state.name)
        held.complete(ok(latest)); advanceUntilIdle()
        assertEquals("다른 기기 이름", h.state.name)
        assertEquals(FormMessage.CONFLICT_LOADED, h.state.message)
        assertFalse(h.state.dirty)
        h.presenter.setName("다시"); advanceUntilIdle()
        h.categories.update.answer(ok(latest.copy(name = "다시", version = 6)))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(5, h.categories.patches.last().second) // the new baseline's version
    }

    @Test fun conflictWithAlreadyAppliedValuesEndsSaved() = scenario { h ->
        edit(h)
        h.presenter.setName("내 이름")
        h.categories.update.answer(fail(ErrorKind.CONFLICT, "CATEGORY_VERSION_CONFLICT", currentVersion = 5))
        h.categories.custom.answer(ok(detail.copy(name = "내 이름", version = 5)))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals("내 이름", (h.state.result as FormResult.Saved).detail.name)
        assertNull(h.state.message)
    }

    @Test fun conflictRefetchFailureBlocksSave() = scenario { h ->
        edit(h)
        h.presenter.setName("내 이름")
        h.categories.update.answer(fail(ErrorKind.CONFLICT, "CATEGORY_VERSION_CONFLICT", currentVersion = 5))
        h.categories.custom.answer(fail(ErrorKind.NETWORK))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(FormPhase.CONFLICT_UNRESOLVED, h.state.phase)
        assertEquals(FormMessage.CONFLICT_FAILED, h.state.message)
        assertEquals("내 이름", h.state.name)
        assertFalse(h.state.canSubmit)
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(1, h.categories.patches.size) // never resent with the old version
        h.categories.custom.answer(ok(detail.copy(name = "다른 이름", version = 5)))
        h.presenter.retryLoad(); advanceUntilIdle()
        assertEquals(FormPhase.READY, h.state.phase)
        assertEquals("다른 이름", h.state.name)
    }

    @Test fun conflictRefetchNotFoundIsGone() = scenario { h ->
        edit(h)
        h.presenter.setName("내 이름")
        h.categories.update.answer(fail(ErrorKind.CONFLICT, "CATEGORY_VERSION_CONFLICT", currentVersion = 5))
        h.categories.custom.answer(fail(ErrorKind.NOT_FOUND, "CATEGORY_NOT_FOUND"))
        h.presenter.submit(); advanceUntilIdle()
        assertEquals(FormResult.Gone, h.state.result)
    }

    @Test fun accountChangeCancelsAndResets() = scenario { h ->
        create(h)
        h.presenter.setName("책상")
        val held = h.categories.create.hold()
        h.presenter.submit(); advanceUntilIdle()
        h.session.changeAccount("B"); advanceUntilIdle()
        held.complete(created()); advanceUntilIdle()
        assertEquals(CategoryFormState.Initial, h.state)
    }
}
