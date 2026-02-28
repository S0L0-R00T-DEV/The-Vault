package com.vault.srd.intruder

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

class IntruderManager(private val context: Context) {
    private var imageCapture: ImageCapture? = null

    fun takeSelfie(lifecycleOwner: LifecycleOwner, onComplete: (String?) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                val file = File(context.filesDir, "intruder_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

                imageCapture?.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e("IntruderManager", "Photo capture failed: ${exc.message}", exc)
                            onComplete(null)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.d("IntruderManager", "Photo capture succeeded: ${file.absolutePath}")
                            onComplete(file.absolutePath)
                        }
                    }
                )
            } catch (exc: Exception) {
                Log.e("IntruderManager", "Use case binding failed", exc)
                onComplete(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
