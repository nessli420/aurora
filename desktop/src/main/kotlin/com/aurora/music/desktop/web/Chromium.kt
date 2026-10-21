package com.aurora.music.desktop.web

import com.aurora.music.desktop.platform.HostPlatform
import com.aurora.music.util.AppLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import org.cef.network.CefRequest
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.math.sign

// one embedded chromium for sign-in pages and the hidden service pages; downloaded the first time it's needed
object Chromium {
    sealed interface Status {
        data object Idle : Status
        // fraction is -1 while there's no estimate
        data class Preparing(val downloading: Boolean, val fraction: Float) : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    @Volatile private var root: File? = null
    private val lock = Mutex()
    @Volatile private var client: CefClient? = null
    private val pages = ConcurrentHashMap<CefBrowser, WebPage>()

    fun configure(root: File) {
        this.root = root
    }

    val installed: Boolean get() = root?.let { File(it, "runtime/install.lock").isFile } == true

    suspend fun client(): CefClient = lock.withLock {
        client?.let { return it }
        val dir = checkNotNull(root) { "Chromium is not configured" }
        try {
            mutableStatus.value = Status.Preparing(downloading = !installed, fraction = -1f)
            val app = withContext(Dispatchers.IO) {
                CefAppBuilder().apply {
                    setInstallDir(File(dir, "runtime"))
                    setProgressHandler { stage, value ->
                        val fraction = when {
                            value < 0f -> -1f
                            value > 1f -> value / 100f
                            else -> value
                        }
                        mutableStatus.value = Status.Preparing(stage == EnumProgress.DOWNLOADING || stage == EnumProgress.EXTRACTING, fraction)
                    }
                    cefSettings.windowless_rendering_enabled = true
                    cefSettings.persist_session_cookies = true
                    cefSettings.root_cache_path = File(dir, "profile").absolutePath
                    cefSettings.cache_path = File(dir, "profile/default").absolutePath
                    cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE
                }.build()
            }
            val created = withContext(Dispatchers.Swing) {
                (if (HostPlatform.isLinux) org.cef.FocusSafeClient.create(app) else app.createClient()).also(::install)
            }
            client = created
            mutableStatus.value = Status.Ready
            created
        } catch (e: Exception) {
            AppLog.w(TAG, "Chromium could not start", e)
            mutableStatus.value = Status.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun install(client: CefClient) {
        val router = CefMessageRouter.create()
        router.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(browser: CefBrowser, frame: CefFrame, queryId: Long, request: String, persistent: Boolean, callback: CefQueryCallback): Boolean {
                val page = pages[browser] ?: return false
                page.receive(frame, request)
                callback.success("")
                return true
            }
        }, true)
        client.addMessageRouter(router)
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {
                val page = pages[browser] ?: return
                if (frame.isMain) page.started()
                page.prepareFrame(frame)
            }

            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                val page = pages[browser] ?: return
                if (frame.isMain) page.finished()
                page.prepareFrame(frame)
            }

