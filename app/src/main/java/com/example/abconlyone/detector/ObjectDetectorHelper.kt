package com.example.abconlyone.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class ObjectDetectorHelper(
    private val context: Context,
    private val modelName: String = "yolov8n_person_fp16.tflite",
    private val inputSize: Int = 320,
    private val confThreshold: Float = 0.5f,
    private val iouThreshold: Float = 0.45f
) {
    companion object {
        private const val PERSON_CLASS_ID = 0
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBuffer: FloatBuffer
    private var numBoxes: Int = 0

    init {
        setupInterpreter()
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    private fun setupInterpreter() {
        try {
            val options = Interpreter.Options()
            val compatList = CompatibilityList()

            if (compatList.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                options.addDelegate(gpuDelegate)
            } else {
                options.setNumThreads(4)
            }

            interpreter = Interpreter(loadModelFile(), options)
            val outputTensor = interpreter?.getOutputTensor(0)
            val outputShape = outputTensor?.shape() ?: intArrayOf(1, 84, 2100)

            numBoxes = outputShape[2]

            inputBuffer = ByteBuffer.allocateDirect(1 * 3 * inputSize * inputSize * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            outputBuffer = FloatBuffer.allocate(outputShape[1] * outputShape[2])

            Log.d("ObjectDetectorHelper", "Model initialized: $modelName. Output shape: ${outputShape.contentToString()}")
        } catch (e: Exception) {
            Log.e("ObjectDetectorHelper", "Setup error: ${e.message}")
        }
    }

    fun detect(originalBitmap: Bitmap): List<BoundingBox> {
        val interp = interpreter ?: return emptyList()

        try {
            val resized = ImageUtils.letterbox(originalBitmap, inputSize)

            inputBuffer.rewind()
            val pixels = IntArray(inputSize * inputSize)
            resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

            // NCHW filling
            for (c in 0 until 3) {
                for (i in 0 until inputSize * inputSize) {
                    val pixel = pixels[i]
                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255f
                        1 -> ((pixel shr 8) and 0xFF) / 255f
                        2 -> (pixel and 0xFF) / 255f
                        else -> 0f
                    }
                    inputBuffer.putFloat(value)
                }
            }

            outputBuffer.rewind()
            interp.run(inputBuffer, outputBuffer)

            return decodeOutput(originalBitmap.width, originalBitmap.height)
        } catch (e: Exception) {
            Log.e("ObjectDetectorHelper", "Detect error: ${e.message}")
            return emptyList()
        }
    }

    private fun decodeOutput(origWidth: Int, origHeight: Int): List<BoundingBox> {
        val result = mutableListOf<BoundingBox>()
        outputBuffer.rewind()
        val raw = FloatArray(outputBuffer.capacity())
        outputBuffer.get(raw)

        val scale = min(inputSize.toFloat() / origWidth, inputSize.toFloat() / origHeight)
        val padX = (inputSize - origWidth * scale) / 2f
        val padY = (inputSize - origHeight * scale) / 2f

        for (i in 0 until numBoxes) {
            val personScore = raw[(PERSON_CLASS_ID + 4) * numBoxes + i]

            if (personScore > confThreshold) {
                var cx = raw[i]
                var cy = raw[1 * numBoxes + i]
                var w = raw[2 * numBoxes + i]
                var h = raw[3 * numBoxes + i]

                if (cx <= 1f && cy <= 1f && w <= 1f && h <= 1f) {
                    cx *= inputSize
                    cy *= inputSize
                    w *= inputSize
                    h *= inputSize
                }

                val x1 = (cx - w / 2f - padX) / scale
                val y1 = (cy - h / 2f - padY) / scale
                val x2 = (cx + w / 2f - padX) / scale
                val y2 = (cy + h / 2f - padY) / scale

                result.add(
                    BoundingBox(
                        x1.coerceIn(0f, origWidth.toFloat()),
                        y1.coerceIn(0f, origHeight.toFloat()),
                        x2.coerceIn(0f, origWidth.toFloat()),
                        y2.coerceIn(0f, origHeight.toFloat()),
                        personScore,
                        PERSON_CLASS_ID,
                        "person"
                    )
                )
            }
        }

        return nonMaxSuppression(result)
    }

    private fun nonMaxSuppression(boxes: List<BoundingBox>): List<BoundingBox> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val selected = mutableListOf<BoundingBox>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)
            sorted.removeAll { it.classId == best.classId && iou(best, it) > iouThreshold }
        }

        return selected
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val interX1 = max(a.x1, b.x1)
        val interY1 = max(a.y1, b.y1)
        val interX2 = min(a.x2, b.x2)
        val interY2 = min(a.y2, b.y2)

        val interArea = max(0f, interX2 - interX1) * max(0f, interY2 - interY1)
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val union = areaA + areaB - interArea

        return if (union <= 0f) 0f else interArea / union
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}