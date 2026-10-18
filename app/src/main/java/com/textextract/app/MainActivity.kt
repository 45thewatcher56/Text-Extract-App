package com.textextract.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
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
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val LOW_CONF = 0.80f

class LineData(val text: String, val rect: Rect, val confidence: Float)

class PageData(val jpeg: ByteArray, val width: Int, val height: Int, val lines: List<LineData>)

class LowConfSpan : BackgroundColorSpan(Color.argb(95, 255, 194, 26))

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
private fun linesOf(result: Text): List<LineData> =
    result.textBlocks.flatMap { it.lines }
        .filter { it.boundingBox != null && it.text.isNotBlank() }
        .map { LineData(it.text.trim(), Rect(it.boundingBox!!), it.confidence) }

private fun medianAngle(result: Text): Float? {
    val angles = result.textBlocks.flatMap { it.lines }
        .filter { it.text.trim().length >= 4 }
        .map {
            var d = it.angle
            while (d > 90f) d -= 180f
            while (d < -90f) d += 180f
            d
        }
        .sorted()
    return if (angles.size < 3) null else angles[angles.size / 2]
}

private fun rotateOnWhite(src: Bitmap, deg: Float): Bitmap {
    val rad = Math.toRadians(abs(deg).toDouble())
    val w = (src.width * cos(rad) + src.height * sin(rad)).toInt()
    val h = (src.width * sin(rad) + src.height * cos(rad)).toInt()
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.eraseColor(Color.WHITE)
    val m = Matrix()
    m.postTranslate(-src.width / 2f, -src.height / 2f)
    m.postRotate(deg)
    m.postTranslate(w / 2f, h / 2f)
    Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

private fun rotate90(src: Bitmap, deg: Int): Bitmap {
    val m = Matrix().apply { postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

private fun cropBitmap(b: Bitmap, f: RectF): Bitmap {
    val x = (f.left * b.width).toInt().coerceIn(0, b.width - 1)
    val y = (f.top * b.height).toInt().coerceIn(0, b.height - 1)
    val w = ((f.right - f.left) * b.width).toInt().coerceIn(1, b.width - x)
    val h = ((f.bottom - f.top) * b.height).toInt().coerceIn(1, b.height - y)
    return Bitmap.createBitmap(b, x, y, w, h)
}

private fun toJpeg(b: Bitmap): ByteArray {
    val bos = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, 80, bos)
    return bos.toByteArray()
}

private fun cleanScan(input: Bitmap): Bitmap {
    var src = input
    val big = maxOf(src.width, src.height)
    if (big > 2000) {
        val s = 2000f / big
        src = Bitmap.createScaledBitmap(
            src, (src.width * s).toInt(), (src.height * s).toInt(), true
        )
    }
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    if (src !== input) src.recycle()
    val gray = ByteArray(w * h)
    for (i in px.indices) {
        val c = px[i]
        gray[i] = ((299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) +
            114 * (c and 255)) / 1000).toByte()
    }
    val stride = w + 1
    val integral = IntArray(stride * (h + 1))
    for (y in 0 until h) {
        var rowSum = 0
        for (x in 0 until w) {
            rowSum += gray[y * w + x].toInt() and 255
            integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
        }
    }
    val r = maxOf(w, h) / 16
    for (y in 0 until h) {
        val y0 = maxOf(0, y - r)
        val y1 = minOf(h, y + r + 1)
        for (x in 0 until w) {
            val x0 = maxOf(0, x - r)
            val x1 = minOf(w, x + r + 1)
            val area = (x1 - x0) * (y1 - y0)
            val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] -
                integral[y1 * stride + x0] + integral[y0 * stride + x0]
            val mean = maxOf(sum / area, 1)
            val g = gray[y * w + x].toInt() and 255
            val v = minOf(255, g * 240 / mean)
            px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

private fun pdfSafe(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        sb.append(
            when {
                c in ' '..'~' -> c
                c.code in 160..255 -> c
                c == '‘' || c == '’' -> '\''
                c == '“' || c == '”' -> '"'
                c == '–' || c == '—' -> '-'
                c == '•' -> '*'
                else -> '?'
            }
        )
    }
    return sb.toString()
}

private fun pdfEscape(s: String) =
    s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

private fun fmt(d: Double) = String.format(Locale.US, "%.2f", d)

// Writes a PDF by hand: each page is the scanned photo with an invisible text layer on top.
private fun buildPdf(pages: List<PageData>): ByteArray {
    val out = ByteArrayOutputStream()
    val offsets = ArrayList<Int>()
    fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
    fun begin(num: Int) {
        offsets.add(out.size())
        w("$num 0 obj\n")
    }
    val n = pages.size
    w("%PDF-1.4\n")
    begin(1)
    w("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    begin(2)
    w("<< /Type /Pages /Kids [" + (0 until n).joinToString(" ") { "${6 + 3 * it} 0 R" } +
        "] /Count $n >>\nendobj\n")
    begin(3)
    w("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n")
    val measure = Paint().apply {
        typeface = Typeface.SANS_SERIF
        textSize = 100f
    }
    for ((i, p) in pages.withIndex()) {
        val pw = 595.0
        val ph = pw * p.height / p.width
        val f = pw / p.width
        begin(4 + 3 * i)
        w("<< /Type /XObject /Subtype /Image /Width ${p.width} /Height ${p.height} " +
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
            "/Length ${p.jpeg.size} >>\nstream\n")
        out.write(p.jpeg)
        w("\nendstream\nendobj\n")

        val sb = StringBuilder()
        sb.append("q ${fmt(pw)} 0 0 ${fmt(ph)} 0 0 cm /Im0 Do Q\n3 Tr\n")
        for (l in p.lines) {
            val t = pdfSafe(l.text)
            if (t.isBlank()) continue
            val hPt = l.rect.height() * f
            val size = maxOf(hPt * 0.8, 4.0)
            val measured = measure.measureText(t) * size / 100.0
            val tz = (100.0 * (l.rect.width() * f) / maxOf(measured, 1.0)).coerceIn(10.0, 400.0)
            val x = l.rect.left * f
            val y = ph - (l.rect.bottom - l.rect.height() * 0.22) * f
            sb.append("BT /F1 ${fmt(size)} Tf ${fmt(tz)} Tz 1 0 0 1 ${fmt(x)} ${fmt(y)} Tm " +
                "(${pdfEscape(t)}) Tj ET\n")
        }
        val content = sb.toString().toByteArray(Charsets.ISO_8859_1)
        begin(5 + 3 * i)
        w("<< /Length ${content.size} >>\nstream\n")
        out.write(content)
        w("endstream\nendobj\n")

        begin(6 + 3 * i)
        w("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pw)} ${fmt(ph)}] " +
            "/Resources << /XObject << /Im0 ${4 + 3 * i} 0 R >> /Font << /F1 3 0 R >> >> " +
            "/Contents ${5 + 3 * i} 0 R >>\nendobj\n")
    }
    val xref = out.size()
    val total = offsets.size + 1
    w("xref\n0 $total\n0000000000 65535 f \n")
    for (o in offsets) w(String.format(Locale.US, "%010d 00000 n \n", o))
    w("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toByteArray()
}

// Shows a scanned page with a box around every line. Tap a line to copy it; pinch to zoom.
class OverlayView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private var lines: List<LineData> = emptyList()
    private var selected: LineData? = null
    var onTap: ((LineData) -> Unit)? = null
    private val m = Matrix()
    private val inv = Matrix()
    private var minScale = 1f
    private val density = resources.displayMetrics.density
    private val okStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#22D3EE")
    }
    private val okFill = Paint().apply { color = Color.argb(28, 34, 211, 238) }
    private val lowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFC21A")
    }
    private val lowFill = Paint().apply { color = Color.argb(70, 255, 194, 26) }
    private val selFill = Paint().apply { color = Color.argb(110, 255, 255, 255) }

    private val scaleDetector = ScaleGestureDetector(
        ctx,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cur = scaleNow()
                val target = (cur * d.scaleFactor).coerceIn(minScale, minScale * 6f)
                val f = target / cur
                m.postScale(f, f, d.focusX, d.focusY)
                clamp()
                invalidate()
                return true
            }
        }
    )
