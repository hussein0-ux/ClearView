package com.clearview.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.zip.ZipInputStream

/** Home screen: open a video, and set up the AI model. */
class MainActivity : ComponentActivity() {

    private var modelLabel: TextView? = null

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                play(uri)
            }
        }

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadModel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiSettings.load(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(t: String, size: Float = 14f) = TextView(this).apply { text = t; textSize = size }

        root.addView(text("ClearView", 28f))
        root.addView(text("Video player that cleans up and sharpens old, low-quality video, live.\n"))

        val crashFile = ClearViewApp.crashFile(this)
        if (crashFile.exists() && crashFile.length() > 0) {
            val log = crashFile.readText()
            root.addView(TextView(this).apply {
                text = "ClearView recorded a problem last time:\n" + log.takeLast(1800)
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setTextColor(Color.parseColor("#FF6B6B"))
            })
            root.addView(Button(this).apply {
                text = "Copy crash log, then clear it"
                setOnClickListener {
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("ClearView crash", log))
                    crashFile.delete()
                    Toast.makeText(this@MainActivity, "Copied. Paste it to Claude.", Toast.LENGTH_LONG).show()
                }
            })
        }

        root.addView(Button(this).apply {
            text = "Open a video file"
            setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        })
        root.addView(Button(this).apply {
            text = "Open a video link (URL)"
            setOnClickListener { askUrl() }
        })

        root.addView(text("\nAI upscaler", 18f))
        root.addView(text(
            "Uses the phone's NPU to upscale each video frame 4x. Pick the model once: the AI Hub \"XLSR\" or " +
                "\"QuickSRNetSmall\" w8a8 ONNX .zip (the original zip, so its metadata.json comes along)."
        ))
        modelLabel = text(
            if (AiSettings.modelPath != null) "Model: ${File(AiSettings.modelPath!!).name}" else "No model picked yet (filters still work without it)"
        )
        root.addView(modelLabel)
        root.addView(Button(this).apply {
            text = "Pick AI model (.zip / .onnx)"
            setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        })
        root.addView(CheckBox(this).apply {
            text = "Use the AI upscaler"
            isChecked = AiSettings.enabled
            setOnCheckedChangeListener { _, on ->
                AiSettings.enabled = on
                AiSettings.save(this@MainActivity)
            }
        })
        root.addView(CheckBox(this).apply {
            text = "Allow CPU fallback (slower, use only if the model will not run on the NPU alone)"
            isChecked = AiSettings.allowCpu
            setOnCheckedChangeListener { _, on ->
                AiSettings.allowCpu = on
                AiSettings.generation++
                AiSettings.save(this@MainActivity)
            }
        })
        val diagView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
        }
        root.addView(Button(this).apply {
            text = "Diagnose model (which nodes does the NPU refuse?)"
            setOnClickListener {
                val path = AiSettings.modelPath
                if (path == null) {
                    Toast.makeText(this@MainActivity, "Pick a model first", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                diagView.text = "Running one test frame, this can take a few seconds..."
                Thread {
                    val result = NpuDiag.run(this@MainActivity, path)
                    runOnUiThread { diagView.text = result }
                }.start()
            }
        })
        root.addView(diagView)
        root.addView(Button(this).apply {
            text = "Copy diagnosis"
            setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("ClearView diagnosis", diagView.text))
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(text("Detail level (bigger = sharper, more battery):"))
        val rg = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Fast", "Balanced", "Detail").forEachIndexed { i, name ->
            rg.addView(RadioButton(this).apply {
                text = name
                id = View.generateViewId()
                isChecked = AiSettings.quality == i
                setOnClickListener {
                    AiSettings.quality = i
                    AiSettings.save(this@MainActivity)
                }
            })
        }
        root.addView(rg)

        root.addView(text(
            "\nNotes\n" +
                "- Works on files and normal video links. Protected streams are not supported here.\n" +
                "- Double-tap the left or right half of the video to jump 10 s. Tap once for controls.\n" +
                "- In the player, open \"Enhance\" to change the picture style or compare with the original."
        ).apply { textSize = 12f })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun play(uri: Uri) {
        startActivity(
            Intent(this, PlayerActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun askUrl() {
        val input = EditText(this).apply {
            hint = "https://..."
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Video link")
            .setView(input)
            .setPositiveButton("Play") { _, _ ->
                val s = input.text.toString().trim()
                if (s.startsWith("http://") || s.startsWith("https://")) play(Uri.parse(s))
                else Toast.makeText(this, "Enter a link starting with http:// or https://", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadModel(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            } ?: "model.onnx"
            val dir = File(filesDir, "onnx_model").apply { deleteRecursively(); mkdirs() }
            var onnx: File? = null
            if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                ZipInputStream(bytes.inputStream()).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        val base = File(e.name).name
                        if (!e.isDirectory && (base.endsWith(".onnx") || base.endsWith(".data") || base == "metadata.json")) {
                            val f = File(dir, base)
                            f.writeBytes(zin.readBytes())
                            if (base.endsWith(".onnx")) onnx = f
                        }
                        e = zin.nextEntry
                    }
                }
            } else if (name.endsWith(".onnx")) {
                onnx = File(dir, name).also { it.writeBytes(bytes) }
            }
            val f = onnx ?: throw IllegalStateException("No .onnx file found in $name")
            // A model with external weights names a sidecar file inside the .onnx. If it is missing the NPU
            // rejects the model with a confusing error, so fail early with a clear message.
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            val missing = Regex("[A-Za-z0-9_.\\-]+\\.(data|bin|weights)").findAll(text)
                .map { it.value }.distinct().filter { !File(dir, it).exists() }.toList()
            if (missing.isNotEmpty()) {
                f.delete()
                throw IllegalStateException(
                    "Model needs external weights file(s) ${missing.joinToString()} that were not provided. " +
                        "Pick the .zip containing them, or use the single-file (inline) .onnx."
                )
            }
            AiSettings.modelPath = f.path
            AiSettings.generation++
            AiSettings.save(this)
            modelLabel?.text = "Model: ${f.name}"
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not load model: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
}
