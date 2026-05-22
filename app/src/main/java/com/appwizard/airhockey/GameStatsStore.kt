package com.appwizard.airhockey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PlayerStats(
    val bestGoalDifference: Int = Int.MIN_VALUE,
    val currentWinStreak: Int = 0,
    val bestWinStreak: Int = 0,
    val totalWins: Int = 0,
    val totalLosses: Int = 0
) {
    val hasBestScore: Boolean
        get() = bestGoalDifference != Int.MIN_VALUE
}

data class AppSettings(
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true
)

data class MatchRecord(
    val modeTitle: String,
    val difficultyTitle: String,
    val bottomLabel: String,
    val topLabel: String,
    val bottomScore: Int,
    val topScore: Int,
    val winnerLabel: String,
    val goalDifference: Int,
    val playedAtMillis: Long
)

class GameStatsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun loadStats(): PlayerStats {
        return PlayerStats(
            bestGoalDifference = prefs.getInt(KEY_BEST_GOAL_DIFF, Int.MIN_VALUE),
            currentWinStreak = prefs.getInt(KEY_CURRENT_STREAK, 0),
            bestWinStreak = prefs.getInt(KEY_BEST_STREAK, 0),
            totalWins = prefs.getInt(KEY_TOTAL_WINS, 0),
            totalLosses = prefs.getInt(KEY_TOTAL_LOSSES, 0)
        )
    }

    fun recordCpuMatch(playerScore: Int, aiScore: Int): PlayerStats {
        val previous = loadStats()
        val isWin = playerScore > aiScore
        val goalDiff = playerScore - aiScore

        val newCurrentStreak = if (isWin) previous.currentWinStreak + 1 else 0
        val newBestStreak = maxOf(previous.bestWinStreak, newCurrentStreak)
        val newBestGoalDiff = maxOf(previous.bestGoalDifference, goalDiff)
        val newWins = previous.totalWins + if (isWin) 1 else 0
        val newLosses = previous.totalLosses + if (isWin) 0 else 1

        prefs.edit()
            .putInt(KEY_BEST_GOAL_DIFF, newBestGoalDiff)
            .putInt(KEY_CURRENT_STREAK, newCurrentStreak)
            .putInt(KEY_BEST_STREAK, newBestStreak)
            .putInt(KEY_TOTAL_WINS, newWins)
            .putInt(KEY_TOTAL_LOSSES, newLosses)
            .apply()

        return PlayerStats(
            bestGoalDifference = newBestGoalDiff,
            currentWinStreak = newCurrentStreak,
            bestWinStreak = newBestStreak,
            totalWins = newWins,
            totalLosses = newLosses
        )
    }

    fun loadSettings(): AppSettings {
        return AppSettings(
            soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true),
            vibrationEnabled = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_SOUND_ENABLED, settings.soundEnabled)
            .putBoolean(KEY_VIBRATION_ENABLED, settings.vibrationEnabled)
            .apply()
    }

    fun loadTopResults(): List<MatchRecord> {
        val raw = prefs.getString(KEY_TOP_RESULTS, null) ?: return emptyList()
        return runCatching {
            parseResults(raw)
        }.getOrElse {
            emptyList()
        }
    }

    fun recordMatch(record: MatchRecord): List<MatchRecord> {
        val updated = (loadTopResults() + record)
            .sortedWith(
                compareByDescending<MatchRecord> { it.goalDifference }
                    .thenByDescending { it.playedAtMillis }
            )
            .take(MAX_TOP_RESULTS)

        prefs.edit().putString(KEY_TOP_RESULTS, serializeResults(updated)).apply()
        return updated
    }

    private fun serializeResults(records: List<MatchRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject().apply {
                    put(JSON_MODE, record.modeTitle)
                    put(JSON_DIFFICULTY, record.difficultyTitle)
                    put(JSON_BOTTOM_LABEL, record.bottomLabel)
                    put(JSON_TOP_LABEL, record.topLabel)
                    put(JSON_BOTTOM_SCORE, record.bottomScore)
                    put(JSON_TOP_SCORE, record.topScore)
                    put(JSON_WINNER, record.winnerLabel)
                    put(JSON_GOAL_DIFF, record.goalDifference)
                    put(JSON_PLAYED_AT, record.playedAtMillis)
                }
            )
        }
        return array.toString()
    }

    private fun parseResults(raw: String): List<MatchRecord> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    MatchRecord(
                        modeTitle = item.optString(JSON_MODE, "Unknown"),
                        difficultyTitle = item.optString(JSON_DIFFICULTY, "-"),
                        bottomLabel = item.optString(JSON_BOTTOM_LABEL, "Bottom"),
                        topLabel = item.optString(JSON_TOP_LABEL, "Top"),
                        bottomScore = item.optInt(JSON_BOTTOM_SCORE, 0),
                        topScore = item.optInt(JSON_TOP_SCORE, 0),
                        winnerLabel = item.optString(JSON_WINNER, "Unknown"),
                        goalDifference = item.optInt(JSON_GOAL_DIFF, 0),
                        playedAtMillis = item.optLong(JSON_PLAYED_AT, 0L)
                    )
                )
            }
        }
    }

    companion object {
        private const val PREF_NAME = "air_hockey_data"

        private const val KEY_BEST_GOAL_DIFF = "best_goal_diff"
        private const val KEY_CURRENT_STREAK = "current_streak"
        private const val KEY_BEST_STREAK = "best_streak"
        private const val KEY_TOTAL_WINS = "total_wins"
        private const val KEY_TOTAL_LOSSES = "total_losses"

        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_TOP_RESULTS = "top_results"

        private const val JSON_MODE = "mode"
        private const val JSON_DIFFICULTY = "difficulty"
        private const val JSON_BOTTOM_LABEL = "bottom_label"
        private const val JSON_TOP_LABEL = "top_label"
        private const val JSON_BOTTOM_SCORE = "bottom_score"
        private const val JSON_TOP_SCORE = "top_score"
        private const val JSON_WINNER = "winner"
        private const val JSON_GOAL_DIFF = "goal_diff"
        private const val JSON_PLAYED_AT = "played_at"

        private const val MAX_TOP_RESULTS = 10
    }
}
