package io.github.xgy.zcoderemote.data

data class RemoteSession(
    val id: String,
    val name: String,
    val displayLocation: String,
    val url: String,
    val lastUsedAt: Long,
)
