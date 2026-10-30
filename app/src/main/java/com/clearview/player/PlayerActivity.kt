package com.clearview.player

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

/** Full-screen player: ExoPlayer decodes, [VideoGl] enhances, simple touch controls on top. */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var gl: VideoGl? = null
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var panel: ScrollView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var playBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var statsText: TextView
    private var userSeeking = false
    private var showStats = false
    private var controlsVisible = true
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 2
    private val refreshers = mutableListOf<() -> Unit>()

    private val hideControls = Runnable { setControlsVisible(false) }

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val dur = p.duration
                if (dur != C.TIME_UNSET && dur > 0 && !userSeeking) {
                    seek.max = 1000
                    seek.progress = (p.currentPosition * 1000 / dur).toInt().coerceIn(0, 1000)
                }
                timeText.text = fmt(p.currentPosition) + " / " + (if (dur == C.TIME_UNSET) "--:--" else fmt(dur))
                playBtn.text = if (p.isPlaying) "Pause" else "Play"
            }
            if (showStats) {
                val st = AiSettings.stats
                statsText.text = AiSettings.status + if (st.isNotEmpty()) "
$st" else ""
            }
            ui.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AiSettings.load(this)

        val uri = intent?.data
        if (uri == null) {
            Toast.makeText(this, "No video given", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildUi()

        val p = ExoPlayer.Builder(this).build()
        player = p
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                gl?.setVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }

            override fun onPlayerError(error: PlaybackException) {
                runCatching { ClearViewApp.record(this@PlayerActivity, "player", error) }
                Toast.makeText(this@PlayerActivity, "Playback error: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        })
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true

        ui.post(tick)
        scheduleHide()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val g = VideoGl(holder.surface, this@PlayerActivity)
                g.start()
                gl = g
                player?.let { p ->
                    p.setVideoSurface(g.captureSurface)
                    val vs = p.videoSize
                    g.setVideoSize(vs.width, vs.height, vs.pixelWidthHeightRatio)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                gl?.requestRedraw()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                player?.clearVideoSurface()
                gl?.release()
                gl = null
            }
        })
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (panel.visibility == View.VISIBLE) panel.visibility = View.GONE
                else setControlsVisible(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return true
                val delta = if (e.x < root.width / 2f) -10_000L else 10_000L
                p.seekTo((p.currentPosition + delta).coerceAtLeast(0L))
                return true
            }
        })
        surface.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }

        // Top bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(button("Back") { finish() })
            addView(TextView(this@PlayerActivity).apply {
                text = "  ClearView"
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Bottom controls
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) player?.let { p ->
                        val dur = p.duration
                        if (dur != C.TIME_UNSET) timeText.text = fmt(dur * progress / 1000) + " / " + fmt(dur)
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    userSeeking = false
                    player?.let { p ->
                        val dur = p.duration
                        if (dur != C.TIME_UNSET) p.seekTo(dur * sb.progress / 1000)
                    }
                    scheduleHide()
                }
            })
        }
        timeText = TextView(this).apply { setTextColor(Color.WHITE); text = "00:00 / --:--" }
        playBtn = button("Pause") {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            scheduleHide()
        }
        speedBtn = button("1x") {
            speedIndex = (speedIndex + 1) % speeds.size
            player?.setPlaybackSpeed(speeds[speedIndex])
            (it as Button).text = fmtSpeed(speeds[speedIndex])
            scheduleHide()
        }
        val fillBtn = button("Fit") {
            val g = gl ?: return@button
            g.fill = !g.fill
            (it as Button).text = if (g.fill) "Fill" else "Fit"
            scheduleHide()
        }
        val enhanceBtn = button("Enhance") {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(playBtn)
            addView(timeText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12) })
            addView(View(this@PlayerActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(speedBtn)
            addView(fillBtn)
            addView(enhanceBtn)
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(row)
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        buildPanel()

        statsText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            visibility = View.GONE
        }
        root.addView(statsText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { topMargin = dp(64); marginStart = dp(16) })

        setContentView(root)
        hideSystemBars()
    }

    private fun buildPanel() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        fun label(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 13f }

        col.addView(label("Enhance").apply { textSize = 18f })

        col.addView(CheckBox(this).apply {
            text = "AI upscaler (NPU)"
            setTextColor(Color.WHITE)
            isChecked = AiSettings.enabled
            setOnCheckedChangeListener { _, on ->
                AiSettings.enabled = on
                AiSettings.save(this@PlayerActivity)
                gl?.requestRedraw()
            }
        })
        col.addView(label("AI picture size (bigger = more detail, more battery)"))
        val rg = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Fast", "Balanced", "Detail").forEachIndexed { i, name ->
            rg.addView(RadioButton(this).apply {
                text = name
                setTextColor(Color.WHITE)
                id = View.generateViewId()
                isChecked = AiSettings.quality == i
                setOnClickListener {
                    AiSettings.quality = i
                    AiSettings.save(this@PlayerActivity)
                    gl?.requestRedraw()
                }
            })
        }
        col.addView(rg)

        col.addView(label("Picture style"))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun preset(name: String, p: VideoLook.Preset) {
            presets.addView(button(name) {
                VideoLook.apply(p)
                refreshers.forEach { it() }
                gl?.requestRedraw()
            })
        }
        preset("Off", VideoLook.OFF)
        preset("Clean", VideoLook.CLEAN)
        preset("Crisp", VideoLook.CRISP)
        preset("HDR", VideoLook.HDR)
        preset("Cinema", VideoLook.CINEMA)
        col.addView(presets)

        slider(col, "Clean (blocks and noise)", 0f, 1f, { VideoLook.clean }) { VideoLook.clean = it }
        slider(col, "Sharpen", 0f, 1f, { VideoLook.sharpen }) { VideoLook.sharpen = it }
        slider(col, "Clarity", 0f, 1f, { VideoLook.clarity }) { VideoLook.clarity = it }
        slider(col, "HDR look", 0f, 1f, { VideoLook.hdr }) { VideoLook.hdr = it }
        slider(col, "Contrast", 0.8f, 1.4f, { VideoLook.contrast }) { VideoLook.contrast = it }
        slider(col, "Saturation", 0.5f, 1.6f, { VideoLook.saturation }) { VideoLook.saturation = it }
        slider(col, "Brightness", 0.8f, 1.4f, { VideoLook.exposure }) { VideoLook.exposure = it }

        col.addView(CheckBox(this).apply {
            text = "Compare: enhanced on the left, original on the right"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, on ->
                VideoLook.compare = on
                gl?.requestRedraw()
            }
        })
        col.addView(CheckBox(this).apply {
            text = "Show AI status and timings"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, on ->
                showStats = on
                statsText.visibility = if (on) View.VISIBLE else View.GONE
            }
        })

        panel = ScrollView(this).apply {
            setBackgroundColor(0xDD111111.toInt())
            visibility = View.GONE
            addView(col)
        }
        root.addView(panel, FrameLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
    }

    private fun slider(parent: LinearLayout, label: String, min: Float, max: Float, get: () -> Float, set: (Float) -> Unit) {
        val steps = 100
        val title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        val bar = SeekBar(this).apply { this.max = steps }
        fun show(v: Float) { title.text = String.format(Locale.US, "%s: %.2f", label, v) }
        fun refresh() {
            val v = get()
            show(v)
            bar.progress = (((v - min) / (max - min)) * steps).toInt().coerceIn(0, steps)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + (max - min) * p / steps
                show(v)
                set(v)
                gl?.requestRedraw()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        refresh()
        refreshers += ::refresh
        parent.addView(title)
        parent.addView(bar)
    }

    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick(it) }
    }

    private fun setControlsVisible(v: Boolean) {
        controlsVisible = v
        val vis = if (v) View.VISIBLE else View.GONE
        controls.visibility = vis
        topBar.visibility = vis
        if (v) scheduleHide() else ui.removeCallbacks(hideControls)
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        ui.postDelayed(hideControls, 4000)
    }

    private fun hideSystemBars() {
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    private fun fmtSpeed(f: Float) = (if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()) + "x"
}
