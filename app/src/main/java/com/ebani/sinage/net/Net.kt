package com.ebani.sinage.net


import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object Net {

    // TODO: replace with your real endpoints
     const val BASE_URL = "http://192.168.29.173:3000/"
     const val WS_URL   = "ws://192.168.29.173:3000/ws"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Retrofit */
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    /** Exposed API service */
    val api: ApiService by lazy { retrofit.create(ApiService::class.java) }

    /**
     * Socket factory — we pass the Application context so callers don’t have to.
     * Usage:
     * val ws = Net.socket(onMessage = { /* handle */ }, onConnected = { /* ... */ })
     * ws.connect()
     */
//    fun socket(
//        onMessage: (PairingMessage) -> Unit,
//        onConnected: () -> Unit = {}
//    ): SocketClient {
//        return SocketClient(
//            ctx = App.appContext,
//            url = WS_URL,
//            onMessage = onMessage,
//            onConnected = onConnected
//        )
//    }
}

