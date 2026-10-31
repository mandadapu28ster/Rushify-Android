package com.rushify.mobile.data

import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

/** Typed failures so ViewModels can map them to the behaviours in the plan's error table. */
sealed class ApiError(message: String) : Exception(message) {
    class BadCredentials(msg: String) : ApiError(msg)
    class Locked(msg: String) : ApiError(msg)
    class Inactive(msg: String) : ApiError(msg)
    class SessionExpired : ApiError("Session expired. Please sign in again.")
    class QuotaReached(msg: String) : ApiError(msg)
    class SyncRunning(msg: String) : ApiError(msg)
    class Server(val code: Int, msg: String) : ApiError(msg)
    class Network(msg: String) : ApiError(msg)
    class Invalid(msg: String) : ApiError(msg)
    /** 400 "not configured": the user has not saved their own Gemini key on the web. */
    class NotConfigured(msg: String) : ApiError(msg)
}

fun detailText(el: JsonElement?): String? = when (el) {
    null -> null
    is JsonPrimitive -> el.content
    is JsonArray -> el.joinToString("; ") { detailText(it) ?: "" }
    is JsonObject -> (el["msg"] as? JsonPrimitive)?.content ?: el.toString()
}

fun mapError(t: Throwable, hadAuth: Boolean): ApiError = when (t) {
    is ApiError -> t
    is HttpException -> {
        val raw = runCatching { t.response()?.errorBody()?.string() }.getOrNull()
        val detail = raw?.let { runCatching { detailText(AppJson.decodeFromString(ErrorBody.serializer(), it).detail) }.getOrNull() }
        when (t.code()) {
            401 -> if (hadAuth) ApiError.SessionExpired() else ApiError.BadCredentials(detail ?: "Login failure try again.")
            403 -> if (detail?.contains("inactive", ignoreCase = true) == true) ApiError.Inactive(detail)
                   else ApiError.Locked(detail ?: "You are locked, wait for one day and try again")
            400 -> if (detail?.contains("not configured", ignoreCase = true) == true) ApiError.NotConfigured(detail) else ApiError.Server(400, detail ?: "Bad request")
            409 -> ApiError.SyncRunning(detail ?: "A sync is already running")
            429 -> ApiError.QuotaReached(detail ?: "Daily manual sync limit reached")
            else -> ApiError.Server(t.code(), detail ?: "Server error (${t.code()})")
        }
    }
    is IOException -> ApiError.Network(t.message ?: "Network error")
    is kotlinx.serialization.SerializationException -> ApiError.Server(200, "Unexpected response from server")
    else -> ApiError.Network(t.message ?: "Unknown error")
}