            override fun onLoadError(browser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String) {
                if (frame.isMain && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) pages[browser]?.failed()
            }
        })
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(browser: CefBrowser, frame: CefFrame, url: String) {
                if (frame.isMain) pages[browser]?.moved(url)
            }
        })
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser) {
                pages[browser]?.created()
            }

            // a page may close its own window, which would leave ours showing a dead browser
            override fun doClose(browser: CefBrowser): Boolean {
                val page = pages[browser] ?: return browser.doClose()
                java.awt.EventQueue.invokeLater(page::close)
                return true
            }
        })
    }

    // every page renders off screen, so java hands it the mouse and keyboard; a hidden one is simply never shown.
    // a native browser window inside a java one never gets typed into reliably
    suspend fun open(url: String, hidden: Boolean, origins: Set<String>): WebPage {
        val client = client()
        return withContext(Dispatchers.Swing) {
            val browser = client.createBrowser(url, true, false)
            correctWheel(browser.uiComponent)
            WebPage(browser, origins, hidden).also { page ->
                pages[browser] = page
                if (hidden) browser.createImmediately()
            }
        }
    }

    // jcef passes java's wheel units straight to cef, which reads them as pixels the other way: three pixels up per notch
    // down. they go through again as block scrolls, which jcef hands over as they are, at chrome's own step per notch
    private fun correctWheel(component: java.awt.Component) {
        val forward = component.mouseWheelListeners.toList()
        forward.forEach(component::removeMouseWheelListener)
        component.addMouseWheelListener { e ->
            val precise = -e.preciseWheelRotation * wheelPixels
            val pixels = precise.roundToInt().takeIf { it != 0 } ?: precise.sign.toInt()
            val corrected = java.awt.event.MouseWheelEvent(e.component, e.id, e.`when`, e.modifiersEx, e.x, e.y, e.xOnScreen, e.yOnScreen,
                e.clickCount, e.isPopupTrigger, java.awt.event.MouseWheelEvent.WHEEL_BLOCK_SCROLL, 1, pixels, pixels.toDouble())
            forward.forEach { it.mouseWheelMoved(corrected) }
        }
    }

    internal fun forget(browser: CefBrowser) {
        pages.remove(browser)
    }

    suspend fun cookies(url: String): List<CefCookie> {
        client()
        val found = mutableListOf<CefCookie>()
        val done = CompletableDeferred<Unit>()
        val started = CefCookieManager.getGlobalManager().visitUrlCookies(url, true) { cookie, count, total, _ ->
            synchronized(found) { found += cookie }
            if (count >= total - 1) done.complete(Unit)
            true
        }
        // the visitor is never called when there are no cookies
        if (started) withTimeoutOrNull(1_500) { done.await() }
        return synchronized(found) { found.toList() }
    }

    suspend fun cookieHeader(url: String): String = cookies(url).joinToString("; ") { "${it.name}=${it.value}" }

    suspend fun clearCookies(hosts: List<String>) {
        client()
        val manager = CefCookieManager.getGlobalManager()
        hosts.forEach { host -> manager.deleteCookies("https://$host", "") }
    }

    suspend fun setCookie(url: String, cookie: CefCookie) {
        client()
        CefCookieManager.getGlobalManager().setCookie(url, cookie)
    }

    fun shutdown() {
        if (client == null) return
        runCatching { CefApp.getInstance().dispose() }
    }

    private const val TAG = "Chromium"
    private val wheelPixels = if (HostPlatform.isWindows) 100.0 else 53.0
}

class WebPage internal constructor(val browser: CefBrowser, private val origins: Set<String>, internal val hidden: Boolean = false) {
    private val mutableUrl = MutableStateFlow("")
    val url: StateFlow<String> = mutableUrl.asStateFlow()
    private val mutableLoading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()
    private val mutableFailed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = mutableFailed.asStateFlow()

    // bridges the page scripts post to, keyed by their window name; only trusted origins reach them
    @Volatile var onMessage: (bridge: String, data: String) -> Unit = { _, _ -> }
    // runs as each new document starts, before most of the page's own scripts
    @Volatile var startScript: String? = null

    private val evaluations = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val ids = AtomicInteger()

    // off-screen browsers can't open windows of their own, so the page's window.open gets a stand-in
    // and the popup opens in a window of ours, its opener relayed back to the frame that asked for it
    private val mutablePopup = MutableStateFlow<WebPage?>(null)
    val popup: StateFlow<WebPage?> = mutablePopup.asStateFlow()
    @Volatile private var opener: Opener? = null
    @Volatile private var pendingPopup: Pair<String, String?>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    @Volatile private var native = false
    @Volatile private var pendingUrl: String? = null
    @Volatile private var closed = false

    // the opener stand-in has to exist before the popup's own scripts run, so devtools adds it to every new document
    private class Opener(val page: WebPage, val frame: String, val id: String, val script: String)

    val origin: String get() = originOf(url.value)

    internal fun started() {
        mutableLoading.value = true
        mutableFailed.value = false
        startScript?.let(::run)
        opener?.let { run(it.script) }
    }

