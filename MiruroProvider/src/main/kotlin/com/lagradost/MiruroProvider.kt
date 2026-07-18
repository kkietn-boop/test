package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Serializable
data class Title(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null
)

@Serializable
data class CoverImage(
    val large: String? = null
)

@Serializable
data class AniListSearchMedia(
    val id: Int,
    val title: Title? = null,
    val coverImage: CoverImage? = null
)

@Serializable
data class PageNode(
    val media: List<AniListSearchMedia>? = null
)

@Serializable
data class AniListSearchData(
    val Page: PageNode? = null
)

@Serializable
data class AniListSearchResponse(
    val data: AniListSearchData? = null
)

@Serializable
data class CharacterNodeName(
    val full: String? = null
)

@Serializable
data class CharacterNodeImage(
    val large: String? = null
)

@Serializable
data class CharacterNode(
    val name: CharacterNodeName? = null,
    val image: CharacterNodeImage? = null
)

@Serializable
data class CharacterEdge(
    val role: String? = null,
    val node: CharacterNode? = null
)

@Serializable
data class CharacterConnection(
    val edges: List<CharacterEdge>? = null
)

@Serializable
data class AniListMedia(
    val id: Int,
    val title: Title? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    val meanScore: Int? = null,
    val seasonYear: Int? = null,
    val characters: CharacterConnection? = null
)

@Serializable
data class AniListMediaData(
    val Media: AniListMedia? = null
)

@Serializable
data class AniListMediaResponse(
    val data: AniListMediaData? = null
)

class MiruroProvider : MainAPI() {
    override var name = "Miruro"
    override var mainUrl = "https://www.miruro.to"
    override var supportedTypes = setOf(TvType.Anime)

