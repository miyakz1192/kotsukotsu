package com.kotsukotsu.kuku

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** 効果音。SoundPool に事前ロードして遅延を避ける（docs/06 1.2）。 */
class Sounds(context: Context) {
    enum class S { CORRECT, WRONG, FANFARE, SPARKLE, TAP }

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()

    private val ids = mapOf(
        S.CORRECT to pool.load(context, R.raw.snd_correct, 1),
        S.WRONG to pool.load(context, R.raw.snd_wrong, 1),
        S.FANFARE to pool.load(context, R.raw.snd_fanfare, 1),
        S.SPARKLE to pool.load(context, R.raw.snd_sparkle, 1),
        S.TAP to pool.load(context, R.raw.snd_tap, 1),
    )

    fun play(s: S) {
        ids[s]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
    }
}