    internal fun finished() {
        mutableLoading.value = false
        startScript?.let(::run)
        opener?.let { run(it.script) }
        if (!hidden) java.awt.EventQueue.invokeLater { focus() }
    }

    internal fun created() {
        val script = opener?.script
        val registered = script?.let {
            runCatching {
                val tools = browser.devToolsClient
                tools.executeDevToolsMethod("Page.enable").thenCompose {
                    tools.executeDevToolsMethod("Page.addScriptToEvaluateOnNewDocument", gson.toJson(mapOf("source" to script)))
                }
            }.onFailure { AppLog.w("Chromium", "popup opener could not be added", it) }.getOrNull()
        }
        if (registered == null) ready() else registered.whenComplete { _, _ -> ready() }
    }

    private fun ready() {
        native = true
        pendingUrl?.let { pendingUrl = null; browser.loadURL(it) }
    }

    internal fun moved(url: String) {
        mutableUrl.value = url
        val from = opener ?: return
        from.page.inFrame(from.frame, "window.__auroraPopups && window.__auroraPopups.moved(${quote(from.id)}, ${quote(url)});")
    }

    internal fun prepareFrame(frame: CefFrame) {
        if (!hidden && opener == null && sameSite(frame.url.orEmpty())) frame.executeJavaScript(POPUP_SCRIPT, frame.url.orEmpty(), 0)
    }

    fun focus() {
        if (hidden) return
        browser.uiComponent.requestFocusInWindow()
        browser.setFocus(true)
    }

    internal fun failed() {
        mutableLoading.value = false
        mutableFailed.value = true
    }

