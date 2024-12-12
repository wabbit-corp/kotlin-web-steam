package one.wabbit.web.steam

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class SteamApiSpec {
    @Test fun main() {
        runBlocking {
            val steamApi = SteamAPI()

//            // Get game details
//            val game = steamApi.getGameDetails("374320")
//            println("Game: ${game.name}")

            // Get game reviews
//            steamApi.getGameReviews("374320", limit = 10)
//                .collect { review ->
//                    println("Review: ${review.review}")
//                }
//
//            // Search for games
//            steamApi.searchGames("Dark Souls", limit = 5)
//                .collect { game ->
//                    println("Found: ${game.name}")
//                }

//              steamApi.getRecentlyUpdatedGames(limit = 5)
//                  .collect { game ->
//                      println("Recently updated: ${game.name}")
//                  }

                steamApi.getGameUpdates("374320", limit = 5, maxLength = 0)
                    .collect { update ->
                        println("Update: ${update}")
                    }
        }
    }
}
