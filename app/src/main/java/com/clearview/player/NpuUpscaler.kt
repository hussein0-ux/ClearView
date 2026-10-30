package com.clearview.player

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Runs a 4x super-resolution ONNX model on the Snapdragon NPU (QNN/HTP).
 *
 * Two I/O flavours are supported:
 *  - float32 in / float32 out (pixel values 0..1);
 *  - uint8 in / uint8 out (the w8a8 exports from Qualcomm AI Hub). The quantisation scale and zero point are read
 *    from the `metadata.json` that ships next to the model (the ONNX Java API does not expose them).
 *    Use [inferU8] for those: feed raw 0..255 pixels mapped through [inLut], and dequantise the output with
 *    `(q - outZero) * outScale`.
 */
class NpuUpscaler(modelPath: String, nativeLibDir: String, cacheDir: String, allowCpu: Boolean = false) : AutoCloseable {

    private companion object {
        const val MAX_ATTEMPTS = 4
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val opts: OrtSession.SessionOptions
    private val session: OrtSession
    private val inName: String
    private val inShape: LongArray

    val inW: Int
    val inH: Int
    val outW: Int
    val outH: Int
    val nchwIn: Boolean
    val planarOut: Boolean
    /** True for uint8 models: use [inferU8] instead of [infer]. */
    val quantized: Boolean
    /** True for uint16 models (AI Hub w8a16): use [inferU16]. ORT Java reports uint16 as INT16. */
    val wide: Boolean
    /** Same-size model (denoise / deblur) rather than a 4x upscaler. */
    val sameSize: Boolean
    val inLut16 = ShortArray(256)
    val inScale: Float
    val inZero: Int
    val outScale: Float
    val outZero: Int
    /** Maps a raw 0..255 pixel byte to the model's quantised input value. Identity for scale 1/255, zero point 0. */
    val inLut = ByteArray(256)
    /** Requested provider configuration; NPU execution is not verified by this class. */
    val note: String

    private val inputLock = Any()
    @Volatile private var closed = false
    private val scratch: FloatBuffer?
    private val scratchU8: ByteBuffer?
    private val scratchS: ShortBuffer?
    private val cachedInputTensor: OnnxTensor

    init {
        Os.setenv("ADSP_LIBRARY_PATH", "$nativeLibDir;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp", true)

        var sessionOpts: OrtSession.SessionOptions? = null
        var createdSession: OrtSession? = null
        var createdTensor: OnnxTensor? = null
        var configNote = ""
        var initFailure: Throwable? = null

        fun safeClose(resource: AutoCloseable?) {
            if (resource != null) {
                try {
                    resource.close()
                } catch (t: Throwable) {
                    if (initFailure == null) initFailure = t
                    else initFailure?.addSuppressed(t)
                }
            }
        }

        // NPU only. There is no CPU fallback: if any node cannot run on the NPU the session is refused.
        // Session creation occasionally fails transiently (the same model passes on another attempt), so retry.
        fun open(): Pair<OrtSession, OrtSession.SessionOptions> {
            val o = OrtSession.SessionOptions()
            try {
                o.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
                o.addQnn(
                    mapOf(
                        "backend_path" to "libQnnHtp.so",
                        "htp_performance_mode" to "burst",
                        "enable_htp_fp16_precision" to "1",
                        "qnn_context_cache_enable" to "1",
                        "qnn_context_cache_path" to "$cacheDir/model_cache.bin"
                    )
                )
                if (!allowCpu) o.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                return env.createSession(modelPath, o) to o
            } catch (t: Throwable) {
                o.close()
                throw t
            }
        }

        val errs = ArrayList<String>()
        var pair: Pair<OrtSession, OrtSession.SessionOptions>? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                pair = open()
                val base = if (allowCpu) "QNN/HTP with CPU fallback allowed (some nodes may run on the CPU)" else "NPU only (QNN/HTP), no CPU fallback"
                configNote = if (attempt == 1) base else "$base, needed $attempt attempts"
                break
            } catch (t: Throwable) {
                errs += "try $attempt: ${t.message?.take(160)}"
                // A missing file or a node the NPU refuses will fail the same way every time.
                val permanent = t.message?.let { "NO_SUCHFILE" in it || "not selected" in it } == true
                if (permanent) break
                if (attempt < MAX_ATTEMPTS && !allowCpu) Thread.sleep(500L * attempt)
            }
        }
        if (pair == null) {
            throw IllegalStateException("Could not run the whole model on the NPU (no CPU fallback). " + errs.joinToString(" | "))
        }

        createdSession = pair!!.first
        sessionOpts = pair!!.second