private val gestures = GestureDetector(
        ctx,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                m.postTranslate(-distanceX, -distanceY)
                clamp()
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapAt(e.x, e.y)
                return true
            }
        }
    )

    init {
        setBackgroundColor(Color.parseColor("#070D1F"))
    }

    fun setPage(b: Bitmap, l: List<LineData>) {
        bmp = b
        lines = l
        selected = null
        fit()
        invalidate()
    }

    private fun scaleNow(): Float {
        val v = FloatArray(9)
        m.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun fit() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        minScale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        m.reset()
        m.postScale(minScale, minScale)
        m.postTranslate((width - b.width * minScale) / 2f, (height - b.height * minScale) / 2f)
    }

    private fun clamp() {
        val b = bmp ?: return
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else {
            if (r.left > 0) dx = -r.left
            if (r.right < width) dx = width - r.right
        }
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else {
            if (r.top > 0) dy = -r.top
            if (r.bottom < height) dy = height - r.bottom
        }
        m.postTranslate(dx, dy)
    }

    private fun tapAt(x: Float, y: Float) {
        if (!m.invert(inv)) return
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        val pad = 6f * density / scaleNow()
        val hit = lines.filter {
            val r = RectF(it.rect)
            r.inset(-pad, -pad)
            r.contains(p[0], p[1])
        }.minByOrNull { it.rect.width().toLong() * it.rect.height() }
        if (hit != null) {
            selected = hit
            invalidate()
            onTap?.invoke(hit)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fit()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        c.save()
        c.concat(m)
        c.drawBitmap(b, 0f, 0f, null)
        val sw = 2f * density / scaleNow()
        okStroke.strokeWidth = sw
        lowStroke.strokeWidth = sw * 1.4f
        for (l in lines) {
            val low = l.confidence > 0f && l.confidence < LOW_CONF
            val r = RectF(l.rect)
            c.drawRect(r, if (low) lowFill else okFill)
            c.drawRect(r, if (low) lowStroke else okStroke)
            if (l === selected) c.drawRect(r, selFill)
        }
        c.restore()
    }
}

