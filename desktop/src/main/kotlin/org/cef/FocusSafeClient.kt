package org.cef

import org.cef.browser.CefBrowser

// jcef's onGotFocus focuses the browser again; with linux's single-threaded message loop cef answers on the same stack,
// so it recursed until the stack overflowed on every focus change. the nested call is dropped instead
class FocusSafeClient private constructor() : CefClient() {
    private var focusing = false

    override fun onGotFocus(browser: CefBrowser?) {
        if (focusing) return
        focusing = true
        try {
            super.onGotFocus(browser)
        } finally {
            focusing = false
        }
    }

    companion object {
        // the same steps as CefApp.createClient, which can only make plain clients
        fun create(app: CefApp): CefClient {
            app.createClient().dispose()
            val client = FocusSafeClient()
            synchronized(app) {
                @Suppress("UNCHECKED_CAST")
                (CefApp::class.java.getDeclaredField("clients_").apply { isAccessible = true }.get(app) as HashSet<CefClient>).add(client)
            }
            return client
        }
    }
}
