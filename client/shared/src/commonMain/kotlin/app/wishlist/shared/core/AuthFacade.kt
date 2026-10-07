package app.wishlist.shared.core

import kotlinx.coroutines.flow.StateFlow

enum class AuthProvider { APPLE, GOOGLE }

data class AuthAccount(val accountId: String, val email: String, val provider: AuthProvider)

/**
 * Login state seen by the apps. The platform SDK's stable account ID is the session account; the
 * session itself is changed only by the implementation, never by app code.
 */
interface AuthFacade {
    /** null = 로그인 전. 앱 시작 시 저장된 계정 복원이 끝나면 [restored] = true. */
    val account: StateFlow<AuthAccount?>
    val restored: StateFlow<Boolean>
    suspend fun signIn(provider: AuthProvider): ClientResult<AuthAccount>

    /** 현재 계정 캐시를 지우고(미전송은 보존) 로그아웃. */
    suspend fun signOut(): ClientResult<Unit>
    suspend fun hasSeenFirstRunLogin(): Boolean
    suspend fun markFirstRunLoginSeen()
}
