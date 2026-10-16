package com.textextract.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.abs

private data class Row(val text: String, val top: Int, val bottom: Int, val cells: Int)

private val bulletRegex = Regex("^\\s*[-–—•●▪■◦*·]\\s+")
private val numberRegex = Regex("^\\s*(\\d{1,3})[.)]\\s+")

private fun rowsOf(group: List<Text.Line>): List<Row> {
    val sorted = group.sortedBy { it.boundingBox!!.top }
    val rows = mutableListOf<MutableList<Text.Line>>()
    for (l in sorted) {
        val b = l.boundingBox!!
        val last = rows.lastOrNull()
        val lb = last?.first()?.boundingBox
        if (last != null && lb != null &&
            abs(b.centerY() - lb.centerY()) < 0.5f * minOf(b.height(), lb.height())
        ) last.add(l) else rows.add(mutableListOf(l))
    }
    return rows.map { r ->
        val s = r.sortedBy { it.boundingBox!!.left }
        Row(
            s.joinToString("  |  ") { it.text.trim() },
            r.minOf { it.boundingBox!!.top },
            r.maxOf { it.boundingBox!!.bottom },
            s.size
        )
    }
}

private fun compose(rows: List<Row>): String {
    if (rows.isEmpty()) return ""
    val heights = rows.map { it.bottom - it.top }.sorted()
    val med = heights[heights.size / 2].coerceAtLeast(1)
    val sb = StringBuilder()
    var prev: Row? = null
    var prevList = false
    var prevHeading = false
    for (r in rows) {
        var t = r.text.trim()
        val isBullet = bulletRegex.containsMatchIn(t)
        val isNum = !isBullet && numberRegex.containsMatchIn(t)
        val isList = isBullet || isNum
        if (isBullet) t = bulletRegex.replaceFirst(t, "• ")
        else if (isNum) t = numberRegex.replaceFirst(t, "\$1. ")
        val heading = (r.bottom - r.top) > med * 1.35 && t.length < 90 && r.cells == 1 && !isList
        if (heading) t = "# $t"
        if (prev != null) {
            val gap = r.top - prev.bottom
            val together = (prevList && isList) ||
                (prev.cells > 1 && r.cells > 1) ||
                (gap <= med * 0.6 && !heading && !prevHeading)
            sb.append(if (together) "\n" else "\n\n")
        }
        sb.append(t)
        prev = r
        prevList = isList
        prevHeading = heading
    }
    return sb.toString()
}

private fun structure(result: Text, pageWidth: Int): String {
    val lines = result.textBlocks.flatMap { it.lines }
        .filter { it.boundingBox != null && it.text.isNotBlank() }
    if (lines.isEmpty()) return result.text
    val mid = pageWidth / 2
    val tol = pageWidth / 20
    val leftCol = lines.filter {
        it.boundingBox!!.centerX() < mid && it.boundingBox!!.right <= mid + tol
    }
    val rightCol = lines.filter {
        it.boundingBox!!.centerX() >= mid && it.boundingBox!!.left >= mid - tol
    }
    val spanning = lines - leftCol.toSet() - rightCol.toSet()
    val twoCols = leftCol.size >= 4 && rightCol.size >= 4 && spanning.size <= lines.size * 0.2
    val groups = if (twoCols) {
        val colTop = (leftCol + rightCol).minOf { it.boundingBox!!.top }
        val above = spanning.filter { it.boundingBox!!.top < colTop }
        val below = spanning - above.toSet()
        listOf(above, leftCol, rightCol, below)
    } else listOf(lines)
    return groups.filter { it.isNotEmpty() }.joinToString("\n\n") { compose(rowsOf(it)) }
}

class MainActivity : AppCompatActivity() {
    companion object {
        private const val MAX_SIDE = 2400
        private const val MAX_PDF_PAGES = 40
    }

    private val BG = Color.parseColor("#070D1F")
    private val CARD = Color.parseColor("#0E1730")
    private val STROKE = Color.parseColor("#1E2B52")
    private val CYAN = Color.parseColor("#22D3EE")
    private val AMBER = Color.parseColor("#FFC21A")
    private val TEXT = Color.parseColor("#E8EEFF")
    private val MUTED = Color.parseColor("#8A97BD")
    private val DARK = Color.parseColor("#04121A")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val pool = Executors.newSingleThreadExecutor()
    private val sources = mutableListOf<Uri>()
    private var rotationDeg = 0
    @Volatile private var cancelled = false
    @Volatile private var truncated = false

    private lateinit var preview: ImageView
    private lateinit var emptyHint: TextView
    private lateinit var output: EditText
    private lateinit var status: TextView
    private lateinit var busy: View
    private lateinit var busyText: TextView
    private lateinit var splash: View
    private lateinit var enhanceBox: CheckBox
    private lateinit var rotateBtn: MaterialButton
    private var cameraUri: Uri? = null

