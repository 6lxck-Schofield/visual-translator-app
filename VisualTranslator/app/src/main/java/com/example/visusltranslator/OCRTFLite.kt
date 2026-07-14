package com.schofielddevs.visuallearning

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import androidx.core.graphics.scale
import androidx.core.graphics.get

class OCRTFLite(private val context: Context) {
    
    companion object {
        private const val TAG = "OCRTFLite"
    }
    
    private val IMG_W = 512
    private val IMG_H = 64
    private val CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -.,"
    private val blankIndex = CHARS.length
    private val idxToChar = CHARS.mapIndexed { i, c -> i to c }.toMap()
    
    private val interpreter: Interpreter by lazy {
        Log.d(TAG, "🔧 Initializing TFLite interpreter")
        try {
            val asset = context.assets.open("prediction_ocr_model_final.tflite")
            val model = asset.readBytes()
            Log.d(TAG, "📦 Model loaded: ${model.size} bytes")
            
            val buffer = ByteBuffer.allocateDirect(model.size)
            buffer.order(ByteOrder.nativeOrder())
            buffer.put(model)
            
            val interp = Interpreter(buffer)
            
            // Log model info
            val inputTensor = interp.getInputTensor(0)
            val outputTensor = interp.getOutputTensor(0)
            Log.d(TAG, "📐 Input shape: ${inputTensor.shape().contentToString()}")
            Log.d(TAG, "📐 Output shape: ${outputTensor.shape().contentToString()}")
            Log.d(TAG, "✅ Interpreter ready")
            
            interp
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize interpreter: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
    
    // -----------------------------
    // Preprocessing: replicate Python
    // -----------------------------
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        Log.d(TAG, "🎨 Preprocessing bitmap: ${bitmap.width}x${bitmap.height}")
        
        // 1. Resize to model input size
        val resized = bitmap.scale(IMG_W, IMG_H)
        Log.d(TAG, "📐 Resized to: ${IMG_W}x${IMG_H}")
        
        val input = ByteBuffer.allocateDirect(1 * IMG_H * IMG_W * 1 * 4)
        input.order(ByteOrder.nativeOrder())
        
        // Collect pixel values first for normalization
        val pixels = FloatArray(IMG_W * IMG_H)
        var idx = 0
        for (y in 0 until IMG_H) {
            for (x in 0 until IMG_W) {
                val p = resized[x, y]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val gray = (0.299f * r + 0.587f * g + 0.114f * b)
                pixels[idx++] = gray
            }
        }
        
        // Calculate mean and std
        val mean = pixels.average().toFloat()
        var variance = 0f
        for (v in pixels) {
            variance += (v - mean) * (v - mean)
        }
        val std = sqrt(variance / pixels.size + 1e-6f)
        
        Log.d(TAG, "📊 Pixel stats - mean: $mean, std: $std")
        
        // 2. Normalize and write to bytebuffer
        var minNorm = Float.MAX_VALUE
        var maxNorm = Float.MIN_VALUE
        for (v in pixels) {
            val norm = (v - mean) / std
            input.putFloat(norm)
            if (norm < minNorm) minNorm = norm
            if (norm > maxNorm) maxNorm = norm
        }
        
        Log.d(TAG, "📊 Normalized range: [$minNorm, $maxNorm]")
        
        input.rewind()
        return input
    }
    
    // -----------------------------
    // CTC Greedy Decoder
    // -----------------------------
    private fun decodeCTC(output: Array<Array<FloatArray>>): String {
        Log.d(TAG, "🔤 Starting CTC decode")
        
        val timeSteps = output[0].size
        val classes = output[0][0].size
        Log.d(TAG, "📊 Output shape: timeSteps=$timeSteps, classes=$classes")
        
        val decoded = StringBuilder()
        var prev = blankIndex
        var blankCount = 0
        var charCount = 0
        var repeatCount = 0
        
        // Log first few timesteps for debugging
        val debugSteps = minOf(5, timeSteps)
        for (t in 0 until debugSteps) {
            var maxIdx = 0
            var maxVal = output[0][t][0]
            for (c in 1 until classes) {
                if (output[0][t][c] > maxVal) {
                    maxVal = output[0][t][c]
                    maxIdx = c
                }
            }
            val char = if (maxIdx == blankIndex) "<blank>" else idxToChar[maxIdx]?.toString() ?: "?"
            Log.d(TAG, "  t=$t: argmax=$maxIdx ($char), prob=$maxVal")
        }
        
        // Full decode
        for (t in 0 until timeSteps) {
            var maxIdx = 0
            var maxVal = output[0][t][0]
            for (c in 1 until classes) {
                if (output[0][t][c] > maxVal) {
                    maxVal = output[0][t][c]
                    maxIdx = c
                }
            }
            
            when {
                maxIdx == blankIndex -> blankCount++
                maxIdx == prev -> repeatCount++
                else -> {
                    decoded.append(idxToChar[maxIdx])
                    charCount++
                }
            }
            prev = maxIdx
        }
        
        val result = decoded.toString()
        Log.d(TAG, "✅ CTC decode complete:")
        Log.d(TAG, "   📝 Result: '$result' (${result.length} chars)")
        Log.d(TAG, "   📊 Stats: chars=$charCount, blanks=$blankCount, repeats=$repeatCount")
        
        return result
    }
    
    // -----------------------------
    // Public call: run OCR
    // -----------------------------
    fun runTFLite(bitmap: Bitmap): String {
        Log.d(TAG, "🚀 Starting OCR inference")
        
        try {
            // Preprocess
            val preprocessStart = System.currentTimeMillis()
            val input = preprocess(bitmap)
            val preprocessTime = System.currentTimeMillis() - preprocessStart
            Log.d(TAG, "⏱️ Preprocessing took ${preprocessTime}ms")
            
            // Output: [1, time, classes]
            val timeSteps = IMG_W / 4 - 1  // matches your Python code
            val numClasses = CHARS.length + 1  // +1 blank
            Log.d(TAG, "📦 Allocating output: [1, $timeSteps, $numClasses]")
            val output = Array(1) { Array(timeSteps) { FloatArray(numClasses) } }
            
            // Run inference
            val inferenceStart = System.currentTimeMillis()
            interpreter.run(input, output)
            val inferenceTime = System.currentTimeMillis() - inferenceStart
            Log.d(TAG, "⏱️ Inference took ${inferenceTime}ms")
            
            // Decode
            val decodeStart = System.currentTimeMillis()
            val result = decodeCTC(output)
            val decodeTime = System.currentTimeMillis() - decodeStart
            Log.d(TAG, "⏱️ Decoding took ${decodeTime}ms")
            
            val totalTime = preprocessTime + inferenceTime + decodeTime
            Log.d(TAG, "✅ Total OCR time: ${totalTime}ms")
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ OCR failed with exception: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}