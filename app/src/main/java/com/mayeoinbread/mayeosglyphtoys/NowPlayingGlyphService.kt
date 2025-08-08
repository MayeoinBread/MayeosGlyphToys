package com.mayeoinbread.mayeosglyphtoys

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toUpperCase
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject
import com.nothing.ketchum.GlyphMatrixUtils

class NowPlayingGlyphService : GlyphMatrixService("NowPlaying-Glyph") {

    private val handler = Handler(Looper.getMainLooper())
    private var scrollIndex = 0
    private val scrollDelay = 200L  // milliseconds

    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentSource: String = ""

    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaControllerCallback: MediaController.Callback? = null

    private val matrix = IntArray(DrawUtils.SCREEN_LENGTH * DrawUtils.SCREEN_LENGTH)
    
    // List of backup logos in case we can't get the logo from the app (eg. YouTube)
    private val appLogo = mapOf(
        "com.amazon.mp3" to R.drawable.amazon_music_logo,
        "com.apple.android.music" to R.drawable.apple_music_logo,
        "com.android.chrome" to R.drawable.chrome_logo,
        "deezer.android.app" to R.drawable.deezer_logo,
        "com.microsoft.emmx" to R.drawable.edge_logo,
        "org.mozilla.firefox" to R.drawable.firefox_logo,
        "com.opera.browser" to R.drawable.opera_logo,
        "com.soundcloud.android" to R.drawable.soundcloud_logo,
        "com.spotify.music" to R.drawable.spotify_logo,
        "com.google.android.youtube" to R.drawable.youtube_logo,
        "com.google.android.apps.youtube.music" to R.drawable.youtube_music_logo
    )

    override fun performOnServiceConnected(
        context: Context,
        glyphMatrixManager: GlyphMatrixManager
    ) {
        mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(context, NowPlayingListenerService::class.java)

        val activeSessions = mediaSessionManager?.getActiveSessions(componentName) ?: emptyList()

        for (controller in activeSessions) {
            mediaControllerCallback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    val source = controller.packageName
                    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""

                    currentArtist = artist
                    currentTitle = title
                    currentSource = source
                    startScrolling()
                }
            }
            controller.registerCallback(mediaControllerCallback!!)

            // Immediately update if metadata already exists
            controller.metadata?.let { meta ->
                currentArtist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                currentTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                currentSource = controller.packageName
            }
            // We need to call this so it still draws to the screen even if no metadata is present
            startScrolling()
            break  // Just bind to the first valid session
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacks(scrollRunnable)

        val componentName = ComponentName(this, NowPlayingListenerService::class.java)
        val sessions = mediaSessionManager?.getActiveSessions(componentName) ?: return

        // Unregister the callback
        for (controller in sessions) {
            mediaControllerCallback?.let { controller.unregisterCallback(it) }
        }
        mediaControllerCallback = null
    }

    private val scrollRunnable = object : Runnable {
        override fun run() {
            drawNowPlaying()
            handler.postDelayed(this, scrollDelay)
        }
    }

    private fun startScrolling() {
        handler.removeCallbacks(scrollRunnable)
        scrollIndex = 0
        handler.post(scrollRunnable)
    }

    override fun onAodUpdate() {
        startScrolling()
    }

    private fun drawNowPlaying() {
        matrix.fill(0)
        val frameBuilder = GlyphMatrixFrame.Builder()

        // If there's no media playing, draw a notice on the screen
        if (currentTitle == "" && currentArtist == "") {
            DrawUtils.drawNormalText(
                "NO",
                DrawUtils.TextAlign.H_CENTER,
                DrawUtils.TextAlign.BOTTOM_C,
                1024,
                1,
                matrix
            )
            DrawUtils.drawNormalText(
                "MUSIC",
                DrawUtils.TextAlign.H_CENTER,
                DrawUtils.TextAlign.TOP_C,
                1024,
                1,
                matrix
            )
        } else {
            // Display the song title + artist
            val displayText = "$currentTitle - $currentArtist  |".toUpperCase(Locale.current)
            DrawUtils.drawScrollingTextCharacterWise(
                displayText,
                scrollIndex,
                DrawUtils.TextAlign.LEFT,
                DrawUtils.TextAlign.V_CENTER,
                1024,
                matrix = matrix
            )

            scrollIndex = (scrollIndex + 1) % (displayText.length + 2)

            val tMatrix = IntArray(DrawUtils.SCREEN_LENGTH * DrawUtils.SCREEN_LENGTH)

            var appLogoDrawable = getAppIcon(applicationContext, currentSource)
            // Use the backup if we can't get the app icon
            if (appLogoDrawable == null) {
                val appLogoDrawableRes = appLogo[currentSource]
                if (appLogoDrawableRes != null) {
                    appLogoDrawable = try {
                        resources.getDrawable(appLogoDrawableRes, null)
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            val logoFrame = if (appLogoDrawable != null) {
                GlyphMatrixObject.Builder()
                    .setImageSource(GlyphMatrixUtils.drawableToBitmap(appLogoDrawable))
                    .setScale(100)
                    .setOrientation(0)
                    .setPosition(0, 0)
                    .setBrightness(24)
                    .build()
            } else {
                val parts = currentSource.split(".")
                val fallbackChar = when {
                    parts.isEmpty() -> '?'
                    parts[0] != "com" -> parts[0].firstOrNull()?.uppercaseChar() ?: '?'
                    parts.size > 1 -> parts[1].firstOrNull()?.uppercaseChar() ?: '?'
                    else -> '?'
                }
                DrawUtils.drawNormalText(
                    fallbackChar.toString(),
                    DrawUtils.TextAlign.H_CENTER,
                    DrawUtils.TextAlign.TOP,
                    512,
                    2,
                    tMatrix
                )
                null
            }

            if (logoFrame != null) {
                frameBuilder.addLow(logoFrame)
            } else {
                frameBuilder.addLow(tMatrix)
            }
        }

        frameBuilder.addMid(matrix)

        val frame = frameBuilder.build(applicationContext)

        glyphMatrixManager?.setMatrixFrame(frame.render())
    }

    private fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            val pm = context.packageManager
            pm.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}