    override suspend fun search(query: String): List<SearchResponse> {
        val graphqlQuery = """
            query (${'$'}search: String) {
              Page(page: 1, perPage: 20) {
                media(search: ${'$'}search, type: ANIME) {
                  id
                  title {
                    romaji
                    english
                    native
                  }
                  coverImage {
                    large
                  }
                }
              }
            }
        """.trimIndent()

        val variables = mapOf("search" to query)
        val requestBody = mapOf("query" to graphqlQuery, "variables" to variables)

        val response = app.post(
            "https://graphql.anilist.co",
            json = requestBody,
            headers = mapOf("Content-Type" to "application/json")
        )

        val parsed = jsonParser.decodeFromString(AniListSearchResponse.serializer(), response.text)
        val mediaList = parsed.data?.Page?.media ?: return emptyList()

        return mediaList.map { media ->
            val mediaName = media.title?.english ?: media.title?.romaji ?: media.title?.native ?: ""
            val url = "https://www.miruro.to/watch/${media.id}"
            newAnimeSearchResponse(mediaName, url) {
                this.posterUrl = media.coverImage?.large
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("?").substringBefore("#")
        val idString = cleanUrl.substringAfter("/watch/").substringBefore("/")
        val anilistId = idString.toIntOrNull() ?: throw Exception("Invalid AniList ID in URL: $url")

        val graphqlQuery = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id
                title {
                  romaji
                  english
                  native
                }
                coverImage {
                  large
                }
                bannerImage
                description
                status
                genres
                meanScore
                seasonYear
                characters(perPage: 10, role: MAIN) {
                  edges {
                    role
                    node {
                      name {
                        full
                      }
                      image {
                        large
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val variables = mapOf("id" to anilistId)
        val requestBody = mapOf("query" to graphqlQuery, "variables" to variables)

        val metadataResponse = app.post(
            "https://graphql.anilist.co",
            json = requestBody,
            headers = mapOf("Content-Type" to "application/json")
        )

        val parsed = jsonParser.decodeFromString(AniListMediaResponse.serializer(), metadataResponse.text)
        val media = parsed.data?.Media ?: throw Exception("Could not fetch metadata for AniList ID: $anilistId")

        val mediaName = media.title?.english ?: media.title?.romaji ?: media.title?.native ?: ""

        val episodesResult = PipeClient.getEpisodes(anilistId)

        val subEpisodesByNumber = mutableMapOf<Double, MutableList<EpisodeSourceLink>>()
        val dubEpisodesByNumber = mutableMapOf<Double, MutableList<EpisodeSourceLink>>()

        val episodeTitles = mutableMapOf<Double, String?>()
        val episodeImages = mutableMapOf<Double, String?>()

        for ((providerName, providerData) in episodesResult.providers) {
            for (episodeItem in providerData.sub) {
                subEpisodesByNumber.getOrPut(episodeItem.number) { mutableListOf() }
                    .add(EpisodeSourceLink(provider = providerName, category = "sub", pipeId = episodeItem.id))
                if (episodeTitles[episodeItem.number] == null && episodeItem.title != null) {
                    episodeTitles[episodeItem.number] = episodeItem.title
                }
                if (episodeImages[episodeItem.number] == null && episodeItem.image != null) {
                    episodeImages[episodeItem.number] = episodeItem.image
                }
            }
            for (episodeItem in providerData.dub) {
                dubEpisodesByNumber.getOrPut(episodeItem.number) { mutableListOf() }
                    .add(EpisodeSourceLink(provider = providerName, category = "dub", pipeId = episodeItem.id))
                if (episodeTitles[episodeItem.number] == null && episodeItem.title != null) {
                    episodeTitles[episodeItem.number] = episodeItem.title
                }
                if (episodeImages[episodeItem.number] == null && episodeItem.image != null) {
                    episodeImages[episodeItem.number] = episodeItem.image
                }
            }
        }

        val allNumbers = (subEpisodesByNumber.keys + dubEpisodesByNumber.keys).sorted()

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (number in allNumbers) {
            val title = episodeTitles[number]
            val image = episodeImages[number]

            val subSources = subEpisodesByNumber[number]
            if (subSources != null && subSources.isNotEmpty()) {
                val payload = EpisodeDataPayload(anilistId, number, subSources)
                val payloadJson = jsonParser.encodeToString(EpisodeDataPayload.serializer(), payload)
                val ep = newEpisode(payloadJson) {
                    this.name = title
                    this.episode = number.toInt()
                    this.posterUrl = image
                }
                subEpisodes.add(ep)
            }

            val dubSources = dubEpisodesByNumber[number]
            if (dubSources != null && dubSources.isNotEmpty()) {
                val payload = EpisodeDataPayload(anilistId, number, dubSources)
                val payloadJson = jsonParser.encodeToString(EpisodeDataPayload.serializer(), payload)
                val ep = newEpisode(payloadJson) {
                    this.name = title
                    this.episode = number.toInt()
                    this.posterUrl = image
                }
                dubEpisodes.add(ep)
            }
        }

        val episodesMap = mutableMapOf<DubStatus, List<Episode>>()
        if (subEpisodes.isNotEmpty()) {
            episodesMap[DubStatus.Subbed] = subEpisodes
        }
        if (dubEpisodes.isNotEmpty()) {
            episodesMap[DubStatus.Dubbed] = dubEpisodes
        }

        return newAnimeLoadResponse(mediaName, url, TvType.Anime) {
            this.posterUrl = media.coverImage?.large
            this.backgroundPosterUrl = media.bannerImage
            this.plot = media.description
            this.year = media.seasonYear

            if (media.meanScore != null) {
                this.score = Score.from10(media.meanScore.toDouble() / 10.0)
            }

            this.showStatus = when (media.status) {
                "FINISHED" -> ShowStatus.Completed
                "RELEASING" -> ShowStatus.Ongoing
                else -> null
            }

            this.tags = media.genres

            this.actors = media.characters?.edges?.mapNotNull { edge ->
                val charName = edge.node?.name?.full ?: return@mapNotNull null
                val actorImage = edge.node.image?.large
                val role = when (edge.role) {
                    "MAIN" -> ActorRole.Main
                    "SUPPORTING" -> ActorRole.Supporting
                    else -> ActorRole.Background
                }
                ActorData(Actor(charName, actorImage), role)
            }

            this.episodes = episodesMap
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = jsonParser.decodeFromString(EpisodeDataPayload.serializer(), data)

        val results = coroutineScope {
            payload.sources.map { source ->
                async {
                    try {
                        PipeClient.getSources(source.pipeId, source.provider, source.category) to source
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        var linkFound = false

        for ((sourcesResult, source) in results) {
            for (stream in sourcesResult.streams) {
                val categoryTag = source.category.uppercase()
                val providerName = source.provider.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                val streamName = "[$categoryTag] $providerName - ${stream.quality ?: "auto"}"

                val isM3u8 = stream.url.contains(".m3u8") || stream.type == "hls"
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                val link = newExtractorLink(
                    source = providerName,
                    name = streamName,
                    url = stream.url,
                    type = linkType
                ) {
                    this.quality = stream.quality?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                    this.referer = stream.referer ?: "https://www.miruro.to/"
                }
                callback(link)
                linkFound = true
            }

            for (subtitle in sourcesResult.subtitles) {
                val subFile = newSubtitleFile(
                    lang = subtitle.language ?: subtitle.label ?: "Unknown",
                    url = subtitle.url
                )
                subtitleCallback(subFile)
            }
        }

        return linkFound
    }
}
