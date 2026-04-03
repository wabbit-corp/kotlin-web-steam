package one.wabbit.web.steam

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import one.wabbit.web.common.Etiquette
import one.wabbit.web.common.Timeouts
import one.wabbit.web.common.applyEtiquette
import one.wabbit.web.common.applyTimeouts
import one.wabbit.web.common.retryingHttpCall
import one.wabbit.web.common.safeBodyPrefix
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmInline
import kotlin.time.Duration.Companion.seconds

sealed class SteamApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidInput(message: String) : SteamApiError(message)

    class NotFound(val appId: String) : SteamApiError("Steam app not found for appId=$appId")

    class Api(
        val url: String,
        message: String,
    ) : SteamApiError("Steam API error from $url: $message")

    class Http(
        val url: String,
        val status: Int,
        val bodySample: String?,
        cause: Throwable? = null,
    ) : SteamApiError(
        buildString {
            append("HTTP ")
            append(status)
            append(" from ")
            append(url)
            if (!bodySample.isNullOrBlank()) {
                append(", body sample: ")
                append(bodySample.take(256))
            }
        },
        cause,
    )

    class Network(
        val url: String,
        cause: Throwable,
    ) : SteamApiError(
        "Network failure talking to $url: ${cause::class.simpleName}: ${cause.message}",
        cause,
    )

    class Parse(
        val url: String,
        val bodySample: String,
        cause: Throwable,
    ) : SteamApiError(
        "Failed to parse Steam response from $url: ${cause::class.simpleName}: ${cause.message}; body sample: ${bodySample.take(256)}",
        cause,
    )
}

interface SteamApi {
    data class Config(
        val storeApiBaseUrl: String = "https://store.steampowered.com/api",
        val reviewsApiBaseUrl: String = "https://store.steampowered.com/appreviews",
        val newsApiBaseUrl: String = "https://api.steampowered.com/ISteamNews",
        val etiquette: Etiquette = Etiquette(
            userAgent = "one.wabbit.web.steam/2.1",
            extraHeaders = mapOf("Accept-Charset" to "UTF-8"),
        ),
        val timeouts: Timeouts = Timeouts(
            request = 30.seconds,
            connect = 30.seconds,
            socket = 30.seconds,
        ),
    ) {
        init {
            require(storeApiBaseUrl.isNotBlank()) { "storeApiBaseUrl must not be blank" }
            require(reviewsApiBaseUrl.isNotBlank()) { "reviewsApiBaseUrl must not be blank" }
            require(newsApiBaseUrl.isNotBlank()) { "newsApiBaseUrl must not be blank" }
        }
    }

    suspend fun getGameDetails(appId: String): GameData

    fun getGameReviews(
        appId: String,
        limit: Int = 100,
        language: String = DEFAULT_LANGUAGE,
        reviewType: String = DEFAULT_REVIEW_TYPE,
        purchaseType: String = DEFAULT_PURCHASE_TYPE,
    ): Flow<Review>

    fun searchGames(query: String, limit: Int = DEFAULT_PAGE_SIZE): Flow<GameData>

    fun getFeaturedCategories(limit: Int = DEFAULT_PAGE_SIZE): Flow<UpdatedGameData>

    fun getGameUpdates(appId: String, limit: Int = 20, maxLength: Int? = null): Flow<NewsItem>

    companion object {
        const val DEFAULT_LANGUAGE: String = "english"
        const val DEFAULT_REVIEW_TYPE: String = "all"
        const val DEFAULT_REVIEW_FILTER: String = "all"
        const val DEFAULT_PURCHASE_TYPE: String = "all"
        const val DEFAULT_PAGE_SIZE: Int = 100
    }
}

