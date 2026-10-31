package com.rushify.mobile.data

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Rewrites scheme/host/port to the stored server, so the stored scheme can never change silently. */
class BaseUrlInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val target = session.current.serverUrl?.toHttpUrlOrNull() ?: return chain.proceed(req)
        val url = req.url.newBuilder().scheme(target.scheme).host(target.host).port(target.port).build()
        return chain.proceed(req.newBuilder().url(url).build())
    }
}

/** Marks a call that must not carry the session token and must not end the session when it returns 401. */
const val NO_AUTH_HEADER = "X-No-Auth"

class AuthInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Public endpoints (change password): a 401 there means "wrong password", not "session expired".
        if (chain.request().header(NO_AUTH_HEADER) != null) {
            return chain.proceed(chain.request().newBuilder().removeHeader(NO_AUTH_HEADER).removeHeader("Authorization").build())
        }
        val token = session.current.token
        val req = if (token != null) chain.request().newBuilder().header("Authorization", "Bearer $token").build() else chain.request()
        val resp = chain.proceed(req)
        if (resp.code == 401 && token != null) session.expire()
        return resp
    }
}

object Network {
    fun client(session: SessionManager, timeoutSec: Long = 30): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BaseUrlInterceptor(session))
        .addInterceptor(AuthInterceptor(session))
        .followSslRedirects(false) // never follow an HTTPS -> HTTP redirect
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec, TimeUnit.SECONDS)
        .build()

    fun api(session: SessionManager, client: OkHttpClient = client(session)): ApiService =
        Retrofit.Builder()
            .baseUrl("http://localhost/") // placeholder; BaseUrlInterceptor swaps in the real server
            .client(client)
            .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
}
