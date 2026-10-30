package com.neurotube.app.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicPlaylistShelfRenderer(
    val playlistId: String? = null,
    val contents: List<MusicShelfRenderer.Content>? = null,
    // Optional: a shelf that omits it must not fail the whole response, as every other optional
    // field here already guards against.
    val collapsedItemCount: Int = 0,
)