class KtorSteamApi(
    private val httpClient: HttpClient,
    val config: SteamApi.Config = SteamApi.Config(),
) : SteamApi {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    init {
        check(runCatching { httpClient.pluginOrNull(HttpTimeout) }.getOrNull() != null) {
            "HttpTimeout plugin must be installed on the provided HttpClient for per-request timeouts to work."
        }
    }

    override suspend fun getGameDetails(appId: String): GameData {
        val normalizedAppId = requireAppId(appId)
        val response =
            getJson<AppDetailsResponse>("${config.storeApiBaseUrl}/appdetails") {
                parameter("appids", normalizedAppId)
            }

        val game = response.content[normalizedAppId]
        if (game?.success != true || game.data == null) {
            throw SteamApiError.NotFound(normalizedAppId)
        }

        return game.data
    }

    override fun getGameReviews(
        appId: String,
        limit: Int,
        language: String,
        reviewType: String,
        purchaseType: String,
    ): Flow<Review> = flow {
        val normalizedAppId = requireAppId(appId)
        val normalizedLanguage = requireNonBlank(language, "language")
        val normalizedReviewType = requireNonBlank(reviewType, "reviewType")
        val normalizedPurchaseType = requireNonBlank(purchaseType, "purchaseType")
        var cursor = "*"
        var remainingReviews = requirePositive(limit, "limit")
        val url = "${config.reviewsApiBaseUrl}/$normalizedAppId"

        while (remainingReviews > 0 && cursor.isNotEmpty()) {
            val requestCursor = cursor
            val responseData =
                getJson<ReviewResponse>(url) {
                    parameter("json", 1)
                    parameter("cursor", requestCursor)
                    parameter("language", normalizedLanguage)
                    parameter("filter", SteamApi.DEFAULT_REVIEW_FILTER)
                    parameter("purchase_type", normalizedPurchaseType)
                    parameter("review_type", normalizedReviewType)
                    parameter("day_range", 365)
                    parameter("num_per_page", minOf(100, remainingReviews))
                    parameter("start_offset", 0)
                }

            if (!responseData.success) {
                throw SteamApiError.Api(url, "review response reported success=0")
            }

            responseData.reviews.take(remainingReviews).forEach { review -> emit(review) }
            remainingReviews -= responseData.reviews.size
            if (responseData.reviews.isEmpty() || responseData.cursor == requestCursor) {
                break
            }
            cursor = responseData.cursor
        }
    }

    override fun searchGames(query: String, limit: Int): Flow<GameData> = flow {
        val normalizedQuery = requireNonBlank(query, "query")
        var page = 1
        var remainingGames = requirePositive(limit, "limit")

        while (remainingGames > 0) {
            val responseData =
                getJson<SearchResponse>("${config.storeApiBaseUrl}/storesearch") {
                    parameter("term", normalizedQuery)
                    parameter("page", page)
                }

            responseData.items.take(remainingGames).forEach { game ->
                emit(getGameDetails(game.id))
            }

            remainingGames -= responseData.items.size
            if (responseData.items.isEmpty()) break
            page++
        }
    }

    override fun getFeaturedCategories(limit: Int): Flow<UpdatedGameData> = flow {
        var page = 1
        var remainingGames = requirePositive(limit, "limit")

        while (remainingGames > 0) {
            val responseData =
                getJson<UpdatedGamesResponse>("${config.storeApiBaseUrl}/featuredcategories/updated") {
                    parameter("page", page)
                }

            if (!responseData.success) {
                throw SteamApiError.Api(
                    "${config.storeApiBaseUrl}/featuredcategories/updated",
                    "featured categories response reported success=false",
                )
            }

            responseData.apps.take(remainingGames).forEach { game -> emit(game) }

            remainingGames -= responseData.apps.size
            if (responseData.apps.isEmpty()) break
            page++
        }
    }

    override fun getGameUpdates(appId: String, limit: Int, maxLength: Int?): Flow<NewsItem> =
        flow {
            val normalizedAppId = requireAppId(appId)
            val normalizedLimit = requirePositive(limit, "limit")
            if (maxLength != null && maxLength < 0) {
                throw SteamApiError.InvalidInput("maxLength must be non-negative if provided")
            }

            val newsData =
                getJson<NewsResponse>("${config.newsApiBaseUrl}/GetNewsForApp/v2/") {
                    parameter("appid", normalizedAppId)
                    parameter("count", normalizedLimit)
                    maxLength?.let { parameter("maxlength", it) }
                }

            newsData.appnews.newsitems.forEach { newsItem -> emit(newsItem) }
        }

    private suspend inline fun <reified T> getJson(
        url: String,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response = try {
            retryingHttpCall {
                httpClient.get(url) {
                    expectSuccess = true
                    applyEtiquette(config.etiquette)
                    applyTimeouts(config.timeouts)
                    accept(ContentType.Application.Json)
                    configure()
                }
            }
        } catch (t: Throwable) {
            throw t.toSteamError(url)
        }

        return response.decodeJson(url)
    }

    private suspend inline fun <reified T> HttpResponse.decodeJson(url: String): T {
        val body = try {
            bodyAsText()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw SteamApiError.Network(url, t)
        }

        return try {
            json.decodeFromString<T>(body)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw SteamApiError.Parse(url, body.take(2048), t)
        }
    }

    private fun requireAppId(appId: String): String = requireNonBlank(appId, "appId")

    private fun requireNonBlank(value: String, name: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            throw SteamApiError.InvalidInput("$name must not be blank")
        }
        return normalized
    }

    private fun requirePositive(value: Int, name: String): Int {
        if (value <= 0) {
            throw SteamApiError.InvalidInput("$name must be positive")
        }
        return value
    }
}

