package com.wren.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat

/**
 * Opens [url] outside the app. Chrome Custom Tabs keeps the OAuth session (and Google's
 * "not an embedded browser" checks) happy; falls back to the system browser when no
 * Custom Tabs provider is installed. Returns false when no browser handled it, so callers
 * can show the link for manual copy instead of failing silently.
 */
fun openInBrowser(context: Context, url: String): Boolean {
    val customTab = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
        .intent
        .setData(Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { ContextCompat.startActivity(context, customTab, null) }.isSuccess) return true

    val plain = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(plain) }.isSuccess
}
