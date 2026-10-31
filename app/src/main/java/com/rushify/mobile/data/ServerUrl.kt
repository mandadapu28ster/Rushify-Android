package com.rushify.mobile.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Validates user input as scheme://host[:port] (port 1-65535); no path, query or credentials. */
object ServerUrl {
    sealed class Result {
        data class Ok(val url: HttpUrl, val isCleartext: Boolean) : Result()
        data class Bad(val reason: String) : Result()
    }

    fun parse(input: String): Result {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty()) return Result.Bad("Enter a server address")
        val withScheme = if (text.contains("://")) text else return Result.Bad("Start with http:// or https://")
        val scheme = withScheme.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return Result.Bad("Only http:// or https:// is supported")
        // OkHttp rejects out-of-range ports outright, so check the port ourselves for a precise message.
        val authority = withScheme.substringAfter("://").substringBefore('/').substringAfterLast('@')
        val rawPort = if (authority.startsWith("[")) authority.substringAfter("]", "").removePrefix(":") else authority.substringAfter(':', "")
        if (rawPort.isNotEmpty() && (rawPort.toIntOrNull() ?: -1) !in 1..65535) return Result.Bad("Port must be between 1 and 65535")
        val url = withScheme.toHttpUrlOrNull() ?: return Result.Bad("That does not look like a valid address")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return Result.Bad("Do not include credentials in the address")
        if (url.encodedPath != "/" || url.query != null || url.fragment != null) return Result.Bad("Use only scheme://host[:port], without a path")
        if (url.port !in 1..65535) return Result.Bad("Port must be between 1 and 65535")
        return Result.Ok(url, isCleartext = url.scheme == "http")
    }
}
