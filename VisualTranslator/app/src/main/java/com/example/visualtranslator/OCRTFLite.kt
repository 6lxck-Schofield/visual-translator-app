package com.example.visualtranslator

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
//import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min
import androidx.core.graphics.scale
import org.tensorflow.lite.Tensor.QuantizationParams
//import org.tensorflow.lite.gpu.CompatibilityList

class OCRTFLite(private val context: Context) {

    companion object {
        private const val TAG = "OCRTFLite"

        private const val MODEL_FILENAME = "prediction_ocr_model_final1.tflite"
        private const val INPUT_W = 512
        private const val INPUT_H = 64
        private const val TIMESTEPS = 127
        private const val NUM_CLASSES = 67
        private const val CTC_BLANK_INDEX = NUM_CLASSES - 1
        private const val CHARSET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -.,"
    }

    private var tflite: Interpreter? = null
    //private var gpuDelegate: GpuDelegate? = null

    val isReady: Boolean
        get() = tflite != null

    /**
     * Loads the TFLite model, attempting GPU delegate first (if [useGpu] is true)
     * and falling back to multithreaded CPU on failure.
     */
    fun init() {
        val options = Interpreter.Options().apply {
            this.setNumThreads(4)
        }
        val modelBuffer = loadModelFile(MODEL_FILENAME)
        tflite = Interpreter(modelBuffer, options)

/*
        if (useGpu) {
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)

                val modelBuffer = loadModelFile(MODEL_FILENAME)
                tflite = Interpreter(modelBuffer, options)
                Log.d(TAG, "✅ TFLite GPU delegate initialized successfully")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ GPU delegate failed: ${e.message}, falling back to CPU")
                gpuDelegate?.close()
                gpuDelegate = null

                try {
                    val cpuOptions = Interpreter.Options().apply { setNumThreads(4) }
                    val modelBuffer = loadModelFile(MODEL_FILENAME)
                    tflite = Interpreter(modelBuffer, cpuOptions)
                    Log.d(TAG, "✅ TFLite CPU fallback initialized successfully")
                } catch (cpuError: Exception) {
                    Log.e(TAG, "❌ CPU fallback also failed: ${cpuError.message}")
                    throw cpuError
                }
            }
        } else {
            try {
                options.setNumThreads(4)
                val modelBuffer = loadModelFile(MODEL_FILENAME)
                tflite = Interpreter(modelBuffer, options)
                Log.d(TAG, "✅ TFLite CPU-only initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ TFLite initialization failed: ${e.message}")
                throw e
            }
        }
*/
        logModelIO()
    }

    private fun logModelIO() {
        tflite?.let { interpreter ->
            try {
                val inputTensor = interpreter.getInputTensor(0)
                val outputTensor = interpreter.getOutputTensor(0)
                Log.d(TAG, "📐 Model Input Shape: ${inputTensor.shape().contentToString()}")
                Log.d(TAG, "📐 Model Input Type: ${inputTensor.dataType()}")
                Log.d(TAG, "📐 Model Output Shape: ${outputTensor.shape().contentToString()}")
                Log.d(TAG, "📐 Model Output Type: ${outputTensor.dataType()}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not read tensor info: ${e.message}")
            }
        }
    }

