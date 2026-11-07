package com.fplmanagerai.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

interface FplApi {
    @GET("bootstrap-static/")
    suspend fun getBootstrap(): JsonObject

    @GET("fixtures/")
    suspend fun getFixtures(): JsonArray

    companion object {
        private const val BASE_URL = "https://fantasy.premierleague.com/api/"

        fun create(): FplApi {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "FPLManagerAI-Android/1.0 (Android Mobile App)")
                        .header("Accept", "application/json")
                        .build()
                    chain.proceed(request)
                }
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(FplApi::class.java)
        }
    }
}
