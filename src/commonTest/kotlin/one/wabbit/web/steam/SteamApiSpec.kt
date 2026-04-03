package one.wabbit.web.steam

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SteamApiSpec {
    @Test
    fun `getGameDetails decodes appdetails response`() = runBlocking {
        val api =
            KtorSteamApi(
                httpClient =
                    testClient { request ->
                        assertEquals("/api/appdetails", request.url.encodedPath)
                        assertEquals("570", request.url.parameters["appids"])

                        respondJson(appDetailsResponse(appId = "570", name = "Dota 2"))
                    },
            )

        val game = api.getGameDetails("570")

        assertEquals("Dota 2", game.name)
        assertEquals(570, game.steamAppId)
    }

    @Test
    fun `searchGames requests search then details`() = runBlocking {
        var searchRequests = 0
        var detailRequests = 0
        val api =
            KtorSteamApi(
                httpClient =
                    testClient { request ->
                        when (request.url.encodedPath) {
                            "/api/storesearch" -> {
                                searchRequests++
                                assertEquals("portal", request.url.parameters["term"])
                                assertEquals("1", request.url.parameters["page"])
                                respondJson(
                                    """
                                    {
                                      "total": 1,
                                      "items": [
                                        {
                                          "id": "400",
                                          "type": "game",
                                          "name": "Portal",
                                          "discounted": false
                                        }
                                      ]
                                    }
                                    """.trimIndent(),
                                )
                            }

                            "/api/appdetails" -> {
                                detailRequests++
                                assertEquals("400", request.url.parameters["appids"])
                                respondJson(appDetailsResponse(appId = "400", name = "Portal"))
                            }

                            else -> error("unexpected path ${request.url.encodedPath}")
                        }
                    },
            )

        val games = api.searchGames("portal", limit = 1).toList()

        assertEquals(1, games.size)
        assertEquals("Portal", games.single().name)
        assertEquals(1, searchRequests)
        assertEquals(1, detailRequests)
    }

    @Test
    fun `getGameReviews uses review_type and maps http failures`() = runBlocking {
        val api =
            KtorSteamApi(
                httpClient =
                    testClient { request ->
                        assertEquals("/appreviews/570", request.url.encodedPath)
                        assertEquals("positive", request.url.parameters["review_type"])
                        assertEquals("all", request.url.parameters["filter"])
                        assertEquals("all", request.url.parameters["purchase_type"])

                        respond(
                            content = "rate limited",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                        )
                    },
            )

        val error =
            assertFailsWith<SteamApiError.Http> {
                api.getGameReviews("570", limit = 5, reviewType = "positive").toList()
            }

        assertEquals(429, error.status)
        assertContains(error.bodySample.orEmpty(), "rate limited")
    }

    @Test
    fun `getGameUpdates decodes news items`() = runBlocking {
        val api =
            KtorSteamApi(
                httpClient =
                    testClient { request ->
                        assertEquals("/ISteamNews/GetNewsForApp/v2/", request.url.encodedPath)
                        assertEquals("570", request.url.parameters["appid"])
                        assertEquals("2", request.url.parameters["count"])
                        assertEquals("120", request.url.parameters["maxlength"])

                        respondJson(
                            """
                            {
                              "appnews": {
                                "appid": 570,
                                "newsitems": [
                                  {
                                    "gid": "1",
                                    "title": "Patch Notes",
                                    "url": "https://example.com/patch",
                                    "is_external_url": false,
                                    "author": "Valve",
                                    "contents": "Gameplay changes",
                                    "feedlabel": "Steam News",
                                    "date": 1700000000,
                                    "feedname": "steam_updates",
                                    "feed_type": 1,
                                    "appid": 570,
                                    "tags": ["patch"]
                                  }
                                ],
                                "count": 1
                              }
                            }
                            """.trimIndent(),
                        )
                    },
            )

        val updates = api.getGameUpdates("570", limit = 2, maxLength = 120).toList()

        assertEquals(1, updates.size)
        assertEquals("Patch Notes", updates.single().title)
    }

    private fun testClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(HttpTimeout)
        }

    private fun MockRequestHandleScope.respondJson(content: String): HttpResponseData =
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}

private fun appDetailsResponse(appId: String, name: String): String =
    """
    {
      "$appId": {
        "success": true,
        "data": {
          "type": "game",
          "name": "$name",
          "steam_appid": ${appId.toInt()},
          "required_age": 0,
          "is_free": true,
          "controller_support": null,
          "dlc": [],
          "detailed_description": "Detailed description",
          "about_the_game": "About the game",
          "short_description": "Short description",
          "supported_languages": "English",
          "reviews": null,
          "header_image": "https://example.com/header.jpg",
          "capsule_image": "https://example.com/capsule.jpg",
          "capsule_imagev5": "https://example.com/capsule-v5.jpg",
          "website": null,
          "pc_requirements": {
            "minimum": "CPU"
          },
          "mac_requirements": {
            "minimum": "CPU"
          },
          "linux_requirements": {
            "minimum": "CPU"
          },
          "legal_notice": null,
          "developers": ["Valve"],
          "publishers": ["Valve"],
          "price_overview": null,
          "platforms": {
            "windows": true,
            "mac": true,
            "linux": true
          },
          "metacritic": null,
          "categories": [
            {
              "id": 2,
              "description": "Single-player"
            }
          ],
          "genres": [
            {
              "id": "1",
              "description": "Action"
            }
          ],
          "screenshots": [
            {
              "id": 1,
              "path_thumbnail": "https://example.com/thumb.jpg",
              "path_full": "https://example.com/full.jpg"
            }
          ],
          "movies": [],
          "recommendations": {
            "total": 42
          },
          "achievements": {
            "total": 1,
            "highlighted": [
              {
                "name": "First Step",
                "path": "https://example.com/achievement.jpg"
              }
            ]
          },
          "release_date": {
            "coming_soon": false,
            "date": "2025-01-01"
          },
          "support_info": {
            "url": "https://example.com/support",
            "email": "support@example.com"
          },
          "background": "https://example.com/background.jpg",
          "background_raw": "https://example.com/background-raw.jpg",
          "content_descriptors": {
            "ids": [],
            "notes": null
          },
          "packages": [],
          "package_groups": []
        }
      }
    }
    """.trimIndent()
