package com.example.abconlyone.camera

import android.content.Context
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrame: (ImageProxy) -> Unit
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientationDegrees: Int) {
            if (orientationDegrees == ORIENTATION_UNKNOWN) return
            val rotation = when {
                orientationDegrees >= 315 || orientationDegrees < 45 -> Surface.ROTATION_0
                orientationDegrees < 135 -> Surface.ROTATION_270
                orientationDegrees < 225 -> Surface.ROTATION_180
                else -> Surface.ROTATION_90
            }
            preview?.targetRotation = rotation
            imageAnalysis?.targetRotation = rotation
        }
    }

    fun start() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            this.preview = preview

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(analysisExecutor) { imageProxy ->
                        Log.d("CameraController", "Received frame: ${imageProxy.width}x${imageProxy.height}")
                        onFrame(imageProxy)
                    }
                }
            this.imageAnalysis = imageAnalysis

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, imageAnalysis
            )

            orientationListener.enable()
        }, ContextCompat.getMainExecutor(context))
    }

    fun shutdown() {
        orientationListener.disable()
        analysisExecutor.shutdown()
    }
}