        try {
            session = createdSession!!
            opts = sessionOpts!!
            note = configNote

            inName = session.inputNames.first()
            val ii = session.inputInfo[inName]!!.info as TensorInfo
            val oi = session.outputInfo[session.outputNames.first()]!!.info as TensorInfo

            val isFloat = ii.type == OnnxJavaType.FLOAT && oi.type == OnnxJavaType.FLOAT
            val isU8 = ii.type == OnnxJavaType.UINT8 && oi.type == OnnxJavaType.UINT8
            val isU16 = ii.type == OnnxJavaType.INT16 && oi.type == OnnxJavaType.INT16
            require(isFloat || isU8 || isU16) {
                "Model must have float32, uint8 or uint16 input and output (found ${ii.type} / ${oi.type})"
            }
            quantized = isU8
            wide = isU16
            require(ii.shape.size == 4 && oi.shape.size == 4) {
                "Model input/output must be rank 4 (in ${ii.shape.toList()}, out ${oi.shape.toList()})"
            }
            require(ii.shape[0] == 1L && oi.shape[0] == 1L) {
                "Model batch dimension must be 1 (in ${ii.shape.toList()}, out ${oi.shape.toList()})"
            }

            val i1 = positiveInt(ii.shape[1], "input dim 1")
            val i2 = positiveInt(ii.shape[2], "input dim 2")
            val i3 = positiveInt(ii.shape[3], "input dim 3")
            val inElements = Math.multiplyExact(Math.multiplyExact(i1, i2), i3)
            val inByteSize = Math.multiplyExact(inElements, if (quantized) 1 else if (wide) 2 else 4)

            val o1 = positiveInt(oi.shape[1], "output dim 1")
            val o2 = positiveInt(oi.shape[2], "output dim 2")
            val o3 = positiveInt(oi.shape[3], "output dim 3")
            val outElements = Math.multiplyExact(Math.multiplyExact(o1, o2), o3)
            require(outElements > 0) { "Output spatial/channel dimensions must be positive" }

            inShape = ii.shape

            val a = parse(ii.shape)
            val b = parse(oi.shape)
            nchwIn = a[0] == 1
            inW = a[1]
            inH = a[2]
            planarOut = b[0] == 1
            outW = b[1]
            outH = b[2]
            sameSize = outW == inW && outH == inH

            val bb = ByteBuffer.allocateDirect(inByteSize).order(ByteOrder.nativeOrder())
            if (wide) {
                val q = readQuantization(modelPath, session.inputNames.first(), session.outputNames.first())
                inScale = q[0]; inZero = q[1].toInt(); outScale = q[2]; outZero = q[3].toInt()
                for (v in 0..255) {
                    inLut16[v] = (Math.round((v / 255f) / inScale) + inZero).coerceIn(0, 65535).toShort()
                    inLut[v] = 0
                }
                scratch = null
                scratchU8 = null
                val sb = bb.asShortBuffer()
                scratchS = sb
                require(sb.capacity() == inElements) { "Scratch buffer capacity mismatch" }
                createdTensor = OnnxTensor.createTensor(env, sb, inShape)
            } else if (quantized) {
                scratchS = null
                val q = readQuantization(modelPath, session.inputNames.first(), session.outputNames.first())
                inScale = q[0]; inZero = q[1].toInt(); outScale = q[2]; outZero = q[3].toInt()
                for (v in 0..255) {
                    val qv = Math.round((v / 255f) / inScale) + inZero
                    inLut[v] = qv.coerceIn(0, 255).toByte()
                }
                scratch = null
                scratchU8 = bb
                require(bb.capacity() == inElements) { "Scratch buffer capacity mismatch" }
                createdTensor = OnnxTensor.createTensor(env, bb, inShape, OnnxJavaType.UINT8)
            } else {
                scratchS = null
                inScale = 1f; inZero = 0; outScale = 1f; outZero = 0
                for (v in 0..255) inLut[v] = v.toByte()
                val fb = bb.asFloatBuffer()
                scratch = fb
                scratchU8 = null
                require(fb.capacity() == inElements) { "Scratch buffer capacity mismatch" }
                createdTensor = OnnxTensor.createTensor(env, fb, inShape)
            }
            cachedInputTensor = createdTensor!!
        } catch (t: Throwable) {
            safeClose(createdTensor)
            safeClose(createdSession)
            safeClose(sessionOpts)
            initFailure?.let { t.addSuppressed(it) }
            throw t
        }
    }

    fun describe() = "in ${inW}x$inH -> out ${outW}x$outH (${if (nchwIn) "NCHW" else "NHWC"} in, " +
        "${if (planarOut) "NCHW" else "NHWC"} out, ${if (quantized) "uint8" else if (wide) "uint16" else "float32"}), $note"

    /** Float models. [consume] sees the output only while it runs; do not keep the buffer. */
    fun infer(input: FloatBuffer, consume: (FloatBuffer) -> Unit): Unit = synchronized(inputLock) {
        check(!closed) { "NpuUpscaler is closed" }
        check(!quantized && !wide) { "Model is quantised; call inferU8 / inferU16" }
        val fb = scratch!!
        val dup = input.duplicate()
        val rem = dup.remaining()
        require(rem == fb.capacity()) {
            "Tile size mismatch: expected ${fb.capacity()} floats, got $rem"
        }

        fb.clear()
        fb.put(dup)
        fb.rewind()
        // Caller position/limit preserved via duplicate().

        session.run(mapOf(inName to cachedInputTensor)).use { results ->
            consume((results[0] as OnnxTensor).floatBuffer)
        }
    }

    /**
     * uint8 models. [input] holds already-quantised bytes (raw pixel bytes mapped through [inLut]).
     * [consume] sees the quantised output bytes only while it runs; do not keep the buffer.
     */
    fun inferU8(input: ByteBuffer, consume: (ByteBuffer) -> Unit): Unit = synchronized(inputLock) {
        check(!closed) { "NpuUpscaler is closed" }
        check(quantized) { "Model is not uint8; call infer or inferU16" }
        val bb = scratchU8!!
        val dup = input.duplicate()
        val rem = dup.remaining()
        require(rem == bb.capacity()) {
            "Tile size mismatch: expected ${bb.capacity()} bytes, got $rem"
        }

        bb.clear()
        bb.put(dup)
        bb.rewind()

        session.run(mapOf(inName to cachedInputTensor)).use { results ->
            consume((results[0] as OnnxTensor).byteBuffer)
        }
    }

    /** uint16 models. [input] holds quantised values (raw pixels mapped through [inLut16]). */
    fun inferU16(input: ShortBuffer, consume: (ShortBuffer) -> Unit): Unit = synchronized(inputLock) {
        check(!closed) { "NpuUpscaler is closed" }
        check(wide) { "Model is not uint16" }
        val sb = scratchS!!
        val dup = input.duplicate()
        require(dup.remaining() == sb.capacity()) {
            "Tile size mismatch: expected ${sb.capacity()} shorts, got ${dup.remaining()}"
        }
        sb.clear()
        sb.put(dup)
        sb.rewind()

        session.run(mapOf(inName to cachedInputTensor)).use { results ->
            consume((results[0] as OnnxTensor).shortBuffer)
        }
    }

    override fun close(): Unit = synchronized(inputLock) {
        if (closed) return@synchronized
        closed = true
        runCatching { cachedInputTensor.close() }
        runCatching { session.close() }
        runCatching { opts.close() }
    }

    private fun positiveInt(v: Long, label: String): Int {
        require(v > 0 && v <= Int.MAX_VALUE) { "$label must be positive and fit in Int, got $v" }
        return v.toInt()
    }

    private fun parse(shape: LongArray): IntArray {
        require(shape.size == 4) { "Expected a 4D tensor, got ${shape.toList()}" }
        return when {
            shape[3] == 3L && shape[1] != 3L -> intArrayOf(0, shape[2].toInt(), shape[1].toInt())
            shape[1] == 3L && shape[3] != 3L -> intArrayOf(1, shape[3].toInt(), shape[2].toInt())
            else -> throw IllegalStateException("Ambiguous or missing 3-channel axis in ${shape.toList()}")
        }
    }

    /**
     * Reads [inScale, inZero, outScale, outZero] for a uint8 model from the metadata.json next to it
     * (AI Hub layout: model_files.<file>.inputs/outputs.<name>.quantization_parameters).
     */
    private fun readQuantization(modelPath: String, inputName: String, outputName: String): FloatArray {
        val model = File(modelPath)
        val meta = File(model.parentFile, "metadata.json")
        check(meta.exists()) {
            "uint8 model needs metadata.json (quantization scale/zero point) next to ${model.name}. " +
                "Pick the original .zip, which contains it."
        }
        val files = JSONObject(meta.readText()).getJSONObject("model_files")
        val entry = files.optJSONObject(model.name) ?: files.getJSONObject(files.keys().next())
        fun params(section: String, name: String): Pair<Float, Int> {
            val tensors = entry.getJSONObject(section)
            val t = tensors.optJSONObject(name) ?: tensors.getJSONObject(tensors.keys().next())
            val q = t.getJSONObject("quantization_parameters")
            val scale = q.getDouble("scale").toFloat()
            check(scale > 0f) { "Bad quantization scale $scale in metadata.json" }
            return scale to q.getInt("zero_point")
        }
        val i = params("inputs", inputName)
        val o = params("outputs", outputName)
        return floatArrayOf(i.first, i.second.toFloat(), o.first, o.second.toFloat())
    }
}