    private val pickPhotos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> setSources(uris) }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> setSources(uris) }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) cameraUri?.let { setSources(listOf(it)) } }

    private val saveFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use {
                it.write(output.text.toString().toByteArray())
            }
            toast("Saved")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun filledButton(
        label: String, color: Int = CYAN, textColor: Int = DARK, onClick: () -> Unit
    ) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(textColor)
        backgroundTintList = ColorStateList.valueOf(color)
        cornerRadius = dp(14)
        minimumHeight = dp(52)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { onClick() }
    }

    private fun outlineButton(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(TEXT)
            strokeColor = ColorStateList.valueOf(CYAN)
            strokeWidth = dp(1)
            cornerRadius = dp(14)
            minimumHeight = dp(52)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }

    private fun card(child: View) = MaterialCardView(this).apply {
        radius = dp(20).toFloat()
        setCardBackgroundColor(CARD)
        strokeColor = STROKE
        strokeWidth = dp(1)
        cardElevation = 0f
        addView(child)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) marginStart = dp(10)
            })
        }
    }

    private fun LinearLayout.put(v: View, top: Int = 0) = addView(
        v, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val logoCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(BG)
            addView(ImageView(context).apply { setImageResource(R.drawable.app_logo) },
                FrameLayout.LayoutParams(dp(52), dp(52)))
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Text Extractor", 22f, TEXT, true))
            addView(tv("Offline and private", 13f, MUTED))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logoCard)
            addView(titles, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(14) })
        }

        preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(260)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        emptyHint = tv("Your photo appears here", 14f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(48), 0, dp(48))
        }
        val previewBox = FrameLayout(this).apply {
            addView(emptyHint, FrameLayout.LayoutParams(MATCH, WRAP))
            addView(preview, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        status = tv("Choose photos, take one, or open a PDF.", 14f, MUTED)

        enhanceBox = CheckBox(this).apply {
            text = "Enhance contrast"
            isChecked = true
            textSize = 14f
            setTextColor(TEXT)
            buttonTintList = ColorStateList.valueOf(CYAN)
        }
        rotateBtn = outlineButton("Rotate 0°") { rotateNext() }

        output = EditText(this).apply {
            background = null
            setPadding(dp(16), dp(16), dp(16), dp(16))
            minLines = 8
            gravity = Gravity.TOP
            textSize = 16f
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            hint = "Extracted text appears here. You can edit it."
            setLineSpacing(0f, 1.2f)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
            put(header)
            put(row(
                filledButton("Gallery") {
                    pickPhotos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                outlineButton("Camera") { openCamera() },
                outlineButton("Files") { pickFiles.launch(arrayOf("application/pdf", "image/*")) }
            ), 20)
            put(card(previewBox), 16)
            put(status, 12)
            put(row(enhanceBox, rotateBtn), 8)
            put(filledButton("Extract text", AMBER) { extract() }, 12)
            put(card(output), 16)
            put(row(
                filledButton("Copy") { copyText() },
                outlineButton("Share") { shareText() },
                outlineButton("Save") { saveFile.launch("extracted-text.txt") }
            ), 16)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            setBackgroundColor(BG)
            addView(col)
        }

        busyText = tv("Reading text…", 15f, TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(20))
        }
        busy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#D9070D1F"))
            isClickable = true
            visibility = View.GONE
            addView(CircularProgressIndicator(context).apply {
                isIndeterminate = true
                setIndicatorColor(CYAN)
            })
            addView(busyText)
            addView(outlineButton("Cancel") {
                cancelled = true
                busyText.text = "Cancelling…"
            }, LinearLayout.LayoutParams(dp(140), WRAP))
        }

        splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            isClickable = true
            addView(MaterialCardView(context).apply {
                radius = dp(54).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(Color.TRANSPARENT)
                addView(ImageView(context).apply { setImageResource(R.drawable.app_logo) },
                    FrameLayout.LayoutParams(dp(240), dp(240)))
            }, LinearLayout.LayoutParams(dp(240), dp(240)))
            addView(tv("Text Extractor", 24f, TEXT, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
            })
            addView(tv("Turn any photo into text", 14f, MUTED).apply {
                gravity = Gravity.CENTER
            })
            addView(LinearProgressIndicator(context).apply {
                isIndeterminate = true
                setIndicatorColor(CYAN)
                trackColor = STROKE
            }, LinearLayout.LayoutParams(dp(180), WRAP).apply { topMargin = dp(28) })
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(busy, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setContentView(root)

        if (savedInstanceState != null) {
            splash.visibility = View.GONE
        } else {
            splash.postDelayed({
                splash.animate().alpha(0f).setDuration(400)
                    .withEndAction { splash.visibility = View.GONE }
                    .start()
            }, 1600)
            handleShare(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelled = true
        pool.shutdownNow()
    }

    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> return
        }
        setSources(uris)
    }

    private fun isPdf(uri: Uri) =
        contentResolver.getType(uri) == "application/pdf" ||
            uri.toString().lowercase().endsWith(".pdf")

    private fun setSources(uris: List<Uri>) {
        if (uris.isEmpty()) return
        sources.clear()
        sources.addAll(uris)
        rotationDeg = 0
        preview.rotation = 0f
        rotateBtn.text = "Rotate 0°"
        val first = uris[0]
        if (isPdf(first)) {
            preview.visibility = View.GONE
            emptyHint.visibility = View.VISIBLE
            emptyHint.text = "PDF document"
        } else {
            emptyHint.visibility = View.GONE
            preview.visibility = View.VISIBLE
            preview.setImageURI(first)
        }
        status.text = "${uris.size} file(s) ready. Tap Extract text."
    }

    private fun rotateNext() {
        rotationDeg = (rotationDeg + 90) % 360
        preview.rotation = rotationDeg.toFloat()
        rotateBtn.text = "Rotate ${rotationDeg}°"
    }

    private fun showBusy(on: Boolean, msg: String = "Reading text…") {
        busyText.text = msg
        busy.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun updateBusy(msg: String) = runOnUiThread { busyText.text = msg }

    private fun openCamera() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "scan_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        cameraUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        cameraUri?.let { takePhoto.launch(it) }
    }

    private fun loadBitmap(uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
            if (s < 1f) {
                decoder.setTargetSize(
                    (info.size.width * s).toInt(), (info.size.height * s).toInt()
                )
            }
        }

    private fun enhance(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val s = 1.35f
        val t = (-0.5f * s + 0.5f) * 255f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    s, 0f, 0f, 0f, t,
                    0f, s, 0f, 0f, t,
                    0f, 0f, s, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun forEachPage(uri: Uri, block: (Bitmap) -> Unit) {
        if (!isPdf(uri)) {
            block(loadBitmap(uri))
            return
        }
        val fd = contentResolver.openFileDescriptor(uri, "r") ?: return
        try {
            val renderer = PdfRenderer(fd)
            try {
                if (renderer.pageCount > MAX_PDF_PAGES) truncated = true
                for (i in 0 until minOf(renderer.pageCount, MAX_PDF_PAGES)) {
                    if (cancelled) return
                    val page = renderer.openPage(i)
                    val bmp: Bitmap
                    try {
                        val w = 1800
                        val h = (page.height * (w.toFloat() / page.width)).toInt()
                        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        page.close()
                    }
                    block(bmp)
                }
            } finally {
                renderer.close()
            }
        } finally {
            fd.close()
        }
    }

    private fun extract() {
        if (sources.isEmpty()) {
            status.text = "Choose a photo or document first."
            return
        }
        val list = sources.toList()
        val enhanceOn = enhanceBox.isChecked
        val rot = rotationDeg
        cancelled = false
        truncated = false
        status.text = "Reading…"
        showBusy(true, "Getting ready…")
        pool.execute {
            val pages = mutableListOf<String>()
            try {
                for (uri in list) {
                    if (cancelled) break
                    forEachPage(uri) { bmp ->
                        updateBusy("Reading page ${pages.size + 1}…")
                        val work = if (enhanceOn) enhance(bmp) else bmp
                        try {
                            val result = Tasks.await(
                                recognizer.process(InputImage.fromBitmap(work, rot))
                            )
                            val w = if (rot % 180 == 0) work.width else work.height
                            pages.add(structure(result, w))
                        } finally {
                            if (work !== bmp) work.recycle()
                            bmp.recycle()
                        }
                    }
                }
                val text = if (pages.size > 1) {
                    pages.mapIndexed { i, p -> "── Page ${i + 1} ──\n\n$p" }
                        .joinToString("\n\n")
                } else pages.firstOrNull().orEmpty()
                runOnUiThread {
                    showBusy(false)
                    if (cancelled) {
                        status.text = "Cancelled."
                        return@runOnUiThread
                    }
                    output.setText(text)
                    status.text = if (text.isBlank()) {
                        "No text found. Try a sharper, well-lit photo."
                    } else {
                        "Done: ${pages.size} page(s) read." +
                            (if (truncated) " Only the first $MAX_PDF_PAGES PDF pages were read." else "") +
                            " You can edit the text below."
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    showBusy(false)
                    status.text = "Could not read the file: ${e.message}"
                }
            }
        }
    }

    private fun copyText() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", output.text))
        toast("Copied")
    }

    private fun shareText() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, output.text.toString())
        }
        startActivity(Intent.createChooser(send, "Share text"))
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}