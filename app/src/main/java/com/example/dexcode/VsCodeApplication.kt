package com.example.dexcode

import android.app.Application
import android.webkit.CookieManager
import android.webkit.ServiceWorkerController
import android.webkit.WebView

/**
 * All activities intentionally use the same process and default WebView data directory.
 * That keeps cookies, IndexedDB, LocalStorage, cache and service-worker data shared by
 * every DeX window of this app.
 */
class VsCodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(com.example.dexcode.runtime.RuntimeTracker)

        // Must happen before the first WebView is created. Calling this explicitly documents
        // that the default, persistent profile is intentional; no private profile is used.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)

        // VS Code Web relies on its Service Worker for the workbench shell, tunnel
        // bootstrap, and callback pages. Configure the shared controller before any
        // WebView is created so every DeX window uses the same profile.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
                allowContentAccess = true
                allowFileAccess = true
            }
        }
    }
}