    internal fun receive(frame: CefFrame, request: String) {
        val json = runCatching { JsonParser.parseString(request).asJsonObject }.getOrNull() ?: return
        val frameUrl = frame.url.orEmpty()
        if (json.has(POPUP)) {
            val id = frame.identifier
            if (!hidden && opener == null && sameSite(frameUrl)) scope.launch { popupRequest(id, json) }
            return
        }
        if (json.has(OPENER)) {
            if (frame.isMain) scope.launch { openerRequest(frameUrl, json) }
            return
        }
        if (!frame.isMain || originOf(frameUrl) !in origins) return
        json.get(EVAL)?.takeIf { it.isJsonPrimitive }?.asInt?.let { id ->
            evaluations.remove(id)?.complete(json.get("value")?.takeIf { it.isJsonPrimitive }?.asString ?: "null")
            return
        }
        val bridge = json.get(BRIDGE)?.takeIf { it.isJsonPrimitive }?.asString ?: return
        onMessage(bridge, json.get("data")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
    }

    fun bridgeScript(name: String): String = """window.$name = { postMessage: function (m) {
        window.cefQuery({ request: JSON.stringify({ $BRIDGE: '$name', data: String(m) }) }); } };"""

    // the value comes back as json text, or null when the page didn't answer
    suspend fun eval(expression: String, timeoutMs: Long = 4_000): String? {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<String>()
        evaluations[id] = reply
        val script = """(function () {
            function send(v) { window.cefQuery({ request: JSON.stringify({ $EVAL: $id, value: JSON.stringify(v === undefined ? null : v) }) }); }
            try { Promise.resolve($expression).then(send, function () { send(null); }); } catch (e) { send(null); }
        })();"""
        return try {
            run(script)
            withTimeoutOrNull(timeoutMs) { reply.await() }
        } finally {
            evaluations.remove(id)
        }
    }

    fun run(script: String) {
        browser.executeJavaScript(script, browser.url ?: "", 0)
    }

    fun load(url: String) {
        if (native) browser.loadURL(url) else pendingUrl = url
    }

    private suspend fun popupRequest(frame: String, json: JsonObject) {
        val id = json.text("id") ?: return
        when (json.text(POPUP)) {
            "open" -> {
                mutablePopup.value?.close()
                var url = json.text("url")?.takeIf(::webAddress) ?: BLANK
                // pages often open a blank window first and send it on its way straight after
                if (url == BLANK) {
                    pendingPopup = id to null
                    delay(400)
                    url = pendingPopup?.takeIf { it.first == id }?.second ?: BLANK
                    pendingPopup = null
                }
                if (closed) return
                val opened = runCatching { Chromium.open(BLANK, hidden = false, origins = emptySet()) }
                    .onFailure { AppLog.w("Chromium", "popup could not open", it) }.getOrNull()
                if (opened == null) {
                    inFrame(frame, "window.__auroraPopups && window.__auroraPopups.closed(${quote(id)});")
                    return
                }
                val from = frame(frame)?.url.orEmpty()
                opened.opener = Opener(this, frame, id, openerScript(webOrigin(from), from))
                if (url != BLANK) opened.load(url)
                if (closed) opened.close() else mutablePopup.value = opened
            }
            "navigate" -> {
                val url = json.text("url")?.takeIf(::webAddress) ?: return
                val pending = pendingPopup
                if (pending != null && pending.first == id) pendingPopup = id to url
                else mutablePopup.value?.takeIf { it.opener?.id == id }?.load(url)
            }
            "close" -> mutablePopup.value?.takeIf { it.opener?.id == id }?.close()
        }
    }

    private fun openerRequest(frameUrl: String, json: JsonObject) {
        val from = opener ?: return
        when (json.text(OPENER)) {
            "post" -> {
                val data = json.text("data") ?: "null"
                val target = json.text("target") ?: "*"
                val frame = from.page.frame(from.frame) ?: return
                if (target != "*" && webOrigin(target) != webOrigin(frame.url.orEmpty())) return
                val script = "window.__auroraPopups && window.__auroraPopups.message(${quote(from.id)}, JSON.parse(${quote(data)}), ${quote(webOrigin(frameUrl))});"
                frame.executeJavaScript(script, frame.url.orEmpty(), 0)
            }
            // a popup from the opener's own origin may reach into it directly, the way the callback hands back its sign-in
            "call" -> {
                val name = json.text("name")?.takeIf { IDENTIFIER.matches(it) } ?: return
                val args = json.text("args") ?: "[]"
                val frame = from.page.frame(from.frame) ?: return
                if (webOrigin(frameUrl) != webOrigin(frame.url.orEmpty())) return
                val script = "(function () { var f = window[${quote(name)}]; if (typeof f === 'function') f.apply(window, JSON.parse(${quote(args)})); })();"
                frame.executeJavaScript(script, frame.url.orEmpty(), 0)
            }
            "navigate" -> {
                val target = json.text("url")?.takeIf(::webAddress) ?: return
                from.page.inFrame(from.frame, "window.location.href = ${quote(target)};")
            }
            "close" -> close()
        }
    }

    private fun frame(id: String): CefFrame? = browser.getFrameByIdentifier(id) ?: browser.mainFrame

    private fun inFrame(id: String, script: String) {
        if (closed) return
        val frame = frame(id) ?: return
        frame.executeJavaScript(script, frame.url.orEmpty(), 0)
    }

    // the sign-in form often sits in a frame from another subdomain of the same site
    private fun sameSite(frameUrl: String): Boolean {
        val host = runCatching { URI(frameUrl).takeIf { it.scheme == "https" }?.host }.getOrNull() ?: return false
        return origins.any { origin ->
            val site = runCatching { URI(origin).host }.getOrNull()?.split('.')?.takeLast(2)?.joinToString(".") ?: return@any false
            host == site || host.endsWith(".$site")
        }
    }

    fun reload() = browser.reload()

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        mutablePopup.value?.close()
        mutablePopup.value = null
        opener?.let { from ->
            from.page.inFrame(from.frame, "window.__auroraPopups && window.__auroraPopups.closed(${quote(from.id)});")
            if (from.page.mutablePopup.value === this) from.page.mutablePopup.value = null
        }
        Chromium.forget(browser)
        evaluations.values.forEach { it.cancel() }
        evaluations.clear()
        browser.setCloseAllowed()
        browser.close(true)
    }

