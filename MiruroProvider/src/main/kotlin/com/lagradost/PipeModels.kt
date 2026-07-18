@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.lagradost

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val jsonParser = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class EpisodeItem(
    val id: String,
    val number: Double,
    val title: String? = null,
    val image: String? = null,
    val filler: Boolean = false
)

@Serializable
data class ProviderData(
    val name: String,
    val sub: List<EpisodeItem> = emptyList(),
    val dub: List<EpisodeItem> = emptyList()
)

@Serializable
data class EpisodesResult(
    val providers: Map<String, ProviderData> = emptyMap()
)

@Serializable
data class Resolution(
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class StreamItem(
    val url: String,
    val type: String? = null,
    val quality: String? = null,
    val audio: String? = null,
    val referer: String? = null,
    val isActive: Boolean = false,
    val resolution: Resolution? = null
)

@Serializable
data class SubtitleItem(
    val url: String,
    val label: String? = null,
    val language: String? = null
)

@Serializable
data class SkipTimeItem(
    val start: Double? = null,
    val end: Double? = null
)

@Serializable
data class SkipTimes(
    val intro: SkipTimeItem? = null,
    val outro: SkipTimeItem? = null
)

@Serializable
data class SourcesResult(
    val streams: List<StreamItem> = emptyList(),
    val subtitles: List<SubtitleItem> = emptyList(),
    val skipTimes: SkipTimes? = null,
    val download: String? = null
)

@Serializable
data class EpisodeDataPayload(
    val anilistId: Int,
    val episodeNumber: Double,
    val sources: List<EpisodeSourceLink>
)

@Serializable
data class EpisodeSourceLink(
    val provider: String,
    val category: String,
    val pipeId: String
)
