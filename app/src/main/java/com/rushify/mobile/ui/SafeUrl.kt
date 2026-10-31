package com.rushify.mobile.ui

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * News links come from third-party feeds. Only absolute http/https URLs with a host may be opened;
 * javascript:, intent:, file:, content:, data: and scheme-less values are rejected.
 */
object SafeUrl {
    fun httpOrNull(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.any { it.isISOControl() }) return null
        return text.toHttpUrlOrNull()?.toString()   // OkHttp parses only http and https
    }
}
