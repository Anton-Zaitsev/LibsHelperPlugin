package com.zaycev.libshelper.ide.ui.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.zaycev.libshelper.core.graph.EXPORT_MAX_PIXELS
import com.zaycev.libshelper.core.graph.GraphMetrics
import com.zaycev.libshelper.core.graph.exportCamera
import com.zaycev.libshelper.core.graph.pngFrame
import com.zaycev.libshelper.ide.diagnostics.LibsHelperDiagnostics
import com.zaycev.libshelper.ide.i18n.msg
import com.zaycev.libshelper.ide.project.primaryGradleRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Path
import kotlin.io.path.writeBytes

@Suppress("InjectDispatcher")
internal suspend fun exportModuleGraphPng(project: Project, scene: GraphScene) {
    if (scene.map.nodes.isEmpty() || scene.near == null) return
    LibsHelperDiagnostics.record("ui", "export-png", scene.map.nodes.size.toString())
    val bytes = withContext(Dispatchers.EDT) {
        try {
            rasterize(scene, EXPORT_MAX_PIXELS)
        } catch (_: OutOfMemoryError) {
            rasterize(scene, EXPORT_MAX_PIXELS / 2)
        }
    }
    val saved = withContext(Dispatchers.EDT) { choosePng(project) } ?: return
    withContext(Dispatchers.Default) { saved.writeBytes(bytes) }
    withContext(Dispatchers.EDT) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("LibsHelper")
            .createNotification(msg("app.title"), msg("graph.export.saved", saved.fileName.toString()), NotificationType.INFORMATION)
            .addAction(
                NotificationAction.createSimpleExpiring(msg("graph.export.reveal")) {
                    RevealFileAction.openFile(saved.toFile())
                },
            )
            .notify(project)
    }
}

private fun rasterize(scene: GraphScene, maxPixels: Int): ByteArray {
    val picture = scene.near ?: return ByteArray(0)
    val map = scene.map
    val pad = GraphMetrics.CARD_H
    val minX = map.minX - pad
    val minY = map.minY - pad
    val maxX = map.maxX + pad
    val maxY = map.maxY + pad
    val frame = pngFrame(worldW = maxX - minX, worldH = maxY - minY, maxPixels = maxPixels)
    val camera = exportCamera(minX, minY, maxX, maxY, frame.pixelScale)
    val bitmap = ImageBitmap(frame.width, frame.height)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = Size(frame.width.toFloat(), frame.height.toFloat()),
    ) {
        drawRect(scene.palette.canvas)
        playGraphPicture(picture, camera)
    }
    return encodePng(bitmap)
}

internal fun encodePng(bitmap: ImageBitmap): ByteArray {
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
        val data = encodeSkiaPng(image) ?: error("png")
        data.use { return it.bytes }
    }
}

private fun encodeSkiaPng(image: Image): org.jetbrains.skia.Data? {
    val format = EncodedImageFormat::class.java
    val methods = Image::class.java.methods.filter { method ->
        method.name == "encodeToData" && method.parameterCount > 0 && method.parameterTypes[0] == format
    }
    val twoArg = methods.firstOrNull { method ->
        method.parameterCount == 2 && method.parameterTypes[1] == Integer.TYPE
    }
    val oneArg = methods.firstOrNull { method -> method.parameterCount == 1 }
    val encoded = when {
        twoArg != null -> twoArg.invoke(image, EncodedImageFormat.PNG, PNG_QUALITY)
        oneArg != null -> oneArg.invoke(image, EncodedImageFormat.PNG)
        else -> return null
    }
    return encoded as? org.jetbrains.skia.Data
}

private fun choosePng(project: Project): Path? {
    val descriptor = FileSaverDescriptor(msg("analytics.graph.export"), "", "png")
    val base = primaryGradleRoot(project)?.let { LocalFileSystem.getInstance().findFileByPath(it.toString()) }
    val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(base, "module-map.png")
        ?: return null
    return wrapper.file.toPath()
}

private const val PNG_QUALITY = 100
