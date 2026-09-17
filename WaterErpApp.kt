package com.sidilahcen.watererp

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebView

class WaterErpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
    }
}