    private companion object {
        const val EVAL = "__auroraEval"
        const val BRIDGE = "__auroraBridge"
        const val POPUP = "__auroraPopup"
        const val OPENER = "__auroraOpener"
        const val BLANK = "about:blank"
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        val gson = Gson()

        fun quote(text: String): String = gson.toJson(text)

        fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

        fun webAddress(url: String): Boolean = url == BLANK || runCatching { URI(url).scheme in setOf("https", "http") }.getOrDefault(false)

        fun webOrigin(url: String): String = runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}" + (if (uri.port > 0) ":${uri.port}" else "")
        }.getOrDefault("null")

        val POPUP_SCRIPT = """(function () {
            if (window.__auroraPopups) return;
            var popups = {}, count = 0;
            function send(m) { window.cefQuery({ request: JSON.stringify(m) }); }
            window.__auroraPopups = {
                closed: function (id) { var p = popups[id]; if (p) { p.closed = true; delete popups[id]; } },
                moved: function (id, url) { var p = popups[id]; if (p) p.__url = url; },
                message: function (id, data, origin) {
                    var event = new MessageEvent('message', { data: data, origin: origin });
                    if (popups[id]) Object.defineProperty(event, 'source', { value: popups[id] });
                    window.dispatchEvent(event);
                }
            };
            window.open = function (url, name) {
                var id = String(++count);
                var popup = { closed: false, opener: window, name: name || '', __url: 'about:blank',
                    focus: function () {}, blur: function () {}, postMessage: function () {},
                    close: function () { if (!popup.closed) { popup.closed = true; delete popups[id]; send({ __auroraPopup: 'close', id: id }); } } };
                function go(target) {
                    var href = new URL(String(target), window.location.href).href;
                    popup.__url = href;
                    send({ __auroraPopup: 'navigate', id: id, url: href });
                }
                var place = { assign: go, replace: go, toString: function () { return popup.__url; } };
                Object.defineProperty(place, 'href', { get: function () { return popup.__url; }, set: go });
                Object.defineProperty(popup, 'location', { get: function () { return place; }, set: go });
                popup.window = popup;
                popup.self = popup;
                popups[id] = popup;
                var href = url ? new URL(String(url), window.location.href).href : 'about:blank';
                popup.__url = href;
                send({ __auroraPopup: 'open', id: id, url: href });
                return popup;
            };
        })();"""

        fun openerScript(origin: String, href: String): String = """(function () {
            if (window.__auroraOpener) return;
            var origin = ${quote(origin)}, href = ${quote(href)};
            function send(m) { window.cefQuery({ request: JSON.stringify(m) }); }
            function go(target) { send({ __auroraOpener: 'navigate', url: new URL(String(target), window.location.href).href }); }
            function same() { return window.location.origin === origin; }
            var place = { assign: go, replace: go, origin: origin, toString: function () { return same() ? href : ''; } };
            Object.defineProperty(place, 'href', { set: go, get: function () { return same() ? href : ''; } });
            var base = { closed: false, focus: function () {}, blur: function () {},
                postMessage: function (data, target) { send({ __auroraOpener: 'post', data: JSON.stringify(data === undefined ? null : data), target: String(target || '*') }); } };
            Object.defineProperty(base, 'location', { get: function () { return place; }, set: go });
            var opener = new Proxy(base, { get: function (target, name) {
                if (typeof name !== 'string' || name in target) return target[name];
                if (name === 'then') return undefined;
                if (!same()) throw new DOMException('Blocked a frame from accessing a cross-origin frame.', 'SecurityError');
                return function () { send({ __auroraOpener: 'call', name: name, args: JSON.stringify(Array.prototype.slice.call(arguments)) }); };
            } });
            window.__auroraOpener = opener;
            window.opener = opener;
            window.close = function () { send({ __auroraOpener: 'close' }); };
        })();"""

        fun originOf(url: String): String = runCatching {
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) "" else "https://${uri.host}" + (if (uri.port > 0) ":${uri.port}" else "")
        }.getOrDefault("")
    }
}
