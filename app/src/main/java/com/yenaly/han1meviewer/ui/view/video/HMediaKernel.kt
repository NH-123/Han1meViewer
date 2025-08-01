package com.yenaly.han1meviewer.ui.view.video

import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import cn.jzvd.JZMediaInterface
import cn.jzvd.JZMediaSystem
import cn.jzvd.Jzvd
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.absoluteValue

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/04/21 021 16:57
 */
sealed interface HMediaKernel {
    enum class Type(val clazz: Class<out JZMediaInterface>) {
        MediaPlayer(SystemMediaKernel::class.java),
        ExoPlayer(ExoMediaKernel::class.java);

        companion object {
            fun fromString(name: String): Type {
                return when (name) {
                    MediaPlayer.name -> MediaPlayer
                    ExoPlayer.name -> ExoPlayer
                    else -> ExoPlayer
                }
            }
        }
    }
}

class ExoMediaKernel(jzvd: Jzvd) : JZMediaInterface(jzvd), Player.Listener, HMediaKernel {
    private var isActuallyPlaying = false
    private var lastBufferedPercent = -1
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            isActuallyPlaying = isPlaying
        }
        override fun onIsLoadingChanged(isLoading: Boolean) {
            val per = _exoPlayer?.bufferedPercentage ?: return
            if (per != lastBufferedPercent) {
                lastBufferedPercent = per
                jzvd.setBufferProgress(per)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            // 这里也可以加上更新，确保播放器状态变更时刷新 UI
            val per = _exoPlayer?.bufferedPercentage ?: return
            jzvd.setBufferProgress(per)
        }
    }

    companion object {
        const val TAG = "ExoMediaKernel"
    }

    private var _exoPlayer: ExoPlayer? = null

    /**
     * 尽量少用，用了之后容易出bug
     */
    private val exoPlayer get() = _exoPlayer!!

    private var callback: Runnable? = null
    private var prevSeek = 0L

    var videoRealWidth: Int = 0
        private set
    var videoRealHeight: Int = 0
        private set
    @OptIn(UnstableApi::class)
    override fun prepare() {
        if (_exoPlayer != null) {
            Log.w(TAG, "prepare called, but player already exists.")
            return // 防止误调用
        }
        Log.i(TAG, "prepare")
        val context = jzvd.context

        release()
        mMediaHandlerThread = HandlerThread("JZVD")
        mMediaHandlerThread.start()
        mMediaHandler = Handler(mMediaHandlerThread.looper)
        handler = Handler(Looper.getMainLooper())
        mMediaHandler?.post {
            val videoTrackSelectionFactory = AdaptiveTrackSelection.Factory()
            val trackSelector = DefaultTrackSelector(context, videoTrackSelectionFactory)

            val loadControl: LoadControl = DefaultLoadControl.Builder()
                // .setBufferDurationsMs(360000, 600000, 1000, 5000)
                // .setPrioritizeTimeOverSizeThresholds(false)
                // .setTargetBufferBytes(C.LENGTH_UNSET)
                .build()


            val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
            // 2. Create the player
            val renderersFactory = DefaultRenderersFactory(context)
            _exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setBandwidthMeter(bandwidthMeter)
                .build().apply {
                    addListener(playerListener)
                }

            // Produces DataSource instances through which media data is loaded.
            val dataSourceFactory = DefaultDataSource.Factory(
                context,
                DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(jzvd.jzDataSource.headerMap)
            )

            val currUrl = jzvd.jzDataSource.currentUrl.toString()
            val videoSource = if (currUrl.contains(".m3u8")) {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(currUrl))
            } else {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(currUrl))
            }

            Log.i(TAG, "URL Link = $currUrl")

            exoPlayer.addListener(this)

            val isLoop = jzvd.jzDataSource.looping
            if (isLoop) {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
            } else {
                exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            }
            exoPlayer.setMediaSource(videoSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
//            callback = OnBufferingUpdate()

            val surfaceTexture = jzvd.textureView?.surfaceTexture
            if (surfaceTexture == null) {
                Log.e(TAG, "❌ surfaceTexture is null, video surface not set")
            }
            surfaceTexture?.let { exoPlayer.setVideoSurface(Surface(it)) }
        }
    }

    override fun start() {
        mMediaHandler?.post {
            _exoPlayer?.playWhenReady = true
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        val realWidth = videoSize.width * videoSize.pixelWidthHeightRatio
        val realHeight = videoSize.height
        videoRealWidth = realWidth.toInt()
        videoRealHeight = realHeight
        Log.i("JZVD-onVideoSizeChanged","realWidth:$realWidth,realHeight:$realHeight")
        handler.post {
            jzvd.onVideoSizeChanged(realWidth.toInt(), realHeight)
        }
        val ratio = realWidth / realHeight // > 1 橫屏， < 1 竖屏
        if (ratio > 1) {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onRenderedFirstFrame() {
        Log.i(TAG, "onRenderedFirstFrame")
    }

    override fun pause() {
        mMediaHandler?.post {
            _exoPlayer?.playWhenReady = false
        }
    }

    override fun isPlaying(): Boolean {
        return runOnPlayerThread{
         //   _exoPlayer?.playWhenReady ?: false
            _exoPlayer?.playWhenReady
        } == true
      //  return isActuallyPlaying
    }

    override fun seekTo(time: Long) {
        mMediaHandler?.post {
            if (time != prevSeek) {
                _exoPlayer?.let { exoPlayer ->
                    if (time >= exoPlayer.bufferedPosition) {
                        jzvd.onStatePreparingPlaying()
                    }
                    exoPlayer.seekTo(time)
                    exoPlayer.playWhenReady = true
                    prevSeek = time
                    jzvd.seekToInAdvance = time
                }
            }
        }
    }

    override fun release() {
        if (mMediaHandler != null && mMediaHandlerThread != null && _exoPlayer != null) { //不知道有没有妖孽
            val tmpHandlerThread = mMediaHandlerThread
            val tmpMediaPlayer = exoPlayer
            SAVED_SURFACE = null
            mMediaHandler?.post {
                tmpMediaPlayer.release() //release就不能放到主线程里，界面会卡顿
                tmpHandlerThread.quit()
                _exoPlayer = null
            }
        }
    }



    // 在类里加一个工具方法，用来在线程里同步访问ExoPlayer

    inline fun <T> runOnPlayerThread(crossinline block: () -> T?): T? {
        if (Looper.myLooper() == mMediaHandler?.looper) {
            return block()
        }

        val result = AtomicReference<T?>()
        val latch = CountDownLatch(1)

        mMediaHandler?.post {
            try {
                result.set(block())
            } finally {
                latch.countDown()
            }
        }

        val finished = latch.await(300, TimeUnit.MILLISECONDS)
        return if (finished) result.get() else null
    }



    override fun getCurrentPosition(): Long {
        return runOnPlayerThread {
            _exoPlayer?.currentPosition
        } ?: 0L
    }


    override fun getDuration(): Long {
        return runOnPlayerThread {
            _exoPlayer?.duration
        } ?: 0L
    }



    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        mMediaHandler?.post {
            _exoPlayer?.volume = (leftVolume + rightVolume) / 2
        }
    }

    override fun setSpeed(speed: Float) {
        mMediaHandler?.post {
            val playbackParams = PlaybackParameters(speed, 1.0F)
            _exoPlayer?.playbackParameters = playbackParams
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        Log.i(TAG, "onTimelineChanged")
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        Log.i(TAG, "onIsLoadingChanged")
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        Log.i(TAG, "onPlayWhenReadyChanged: $playWhenReady, reason: $reason, " +
                "playbackState=${_exoPlayer?.playbackState}")
        if (playWhenReady && _exoPlayer?.playbackState == Player.STATE_READY) {
            handler?.post {
                Log.i(TAG,"onPlayWhenReadyChanged_ready")
                jzvd.onStatePlaying()
            }
        }
        Log.i(TAG,"onPlayWhenReadyChanged")
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Log.i(TAG, "onPlaybackStateChanged: $playbackState")
        handler?.post {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    jzvd.onStatePreparingPlaying()
                    callback?.let(handler::post)
                }

                Player.STATE_READY -> {

                    runOnPlayerThread {
                        Log.i(TAG, "STATE_READY, playWhenReady=${_exoPlayer?.playWhenReady}")
                        if (_exoPlayer?.playWhenReady == true){
                            jzvd.onStatePlaying()
                        }
                    }
                }

                Player.STATE_ENDED -> {
                    jzvd.onCompletion()
                }

                else -> {
                    Log.i(TAG, "onPlaybackStateChanged: $playbackState")
                }
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "onPlayerError: $error")
        handler?.post { jzvd.onError(1000, 1000) }
    }

//    override fun onPositionDiscontinuity(
//        oldPosition: Player.PositionInfo,
//        newPosition: Player.PositionInfo,
//        reason: Int,
//    ) {
//        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
//            handler?.post { jzvd.onSeekComplete() }
//        }
//    }
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            handler?.post {
                jzvd.onSeekComplete()
                runOnPlayerThread {
                    if (_exoPlayer?.playWhenReady==true) {
                        jzvd.onStatePlaying()
                    }
                }

            }
        }
    }

    override fun setSurface(surface: Surface?) {
        _exoPlayer?.setVideoSurface(surface)
    }

