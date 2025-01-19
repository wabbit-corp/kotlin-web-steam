package one.wabbit.web.steam

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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

class SteamAPI(
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json()
        }

        // Configure encoding
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 30000
        }

        // Configure default request
        defaultRequest {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)

            // Add required headers
            header("Accept-Charset", "UTF-8")
            header("User-Agent", "Steam API Client/1.0")
        }
    }
) {
    companion object {
        private const val STEAM_STORE_API = "https://store.steampowered.com/api"
        private const val STEAM_COMMUNITY_API = "https://steamcommunity.com/api"
        private const val STEAM_REVIEWS_API = "https://store.steampowered.com/appreviews"
        private const val STEAM_NEWS_API = "https://api.steampowered.com/ISteamNews"

        private const val DEFAULT_LANGUAGE = "english"
        private const val DEFAULT_REVIEW_TYPE = "all"
        private const val DEFAULT_PURCHASE_TYPE = "all"
        private const val DEFAULT_PAGE_SIZE = 100
    }

    /**
     * Retrieves game details from Steam
     * @param appId The Steam application ID
     * @return SteamGame object containing game details
     */
    suspend fun getGameDetails(appId: String): GameData {
        val response = client.get("$STEAM_STORE_API/appdetails") {
            parameter("appids", appId)
        }.bodyAsText()
        return Json.decodeFromString<AppDetailsResponse>(response).content[appId]?.data ?: error("Game not found")
    }

    /**
     * Retrieves reviews for a specific game
     * @param appId The Steam application ID
     * @param limit Maximum number of reviews to retrieve (default: 100)
     * @param language Review language (default: english)
     * @param reviewType Type of reviews to retrieve (all, positive, negative)
     * @param purchaseType Filter by purchase type (all, steam, other)
     * @return Flow of SteamReview objects
     */
    fun getGameReviews(
        appId: String,
        limit: Int = 100,
        language: String = DEFAULT_LANGUAGE,
        reviewType: String = DEFAULT_REVIEW_TYPE,
        purchaseType: String = DEFAULT_PURCHASE_TYPE
    ): Flow<Review> = flow {
        var cursor = "*"
        var remainingReviews = limit

        while (remainingReviews > 0 && cursor != "") {
            val response = client.get("$STEAM_REVIEWS_API/$appId") {
                parameter("json", 1)
                parameter("cursor", cursor)
                parameter("language", language)
                parameter("filter", reviewType)         // changed from review_type
                parameter("purchase_type", purchaseType)
                parameter("day_range", 365)            // optional: limit to recent reviews
                parameter("num_per_page", minOf(100, remainingReviews))
                parameter("review_type", "all")        // can be "all", "positive", "negative"
                parameter("start_offset", 0)
            }
            println(response.status)

            val responseText = response.bodyAsText()
            println(responseText)

            val responseData = Json.decodeFromString<ReviewResponse>(responseText)

            responseData.reviews.forEach { review ->
                emit(review)
            }

            remainingReviews -= responseData.reviews.size
            cursor = responseData.cursor
        }
    }

    /**
     * Searches for games on Steam
     * @param query Search query
     * @param limit Maximum number of results
     * @return Flow of SteamGame objects
     */
    fun searchGames(query: String, limit: Int = DEFAULT_PAGE_SIZE): Flow<GameData> = flow {
        var page = 1
        var remainingGames = limit

        while (remainingGames > 0) {
            val response = client.get("$STEAM_STORE_API/storesearch") {
                parameter("term", query)
                parameter("page", page)
            }

            val responseText = response.bodyAsText()
            println(responseText)

            val responseData = Json.decodeFromString<SearchResponse>(responseText)

            responseData.items.take(remainingGames).forEach { game ->
                emit(getGameDetails(game.id))
            }

            remainingGames -= responseData.items.size
            if (responseData.items.isEmpty()) break
            page++
        }
    }

    /**
     * Retrieves recently updated games
     * @param limit Maximum number of games to retrieve
     * @return Flow of SteamGame objects
     */
    fun getFeaturedCategories(limit: Int = DEFAULT_PAGE_SIZE): Flow<UpdatedGameData> = flow {
        var page = 1
        var remainingGames = limit

        while (remainingGames > 0) {
            val response = client.get("$STEAM_STORE_API/featuredcategories/updated") {
                parameter("page", page)
            }

            val responseText = response.bodyAsText()

            println(responseText)

            val responseData = Json.decodeFromString<UpdatedGamesResponse>(responseText)

            responseData.apps.take(remainingGames).forEach { game ->
                emit(game)
            }

            remainingGames -= responseData.apps.size
            if (responseData.apps.isEmpty()) break
            page++
        }
    }

    /**
     * Retrieves news and updates for a specific game
     * @param appId The Steam application ID
     * @param limit Number of news items to retrieve (default: 20)
     * @param maxLength Maximum length of news content (default: 0 for full length)
     * @return Flow of news items/updates
     */
    fun getGameUpdates(appId: String, limit: Int = 20, maxLength: Int? = null): Flow<NewsItem> = flow {
        val response = client.get("$STEAM_NEWS_API/GetNewsForApp/v2/") {
            parameter("appid", appId)
            parameter("count", limit)
            maxLength?.let { parameter("maxlength", it) }
//            parameter("feeds", "steam_updates")  // This specifically gets update news
        }

        val responseText = response.bodyAsText()
        println(responseText)
        val newsData = Json.decodeFromString<NewsResponse>(responseText)

        newsData.appnews.newsitems.forEach { newsItem ->
            emit(newsItem)
        }
    }
}

