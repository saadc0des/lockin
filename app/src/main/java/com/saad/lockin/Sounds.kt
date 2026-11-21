package com.saad.lockin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

/** The same four short sounds as the desktop app: start, end, break_start, break_end.
 *  Plays on the notification stream, so the phone's silent mode and volume are respected. */
object Sounds {
    fun play(c: Context, name: String) {
        if (!LockStore.soundsOn(c)) return
        val res = when (name) {
            "start" -> R.raw.start
            "end" -> R.raw.end
            "break_start" -> R.raw.break_start
            "break_end" -> R.raw.break_end
            else -> return
        }
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val mp = MediaPlayer.create(c.applicationContext, res, attrs, AudioManager.AUDIO_SESSION_ID_GENERATE)
                ?: return
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { m, _, _ -> m.release(); true }
            mp.start()
        } catch (_: Exception) {
            // a sound must never break the timer
        }
    }
}
