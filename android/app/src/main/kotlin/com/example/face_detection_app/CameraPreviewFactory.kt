package com.example.face_detection_app

import android.content.Context
import android.view.View
import androidx.camera.view.PreviewView
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.common.StandardMessageCodec

class CameraPreviewFactory (
    private val context: Context
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context?, id: Int, args: Any?): PlatformView {
        return CameraPreview(context!!)
    }
}

class CameraPreview(context: Context) : PlatformView {
    private val previewView: PreviewView = PreviewView(context)

    override fun getView(): View = previewView
    override fun dispose() {}
}