package com.maxim.ybookdownloader.data

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

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

    @GET("audiobooks/{uuid}")
    suspend fun getAudiobookInfo(
        @Path("uuid") uuid: String,
        @Header("auth-token") token: String,
        @Header("app-user-agent") appUserAgent: String = BookmateApiFactory.APP_USER_AGENT
    ): Response<ResponseBody>

    @GET("audiobooks/{uuid}/playlists.json")
    suspend fun getAudiobookPlaylist(
        @Path("uuid") uuid: String,
        @Header("auth-token") token: String,
        @Header("app-user-agent") appUserAgent: String = BookmateApiFactory.APP_USER_AGENT
    ): Response<ResponseBody>

    @GET
    suspend fun downloadByUrl(
        @Url url: String,
        @Header("auth-token") token: String,
        @Header("app-user-agent") appUserAgent: String = BookmateApiFactory.APP_USER_AGENT
    ): Response<ResponseBody>

    @POST
    suspend fun postGraphQl(
        @Url url: String,
        @Header("auth-token") token: String,
        @Header("app-user-agent") appUserAgent: String = BookmateApiFactory.APP_USER_AGENT,
        @Header("Accept") accept: String = BookmateApiFactory.GRAPHQL_ACCEPT,
        @Header("App-Language") appLanguage: String = "ru",
        @Header("App-Locale") appLocale: String = "ru",
        @Header("App-Platform") appPlatform: String = "android",
        @Header("Bookmate-Version") bookmateVersion: String = "20200305",
        @Header("Device-Os") deviceOs: String = "Android",
        @Body body: RequestBody
    ): Response<ResponseBody>
}

object BookmateApiFactory {
    const val BASE_URL = "https://api.bookmate.yandex.net/api/v5/"
    const val GRAPHQL_URL = "https://api-gateway.bookmate.yandex.net/graphql"
    const val APP_USER_AGENT = "Samsung/Galaxy_A51 Android/12 Bookmate/3.7.3"
    const val GRAPHQL_ACCEPT = "multipart/mixed; deferSpec=20220824, application/json"
}
