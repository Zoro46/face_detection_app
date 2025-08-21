package com.example.face_detection_app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.View
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facedetector.FaceDetectorResult
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import com.google.mediapipe.tasks.core.BaseOptions

class MainActivity : FlutterActivity() {

    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private lateinit var methodChannel: MethodChannel
    internal var previewPlatformView: PreviewPlatformView? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Event channel sends detection boxes to Dart
        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, "mediapipe_faces")
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }
            override fun onCancel(arguments: Any?) { eventSink = null }
        })

        // Method channel for start/stop
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "mediapipe_channel")
        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startFaceDetection" -> {
                    // No-op here; the PlatformView will start automatically
                    result.success(true)
                }
                "stopFaceDetection" -> {
                    // We let PlatformView handle lifecycle on dispose
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        // Register PlatformView
        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("camera_preview",  PreviewPlatformViewFactory(this) { detections ->
                eventSink?.success(detections)
            })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PreviewPlatformView.CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                previewPlatformView?.startCameraAfterPermission()
            }
        }
    }
}

class PreviewPlatformViewFactory(
    private val activity: FlutterActivity,
    private val onDetections: (String) -> Unit
) : PlatformViewFactory(io.flutter.plugin.common.StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, id: Int, args: Any?): PlatformView {
        val view = PreviewPlatformView(activity, onDetections)
        (activity as MainActivity).previewPlatformView = view
        return view
    }
}

class PreviewPlatformView(
    private val activity: FlutterActivity,
    private val onDetections: (String) -> Unit
) : PlatformView {

    private val previewView: PreviewView = PreviewView(activity).apply {
        layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleX = -1f // mirror front camera
    }
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var faceDetector: FaceDetector? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var imageSize = Size(0, 0)

    init {
        checkCameraPermissionAndStart()
    }

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
    }

    fun startCameraAfterPermission() {
        startCamera()
    }

    companion object {
        const val CAMERA_PERMISSION_CODE = 100
    }

    override fun getView(): View = previewView

    override fun dispose() {
        faceDetector?.close()
        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Front camera (mirrored UI)
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            // Analyzer for frames
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageRotationEnabled(true)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (imageSize.width == 0) {
                            imageSize = Size(imageProxy.width, imageProxy.height)
                        }
                        processFrame(imageProxy)
                    }
                }

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("blaze_face_short_range.tflite")
                .build()

            // MediaPipe FaceDetector live stream
            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setMinDetectionConfidence(0.5f)
                .setResultListener { result: FaceDetectorResult, _ ->
                    sendDetections(result)
                }
                .build()

            faceDetector = FaceDetector.createFromOptions(activity, options)


            cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    activity, cameraSelector, preview, imageAnalyzer
                )
            }, ContextCompat.getMainExecutor(activity))
        } catch (exc: Exception) {
            exc.printStackTrace()
        }
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            // Convert to MPImage (fast-path YUV->Bitmap; minimal work)
            val bitmap = imageProxy.toBitmap() // helper extension below
            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
            // Timestamp in millis is recommended; we can use systemTime
            faceDetector?.detectAsync(mpImage, System.currentTimeMillis())
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    private fun sendDetections(result: FaceDetectorResult) {
        
        // Only send if we have faces
        if (result.detections().isEmpty()) return
        
        // Build normalized boxes JSON
        val root = JSONObject()
        val arr = JSONArray()
        result.detections().forEach { det ->
            val box = det.boundingBox() // normalized rect [0..1] in live stream
            val obj = JSONObject()
            // Normalize coordinates to 0-1 range with padding
            val scaleFactor = 1.6f // Make box 50% larger
            val originalW = box.width() / imageSize.width
            val originalH = box.height() / imageSize.height
            val originalX = box.left / imageSize.width
            val originalY = box.top / imageSize.height
            
            val normalizedW = originalW * scaleFactor
            val normalizedH = originalH * scaleFactor
            val normalizedX = originalX - (normalizedW - originalW) / 2
            val normalizedY = originalY - (normalizedH - originalH) / 2
            
            obj.put("x", normalizedX)
            obj.put("y", normalizedY)
            obj.put("w", normalizedW)
            obj.put("h", normalizedH)
            arr.put(obj)
        }
        root.put("boxes", arr)
        root.put("imageWidth", imageSize.width)
        root.put("imageHeight", imageSize.height)
        root.put("mirrored", true) // front camera
        
        val jsonString = root.toString()
        
        // Switch to main thread for Flutter EventSink
        Handler(Looper.getMainLooper()).post {
            try {
                onDetections(jsonString)
                // Successfully sent to Flutter
            } catch (e: Exception) {
                // Failed to send to Flutter
            }
        }
    }
}
