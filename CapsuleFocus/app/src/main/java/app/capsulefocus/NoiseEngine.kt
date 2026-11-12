package app.capsulefocus

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.random.Random

/**
 * 专注白噪音：AudioTrack 实时合成（白色/粉色/棕色），不携带任何音频文件。
 * 棕色噪音低沉似雨声最适合专注；只在专注阶段播放，休息/呼吸自动暂停。
 *
 * [FIX] track 改为 volatile 且 stop 会等待生成线程退出；快速"结束→重开"不会
 * 出现两个 AudioTrack 并存、旧线程把新 track 引用清空的问题。
 */
object NoiseEngine {

    const val TYPE_WHITE = 0
    const val TYPE_PINK = 1
    const val TYPE_BROWN = 2

    private const val JOIN_TIMEOUT_MS = 300L

    fun typeName(type: Int): String = when (type) {
        TYPE_WHITE -> "白色噪音"
        TYPE_PINK -> "粉色噪音"
        else -> "棕色噪音"
    }

    @Volatile private var running = false
    @Volatile private var type = TYPE_BROWN
    @Volatile private var volume = 0.24f
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start(newType: Int, volumePercent: Int) {
        if (running) return
        joinPrevious()   // 双保险：上一个线程彻底退出后再起新的
        type = newType
        volume = (volumePercent.coerceIn(0, 100) / 100f) * 0.6f   // 上限 60% 防炸耳
        running = true
        thread = thread(name = "NoiseEngine") { loop() }
    }

    fun pause() {
        try { track?.pause() } catch (_: IllegalStateException) {}
    }

    fun resume() {
        if (!running) return
        try { track?.play() } catch (_: IllegalStateException) {}
    }

    fun stop() {
        running = false
        try { track?.stop() } catch (_: IllegalStateException) {}
        joinPrevious()
    }

    private fun joinPrevious() {
        val t = thread ?: return
        if (t.isAlive && t !== Thread.currentThread()) {
            try {
                t.join(JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
    }

    private fun loop() {
        val sampleRate = 44100
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val localTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf, 8192))
            .build()
        track = localTrack
        localTrack.play()

        val chunk = 4096
        val samples = ShortArray(chunk)
        var brown = 0.0
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0
        var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        try {
            while (running) {
                localTrack.setVolume(volume)
                for (i in 0 until chunk) {
                    val w = Random.nextDouble(-1.0, 1.0)
                    val v = when (type) {
                        TYPE_WHITE -> w * 0.5
                        TYPE_BROWN -> {
                            brown = (brown + 0.02 * w) / 1.02
                            brown * 3.5
                        }
                        else -> {   // Paul Kellet 粉色噪音滤波
                            b0 = 0.99886 * b0 + w * 0.0555179
                            b1 = 0.99332 * b1 + w * 0.0750759
                            b2 = 0.96900 * b2 + w * 0.1538520
                            b3 = 0.86650 * b3 + w * 0.3104856
                            b4 = 0.55000 * b4 + w * 0.5329522
                            b5 = -0.7616 * b5 - w * 0.0168980
                            (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11
                                .also { b6 = w * 0.115926 }
                        }
                    }
                    samples[i] = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                }
                localTrack.write(samples, 0, chunk)
            }
        } catch (_: Exception) {
        } finally {
            try { localTrack.stop(); localTrack.release() } catch (_: Exception) {}
            // [FIX] 只清自己创建的实例，绝不误清新会话的 track
            if (track === localTrack) track = null
        }
    }
}
