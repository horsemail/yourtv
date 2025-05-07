package com.horsenma.yourtv.data

data class ReqSources(
    var sourceId: String,
)

data class ReqSourceAdd(
    val id: String,
    var uri: String,
)
