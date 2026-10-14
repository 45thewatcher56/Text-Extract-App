package com.textextract.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity() {
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
    private lateinit var preview: ImageView
    private lateinit var emptyHint: TextView
    private lateinit var output: EditText
    private lateinit var status: TextView
    private lateinit var busy: View
    private lateinit var busyText: TextView
    private lateinit var splash: View
    private var cameraUri: Uri? = null

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) read(uri) }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) cameraUri?.let { read(it) } }

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

        // Header
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

        // Preview card
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

        status = tv("Choose a photo or take one to begin.", 14f, MUTED)

        // Result card
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
                    pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                outlineButton("Camera") { openCamera() }
            ), 20)
            put(card(previewBox), 16)
            put(status, 12)
            put(card(output), 12)
            put(row(
                filledButton("Copy", AMBER) { copyText() },
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

        // Busy overlay (shown while reading a photo)
        busyText = tv("Reading text…", 15f, TEXT).apply { setPadding(0, dp(16), 0, 0) }
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
        }

        // Loading screen (shown when the app starts)
        splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            isClickable = true
            addView(ImageView(context).apply { setImageResource(R.drawable.app_logo) },
                LinearLayout.LayoutParams(dp(240), dp(240)))
            addView(tv("Text Extractor", 24f, TEXT, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(tv("Turn any photo into text", 14f, MUTED))
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
        }
    }

    private fun showBusy(on: Boolean) {
        busy.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun openCamera() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "scan_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        cameraUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        cameraUri?.let { takePhoto.launch(it) }
    }

    private fun read(uri: Uri) {
        emptyHint.visibility = View.GONE
        preview.visibility = View.VISIBLE
        preview.setImageURI(uri)
        status.text = "Reading text…"
        showBusy(true)
        try {
            val image = InputImage.fromFilePath(this, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    showBusy(false)
                    output.setText(result.text)
                    status.text = if (result.text.isBlank())
                        "No text found. Try a sharper, well-lit photo."
                    else "Done. You can edit the text below."
                }
                .addOnFailureListener { e ->
                    showBusy(false)
                    status.text = "Could not read the photo: ${e.message}"
                }
        } catch (e: Exception) {
            showBusy(false)
            status.text = "Could not open the photo: ${e.message}"
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