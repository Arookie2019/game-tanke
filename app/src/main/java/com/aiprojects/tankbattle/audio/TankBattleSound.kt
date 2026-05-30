package com.aiprojects.tankbattle.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.aiprojects.tankbattle.game.GameSfx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 游戏音效：程序化生成 WAV 写入缓存目录后交给 [SoundPool]（无需随包附带音频文件）。
 */
class TankBattleSound(context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val cacheDir: File =
        context.applicationContext.cacheDir.resolve("tank_sfx").apply { mkdirs() }

    private val ids = mutableMapOf<GameSfx, Int>()

    init {
        val files = mapOf(
            GameSfx.PlayerFire to write("fire.wav", WavTone.buildSineWav(920.0, 0.065)),
            GameSfx.EnemyFire to write("enemy_fire.wav", WavTone.buildSineWav(520.0, 0.05, amplitude = 0.14)),
            GameSfx.Impact to write("impact.wav", WavTone.buildSineWav(2100.0, 0.045, amplitude = 0.18)),
            GameSfx.Explosion to write("boom.wav", WavTone.buildNoiseBurstWav(0.28, amplitude = 0.38)),
            GameSfx.PlayerHit to write("hit.wav", WavTone.buildSlideWav(380.0, 120.0, 0.22)),
            GameSfx.BaseHit to write("base.wav", WavTone.buildSlideWav(880.0, 180.0, 0.35)),
            GameSfx.LevelWin to write("win.wav", WavTone.buildSineWav(660.0, 0.14, amplitude = 0.22)),
            GameSfx.GameOver to write("lose.wav", WavTone.buildSlideWav(220.0, 90.0, 0.45)),
        )

        for ((sfx, file) in files) {
            FileInputStream(file).use { fis ->
                val len = file.length()
                val id = pool.load(fis.fd, 0, len, 1)
                if (id > 0) ids[sfx] = id
            }
        }
    }

    private fun write(name: String, bytes: ByteArray): File {
        val f = File(cacheDir, name)
        FileOutputStream(f).use { it.write(bytes) }
        return f
    }

    fun play(sfx: GameSfx) {
        val sid = ids[sfx] ?: return
        pool.play(sid, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        pool.release()
        ids.clear()
    }
}
