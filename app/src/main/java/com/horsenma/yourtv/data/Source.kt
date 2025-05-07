package com.horsenma.yourtv.data

import java.util.UUID
import java.io.Serializable

data class Source(
    var id: String? = null,
    var uri: String,
    var checked: Boolean = false,
) {
    init {
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
        }
    }
}


data class StableSource(
    val id: Int,
    val name: String,
    val title: String,
    val description: String?,
    val logo: String,
    val image: String?,
    val uris: List<String>,
    val videoIndex: Int,
    val headers: Map<String, String>?,
    val group: String,
    val sourceType: String,
    val number: Int,
    val child: List<TV>,
    val timestamp: Long
) : Serializable