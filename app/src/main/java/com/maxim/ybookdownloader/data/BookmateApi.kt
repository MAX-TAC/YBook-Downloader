package com.maxim.ybookdownloader.data

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

interface BookmateApi {
    @GET("books/{uuid}")
    suspend fun getBookInfo(
        @Path("uuid") uuid: String,
        @Header("auth-token") token: String,
        @Header("app-user-agent") appUserAgent: String = BookmateApiFactory.APP_USER_AGENT
    ): Response<ResponseBody>

    @GET("books/{uuid}/content/v4")
    suspend fun downloadEpub(
        @Path("uuid") uuid: String,
        @Header("auth-token") token: String,
        @Header("app-user-agent") appUserAgent: String = BookmateApiFactory.APP_USER_AGENT
    ): Response<ResponseBody>
}

object BookmateApiFactory {
    const val BASE_URL = "https://api.bookmate.yandex.net/api/v5/"
    const val APP_USER_AGENT = "Samsung/Galaxy_A51 Android/12 Bookmate/3.7.3"
}
