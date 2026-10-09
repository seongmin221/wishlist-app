@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.android.feature.session

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthFacade
import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.presentation.AccountPresenter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The ViewModel owns the AccountPresenter's lifetime: clearing its store closes the Presenter (JVM only). */
class AccountPresenterOwnerTest {
    private class RecordingAuth : AuthFacade {
        override val account = MutableStateFlow<AuthAccount?>(null)
        override val restored: StateFlow<Boolean> = MutableStateFlow(true)
        val signIns = mutableListOf<AuthProvider>()
        var signOuts = 0
        var skips = 0

        override suspend fun signIn(provider: AuthProvider): ClientResult<AuthAccount> {
            signIns += provider
            val signedIn = AuthAccount("fake-${provider.name.lowercase()}", "user@example.com", provider)
            account.value = signedIn
            return ClientResult.Success(signedIn)
        }

        override suspend fun signOut(): ClientResult<Unit> {
            signOuts++
            account.value = null
            return ClientResult.Success(Unit)
        }

        override suspend fun hasSeenFirstRunLogin() = false
        override suspend fun markFirstRunLoginSeen() {
            skips++
        }
    }

    private val auth = RecordingAuth()

    /** The Presenter constructor is internal to :shared; the runtime builds it in the app. */
    private fun TestScope.presenter(): AccountPresenter =
        AccountPresenter::class.java
            .getDeclaredConstructor(AuthFacade::class.java, CoroutineDispatcher::class.java)
            .apply { isAccessible = true }
            .newInstance(auth, StandardTestDispatcher(testScheduler))

    private fun TestScope.owner(store: ViewModelStore): Pair<AccountPresenterOwner, AccountPresenter> {
        val presenter = presenter()
        val factory = viewModelFactory { initializer { AccountPresenterOwner(presenter) } }
        return ViewModelProvider.create(store, factory)[AccountPresenterOwner::class] to presenter
    }

    @Test fun owner_exposes_the_presenter_state_and_forwards_intents() = runTest {
        val store = ViewModelStore()
        val (owner, presenter) = owner(store)
        assertSame(presenter.state, owner.state)
        advanceUntilIdle()
        assertEquals(true, owner.state.value.showFirstRunLogin)

        owner.skipFirstRunLogin()
        owner.signIn(AuthProvider.GOOGLE)
        advanceUntilIdle()
        assertEquals(listOf(AuthProvider.GOOGLE), auth.signIns)
        assertEquals(AuthProvider.GOOGLE, owner.state.value.account?.provider)

        owner.signOut()
        advanceUntilIdle()
        assertEquals(1, auth.signOuts)
        assertEquals(null, owner.state.value.account)
        store.clear()
    }

    @Test fun clearing_the_view_model_store_closes_the_presenter() = runTest {
        val store = ViewModelStore()
        val (owner, _) = owner(store)
        advanceUntilIdle()
        val before = owner.state.value

        store.clear() // onCleared -> presenter.close()
        owner.signIn(AuthProvider.APPLE)
        owner.signOut()
        auth.account.value = AuthAccount("fake-apple-0001", "apple@example.com", AuthProvider.APPLE)
        advanceUntilIdle()

        assertEquals(emptyList<AuthProvider>(), auth.signIns)
        assertEquals(0, auth.signOuts)
        assertEquals(before, owner.state.value) // no longer follows the facade
    }

    @Test fun the_same_store_returns_the_same_owner_until_cleared() = runTest {
        val store = ViewModelStore()
        val (first, _) = owner(store)
        val (second, unused) = owner(store)
        assertSame(first, second)
        unused.close()
        store.clear()
    }
}