typealias SteamAPI = KtorSteamApi

private suspend fun Throwable.toSteamError(url: String): SteamApiError {
    if (this is CancellationException) throw this
    return if (this is ResponseException) {
        val sample = runCatching { response.safeBodyPrefix(2048) }.getOrNull()
        SteamApiError.Http(url, response.status.value, sample, this)
    } else {
        SteamApiError.Network(url, this)
    }
}

@Serializable data class NewsResponse(val appnews: AppNews)

@Serializable
data class AppNews(
    val appid: Int,
    val newsitems: List<NewsItem>,
    @SerialName("count") val totalCount: Int,
)

@Serializable
data class NewsItem(
    val gid: String,
    val title: String,
    val url: String,
    @SerialName("is_external_url") val isExternalUrl: Boolean,
    val author: String,
    val contents: String,
    @SerialName("feedlabel") val feedLabel: String,
    val date: Long,
    @SerialName("feedname") val feedName: String,
    @SerialName("feed_type") val feedType: Int,
    @SerialName("appid") val appId: Int,
    val tags: List<String> = emptyList(),
)

// Modified wrapper response classes
@Serializable @JvmInline value class AppDetailsResponse(val content: Map<String, GameResponse>)

@Serializable data class GameResponse(val success: Boolean, val data: GameData?)

@Serializable
data class GameData(
    val type: String?,
    val name: String,
    @SerialName("steam_appid") val steamAppId: Int,
    @SerialName("required_age") val requiredAge: Int?, // Can be either "0" or actual age
    @SerialName("is_free") val isFree: Boolean,
    @SerialName("controller_support") val controllerSupport: String?,
    val dlc: List<Int>?,
    @SerialName("detailed_description") val detailedDescription: String,
    @SerialName("about_the_game") val aboutTheGame: String,
    @SerialName("short_description") val shortDescription: String,
    @SerialName("supported_languages") val supportedLanguages: String,
    val reviews: String?,
    @SerialName("header_image") val headerImage: String,
    @SerialName("capsule_image") val capsuleImage: String,
    @SerialName("capsule_imagev5") val capsuleImageV5: String,
    val website: String?,
    @SerialName("pc_requirements") val pcRequirements: Requirements,
    @SerialName("mac_requirements") val macRequirements: Requirements,
    @SerialName("linux_requirements") val linuxRequirements: Requirements,
    @SerialName("legal_notice") val legalNotice: String?,
    val developers: List<String>,
    val publishers: List<String>,
    @SerialName("price_overview") val priceOverview: PriceOverview?,
    val platforms: Platforms,
    val metacritic: MetacriticInfo?,
    val categories: List<Category>,
    val genres: List<Genre>,
    val screenshots: List<Screenshot>,
    val movies: List<Movie>?,
    val recommendations: Recommendations?,
    val achievements: Achievements?,
    @SerialName("release_date") val releaseDate: ReleaseDate,
    @SerialName("support_info") val supportInfo: SupportInfo,
    val background: String,
    @SerialName("background_raw") val backgroundRaw: String,
    @SerialName("content_descriptors") val contentDescriptors: ContentDescriptors,
    val packages: List<Int>,
    @SerialName("package_groups") val packageGroups: List<PackageGroup>,
    val ratings: Ratings? = null,
)

@Serializable
data class PackageGroup(
    val name: String,
    val title: String,
    val description: String,
    @SerialName("selection_text") val selectionText: String,
    @SerialName("save_text") val saveText: String,
    @SerialName("display_type") val displayType: Int,
    @SerialName("is_recurring_subscription") val isRecurringSubscription: String,
    val subs: List<PackageSubscription>,
)

