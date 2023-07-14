package com.anago.frida.network

import com.anago.frida.models.GithubTag
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET


object GithubAPI {
    interface GithubService {
        @GET("repos/frida/frida/git/refs/tags")
        fun getFridaTags(): Call<List<GithubTag>>
    }

    private var retrofit = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val service: GithubService = retrofit.create(GithubService::class.java)

    fun getFridaVersions(callback: (versions: List<String>) -> Unit) {
        service.getFridaTags().enqueue(object : Callback<List<GithubTag>> {
            override fun onResponse(
                call: Call<List<GithubTag>>,
                response: Response<List<GithubTag>>
            ) {
                if (response.body() != null && response.isSuccessful) {
                    val versions = response.body()!!
                        .map { it.ref.substringAfterLast("/") }
                        .reversed()
                    callback(versions)
                } else {
                    callback(emptyList())
                }
            }

            override fun onFailure(call: Call<List<GithubTag>>, t: Throwable) {
                t.printStackTrace()
                callback(emptyList())
            }
        })
    }
}