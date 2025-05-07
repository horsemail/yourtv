package com.horsenma.yourtv.data

data class ReqSettings(
    var uri: String? = "",
    val proxy: String?,
    val epg: String?,
    val channel: Int?,
)
