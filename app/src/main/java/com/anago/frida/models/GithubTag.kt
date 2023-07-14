package com.anago.frida.models

import com.google.gson.annotations.SerializedName

data class GithubTag(
    @SerializedName("ref")
    val ref: String
)