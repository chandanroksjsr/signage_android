package com.ebani.sinage.net


import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object Net {

    // TODO: replace with your real endpoints
//    const val WS_URL   = "http://192.168.29.175"
    const val WS_URL   = "https://signage.digitopia.live"
    const val BASE_URL = "$WS_URL/"



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

}