@Serializable
data class NewsResponse(
    val appnews: AppNews
)

@Serializable
data class AppNews(
    val appid: Int,
    val newsitems: List<NewsItem>,
    @SerialName("count")
    val totalCount: Int
)

@Serializable
data class NewsItem(
    val gid: String,
    val title: String,
    val url: String,
    @SerialName("is_external_url")
    val isExternalUrl: Boolean,
    val author: String,
    val contents: String,
    @SerialName("feedlabel")
    val feedLabel: String,
    val date: Long,
    @SerialName("feedname")
    val feedName: String,
    @SerialName("feed_type")
    val feedType: Int,
    @SerialName("appid")
    val appId: Int,
    val tags: List<String> = emptyList()
)

// Modified wrapper response classes
@Serializable @JvmInline
value class AppDetailsResponse(
    val content: Map<String, GameResponse>
)

@Serializable
data class GameResponse(
    val success: Boolean,
    val data: GameData?
)

@Serializable
data class GameData(
    val type: String?,
    val name: String,
    @SerialName("steam_appid")
    val steamAppId: Int,
    @SerialName("required_age")
    val requiredAge: String?, // Can be either "0" or actual age
    @SerialName("is_free")
    val isFree: Boolean,
    @SerialName("controller_support")
    val controllerSupport: String?,
    val dlc: List<Int>?,
    @SerialName("detailed_description")
    val detailedDescription: String,
    @SerialName("about_the_game")
    val aboutTheGame: String,
    @SerialName("short_description")
    val shortDescription: String,
    @SerialName("supported_languages")
    val supportedLanguages: String,
    val reviews: String?,
    @SerialName("header_image")
    val headerImage: String,
    @SerialName("capsule_image")
    val capsuleImage: String,
    @SerialName("capsule_imagev5")
    val capsuleImageV5: String,
    val website: String?,
    @SerialName("pc_requirements")
    val pcRequirements: Requirements,
    @SerialName("mac_requirements")
    val macRequirements: Requirements,
    @SerialName("linux_requirements")
    val linuxRequirements: Requirements,
    @SerialName("legal_notice")
    val legalNotice: String?,
    val developers: List<String>,
    val publishers: List<String>,
    @SerialName("price_overview")
    val priceOverview: PriceOverview?,
    val platforms: Platforms,
    val metacritic: MetacriticInfo?,
    val categories: List<Category>,
    val genres: List<Genre>,
    val screenshots: List<Screenshot>,
    val movies: List<Movie>?,
    val recommendations: Recommendations?,
    val achievements: Achievements?,
    @SerialName("release_date")
    val releaseDate: ReleaseDate,
    @SerialName("support_info")
    val supportInfo: SupportInfo,
    val background: String,
    @SerialName("background_raw")
    val backgroundRaw: String,
    @SerialName("content_descriptors")
    val contentDescriptors: ContentDescriptors,
    val packages: List<Int>,
    @SerialName("package_groups")
    val packageGroups: List<PackageGroup>,
    val ratings: Ratings? = null,
)

@Serializable
data class PackageGroup(
    val name: String,
    val title: String,
    val description: String,
    @SerialName("selection_text")
    val selectionText: String,
    @SerialName("save_text")
    val saveText: String,
    @SerialName("display_type")
    val displayType: Int,
    @SerialName("is_recurring_subscription")
    val isRecurringSubscription: String,
    val subs: List<PackageSubscription>
)