//    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
//        if (SAVED_SURFACE == null) {
//            SAVED_SURFACE = surface
//            prepare()
//        } else {
//            jzvd.textureView.setSurfaceTexture(SAVED_SURFACE)
//        }
//    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (SAVED_SURFACE == null) {
            SAVED_SURFACE = surface

            if (_exoPlayer == null) {
                prepare()
            } else {
                _exoPlayer?.setVideoSurface(Surface(surface))
            }
        } else {
            jzvd.textureView.setSurfaceTexture(SAVED_SURFACE)
        }
    }


    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit


//    private inner class OnBufferingUpdate : Runnable {
//        override fun run() {
//            _exoPlayer?.bufferedPercentage?.let { per ->
//                handler.post {
//                    jzvd.setBufferProgress(per)
//                }
//                if (per < 100) {
//                    handler.postDelayed(this, 300)
//                } else {
//                    handler.removeCallbacks(this)
//                }
//                return
//            }
//            handler.removeCallbacks(this)
//        }
//    }
}


class SystemMediaKernel(jzvd: Jzvd) : JZMediaSystem(jzvd), HMediaKernel {
    // #issue-26: 有的手機長按快進會報錯，合理懷疑是不是因爲沒有加 post
    // #issue-28: 有的平板长按快进也会报错，结果是 IllegalArgumentException，很奇怪，两次 try-catch 处理试试。
    override fun setSpeed(speed: Float) {
        mMediaHandler?.post {
            try {
                val pp = mediaPlayer.playbackParams
                pp.speed = speed.absoluteValue
                mediaPlayer.playbackParams = pp
            } catch (_: IllegalArgumentException) {
                try {
                    val opp = PlaybackParams().setSpeed(speed.absoluteValue)
                    mediaPlayer.playbackParams = opp
                } catch (e: IllegalArgumentException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onVideoSizeChanged(mediaPlayer: MediaPlayer?, width: Int, height: Int) {
        super.onVideoSizeChanged(mediaPlayer, width, height)
        val ratio = width.toFloat() / height // > 1 橫屏， < 1 竖屏
        if (ratio > 1) {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            Jzvd.FULLSCREEN_ORIENTATION = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // #issue-139: 部分机型暂停报错，没判空导致的
    override fun pause() {
        mMediaHandler?.post {
            mediaPlayer?.pause()
        }
    }

    // #issue-crashlytics-c8636c4bb0b8516675cbeb9e8776bf0b:
    // 有些机器到这里可能会报空指针异常，所以加了个判断，但是不知道为什么会报空指针异常
    override fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying == true
    }
}