package com.rtsptunnel.cam.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import javax.net.SocketFactory

/**
 * Wrapper di ExoPlayer specializzato RTSP.
 *
 * Punti chiave privacy/condizionalità:
 *  - setSocketFactory(factory)      -> il canale RTSP passa dal tunnel
 *  - setForceUseRtpTcp(true)        -> anche i dati RTP viaggiano interleaved
 *                                      sullo STESSO socket TCP del tunnel,
 *                                      quindi il video non può bypassarlo
 *  - Le credenziali (se presenti) sono già incluse nell'URI
 *      rtsp://utente:password@ip:porta/percorso
 *    e media3 le usa per rispondere alle sfide Basic/Digest.
 */
class CameraPlayer(
    private val context: Context,
    private val playerView: PlayerView
) {
    private var player: ExoPlayer? = null

    interface Callbacks {
        fun onPlaying()
        fun onAuthError()
        fun onGenericError(message: String?)
    }

    fun start(rtspUrl: String, socketFactory: SocketFactory?, callbacks: Callbacks) {
        release()

        val exoPlayer = ExoPlayer.Builder(context).build()
        playerView.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val msg = error.message ?: error.cause?.message ?: ""
                // 401 / Unauthorized = credenziali errate o mancanti
                if (msg.contains("401") || msg.contains("Unauthorized", ignoreCase = true)) {
                    callbacks.onAuthError()
                } else {
                    callbacks.onGenericError(msg)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) callbacks.onPlaying()
            }
        })

        val source = RtspMediaSource.Factory()
            // tutto il traffico (handshake + video) passa dal tunnel
            .setSocketFactory(socketFactory ?: SocketFactory.getDefault())
            // RTP interleaved sul canale TCP: niente UDP fuori dal proxy
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(Uri.parse(rtspUrl)))

        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        player = exoPlayer
    }

    fun release() {
        player?.release()
        player = null
        playerView.player = null
    }
}
