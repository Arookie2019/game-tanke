package com.aiprojects.tankbattle.level

import android.content.Context
import android.util.Log
import com.aiprojects.tankbattle.game.Level
import com.aiprojects.tankbattle.game.LevelDefinitions
import java.io.IOException

/**
 *
 */

object LevelAssetLoader {

    private const val TAG = "LevelAssetLoader"
    private const val LEVELS_DIR = "levels"

    fun loadCampaign(context: Context): List<Level> {
        val names =
            try {
                context.assets.list(LEVELS_DIR)
                    ?.filter { it.endsWith(".txt", ignoreCase = true) }
                    ?.sorted()
            } catch (_: IOException) {
                null
            }

        if (names.isNullOrEmpty()) {
            Log.i(TAG, "assets/$LEVELS_DIR 下无 .txt，使用内置关卡")
            return LevelDefinitions.CAMPAIGN
        }

        val out = ArrayList<Level>(names.size)
        for (name in names) {
            try {
                val text =
                    context.assets.open("$LEVELS_DIR/$name").bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                val defaultTitle = defaultTitleFromFileName(name)
                out += parseLevelFile(text, defaultTitle)
            } catch (e: Exception) {
                Log.w(TAG, "跳过关卡文件 $name: ${e.message}")
            }
        }

        if (out.isEmpty()) {
            Log.w(TAG, "未能解析任何关卡文件，使用内置关卡")
            return LevelDefinitions.CAMPAIGN
        }

        return out
    }

    internal fun parseLevelFile(text: String, defaultTitle: String): Level {
        val stripped = text.trimStart('\uFEFF')
        var title = defaultTitle
        var enemies = DEFAULT_ENEMIES
        val mapLines = mutableListOf<String>()

        for (line in stripped.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue

            when {
                t.startsWith("@TITLE", ignoreCase = true) -> {
                    val rest = directiveBodyAfterKeyword(t, keywordPrefixLen = 6)
                    title = rest.ifEmpty { defaultTitle }
                }

                t.startsWith("@ENEMIES", ignoreCase = true) -> {
                    val rest = directiveBodyAfterKeyword(t, keywordPrefixLen = 8)
                    val n = rest.toIntOrNull()
                    if (n != null && n > 0) enemies = n
                }

                t.startsWith("@") -> Unit

                else -> mapLines += line.trimEnd()
            }
        }

        require(mapLines.isNotEmpty()) { "地图不能为空" }
        return Level(title = title, lines = mapLines, enemiesInLevel = enemies)
    }

    /** `@KEYWORD` 固定占 6（TITLE）或 8（ENEMIES）个字符前缀后的正文。 */
    private fun directiveBodyAfterKeyword(line: String, keywordPrefixLen: Int): String {
        var s = line.trim().drop(keywordPrefixLen).trim()
        if (s.startsWith("=") || s.startsWith(":")) {
            s = s.drop(1).trim()
        }
        return s
    }

    private fun defaultTitleFromFileName(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        return base.replace('_', ' ').trim()
    }

    private const val DEFAULT_ENEMIES = 12
}
