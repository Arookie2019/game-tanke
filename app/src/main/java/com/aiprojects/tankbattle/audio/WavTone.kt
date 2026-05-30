package com.aiprojects.tankbattle.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * 内存中合成极短 PCM16 单声道 WAV（无外部资源文件，避免仓库塞二进制）。
 * 仅作复古“嘀”声占位，可自行替换为 res/raw 资源加载。
 */
internal object WavTone {

    fun buildSineWav(
        freqHz: Double,
        durationSec: Double,
        sampleRate: Int = 22050,
        amplitude: Double = 0.22,
    ): ByteArray {
        val numSamples = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        val pcm = ShortArray(numSamples)
        for (i in pcm.indices) {
            val env = 1.0 - i.toDouble() / numSamples.coerceAtLeast(1)
            val s = sin(2.0 * PI * freqHz * i / sampleRate) * amplitude * env
            pcm[i] = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return encodeWavPcm16Mono(pcm, sampleRate)
    }

    /** 噪声短促爆音，模拟爆炸感 */
    fun buildNoiseBurstWav(
        durationSec: Double,
        sampleRate: Int = 22050,
        amplitude: Double = 0.35,
        seed: Int = 1337,
    ): ByteArray {
        val numSamples = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        var x = seed.toLong() and 0xFFFFFFFFL
        fun nextRand(): Double {
            x = (x * 1103515245L + 12345L) and 0x7FFFFFFFL
            return (x / 2147483647.0) * 2.0 - 1.0
        }
        val pcm = ShortArray(numSamples)
        for (i in pcm.indices) {
            val env = (1.0 - i.toDouble() / numSamples).let { it * it }
            val s = nextRand() * amplitude * env
            pcm[i] = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return encodeWavPcm16Mono(pcm, sampleRate)
    }

    /** 下行滑音（基地告急） */
    fun buildSlideWav(
        fromHz: Double,
        toHz: Double,
        durationSec: Double,
        sampleRate: Int = 22050,
        amplitude: Double = 0.25,
    ): ByteArray {
        val numSamples = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        val pcm = ShortArray(numSamples)
        for (i in pcm.indices) {
            val t = i.toDouble() / numSamples.coerceAtLeast(1)
            val f = fromHz + (toHz - fromHz) * t
            val env = (1.0 - t).let { it * it }
            val s = sin(2.0 * PI * f * i / sampleRate) * amplitude * env
            pcm[i] = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return encodeWavPcm16Mono(pcm, sampleRate)
    }

    private fun encodeWavPcm16Mono(samples: ShortArray, sampleRate: Int): ByteArray {
        val bitsPerSample = 16
        val channels = 1
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val dataSize = samples.size * 2
        val riffSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(riffSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)
        out.write(header.array())

        val data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            data.putShort(s)
        }
        out.write(data.array())
        return out.toByteArray()
    }
}
