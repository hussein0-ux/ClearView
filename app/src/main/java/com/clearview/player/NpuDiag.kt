package com.clearview.player

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.system.Os
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finds out which parts of a model the NPU will not take. It opens the model with CPU fallback ALLOWED and
 * profiling on, runs one blank frame, and reads the profile: every node is listed with the provider that ran it.
 * Nodes on "CPUExecutionProvider" are the ones that make NPU-only mode fail.
 */
object NpuDiag {

    fun run(ctx: Context, modelPath: String): String {
        val sb = StringBuilder()
        sb.append("Model: ").append(File(modelPath).name).append('\n')
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        Os.setenv("ADSP_LIBRARY_PATH", "$nativeDir;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp", true)
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        try {
            opts.addQnn(
                mapOf(
                    "backend_path" to "libQnnHtp.so",
                    "htp_performance_mode" to "burst",
                    "enable_htp_fp16_precision" to "1",
                )
            )
            val dir = File(ctx.cacheDir, "ortprof").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            opts.enableProfiling(File(dir, "prof").path)

            val session = env.createSession(modelPath, opts)
            var profilePath: String? = null
            try {
                val name = session.inputNames.first()
                val info = session.inputInfo[name]!!.info as TensorInfo
                var n = 1
                for (d in info.shape) n *= d.toInt()
                sb.append("Input: ").append(info.type).append(' ').append(info.shape.toList()).append('\n')
                val tensor = when (info.type) {
                    OnnxJavaType.UINT8 ->
                        OnnxTensor.createTensor(env, ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder()), info.shape, OnnxJavaType.UINT8)
                    // ORT Java reports uint16 as INT16.
                    OnnxJavaType.INT16 ->
                        OnnxTensor.createTensor(env, ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder()).asShortBuffer(), info.shape)
                    else ->
                        OnnxTensor.createTensor(env, ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(), info.shape)
                }
                tensor.use { t -> session.run(mapOf(name to t)).close() }
                profilePath = session.endProfiling()
            } finally {
                session.close()
            }

            val text = File(profilePath!!).readText()
            val events = JSONArray(text)
            val counts = LinkedHashMap<String, Int>()
            val cpuNodes = ArrayList<String>()
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                if (e.optString("cat") != "Node") continue
                val nodeName = e.optString("name")
                if (!nodeName.endsWith("_kernel_time")) continue
                val args = e.optJSONObject("args") ?: continue
                val provider = args.optString("provider", "?")
                val op = args.optString("op_name", "?")
                val key = "$provider / $op"
                counts[key] = (counts[key] ?: 0) + 1
                if (provider.startsWith("CPU")) cpuNodes += "$op  ${nodeName.removeSuffix("_kernel_time")}"
            }
            sb.append("\nNodes by provider:\n")
            if (counts.isEmpty()) sb.append("  (no node events in the profile)\n")
            for ((k, v) in counts) sb.append("  ").append(k).append("  x").append(v).append('\n')
            if (cpuNodes.isEmpty()) {
                sb.append("\nNo node ran on the CPU in this run.\n")
            } else {
                sb.append("\nNodes that ran on the CPU (the NPU refused these):\n")
                cpuNodes.take(30).forEach { sb.append("  ").append(it).append('\n') }
            }
        } catch (t: Throwable) {
            sb.append("\nDiagnosis failed: ").append(t.javaClass.simpleName).append(": ").append(t.message).append('\n')
            runCatching { ClearViewApp.record(ctx, "NpuDiag", t) }
        } finally {
            opts.close()
        }
        return sb.toString()
    }
}
