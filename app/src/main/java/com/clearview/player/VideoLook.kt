package com.clearview.player

/** Live-tunable picture settings. Written by the UI thread, read by the GL thread every frame. */
object VideoLook {
    @Volatile var clean = 0.5f        // 0..1    edge-preserving smoothing of blocks and noise
    @Volatile var sharpen = 0.45f     // 0..1    adaptive (CAS-style) sharpening
    @Volatile var clarity = 0.25f     // 0..1    local contrast
    @Volatile var contrast = 1.04f    // 0.8..1.4
    @Volatile var saturation = 1.08f  // 0.5..1.6
    @Volatile var filmic = 0.15f      // 0..1    gentle S-curve
    @Volatile var exposure = 1.03f    // 0.8..1.4
    @Volatile var grade = 0f          // 0..1    teal shadows / warm highlights
    @Volatile var vibrance = 0.25f    // 0..1
    @Volatile var hdr = 0.15f         // 0..1    HDR-style local tone mapping + highlight punch (SDR emulation)
    @Volatile var blackPt = 0.10f     // 0..1    deeper blacks
    /** Compare mode: enhanced on the left half, untouched on the right half. */
    @Volatile var compare = false

    class Preset(
        val clean: Float, val sharpen: Float, val clarity: Float, val contrast: Float, val saturation: Float,
        val filmic: Float, val exposure: Float, val grade: Float, val vibrance: Float, val hdr: Float,
        val blackPt: Float,
    )

    val OFF = Preset(0f, 0f, 0f, 1f, 1f, 0f, 1f, 0f, 0f, 0f, 0f)
    val CLEAN = Preset(0.50f, 0.45f, 0.25f, 1.04f, 1.08f, 0.15f, 1.03f, 0.0f, 0.25f, 0.15f, 0.10f)
    val CRISP = Preset(0.35f, 0.80f, 0.50f, 1.08f, 1.08f, 0.15f, 1.04f, 0.0f, 0.25f, 0.25f, 0.15f)
    val HDR = Preset(0.30f, 0.60f, 0.45f, 1.10f, 1.18f, 0.25f, 1.06f, 0.10f, 0.50f, 0.80f, 0.35f)
    val CINEMA = Preset(0.30f, 0.50f, 0.35f, 1.12f, 1.05f, 0.45f, 1.05f, 0.50f, 0.30f, 0.30f, 0.30f)

    fun apply(p: Preset) {
        clean = p.clean; sharpen = p.sharpen; clarity = p.clarity; contrast = p.contrast
        saturation = p.saturation; filmic = p.filmic; exposure = p.exposure; grade = p.grade
        vibrance = p.vibrance; hdr = p.hdr; blackPt = p.blackPt
    }
}
