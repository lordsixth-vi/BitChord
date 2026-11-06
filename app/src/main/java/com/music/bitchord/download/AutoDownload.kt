package com.music.bitchord.download

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Saves a song for offline listening by itself once it has been played enough
 * times to show it is a favourite.
 *
 * ## What counts as a play
 *
 * Not this file's decision. [com.music.bitchord.data.stats.ListeningRecorder]
 * already says when a track has been listened to — half of it, or four minutes,
 * and never under thirty seconds, the same rule the scrobbler uses — and calls
 * [onPlayCounted] once per track per listen. Skipping through a song in ten
 * seconds therefore never moves its count, and neither does leaving one paused.
 *
 * ## Counting and downloading are separate on purpose
 *
 * The count is kept for every song whether or not the feature is switched on,
 * so turning it on later takes effect for what has already been replayed rather
 * than starting everyone from zero. The *download* is what the switch guards.
 *
 * A song at or past the threshold is tried again on its next counted play
 * rather than only on the play that crossed it. That is what makes the cases
 * where nothing could be done at the time — mobile data with the Wi-Fi-only
 * rule on, a failed fetch — repair themselves the next time the song comes
 * round, without any retry machinery of its own.
 *
 * The download itself is the ordinary one: [Downloads.enqueue], so it uses the
 * Download quality setting, the Wi-Fi-only rule, the notification and the
 * Downloads page like any other.
 */
object AutoDownload {

    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_auto_download"
    private const val KEY_PREFIX = "plays_"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** How many times [videoId] has been played through, as far as this feature knows. */
    fun playsOf(videoId: String): Int =
        if (::prefs.isInitialized) prefs.getInt(KEY_PREFIX + videoId, 0) else 0

    /**
     * [song] was just listened to. Called from the sampler's thread, so it only
     * does a counter write and hands everything else to [scope].
     */
    @Synchronized
    fun onPlayCounted(song: Song) {
        if (!::prefs.isInitialized) return
        // A file already on the device has nothing to fetch.
        if (song.localUri != null) return

        val plays = prefs.getInt(KEY_PREFIX + song.videoId, 0) + 1
        prefs.edit().putInt(KEY_PREFIX + song.videoId, plays).apply()

        if (!AppSettings.autoDownloadEnabled.value) return
        if (plays < AppSettings.autoDownloadPlays.value) return

        scope.launch { downloadIfNeeded(song, plays) }
    }

    private suspend fun downloadIfNeeded(song: Song, plays: Int) {
        val id = song.videoId
        // Already on the device, and verified to still be there.
        if (Downloads.savedUri(appContext, id) != null) return
        // Already on its way.
        val inFlight = Downloads.active.value[id]
        if (inFlight is DownloadState.Queued || inFlight is DownloadState.Running) return
        // On a metered connection with Wi-Fi-only on, the listener never asked
        // for this download, so it must not turn up as a failed row on the
        // Downloads page. The next play tries again. It does say so once, though:
        // Wi-Fi-only is on by default, and an app that quietly does nothing looks
        // exactly like an app that is broken.
        if (!AppSettings.downloadsAllowedNow) {
            if (waitingNotified.add(id)) {
                toast("\"${song.title}\" is ready to save offline, but \"Download over Wi-Fi only\" is on. Connect to Wi-Fi, or turn it off in Settings > Downloads.")
            }
            return
        }

        Log.d(TAG, "auto-download: '${song.title}' played $plays times, saving for offline")
        runCatching { Downloads.enqueue(appContext, song) }
            .onSuccess { toast("Saving \"${song.title}\" for offline listening") }
            .onFailure { Log.w(TAG, "auto-download could not queue ${song.videoId}: ${it.message}") }
    }

    /** Songs already told about waiting for Wi-Fi, so the message comes once per song per launch. */
    private val waitingNotified = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun toast(message: String) {
        mainHandler.post {
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