@Serializable
data class PackageSubscription(
    val packageid: Int,
    @SerialName("percent_savings_text")
    val percentSavingsText: String,
    @SerialName("percent_savings")
    val percentSavings: Int,
    @SerialName("option_text")
    val optionText: String,
    @SerialName("option_description")
    val optionDescription: String,
    @SerialName("can_get_free_license")
    val canGetFreeLicense: String,
    @SerialName("is_free_license")
    val isFreeLicense: Boolean,
    @SerialName("price_in_cents_with_discount")
    val priceInCentsWithDiscount: Int
)

@Serializable
data class Ratings(
    val esrb: RatingDetails?,
    val pegi: RatingDetails?,
    val usk: RatingDetails?,
    val oflc: RatingDetails?,
    val cero: RatingDetails?,
    val crl: RatingDetails?
)

@Serializable
data class RatingDetails(
    val rating: String,
    val descriptors: String? = null,
    @SerialName("display_online_notice")
    val displayOnlineNotice: String? = null,
    @SerialName("use_age_gate")
    val useAgeGate: String? = null,
    @SerialName("required_age")
    val requiredAge: String? = null
)

@Serializable(with = RequirementsSerializer::class)
data class Requirements(
    val minimum: String? = null,
    val recommended: String? = null
)

object RequirementsSerializer : KSerializer<Requirements> {
    private @Serializable data class RequirementsData(
        val minimum: String? = null,
        val recommended: String? = null
    ) {
        fun toRequirements(): Requirements = Requirements(minimum, recommended)
    }

    private val objectSerializer = RequirementsData.serializer()

    override val descriptor: SerialDescriptor = objectSerializer.descriptor

    override fun serialize(encoder: Encoder, value: Requirements) {
        objectSerializer.serialize(encoder, RequirementsData(value.minimum, value.recommended))
    }

    override fun deserialize(decoder: Decoder): Requirements {
        val jsonReader = decoder as? JsonDecoder ?: throw SerializationException("Expected JSON decoder")
        val element = jsonReader.decodeJsonElement()

        return when {
            element is JsonArray -> Requirements()
            element is JsonObject -> Json.decodeFromJsonElement(objectSerializer, element).toRequirements()
            else -> throw SerializationException("Unknown requirements format")
        }
    }
}

@Serializable
data class Platforms(
    val windows: Boolean,
    val mac: Boolean,
    val linux: Boolean
)

@Serializable
data class PriceOverview(
    val currency: String,
    val initial: Int,
    val final: Int,
    @SerialName("discount_percent")
    val discountPercent: Int,
    @SerialName("initial_formatted")
    val initialFormatted: String,
    @SerialName("final_formatted")
    val finalFormatted: String
)

@Serializable
data class MetacriticInfo(
    val score: Int,
    val url: String
)

@Serializable
data class Category(
    val id: Int,
    val description: String
)

@Serializable
data class Genre(
    val id: String,
    val description: String
)

@Serializable
data class Screenshot(
    val id: Int,
    @SerialName("path_thumbnail")
    val pathThumbnail: String,
    @SerialName("path_full")
    val pathFull: String
)

@Serializable
data class Movie(
    val id: Long,
    val name: String,
    val thumbnail: String,
    val webm: MovieUrls,
    val mp4: MovieUrls,
    val highlight: Boolean
)

@Serializable
data class MovieUrls(
    @SerialName("480")
    val resolution480: String,
    val max: String
)

@Serializable
data class Recommendations(
    val total: Int
)

@Serializable
data class Achievements(
    val total: Int,
    val highlighted: List<Achievement>
)

@Serializable
data class Achievement(
    val name: String,
    val path: String
)

@Serializable
data class ReleaseDate(
    @SerialName("coming_soon")
    val comingSoon: Boolean,
    val date: String
)

@Serializable
data class SupportInfo(
    val url: String,
    val email: String
)

@Serializable
data class ContentDescriptors(
    val ids: List<Int>,
    val notes: String?
)

@Serializable
data class Review(
    @SerialName("recommendationid")
    val id: String,
    val author: Author,
    val language: String,
    val review: String,
    @SerialName("timestamp_created")
    val timestampCreated: Long,
    @SerialName("timestamp_updated")
    val timestampUpdated: Long,
    @SerialName("voted_up")
    val votedUp: Boolean,
    @SerialName("votes_up")
    val votesUp: Int,
    @SerialName("votes_funny")
    val votesFunny: Int,
    @SerialName("weighted_vote_score")
    val weightedVoteScore: String,
    @SerialName("comment_count")
    val commentCount: Int,
    @SerialName("steam_purchase")
    val steamPurchase: Boolean,
    @SerialName("received_for_free")
    val receivedForFree: Boolean,
    @SerialName("written_during_early_access")
    val writtenDuringEarlyAccess: Boolean,
    @SerialName("primarily_steam_deck")
    val primarilySteamDeck: Boolean = false
)