@Serializable
data class PackageSubscription(
    val packageid: Int,
    @SerialName("percent_savings_text") val percentSavingsText: String,
    @SerialName("percent_savings") val percentSavings: Int,
    @SerialName("option_text") val optionText: String,
    @SerialName("option_description") val optionDescription: String,
    @SerialName("can_get_free_license") val canGetFreeLicense: String,
    @SerialName("is_free_license") val isFreeLicense: Boolean,
    @SerialName("price_in_cents_with_discount") val priceInCentsWithDiscount: Int,
)

@Serializable
data class Ratings(
    val esrb: RatingDetails?,
    val pegi: RatingDetails?,
    val usk: RatingDetails?,
    val oflc: RatingDetails?,
    val cero: RatingDetails?,
    val crl: RatingDetails?,
)

@Serializable
data class RatingDetails(
    val rating: String,
    val descriptors: String? = null,
    @SerialName("display_online_notice") val displayOnlineNotice: String? = null,
    @SerialName("use_age_gate") val useAgeGate: String? = null,
    @SerialName("required_age") val requiredAge: String? = null,
)

@Serializable(with = RequirementsSerializer::class)
data class Requirements(val minimum: String? = null, val recommended: String? = null)

object RequirementsSerializer : KSerializer<Requirements> {
    @Serializable
    private data class RequirementsData(
        val minimum: String? = null,
        val recommended: String? = null,
    ) {
        fun toRequirements(): Requirements = Requirements(minimum, recommended)
    }

    private val objectSerializer = RequirementsData.serializer()

    override val descriptor: SerialDescriptor = objectSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Requirements) {
        objectSerializer.serialize(encoder, RequirementsData(value.minimum, value.recommended))
    }

    override fun deserialize(decoder: Decoder): Requirements {
        val jsonReader =
            decoder as? JsonDecoder ?: throw SerializationException("Expected JSON decoder")
        val element = jsonReader.decodeJsonElement()

        return when {
            element is JsonArray -> Requirements()
            element is JsonObject ->
                Json.decodeFromJsonElement(objectSerializer, element).toRequirements()
            else -> throw SerializationException("Unknown requirements format")
        }
    }
}

@Serializable data class Platforms(val windows: Boolean, val mac: Boolean, val linux: Boolean)

@Serializable
data class PriceOverview(
    val currency: String,
    val initial: Int,
    val final: Int,
    @SerialName("discount_percent") val discountPercent: Int,
    @SerialName("initial_formatted") val initialFormatted: String,
    @SerialName("final_formatted") val finalFormatted: String,
)

@Serializable data class MetacriticInfo(val score: Int, val url: String)

@Serializable data class Category(val id: Int, val description: String)

@Serializable data class Genre(val id: String, val description: String)

@Serializable
data class Screenshot(
    val id: Int,
    @SerialName("path_thumbnail") val pathThumbnail: String,
    @SerialName("path_full") val pathFull: String,
)

@Serializable
data class Movie(
    val id: Long,
    val name: String,
    val thumbnail: String,
//    val webm: MovieUrls,
//    val mp4: MovieUrls,
    val highlight: Boolean,
    val dash_av1: String? = null,
    val dash_h264: String? = null,
    val hls_h264: String? = null,
)

@Serializable data class MovieUrls(@SerialName("480") val resolution480: String, val max: String)

@Serializable data class Recommendations(val total: Int)

@Serializable data class Achievements(val total: Int, val highlighted: List<Achievement>)

@Serializable data class Achievement(val name: String, val path: String)

@Serializable
data class ReleaseDate(@SerialName("coming_soon") val comingSoon: Boolean, val date: String)

@Serializable data class SupportInfo(val url: String, val email: String)

@Serializable data class ContentDescriptors(val ids: List<Int>, val notes: String?)

@Serializable
data class Review(
    @SerialName("recommendationid") val id: String,
    val author: Author,
    val language: String,
    val review: String,
    @SerialName("timestamp_created") val timestampCreated: Long,
    @SerialName("timestamp_updated") val timestampUpdated: Long,
    @SerialName("voted_up") val votedUp: Boolean,
    @SerialName("votes_up") val votesUp: Int,
    @SerialName("votes_funny") val votesFunny: Int,
    @SerialName("weighted_vote_score") val weightedVoteScore: String,
    @SerialName("comment_count") val commentCount: Int,
    @SerialName("steam_purchase") val steamPurchase: Boolean,
    @SerialName("received_for_free") val receivedForFree: Boolean,
    @SerialName("written_during_early_access") val writtenDuringEarlyAccess: Boolean,
    @SerialName("primarily_steam_deck") val primarilySteamDeck: Boolean = false,
)

