package eu.kanade.tachiyomi.animeextension.all.jellyfin.dto

import kotlinx.serialization.Serializable

@Serializable
data class QuickConnectResultDto(
    val authenticated: Boolean = false,
    // Defaults are intentional: Jellyfin omits secret/code in some poll
    // states, so missing fields must decode as blank rather than fail.
    val secret: String = "",
    val code: String = "",
)