    private fun loadModelFile(filename: String): ByteBuffer {
        val afd = context.assets.openFd(filename)
        val inputStream = FileInputStream(afd.fileDescriptor)
        val fc = inputStream.channel
        return fc.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    /**
     * Runs OCR on a single line-level [lineBitmap] and returns the decoded string.
     * Returns an empty string if the model isn't loaded or inference fails.
     */
    fun runOCR(lineBitmap: Bitmap): String {
        val model = tflite ?: run {
            Log.w(TAG, "runOCR called before init()")
            return ""
        }

        val inputTensor = model.getInputTensor(0)
        val dtype = inputTensor.dataType()
        val quantParams = inputTensor.quantizationParams()

        try {
            Log.d(TAG, "▶️ Input tensor shape: ${inputTensor.shape().contentToString()}, dtype: $dtype")
            quantParams?.let {
                Log.d(TAG, "▶️ Input quant params: scale=${it.scale}, zeroPoint=${it.zeroPoint}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't inspect input tensor: ${e.message}")
        }

        val numElements = INPUT_W * INPUT_H // channels == 1

        val bb: ByteBuffer = when (dtype) {
            org.tensorflow.lite.DataType.FLOAT32 ->
                ByteBuffer.allocateDirect(numElements * 4).apply { order(ByteOrder.nativeOrder()) }
            org.tensorflow.lite.DataType.UINT8 ->
                ByteBuffer.allocateDirect(numElements).apply { order(ByteOrder.nativeOrder()) }
            else -> {
                Log.e(TAG, "Unsupported input data type: $dtype")
                return ""
            }
        }

        fillInputBuffer(bb, lineBitmap, dtype, quantParams)

        val output = Array(1) { Array(TIMESTEPS) { FloatArray(NUM_CLASSES) } }

        try {
            model.run(bb, output)
        } catch (e: Exception) {
            Log.w(TAG, "run(bb, output) failed: ${e.message}; trying nested array fallback")
            if (dtype == org.tensorflow.lite.DataType.FLOAT32) {
                bb.rewind()
                val fa = FloatArray(numElements)
                bb.asFloatBuffer().get(fa)
                val nested = Array(1) { Array(INPUT_H) { Array(INPUT_W) { FloatArray(1) } } }
                var idx = 0
                for (yy in 0 until INPUT_H) {
                    for (xx in 0 until INPUT_W) {
                        nested[0][yy][xx][0] = fa[idx++]
                    }
                }
                model.run(nested, output)
            } else {
                Log.e(TAG, "Cannot fallback for dtype $dtype")
                return ""
            }
        }

        logCtcStats(output[0])
        return decodeCTC(output[0])
    }

    private fun fillInputBuffer(
        bb: ByteBuffer,
        lineBitmap: Bitmap,
        dtype: org.tensorflow.lite.DataType,
        quantParams: QuantizationParams?

    ) {
        val scaledBitmap = lineBitmap.scale(INPUT_W, INPUT_H)
        val pixels = IntArray(INPUT_W * INPUT_H)
        scaledBitmap.getPixels(pixels, 0, INPUT_W, 0, 0, INPUT_W, INPUT_H)

        if (dtype == org.tensorflow.lite.DataType.FLOAT32) {
            val fb = bb.asFloatBuffer()
            var minVal = Float.MAX_VALUE
            var maxVal = -Float.MAX_VALUE
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val gray = (0.299f * r + 0.587f * g + 0.114f * b)
                val normalized = gray / 255f
                fb.put(normalized)
                if (normalized < minVal) minVal = normalized
                if (normalized > maxVal) maxVal = normalized
            }
            bb.rewind()
            Log.d(TAG, "📊 FLOAT input range after norm: min=$minVal max=$maxVal")
        } else {
            val scale = if (quantParams != null && quantParams.scale > 0f) quantParams.scale else 1.0f
            val zeroPoint = quantParams?.zeroPoint ?: 0
            var minVal = Int.MAX_VALUE
            var maxVal = Int.MIN_VALUE
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val q = (gray / scale).toInt() + zeroPoint
                val clipped = q.coerceIn(0, 255)
                bb.put(clipped.toByte())
                if (clipped < minVal) minVal = clipped
                if (clipped > maxVal) maxVal = clipped
            }
            bb.rewind()
            Log.d(TAG, "📊 UINT8 input range after quantize: min=$minVal max=$maxVal (scale=$scale zeroPoint=$zeroPoint)")
        }
    }

    private fun logCtcStats(logits: Array<FloatArray>) {
        val debugSteps = min(8, TIMESTEPS)
        var blankCount = 0
        var sumTopProb = 0f
        for (t in 0 until TIMESTEPS) {
            val probs = logits[t]
            var maxIdx = 0
            var maxVal = probs[0]
            for (i in probs.indices) {
                if (probs[i] > maxVal) {
                    maxVal = probs[i]
                    maxIdx = i
                }
            }
            if (t < debugSteps) Log.d(TAG, "t=$t argmax=$maxIdx maxVal=$maxVal")
            if (maxIdx == CTC_BLANK_INDEX) blankCount++
            sumTopProb += maxVal
        }
        Log.d(TAG, "🔤 CTC stats - blanks=$blankCount/$TIMESTEPS avgTopProb=${sumTopProb / TIMESTEPS}")
    }

    /** Greedy CTC decode: collapse repeats, drop blanks. */
    private fun decodeCTC(logits: Array<FloatArray>): String {
        val sb = StringBuilder()
        var prev = -1
        var totalChars = 0
        var blanksSkipped = 0
        var repeatsSkipped = 0

        for (t in logits.indices) {
            val probs = logits[t]
            var maxIdx = 0
            var maxVal = probs[0]
            for (i in probs.indices) {
                if (probs[i] > maxVal) {
                    maxVal = probs[i]
                    maxIdx = i
                }
            }

            when {
                maxIdx == CTC_BLANK_INDEX -> blanksSkipped++
                maxIdx == prev -> repeatsSkipped++
                maxIdx < CHARSET.length -> {
                    sb.append(CHARSET[maxIdx])
                    totalChars++
                }
                else -> Log.w(TAG, "⚠️ Index $maxIdx out of charset bounds (size: ${CHARSET.length})")
            }

            prev = maxIdx
        }

        Log.d(TAG, "🔤 CTC Decode - Total chars: $totalChars, Blanks: $blanksSkipped, Repeats: $repeatsSkipped")
        return sb.toString()
    }

    /** Releases the interpreter and GPU delegate. Call from onDestroy(). */
    fun close() {
        //gpuDelegate?.close()
        //gpuDelegate = null
        tflite?.close()
        tflite = null
    }
}