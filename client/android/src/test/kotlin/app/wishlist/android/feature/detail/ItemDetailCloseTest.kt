package app.wishlist.android.feature.detail

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.presentation.ItemDetailState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the item detail screen closes itself (account generation change, signed-out restored stack). */
class ItemDetailCloseTest {
    @Test fun initial_closes_only_after_work_started() {
        assertFalse(shouldClose(ItemDetailState.Initial, seenWork = false)) // load not started yet
        assertTrue(shouldClose(ItemDetailState.Initial, seenWork = true))
    }

    @Test fun signed_out_with_nothing_shown_closes() {
        val unauthenticated = ItemDetailState(item = null, loading = false, error = ClientError(ErrorKind.UNAUTHENTICATED))
        assertTrue(shouldClose(unauthenticated, seenWork = false))
        assertFalse(shouldClose(unauthenticated.copy(loading = true), seenWork = true))
    }

    @Test fun other_states_stay() {
        ErrorKind.entries.filter { it != ErrorKind.UNAUTHENTICATED }.forEach { kind ->
            assertFalse(kind.name, shouldClose(ItemDetailState(null, false, ClientError(kind)), seenWork = true))
        }
        assertFalse(shouldClose(ItemDetailState(null, loading = true, error = null), seenWork = true))
    }
}
