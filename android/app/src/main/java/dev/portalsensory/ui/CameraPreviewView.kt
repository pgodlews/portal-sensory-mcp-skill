package dev.portalsensory.ui

import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.portalsensory.camera.SensoryCameraEngine
import dev.portalsensory.video.SensoryVideoEngine

@Composable
fun CameraPreviewView(
    cameraEngine: SensoryCameraEngine,
    videoEngine: SensoryVideoEngine,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                videoEngine.setTextureView(this)
                cameraEngine.startCamera(this)
            }
        },
        update = {}
    )
}
