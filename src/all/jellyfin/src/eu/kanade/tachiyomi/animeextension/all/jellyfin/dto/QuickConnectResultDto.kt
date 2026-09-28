package eu.kanade.tachiyomi.animeextension.all.jellyfin.dto

import kotlinx.serialization.Serializable

@Serializable
data class QuickConnectResultDto(
    val authenticated: Boolean = false,
    /**
     * Jellyfin omits [secret] and [code] in some poll states,
     * so they default to blank instead of failing decoding.
     */
    val secret: String = "",
    val code: String = "",
)