// Lets you drag a crop box over an image.
class CropView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val box = RectF()
    private val dest = RectF()
    private var scale = 1f
    private var drag = 0
    private var lastX = 0f
    private var lastY = 0f
    private val density = resources.displayMetrics.density
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#22D3EE")
        strokeWidth = 3f * density
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22D3EE") }

    init {
        setBackgroundColor(Color.parseColor("#070D1F"))
    }

    fun setImage(b: Bitmap, f: RectF?) {
        bmp = b
        if (f != null) box.set(f.left * b.width, f.top * b.height, f.right * b.width, f.bottom * b.height)
        else box.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
        layoutDest()
        invalidate()
    }

    fun clear() {
        bmp = null
        invalidate()
    }

    fun reset() {
        val b = bmp ?: return
        box.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
        invalidate()
    }

    fun fractions(): RectF {
        val b = bmp ?: return RectF(0f, 0f, 1f, 1f)
        return RectF(box.left / b.width, box.top / b.height, box.right / b.width, box.bottom / b.height)
    }

    private fun layoutDest() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        dest.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private fun boxView() = RectF(
        dest.left + box.left * scale, dest.top + box.top * scale,
        dest.left + box.right * scale, dest.top + box.bottom * scale
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutDest()
        invalidate()
    }
override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        c.drawBitmap(b, null, dest, null)
        val r = boxView()
        c.drawRect(0f, 0f, width.toFloat(), r.top, dim)
        c.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), dim)
        c.drawRect(0f, r.top, r.left, r.bottom, dim)
        c.drawRect(r.right, r.top, width.toFloat(), r.bottom, dim)
        c.drawRect(r, edge)
        val k = 10f * density
        c.drawCircle(r.left, r.top, k, knob)
        c.drawCircle(r.right, r.top, k, knob)
        c.drawCircle(r.left, r.bottom, k, knob)
        c.drawCircle(r.right, r.bottom, k, knob)
    }

    private fun near(x: Float, y: Float, px: Float, py: Float, hit: Float) =
        hypot(x - px, y - py) < hit

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bmp ?: return true
        val r = boxView()
        val hit = 36f * density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = when {
                    near(e.x, e.y, r.left, r.top, hit) -> 2
                    near(e.x, e.y, r.right, r.top, hit) -> 3
                    near(e.x, e.y, r.left, r.bottom, hit) -> 4
                    near(e.x, e.y, r.right, r.bottom, hit) -> 5
                    r.contains(e.x, e.y) -> 1
                    else -> 0
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> if (drag != 0) {
                val dx = (e.x - lastX) / scale
                val dy = (e.y - lastY) / scale
                val minS = minOf(80f, minOf(b.width, b.height) / 4f)
                val bw = b.width.toFloat()
                val bh = b.height.toFloat()
                when (drag) {
                    1 -> {
                        val w = box.width()
                        val h = box.height()
                        val l = (box.left + dx).coerceIn(0f, bw - w)
                        val t = (box.top + dy).coerceIn(0f, bh - h)
                        box.set(l, t, l + w, t + h)
                    }
                    2 -> {
                        box.left = (box.left + dx).coerceIn(0f, box.right - minS)
                        box.top = (box.top + dy).coerceIn(0f, box.bottom - minS)
                    }
                    3 -> {
                        box.right = (box.right + dx).coerceIn(box.left + minS, bw)
                        box.top = (box.top + dy).coerceIn(0f, box.bottom - minS)
                    }
                    4 -> {
                        box.left = (box.left + dx).coerceIn(0f, box.right - minS)
                        box.bottom = (box.bottom + dy).coerceIn(box.top + minS, bh)
                    }
                    5 -> {
                        box.right = (box.right + dx).coerceIn(box.left + minS, bw)
                        box.bottom = (box.bottom + dy).coerceIn(box.top + minS, bh)
                    }
                }
                lastX = e.x
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = 0
        }
        return true
    }
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
    private val crops = mutableMapOf<Uri, RectF>()
    private val pageStore = mutableListOf<PageData>()
    private var rotationDeg = 0
    private var applying = false
    private var reviewIdx = 0
    private var reviewBmp: Bitmap? = null
    private var cropList: List<Uri> = emptyList()
    private var cropIdx = 0
    private var cropBmp: Bitmap? = null
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
    private lateinit var straightenBox: CheckBox
    private lateinit var cleanBox: CheckBox
    private lateinit var rotateBtn: MaterialButton
    private lateinit var pageOverlay: OverlayView
    private lateinit var cropView: CropView
    private lateinit var reviewLayer: View
    private lateinit var cropLayer: View
    private lateinit var reviewTitle: TextView
    private lateinit var cropTitle: TextView
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

    private val savePdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) writePdf(uri) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
