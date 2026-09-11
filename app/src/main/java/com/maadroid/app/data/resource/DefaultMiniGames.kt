package com.maadroid.app.data.resource

import androidx.annotation.StringRes
import com.maadroid.app.R

/**
 * 默认小游戏列表
 */
object DefaultMiniGames {
    data class DefaultMiniGameEntry(
        @param:StringRes val displayRes: Int,
        val value: String,
        @param:StringRes val tipRes: Int,
    )

    val ENTRIES = listOf(
        DefaultMiniGameEntry(
            R.string.mini_game_name_ss_store,
            "SS@Store@Begin",
            R.string.mini_game_tip_ss_store
        ),
        DefaultMiniGameEntry(
            R.string.mini_game_name_green_ticket_store,
            "GreenTicket@Store@Begin",
            R.string.mini_game_tip_green_ticket_store
        ),
        DefaultMiniGameEntry(
            R.string.mini_game_name_yellow_ticket_store,
            "YellowTicket@Store@Begin",
            R.string.mini_game_tip_yellow_ticket_store
        ),
        DefaultMiniGameEntry(
            R.string.mini_game_name_ra_store,
            "RA@Store@Begin",
            R.string.mini_game_tip_ra_store
        ),
        DefaultMiniGameEntry(
            R.string.mini_game_name_secret_front,
            "MiniGame@SecretFront",
            R.string.mini_game_tip_secret_front
        )
    )
}
