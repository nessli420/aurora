package com.aurora.music.data.container

import com.aurora.music.data.MediaBackend
import com.aurora.music.data.Session
import com.aurora.music.data.accountKey
import com.aurora.music.data.settings.AccountSettings
import com.aurora.music.data.settings.LibrarySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SessionCoordinator(
    private val accounts: AccountSettings,
    private val library: LibrarySettings,
    private val scope: CoroutineScope,
    private val buildActive: (session: Session, unified: Boolean, mergeKeys: Set<String>, saved: List<Session>) -> MediaBackend?,
    private val backend: () -> MediaBackend?,
    private val setBackend: (MediaBackend?) -> Unit,
    private val rememberRestored: suspend (Session) -> Unit,
    private val rememberApplied: suspend (Session) -> Unit,
    private val onAccountChanged: suspend (Session?) -> Unit,
    private val forgetLibrary: () -> Unit,
    private val beforeRebuild: suspend (Session?) -> Unit = {},
    // null means every account builds a backend, so ready follows the session alone
    private val supports: ((Session) -> Boolean)? = null,
) {
    @Volatile private var unifiedLibrary: Boolean = false
    @Volatile private var mergeSourceKeys: Set<String> = emptySet()
    @Volatile var lastSession: Session? = null
        private set
    @Volatile private var lastAccountKey: String? = null

    private val _sessionReady = MutableStateFlow<Boolean?>(null)
    val sessionReady: StateFlow<Boolean?> = _sessionReady.asStateFlow()

    private val _unsupportedAccount = MutableStateFlow<Session?>(null)
    val unsupportedAccount: StateFlow<Session?> = _unsupportedAccount.asStateFlow()

    // bumped only on a real account change not initial load or token refresh
    private val _accountEpoch = MutableStateFlow(0)
    val accountEpoch: StateFlow<Int> = _accountEpoch.asStateFlow()

    // reloads home/library without the playback-stopping semantics of an account change
    private val _libraryReload = MutableStateFlow(0)
    val libraryReload: StateFlow<Int> = _libraryReload.asStateFlow()

    fun reloadLibrary() { _libraryReload.value++ }

    fun isActive(session: Session): Boolean = lastSession?.accountKey() == session.accountKey() ||
        (unifiedLibrary && lastSession?.type?.supportsMergedLibrary == true &&
            (mergeSourceKeys.isEmpty() || session.accountKey() in mergeSourceKeys))

    fun start() {
        scope.launch {
            var previous: Triple<Session?, Boolean, Set<String>>? = null
            // one collector, so launch builds once with the merge settings already known
            combine(accounts.session.distinctUntilChanged(), library.unifiedLibrary.distinctUntilChanged(),
                library.mergeSources.distinctUntilChanged()) { session, unified, merge -> Triple(session, unified, merge) }
                .distinctUntilChanged().collect { current ->
                    val (session, unified, merge) = current
                    val last = previous
                    previous = current
                    val sessionChanged = last == null || last.first != session
                    unifiedLibrary = unified
                    mergeSourceKeys = merge
                    if (sessionChanged) {
                        lastSession = session
                        // keep a disk-restored session in the saved list so it shows up for switching
                        session?.let { rememberRestored(it) }
                    }
                    beforeRebuild(lastSession)
                    rebuildBackend()
                    if (sessionChanged) {
                        publish(session)
                        // ignore the first load so startup doesn't count as an account change
                        val key = session?.accountKey().orEmpty()
                        if (lastAccountKey != null && lastAccountKey != key) _accountEpoch.value++
                        lastAccountKey = key
                        onAccountChanged(session)
                    }
                    if (last != null && (last.second != unified || last.third != merge)) { reloadLibrary(); forgetLibrary() }
                }
        }
        scope.launch {
            accounts.savedSessions.distinctUntilChanged().drop(1).collect {
                if (unifiedLibrary && lastSession?.type?.supportsMergedLibrary == true) {
                    rebuildBackend()
                    reloadLibrary()
                    forgetLibrary()
                }
            }
        }
    }

    suspend fun rebuildBackend() {
        val session = lastSession
        setBackend(session?.let {
            val saved = runCatching { accounts.savedSessions.first() }.getOrDefault(emptyList())
            buildActive(it, unifiedLibrary, mergeSourceKeys, saved)
        })
    }

    suspend fun applySession(session: Session) {
        lastSession = session
        accounts.saveSession(session)
        rememberApplied(session)
        rebuildBackend()
        publish(session)
    }

    suspend fun signOut() {
        lastSession = null
        setBackend(null)
        publish(null)
        accounts.clearSession()
    }

    private fun publish(session: Session?) {
        supports?.let { check -> _unsupportedAccount.value = session?.takeIf { !check(it) } }
        _sessionReady.value = session != null && (supports == null || backend() != null)
    }
}