@Serializable
data class Author(
    val steamid: String,
    @SerialName("num_games_owned")
    val numGamesOwned: Int,
    @SerialName("num_reviews")
    val numReviews: Int,
    @SerialName("playtime_forever")
    val playtimeForever: Int,
    @SerialName("playtime_last_two_weeks")
    val playtimeLastTwoWeeks: Int = 0,
    @SerialName("playtime_at_review")
    val playtimeAtReview: Int,
    @SerialName("deck_playtime_at_review")
    val deckPlaytimeAtReview: Int? = null,
    @SerialName("last_played")
    val lastPlayed: Long
)

@Serializable
data class ReviewResponse(
    @SerialName("success")
    private val successInt: Int = 0,
    @SerialName("query_summary")
    val querySummary: QuerySummary? = null,
    val reviews: List<Review> = emptyList(),
    val cursor: String = ""
) {
    val success: Boolean
        get() = successInt == 1
}

@Serializable
data class QuerySummary(
    @SerialName("num_reviews")
    val numReviews: Int,
    @SerialName("review_score")
    val reviewScore: Int,
    @SerialName("review_score_desc")
    val reviewScoreDesc: String,
    @SerialName("total_positive")
    val totalPositive: Int,
    @SerialName("total_negative")
    val totalNegative: Int,
    @SerialName("total_reviews")
    val totalReviews: Int
)

@Serializable
internal data class ReviewData(
    @SerialName("recommendationid")
    val id: String,
    @SerialName("author")
    val authorData: AuthorData,
    val review: String,
    @SerialName("timestamp_created")
    val timestampCreated: Long,
    @SerialName("voted_up")
    val votedUp: Boolean,
    @SerialName("votes_up")
    val votesUp: Int,
    @SerialName("votes_funny")
    val votesFunny: Int,
    @SerialName("weighted_vote_score")
    val weightedVoteScore: Double,
    @SerialName("playtime_at_review")
    val playtimeAtReview: Double,
    @SerialName("received_for_free")
    val receivedForFree: Boolean,
    @SerialName("written_during_early_access")
    val writtenDuringEarlyAccess: Boolean,
    @SerialName("steam_purchase")
    val steamPurchase: Boolean,
    val language: String
)

@Serializable
internal data class AuthorData(
    @SerialName("steamid")
    val steamId: String,
    @SerialName("num_games_owned")
    val numGamesOwned: Int,
    @SerialName("num_reviews")
    val numReviews: Int,
    @SerialName("playtime_forever")
    val playtimeForever: Double,
    @SerialName("playtime_last_two_weeks")
    val playtimeLastTwoWeeks: Double?,
    @SerialName("steam_level")
    val steamLevel: Int,
    val name: String?
)

@Serializable
internal data class UpdatedGamesResponse(
    val success: Boolean,
    val apps: List<UpdatedGameData>
)

@Serializable
data class UpdatedGameData(
    @SerialName("id")
    val appId: String,
    val name: String,
    @SerialName("last_modified")
    val lastModified: Long,
    @SerialName("price_change")
    val priceChange: String?
)

@Serializable
data class SearchResponse(
    val total: Int,
    val items: List<SearchItem>
)

@Serializable
data class SearchItem(
    val id: String,
    val type: String,
    val name: String,
    val discounted: Boolean = false,
    @SerialName("discount_percent")
    val discountPercent: Int = 0,
    @SerialName("original_price")
    val originalPrice: Int? = null,
    @SerialName("final_price")
    val finalPrice: Int? = null,
    val currency: String? = null,
    @SerialName("large_capsule_image")
    val largeCapsuleImage: String? = null,
    @SerialName("small_capsule_image")
    val smallCapsuleImage: String? = null,
    val windows_available: Boolean = false,
    val mac_available: Boolean = false,
    val linux_available: Boolean = false,
    @SerialName("streamingvideo_available")
    val streamingVideoAvailable: Boolean = false,
    @SerialName("header_image")
    val headerImage: String? = null,
    @SerialName("controller_support")
    val controllerSupport: String? = null,
    val ratings: SearchRatings? = null
)

@Serializable
data class SearchRatings(
    val total: Int = 0,
    val positive: Int = 0,
    val negative: Int = 0,
    val percentage: Int = 0
)
