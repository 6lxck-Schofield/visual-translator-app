package com.schofielddevs.visuallearning

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.text.Selection
import android.text.Spannable
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.example.visusltranslator.OCRTFLite
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.android.Utils.matToBitmap
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint

import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

@Suppress("SameParameterValue")
class MainActivity : AppCompatActivity() {

    // ---------- CONFIG ----------
    private val INPUT_W = 512
    private val INPUT_H = 64
    // ----------------------------

    private lateinit var ocrTFLite: OCRTFLite
    
    private lateinit var previewView: PreviewView
    private lateinit var capturedImageView: ImageView
    private lateinit var cropSelectorView: CropSelectorView
    private lateinit var captureButton: Button
    private lateinit var processButton: Button
    private lateinit var retakeButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageCapture: ImageCapture
    private lateinit var ocrResultText: TextView
    private lateinit var translatedText: TextView

    private var capturedBitmap: Bitmap? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        private const val TAG = "MainActivity"
        init {
            try {
                System.loadLibrary("opencv_java4")
                Log.d(TAG, "✅ OpenCV initialized.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ OpenCV init failed: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        capturedImageView = findViewById(R.id.capturedImageView)
        cropSelectorView = findViewById(R.id.cropSelectorView)
        captureButton = findViewById(R.id.captureButton)
        processButton = findViewById(R.id.processButton)
        retakeButton = findViewById(R.id.retakeButton)
        ocrResultText = findViewById(R.id.ocrResultText)
        translatedText = findViewById(R.id.translatedText)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)

        captureButton.setOnClickListener { takePhoto() }
        processButton.setOnClickListener { processCroppedRegion() }
        retakeButton.setOnClickListener { returnToCamera() }

        // Initialize OCRTFLite
        try {
            ocrTFLite = OCRTFLite(this)
            Log.d(TAG, "✅ OCRTFLite initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to init OCRTFLite: ${e.message}")
        }

        // long-press translation selection
        ocrResultText.setOnLongClickListener {
            val text = ocrResultText.text
            if (text is Spannable) {
                val start = Selection.getSelectionStart(text)
                val end = Selection.getSelectionEnd(text)
                if (start >= 0 && end > start) {
                    val selected = text.subSequence(start, end).toString().trim()
                    if (selected.isNotEmpty()) translateText(selected)
                }
            }
            true
        }
    }

    // ---------- Camera helpers ----------
    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(display?.rotation ?: android.view.Surface.ROTATION_0)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    val fullBitmap = imageProxyToBitmap(image)
                    image.close()

                    if (fullBitmap.width <= 1) {
                        Log.e(TAG, "❌ Invalid bitmap")
                        return
                    }

                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    capturedBitmap = Bitmap.createBitmap(
                        fullBitmap, 0, 0,
                        fullBitmap.width, fullBitmap.height, matrix, true
                    )
                    fullBitmap.recycle()
                    showCropMode()
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "❌ Capture failed: ${exc.message}")
                }
            })
    }

    private fun showCropMode() {
        previewView.visibility = View.GONE
        captureButton.visibility = View.GONE

        capturedImageView.visibility = View.VISIBLE
        cropSelectorView.visibility = View.VISIBLE
        processButton.visibility = View.VISIBLE
        retakeButton.visibility = View.VISIBLE

        capturedImageView.setImageBitmap(capturedBitmap)
        capturedImageView.post {
            cropSelectorView.setImageDimensions(
                capturedImageView.width,
                capturedImageView.height,
                capturedBitmap?.width ?: 1,
                capturedBitmap?.height ?: 1
            )
        }
    }

    private fun returnToCamera() {
        capturedBitmap?.recycle()
        capturedBitmap = null

        previewView.visibility = View.VISIBLE
        captureButton.visibility = View.VISIBLE

        capturedImageView.visibility = View.GONE
        cropSelectorView.visibility = View.GONE
        processButton.visibility = View.GONE
        retakeButton.visibility = View.GONE

        ocrResultText.text = getString(R.string.ocr_result_placeholder)
        translatedText.text = getString(R.string.translation_will_appear_here)
    }

    // ---------- Simplified Processing Pipeline ----------
    private fun processCroppedRegion() {
        Log.d(TAG, "📸 Starting processCroppedRegion()")
        
        val bitmap = capturedBitmap ?: run {
            Log.e(TAG, "❌ capturedBitmap is null")
            return
        }
        
        val cropRect = cropSelectorView.getCropRectOnImage()
        Log.d(TAG, "✂️ Crop rect: ${cropRect.toShortString()}")

        if (cropRect.width() <= 0 || cropRect.height() <= 0) {
            Log.e(TAG, "❌ Invalid crop dimensions: w=${cropRect.width()}, h=${cropRect.height()}")
            return
        }

        val croppedBitmap = Bitmap.createBitmap(
            bitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height()
        )
        Log.d(TAG, "✅ Cropped bitmap created: ${croppedBitmap.width}x${croppedBitmap.height}")

        saveBitmapToStorage(croppedBitmap, "01_cropped_selection.jpg")

        // Convert to grayscale Mat
        Log.d(TAG, "🎨 Converting to grayscale Mat")
        val mat = Mat()
        Utils.bitmapToMat(croppedBitmap, mat)
        Log.d(TAG, "📐 Mat created: ${mat.cols()}x${mat.rows()}, channels=${mat.channels()}")
        
        val gray = Mat()
        when (mat.channels()) {
            4 -> {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                Log.d(TAG, "🔄 Converted RGBA to GRAY")
            }
            3 -> {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
                Log.d(TAG, "🔄 Converted BGR to GRAY")
            }
            else -> {
                mat.copyTo(gray)
                Log.d(TAG, "📋 Copied Mat as-is (already grayscale)")
            }
        }
        mat.release()

        // Step 1: Smart Deskew
        Log.d(TAG, "📏 Starting smart deskew")
        val deskewed = smartDeskewImage(gray)
        Log.d(TAG, "✅ Deskew complete: ${deskewed.cols()}x${deskewed.rows()}")
        saveMatToStorage(deskewed, "02_deskewed.jpg")

        // Step 2: Resize to model input size
        Log.d(TAG, "📐 Resizing to ${INPUT_W}x${INPUT_H}")
        val resized = Mat()
        Imgproc.resize(deskewed, resized, Size(INPUT_W.toDouble(), INPUT_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Log.d(TAG, "✅ Resize complete: ${resized.cols()}x${resized.rows()}")
        saveMatToStorage(resized, "03_resized.jpg")

        // Step 3: Contrast enhancement (CLAHE)
        Log.d(TAG, "✨ Applying CLAHE contrast enhancement")
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(resized, enhanced)
        Log.d(TAG, "✅ CLAHE complete")
        saveMatToStorage(enhanced, "04_enhanced.jpg")

        // Convert to bitmap for TFLite
        Log.d(TAG, "🖼️ Converting enhanced Mat to Bitmap for TFLite")
        val processedBitmap = createBitmap(enhanced.cols(), enhanced.rows())
        matToBitmap(enhanced, processedBitmap)
        Log.d(TAG, "✅ Bitmap ready: ${processedBitmap.width}x${processedBitmap.height}")

        // Step 4: Run TFLite OCR using the new OCRTFLite class
        Log.d(TAG, "🤖 Running TFLite OCR...")
        val startTime = System.currentTimeMillis()
        val ocrText = try {
            ocrTFLite.runTFLite(processedBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "❌ OCR failed: ${e.message}")
            e.printStackTrace()
            ""
        }
        val inferenceTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "⏱️ OCR inference took ${inferenceTime}ms")
        Log.d(TAG, "📝 OCR result: '$ocrText' (${ocrText.length} chars)")

        runOnUiThread {
            ocrResultText.text = ocrText.trim()
            translatedText.text = ""
            if (ocrText.isNotBlank()) {
                Log.d(TAG, "🌐 Triggering translation for: '$ocrText'")
                translateText(ocrText)
            } else {
                Log.w(TAG, "⚠️ OCR result is blank, skipping translation")
            }
        }

        // Cleanup
        Log.d(TAG, "🧹 Cleaning up resources")
        croppedBitmap.recycle()
        gray.release()
        deskewed.release()
        resized.release()
        enhanced.release()
        processedBitmap.recycle()
        Log.d(TAG, "✅ processCroppedRegion() complete")
    }

    // ---------- Smart Deskew ----------
    private fun smartDeskewImage(src: Mat): Mat {
        if (src.rows() < 80 || src.cols() < 150) {
            return src.clone()
        }
        val binary = Mat()
        Imgproc.threshold(src, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val nonZeroPixels = Core.countNonZero(binary)
        val textDensity = nonZeroPixels.toDouble() / (binary.rows() * binary.cols())
        if (textDensity < 0.05 || textDensity > 0.6) {
            binary.release()
            return src.clone()
        }
        val angle = detectSkewAngleRobust(binary)
        binary.release()
        return if (abs(angle) > 0.8 && abs(angle) <= 20.0) rotateImage(src, angle) else src.clone()
    }

    private fun detectSkewAngleRobust(binary: Mat): Double {
        val angle1 = detectSkewWithHough(binary)
        val angle2 = detectSkewWithProjection(binary)
        val angle3 = detectSkewWithContours(binary)
        val angles = listOf(angle1, angle2, angle3).filter { abs(it) in 0.5..20.0 }
        return if (angles.isNotEmpty()) angles.sorted()[angles.size / 2] else 0.0
    }

    private fun detectSkewWithHough(binary: Mat): Double {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 1.0))
        val morphed = Mat()
        Imgproc.morphologyEx(binary, morphed, Imgproc.MORPH_CLOSE, kernel)
        val edges = Mat()
        Imgproc.Canny(morphed, edges, 50.0, 150.0, 3)
        val lines = Mat()
        Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180.0, 100)
        val angles = mutableListOf<Double>()
        for (i in 0 until min(lines.rows(), 100)) {
            val line = lines.get(i, 0)
            val theta = line[1]
            var angle = Math.toDegrees(theta)
            when {
                angle > 90 -> angle -= 180
                angle < -90 -> angle += 180
            }
            if (abs(angle) <= 25) angles.add(angle)
        }
        morphed.release()
        edges.release()
        lines.release()
        kernel.release()
        return if (angles.size >= 3) angles.sorted()[angles.size / 2] else 0.0
    }

    private fun detectSkewWithProjection(binary: Mat): Double {
        val anglesToTest = (-15..15 step 1).toList()
        var bestAngle = 0.0
        var maxVariance = 0.0
        for (angle in anglesToTest) {
            val rotated = rotateImage(binary, angle.toDouble())
            val projection = Mat()
            Core.reduce(rotated, projection, 1, Core.REDUCE_AVG)
            val mean = MatOfDouble()
            val stddev = MatOfDouble()
            Core.meanStdDev(projection, mean, stddev)
            val variance = stddev.toArray()[0]
            if (variance > maxVariance) {
                maxVariance = variance
                bestAngle = angle.toDouble()
            }
            rotated.release()
            projection.release()
        }
        return bestAngle
    }

    private fun detectSkewWithContours(binary: Mat): Double {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val angles = mutableListOf<Double>()
        for (contour in contours) {
            if (Imgproc.contourArea(contour) > 100) {
                val rect = Imgproc.minAreaRect(MatOfPoint2f(*contour.toArray()))
                val angle = rect.angle
                if (rect.size.width < rect.size.height) angle += 90.0
                if (angle > 45) angle -= 90
                if (angle < -45) angle += 90
                if (abs(angle) <= 20) angles.add(angle)
            }
        }
        hierarchy.release()
        return if (angles.size >= 3) angles.sorted()[angles.size / 2] else 0.0
    }

    private fun rotateImage(src: Mat, angle: Double): Mat {
        val center = Point(src.cols() / 2.0, src.rows() / 2.0)
        val rotMatrix = Imgproc.getRotationMatrix2D(center, angle, 1.0)
        val rotated = Mat()
        Imgproc.warpAffine(src, rotated, rotMatrix, src.size(), Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar(255.0))
        rotMatrix.release()
        return rotated
    }

    // ---------- Helper functions ----------
    private fun saveMatToStorage(mat: Mat, filename: String) {
        try {
            val bitmap = createBitmap(mat.cols(), mat.rows())
            matToBitmap(mat, bitmap)
            saveBitmapToStorage(bitmap, filename)
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to convert Mat to Bitmap: ${e.message}")
        }
    }

    private fun saveBitmapToStorage(bitmap: Bitmap, filename: String) {
        try {
            val path = getExternalFilesDir(null)?.absolutePath + "/" + filename
            val file = File(path)
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            out.flush()
            out.close()
            Log.d(TAG, "💾 Saved image: $path")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save image: ${e.message}")
        }
    }

    private fun translateText(textToTranslate: String) {
        val apiKey = BuildConfig.GOOGLE_TRANSLATE_API_KEY
        val url = "https://translation.googleapis.com/language/translate/v2?key=$apiKey"

        val jsonBody = JSONObject().apply {
            put("q", textToTranslate)
            put("target", "zu")
            put("format", "text")
        }.toString()

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            @SuppressLint("SetTextI18n")
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { translatedText.text = "❌ Translation failed: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string() ?: ""
                try {
                    val json = JSONObject(bodyString)
                    val translated = json.getJSONObject("data").getJSONArray("translations").getJSONObject(0).getString("translatedText")
                    runOnUiThread { translatedText.text = translated }
                } catch (e: Exception) {
                    Log.e("TRANSLATE_RESPONSE", bodyString)
                    runOnUiThread { translatedText.text = "⚠️ Parsing error: ${e.message}\n$bodyString" }
                }
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        return try {
            when (image.format) {
                ImageFormat.YUV_420_888 -> {
                    val yBuffer = image.planes[0].buffer
                    val uBuffer = image.planes[1].buffer
                    val vBuffer = image.planes[2].buffer
                    val ySize = yBuffer.remaining()
                    val uSize = uBuffer.remaining()
                    val vSize = vBuffer.remaining()
                    val nv21 = ByteArray(ySize + uSize + vSize)
                    yBuffer.get(nv21, 0, ySize)
                    vBuffer.get(nv21, ySize, vSize)
                    uBuffer.get(nv21, ySize + vSize, uSize)
                    val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                    val out = ByteArrayOutputStream()
                    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
                    BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
                }
                ImageFormat.JPEG -> {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                else -> createBitmap(1, 1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to convert ImageProxy: ${e.message}")
            createBitmap(1, 1)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        capturedBitmap?.recycle()
        textRecognizer.close()
        cameraExecutor.shutdown()
    }
}