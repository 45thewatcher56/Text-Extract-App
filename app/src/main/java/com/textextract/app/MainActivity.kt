package com.textextract.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity() {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private lateinit var preview: ImageView
    private lateinit var output: EditText
    private lateinit var status: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = pad * 15
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        status = TextView(this).apply {
            text = "Choose or take a photo of printed text."
            setPadding(0, pad / 2, 0, pad / 2)
        }
        output = EditText(this).apply {
            minLines = 8
            gravity = Gravity.TOP
            hint = "Extracted text appears here"
            typeface = Typeface.SERIF
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(button("Choose photo") {
                pickPhoto.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            })
            addView(button("Take photo") { openCamera() })
            addView(preview)
            addView(status)
            addView(output)
            addView(button("Copy text") { copyText() })
            addView(button("Share") { shareText() })
            addView(button("Save as TXT") { saveFile.launch("extracted-text.txt") })
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
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
        preview.setImageURI(uri)
        status.text = "Reading text…"
        try {
            val image = InputImage.fromFilePath(this, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    output.setText(result.text)
                    status.text = if (result.text.isBlank())
                        "No text found. Try a sharper, well-lit photo."
                    else "Done. You can edit the text below."
                }
                .addOnFailureListener { e ->
                    status.text = "Could not read the photo: ${e.message}"
                }
        } catch (e: Exception) {
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