@Serializable
data class Author(
    val steamid: String,
    @SerialName("num_games_owned") val numGamesOwned: Int,
    @SerialName("num_reviews") val numReviews: Int,
    @SerialName("playtime_forever") val playtimeForever: Int,
    @SerialName("playtime_last_two_weeks") val playtimeLastTwoWeeks: Int = 0,
    @SerialName("playtime_at_review") val playtimeAtReview: Int,
    @SerialName("deck_playtime_at_review") val deckPlaytimeAtReview: Int? = null,
    @SerialName("last_played") val lastPlayed: Long,
)

@Serializable
data class ReviewResponse(
    @SerialName("success") private val successInt: Int = 0,
    @SerialName("query_summary") val querySummary: QuerySummary? = null,
    val reviews: List<Review> = emptyList(),
    val cursor: String = "",
) {
    val success: Boolean
        get() = successInt == 1
}

@Serializable
data class QuerySummary(
    @SerialName("num_reviews") val numReviews: Int,
    @SerialName("review_score") val reviewScore: Int,
    @SerialName("review_score_desc") val reviewScoreDesc: String,
    @SerialName("total_positive") val totalPositive: Int,
    @SerialName("total_negative") val totalNegative: Int,
    @SerialName("total_reviews") val totalReviews: Int,
)

@Serializable
internal data class ReviewData(
    @SerialName("recommendationid") val id: String,
    @SerialName("author") val authorData: AuthorData,
    val review: String,
    @SerialName("timestamp_created") val timestampCreated: Long,
    @SerialName("voted_up") val votedUp: Boolean,
    @SerialName("votes_up") val votesUp: Int,
    @SerialName("votes_funny") val votesFunny: Int,
    @SerialName("weighted_vote_score") val weightedVoteScore: Double,
    @SerialName("playtime_at_review") val playtimeAtReview: Double,
    @SerialName("received_for_free") val receivedForFree: Boolean,
    @SerialName("written_during_early_access") val writtenDuringEarlyAccess: Boolean,
    @SerialName("steam_purchase") val steamPurchase: Boolean,
    val language: String,
)

@Serializable
internal data class AuthorData(
    @SerialName("steamid") val steamId: String,
    @SerialName("num_games_owned") val numGamesOwned: Int,
    @SerialName("num_reviews") val numReviews: Int,
    @SerialName("playtime_forever") val playtimeForever: Double,
    @SerialName("playtime_last_two_weeks") val playtimeLastTwoWeeks: Double?,
    @SerialName("steam_level") val steamLevel: Int,
    val name: String?,
)

@Serializable
internal data class UpdatedGamesResponse(val success: Boolean, val apps: List<UpdatedGameData>)

@Serializable
data class UpdatedGameData(
    @SerialName("id") val appId: String,
    val name: String,
    @SerialName("last_modified") val lastModified: Long,
    @SerialName("price_change") val priceChange: String?,
)

@Serializable data class SearchResponse(val total: Int, val items: List<SearchItem>)

@Serializable
data class SearchItem(
    val id: String,
    val type: String,
    val name: String,
    val discounted: Boolean = false,
    @SerialName("discount_percent") val discountPercent: Int = 0,
    @SerialName("original_price") val originalPrice: Int? = null,
    @SerialName("final_price") val finalPrice: Int? = null,
    val currency: String? = null,
    @SerialName("large_capsule_image") val largeCapsuleImage: String? = null,
    @SerialName("small_capsule_image") val smallCapsuleImage: String? = null,
    val windows_available: Boolean = false,
    val mac_available: Boolean = false,
    val linux_available: Boolean = false,
    @SerialName("streamingvideo_available") val streamingVideoAvailable: Boolean = false,
    @SerialName("header_image") val headerImage: String? = null,
    @SerialName("controller_support") val controllerSupport: String? = null,
    val ratings: SearchRatings? = null,
)

@Serializable
data class SearchRatings(
    val total: Int = 0,
    val positive: Int = 0,
    val negative: Int = 0,
    val percentage: Int = 0,
)
