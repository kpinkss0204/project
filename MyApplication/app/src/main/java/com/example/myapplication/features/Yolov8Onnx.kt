package com.example.myapplication.features

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class Detection(
    val className: String,
    val confidence: Float,
    val boundingBox: RectF
)

class Yolov8Onnx(
    context: Context,
    modelPath: String = "best.onnx",
    private val confThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.45f
) {
    companion object {
        private const val TAG = "Yolov8Onnx"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputWidth = 640
    private val inputHeight = 640
    private var originalWidth = 0
    private var originalHeight = 0
    private val classNames = listOf("1000원", "5000원", "10000원", "50000원")
    private val runLock = ReentrantLock()

    init {
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        try {
            val modelBytes = context.assets.open(modelPath).readBytes()
            session = env.createSession(modelBytes, sessionOptions)
            val inputInfo = session.inputInfo.values.firstOrNull()
            val shape = (inputInfo?.info as? TensorInfo)?.shape
            Log.i(TAG, "Model input shape: ${shape?.contentToString() ?: "unknown"}")
        } catch (e: Exception) {
            Log.e(TAG, "ONNX 모델 로드 실패: ${e.message}", e)
            throw e
        }
    }

    private fun preprocessToFloatBuffer(bitmap: Bitmap): OnnxTensor? {
        originalWidth = bitmap.width
        originalHeight = bitmap.height
        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val nchwSize = 1 * 3 * inputHeight * inputWidth
        val byteBuffer = ByteBuffer.allocateDirect(nchwSize * 4).order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        for (c in 0 until 3) {
            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val pixel = resized.getPixel(x, y)
                    val value = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255.0f
                        1 -> ((pixel shr 8) and 0xFF) / 255.0f
                        else -> (pixel and 0xFF) / 255.0f
                    }
                    floatBuffer.put(value)
                }
            }
        }
        floatBuffer.rewind()
        val shape = longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())

        val tensor = try {
            OnnxTensor.createTensor(env, floatBuffer, shape)
        } catch (e: Exception) {
            Log.w(TAG, "Tensor 생성 실패: ${e.message}")
            null
        }

        try { if (!resized.isRecycled) resized.recycle() } catch (_: Exception) {}
        return tensor
    }

    private fun xywh2xyxy(box: FloatArray) = RectF(
        box[0] - box[2] / 2f,
        box[1] - box[3] / 2f,
        box[0] + box[2] / 2f,
        box[1] + box[3] / 2f
    )

    private fun scaleBox(box: RectF) = RectF(
        max(0f, box.left * originalWidth / inputWidth),
        max(0f, box.top * originalHeight / inputHeight),
        min(originalWidth.toFloat(), box.right * originalWidth / inputWidth),
        min(originalHeight.toFloat(), box.bottom * originalHeight / inputHeight)
    )

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    private fun nms(boxes: List<RectF>, scores: List<Float>): List<Int> {
        val picked = mutableListOf<Int>()
        val sorted = scores.indices.sortedByDescending { scores[it] }
        for (i in sorted) {
            if (picked.any { iou(boxes[i], boxes[it]) > iouThreshold }) continue
            picked.add(i)
        }
        return picked
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        return runLock.withLock {
            val detections = mutableListOf<Detection>()
            var inputTensor: OnnxTensor? = null
            var results: OrtSession.Result? = null

            try {
                inputTensor = preprocessToFloatBuffer(bitmap) ?: return emptyList()
                val inputName = session.inputNames.iterator().nextOrNull() ?: return emptyList()

                results = try {
                    session.run(mapOf(inputName to inputTensor))
                } catch (e: Exception) {
                    Log.e(TAG, "session.run() 실패: ${e.message}", e)
                    return emptyList()
                }

                val out0 = results[0].value
                if (out0 !is Array<*>) return emptyList()

                @Suppress("UNCHECKED_CAST")
                val outputArray = out0 as? Array<Array<FloatArray>> ?: return emptyList()
                val predictions = outputArray.getOrNull(0) ?: return emptyList()

                val boxes = mutableListOf<RectF>()
                val scores = mutableListOf<Float>()
                val clsIds = mutableListOf<Int>()

                predictions.forEachIndexed { i, pred ->
                    val x = pred.getOrNull(0) ?: return@forEachIndexed
                    val y = pred.getOrNull(1) ?: return@forEachIndexed
                    val w = pred.getOrNull(2) ?: return@forEachIndexed
                    val h = pred.getOrNull(3) ?: return@forEachIndexed

                    val classScores = FloatArray(classNames.size) { j ->
                        pred.getOrNull(4 + j) ?: 0f
                    }

                    val maxIdx = classScores.indices.maxByOrNull { classScores[it] } ?: -1
                    if (maxIdx < 0) return@forEachIndexed
                    val confidence = classScores[maxIdx]
                    if (confidence > confThreshold) {
                        boxes.add(scaleBox(xywh2xyxy(floatArrayOf(x, y, w, h))))
                        scores.add(confidence)
                        clsIds.add(maxIdx)
                    }
                }

                val keep = nms(boxes, scores)
                keep.forEach { i ->
                    detections.add(
                        Detection(
                            className = classNames.getOrElse(clsIds[i]) { "unknown" },
                            confidence = scores[i],
                            boundingBox = boxes[i]
                        )
                    )
                }

            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM 발생: ${oom.message}", oom)
            } catch (e: Exception) {
                Log.e(TAG, "detect 예외: ${e.message}", e)
            } finally {
                try { results?.close() } catch (_: Exception) {}
                try { inputTensor?.close() } catch (_: Exception) {}
            }

            return detections
        }
    }

    fun close() {
        try { session.close() } catch (_: Exception) {}
        try { env.close() } catch (_: Exception) {}
    }
}

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null
