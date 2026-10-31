package com.clearview.player

import android.content.Context

/** Shared state for the AI upscaler. The UI writes it, the GL thread reads it every frame. */
object AiSettings {
    @Volatile var enabled = true
    /** Let ONNX Runtime run nodes the NPU refuses on the CPU (slower). Off by default. */
    @Volatile var allowCpu = false
    @Volatile var modelPath: String? = null
    /** 0 = fast, 1 = balanced, 2 = detail: how large a picture goes through the NPU (see VideoGl). */
    @Volatile var quality = 1
    /** 0 = AI only, 1 = split (AI left, plain bilinear right), 2 = bilinear only. */
    @Volatile var viewMode = 0
    /** Bumped by the UI to make the pipeline retry after a failure. */
    @Volatile var generation = 0
    /** Set by [ThermalGuard]: 0 normal, 1 warm (AI forced to Fast), 2 hot (AI paused). */
    @Volatile var thermalMode = 0
    @Volatile var nativeLibDir = ""
    @Volatile var status = "AI idle"
    @Volatile var stats = ""

    private const val PREFS = "clearview"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean("ai", true)
        allowCpu = p.getBoolean("allowCpu", false)
        quality = p.getInt("quality", 1).coerceIn(0, 2)
        val path = p.getString("model", null)
        if (modelPath == null && path != null && java.io.File(path).exists()) modelPath = path
        nativeLibDir = ctx.applicationInfo.nativeLibraryDir
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("ai", enabled)
            .putBoolean("allowCpu", allowCpu)
            .putInt("quality", quality)
            .putString("model", modelPath)
            .apply()
    }
}