private fun checkBox(label: String, checked: Boolean) = CheckBox(this).apply {
        text = label
        isChecked = checked
        textSize = 14f
        setTextColor(TEXT)
        buttonTintList = ColorStateList.valueOf(CYAN)
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

        enhanceBox = checkBox("Enhance contrast", true)
        straightenBox = checkBox("Auto-straighten", true)
        cleanBox = checkBox("Clean scan", false)
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
        output.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (applying || s !is Spannable) return
                val end = start + maxOf(before, count)
                for (sp in s.getSpans(start, end, LowConfSpan::class.java)) s.removeSpan(sp)
            }
        })

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
            put(row(enhanceBox, straightenBox), 8)
            put(row(cleanBox, rotateBtn), 4)
            put(tv("Clean scan evens out shadows and faded paper. It is slower.", 12f, MUTED), 2)
            put(row(outlineButton("Crop") { openCrop() }, outlineButton("Review pages") { openReview() }), 12)
            put(filledButton("Extract text", AMBER) { extract() }, 10)
            put(card(output), 16)
            put(row(
                filledButton("Copy") { copyText() },
                outlineButton("Share") { shareText() }
            ), 16)
            put(row(
                outlineButton("Save TXT") { saveFile.launch("extracted-text.txt") },
                outlineButton("Save PDF") {
                    if (pageStore.isEmpty()) toast("Extract text first.")
                    else savePdf.launch("scan.pdf")
                }
            ), 10)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            setBackgroundColor(BG)
            addView(col)
        }

        // Review screen: page image with a box around each line
        pageOverlay = OverlayView(this).apply { onTap = { copyLine(it) } }
        reviewTitle = tv("", 16f, TEXT, true)
        reviewLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
            isClickable = true
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            put(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(reviewTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(outlineButton("Close") { hideReview() },
                    LinearLayout.LayoutParams(dp(110), WRAP))
            })
            put(tv("Tap a line to copy it. Pinch to zoom. Amber means uncertain.", 12f, MUTED), 6)
            addView(pageOverlay, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
            put(row(
                outlineButton("Previous") { stepReview(-1) },
                outlineButton("Next") { stepReview(1) }
            ))
        }

        // Crop screen
        cropView = CropView(this)
        cropTitle = tv("", 16f, TEXT, true)
        cropLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
            isClickable = true
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            put(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(cropTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(filledButton("Done") { finishCrop() },
                    LinearLayout.LayoutParams(dp(110), WRAP))
            })
            put(tv("Drag the corners or move the box, then tap Done.", 12f, MUTED), 6)
            addView(cropView, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
            put(row(
                outlineButton("Reset") { cropView.reset() },
                outlineButton("Previous") { stepCrop(-1) },
                outlineButton("Next") { stepCrop(1) }
            ))
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
            addView(reviewLayer, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(cropLayer, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(busy, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    reviewLayer.visibility == View.VISIBLE -> hideReview()
                    cropLayer.visibility == View.VISIBLE -> finishCrop()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

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
        crops.clear()
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

    // ---------- Crop ----------
private fun openCrop() {
        val imgs = sources.filter { !isPdf(it) }
        if (imgs.isEmpty()) {
            toast("Choose a photo first. PDF pages cannot be cropped.")
            return
        }
        cropList = imgs
        cropIdx = 0
        cropLayer.visibility = View.VISIBLE
        showCropImage()
    }

    private fun showCropImage() {
        val uri = cropList[cropIdx]
        try {
            val b = loadBitmap(uri)
            val old = cropBmp
            cropBmp = b
            cropView.setImage(b, crops[uri])
            old?.recycle()
            cropTitle.text = "Crop image ${cropIdx + 1} of ${cropList.size}"
        } catch (e: Exception) {
            toast("Could not open the image.")
        }
    }

    private fun saveCurrentCrop() {
        val uri = cropList.getOrNull(cropIdx) ?: return
        val f = cropView.fractions()
        val full = f.left <= 0.001f && f.top <= 0.001f && f.right >= 0.999f && f.bottom >= 0.999f
        if (full) crops.remove(uri) else crops[uri] = f
    }

    private fun stepCrop(d: Int) {
        val n = cropIdx + d
        if (n !in cropList.indices) return
        saveCurrentCrop()
        cropIdx = n
        showCropImage()
    }

    private fun finishCrop() {
        saveCurrentCrop()
        cropLayer.visibility = View.GONE
        cropView.clear()
        cropBmp?.recycle()
        cropBmp = null
        refreshPreview()
        status.text = if (crops.isEmpty()) "No crop set."
        else "Crop set for ${crops.size} image(s). Tap Extract text."
    }

    private fun refreshPreview() {
        val first = sources.firstOrNull() ?: return
        if (isPdf(first)) return
        try {
            val raw = loadBitmap(first)
            val f = crops[first]
            val shown = if (f != null) cropBitmap(raw, f) else raw
            if (shown !== raw) raw.recycle()
            preview.setImageBitmap(shown)
        } catch (e: Exception) {
            // keep the old preview
        }
    }

    // ---------- Review (tap to copy) ----------

    private fun openReview() {
        if (pageStore.isEmpty()) {
            toast("Extract text first.")
            return
        }
        reviewIdx = 0
        reviewLayer.visibility = View.VISIBLE
        showReviewPage()
    }

    private fun showReviewPage() {
        val p = pageStore[reviewIdx]
        val b = BitmapFactory.decodeByteArray(p.jpeg, 0, p.jpeg.size)
        val old = reviewBmp
        reviewBmp = b
        pageOverlay.setPage(b, p.lines)
        old?.recycle()
        reviewTitle.text = "Page ${reviewIdx + 1} of ${pageStore.size}"
    }

    private fun stepReview(d: Int) {
        val n = reviewIdx + d
        if (n !in pageStore.indices) return
        reviewIdx = n
        showReviewPage()
    }

    private fun hideReview() {
        reviewLayer.visibility = View.GONE
    }

    private fun copyLine(l: LineData) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("line", l.text))
        toast("Copied: " + l.text.take(40))
    }

    // ---------- Reading ----------

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

    private fun forEachPage(uri: Uri, crop: RectF?, block: (Bitmap) -> Unit) {
        if (!isPdf(uri)) {
            val raw = loadBitmap(uri)
            val shown = if (crop != null) cropBitmap(raw, crop) else raw
            if (shown !== raw) raw.recycle()
            block(shown)
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

    private fun showResult(text: String): Int {
        var count = 0
        val sp = SpannableStringBuilder(text)
        var cursor = 0
        for (p in pageStore) for (l in p.lines) {
            if (!(l.confidence > 0f && l.confidence < LOW_CONF)) continue
            val s = l.text.trim().replace(bulletRegex, "").replace(numberRegex, "").trim()
            if (s.length < 3) continue
            var idx = text.indexOf(s, cursor)
            if (idx < 0) idx = text.indexOf(s)
            if (idx < 0) continue
            sp.setSpan(LowConfSpan(), idx, idx + s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = idx + s.length
            count++
        }
        applying = true
        output.setText(sp)
        applying = false
        return count
    }

    private fun extract() {
        if (sources.isEmpty()) {
            status.text = "Choose a photo or document first."
            return
        }
        val list = sources.toList()
        val snapshot = crops.toMap()
        val enhanceOn = enhanceBox.isChecked
        val straightenOn = straightenBox.isChecked
        val cleanOn = cleanBox.isChecked
        val rot = rotationDeg
        cancelled = false
        truncated = false
        status.text = "Reading…"
        showBusy(true, "Getting ready…")
        pool.execute {
            val pages = mutableListOf<String>()
            val data = mutableListOf<PageData>()
            var straightened = 0
            try {
                for (uri in list) {
                    if (cancelled) break
                    forEachPage(uri, snapshot[uri]) { bmp ->
                        if (cancelled) {
                            bmp.recycle()
                            return@forEachPage
                        }
                        val n = pages.size + 1
                        updateBusy("Reading page $n…")
                        val toFree = mutableSetOf<Bitmap>(bmp)
                        try {
                            val prepared = when {
                                cleanOn -> cleanScan(bmp)
                                enhanceOn -> enhance(bmp)
                                else -> bmp
                            }
                            toFree.add(prepared)
                            val upright = if (rot != 0) rotate90(prepared, rot) else prepared
                            toFree.add(upright)
                            var pageBmp = upright
                            var result = Tasks.await(
                                recognizer.process(InputImage.fromBitmap(upright, 0))
                            )
                            if (straightenOn && !cancelled) {
                                val a1 = medianAngle(result)
                                if (a1 != null && abs(a1) in 1.5f..20f) {
                                    updateBusy("Straightening page $n…")
                                    for (sign in listOf(-1f, 1f)) {
                                        val fixed = rotateOnWhite(upright, sign * a1)
                                        val r2 = Tasks.await(
                                            recognizer.process(InputImage.fromBitmap(fixed, 0))
                                        )
                                        val a2 = medianAngle(r2)
                                        if (a2 != null && abs(a2) < abs(a1) * 0.6f) {
                                            result = r2
                                            pageBmp = fixed
                                            toFree.add(fixed)
                                            straightened++
                                            break
                                        } else {
                                            fixed.recycle()
                                        }
                                    }
                                }
                            }
                            pages.add(structure(result, pageBmp.width))
                            data.add(
                                PageData(toJpeg(pageBmp), pageBmp.width, pageBmp.height, linesOf(result))
                            )
                        } finally {
                            toFree.forEach { if (!it.isRecycled) it.recycle() }
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
                    pageStore.clear()
                    pageStore.addAll(data)
                    val low = showResult(text)
                    status.text = if (text.isBlank()) {
                        "No text found. Try a sharper, well-lit photo."
                    } else {
                        "Done: ${pages.size} page(s) read." +
                            (if (straightened > 0) " Straightened $straightened page(s)." else "") +
                            (if (low > 0) " $low uncertain line(s) are highlighted in amber." else "") +
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

    private fun writePdf(uri: Uri) {
        val snapshot = pageStore.toList()
        showBusy(true, "Building PDF…")
        pool.execute {
            try {
                val bytes = buildPdf(snapshot)
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                runOnUiThread {
                    showBusy(false)
                    toast("PDF saved")
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    showBusy(false)
                    status.text = "Could not save the PDF: ${e.message}"
                }
            }
        }
    }

    private fun copyText() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", output.text.toString()))
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