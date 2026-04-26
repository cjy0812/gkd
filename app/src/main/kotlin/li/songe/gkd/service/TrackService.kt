package li.songe.gkd.service

import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.util.lerp
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import li.songe.gkd.app
import li.songe.gkd.notif.StopServiceReceiver
import li.songe.gkd.notif.trackNotif
import li.songe.gkd.shizuku.casted
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.OnSimpleLife
import li.songe.gkd.util.ScreenUtils
import li.songe.gkd.util.runMainPost
import li.songe.gkd.util.startForegroundServiceByClass
import li.songe.gkd.util.stopServiceByClass

class TrackService : LifecycleService(), SavedStateRegistryOwner, OnSimpleLife {
    override fun onCreate() {
        super.onCreate()
        onCreated()
    }

    override fun onDestroy() {
        super.onDestroy()
        onDestroyed()
    }

    val registryController = SavedStateRegistryController.create(this).apply {
        performAttach()
        performRestore(null)
    }
    override val savedStateRegistry = registryController.savedStateRegistry
    override val scope get() = lifecycleScope

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val resizeFlow = MutableSharedFlow<Unit>()
    override fun onConfigurationChanged(newConfig: Configuration) {
        lifecycleScope.launch { resizeFlow.emit(Unit) }
    }

    val curRotationFlow = MutableStateFlow(app.compatDisplay.rotation).apply {
        lifecycleScope.launch {
            resizeFlow.collect { update { app.compatDisplay.rotation } }
        }
    }
    val screenSizeFlow = MutableStateFlow(getScreenSize())
    val strokeWidth = 2f
    val lineMargin = 75f
    val trackPadding = lineMargin + 8f
    private var isViewAttached = false
    private val overlayOriginFlow = MutableStateFlow(Offset.Zero)

    private fun getScreenSize() = Size(
        ScreenUtils.getScreenWidth().toFloat(),
        ScreenUtils.getScreenHeight().toFloat(),
    )

    private data class OverlayRegion(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    private fun attachViewIfNeeded() {
        if (isViewAttached) return
        windowManager.addView(view, layoutParams)
        isViewAttached = true
    }

    private fun detachViewIfNeeded() {
        if (!isViewAttached) return
        windowManager.removeView(view)
        isViewAttached = false
    }

    private fun applyOverlayRegion(region: OverlayRegion) {
        layoutParams.x = region.left
        layoutParams.y = region.top
        layoutParams.width = region.width
        layoutParams.height = region.height
        overlayOriginFlow.value = Offset(region.left.toFloat(), region.top.toFloat())
        if (isViewAttached) {
            windowManager.updateViewLayout(view, layoutParams)
        } else {
            attachViewIfNeeded()
        }
    }

    private fun buildOverlayRegion(
        points: List<TrackPoint>,
        swipePoints: List<SwipeTrackPoint>,
        screenSize: Size,
        curRotation: Int,
    ): OverlayRegion? {
        if (points.isEmpty() && swipePoints.isEmpty()) return null

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        fun expand(center: Offset) {
            minX = minOf(minX, center.x - trackPadding)
            minY = minOf(minY, center.y - trackPadding)
            maxX = maxOf(maxX, center.x + trackPadding)
            maxY = maxOf(maxY, center.y + trackPadding)
        }

        points.forEach { point ->
            expand(point.getScreenCenter(screenSize, curRotation))
        }
        swipePoints.forEach { swipePoint ->
            expand(swipePoint.start.getScreenCenter(screenSize, curRotation))
            expand(swipePoint.end.getScreenCenter(screenSize, curRotation))
        }

        val screenWidth = screenSize.width.toInt()
        val screenHeight = screenSize.height.toInt()
        val left = minX.toInt().coerceIn(0, screenWidth)
        val top = minY.toInt().coerceIn(0, screenHeight)
        val right = kotlin.math.ceil(maxX.toDouble()).toInt().coerceIn(left + 1, screenWidth)
        val bottom = kotlin.math.ceil(maxY.toDouble()).toInt().coerceIn(top + 1, screenHeight)

        return OverlayRegion(
            left = left,
            top = top,
            width = right - left,
            height = bottom - top,
        )
    }

    private fun DrawScope.drawTrackPoint(center: Offset) {

        drawLine(
            color = Color.Yellow,
            start = Offset(center.x, center.y - lineMargin),
            end = Offset(center.x, center.y + lineMargin),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = Color.Yellow,
            start = Offset(center.x - lineMargin, center.y),
            end = Offset(center.x + lineMargin, center.y),
            strokeWidth = strokeWidth,
        )
        drawCircle(
            color = Color.Red,
            radius = 10f,
            center = center,
        )
        drawCircle(
            color = Color.Red,
            radius = 20f,
            center = center,
            style = Stroke(4f)
        )
        drawCircle(
            color = Color.Red,
            radius = 30f,
            center = center,
            style = Stroke(4f)
        )
    }

    @Composable
    private fun SwipePointCanvas(
        swipePoint: SwipeTrackPoint,
        curRotation: Int,
        screenSize: Size,
        overlayOrigin: Offset,
    ) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(null) {
            // 匀速直线
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(swipePoint.duration.toInt(), easing = LinearEasing),
            )
            delayRemovePosition(swipePoint.id)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val startCenter = swipePoint.start.getCenter(screenSize, curRotation, overlayOrigin)
            drawTrackPoint(startCenter)
            val endCenter = swipePoint.end.getCenter(screenSize, curRotation, overlayOrigin)
            val midCenter = Offset(
                lerp(startCenter.x, endCenter.x, progress.value),
                lerp(startCenter.y, endCenter.y, progress.value)
            )
            drawTrackPoint(midCenter)
            drawLine(
                color = Color.Blue,
                start = startCenter,
                end = midCenter,
                strokeWidth = strokeWidth,
            )
        }
    }

    @Composable
    fun ComposeContent() {
        val curRotation by curRotationFlow.collectAsState()
        val screenSize by screenSizeFlow.collectAsState()
        val overlayOrigin by overlayOriginFlow.collectAsState()
        val positionList = pointListFlow.collectAsState().value
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            positionList.forEach { point ->
                drawTrackPoint(point.getCenter(screenSize, curRotation, overlayOrigin))
            }
        }
        val swipePointList = swipePointListFlow.collectAsState().value
        swipePointList.forEach { swipePoint ->
            key(swipePoint.id) { SwipePointCanvas(swipePoint, curRotation, screenSize, overlayOrigin) }
        }
    }

    val view by lazy {
        ComposeView(this).apply {
            setViewTreeSavedStateRegistryOwner(this@TrackService)
            setViewTreeLifecycleOwner(this@TrackService)
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    ComposeContent()
                }
            }
        }
    }

    val layoutParams = WindowManager.LayoutParams(
        1,
        1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.START or Gravity.TOP
        if (AndroidTarget.S) {
            // fix #1325
            alpha = app.inputManager.maximumObscuringOpacityForTouch
        }
    }

    init {
        useLogLifecycle()
        useAliveFlow(isRunning)
        useAliveToast("轨迹提示")
        StopServiceReceiver.autoRegister()
        onCreated { trackNotif.notifyService() }
        onCreated {
            scope.launch {
                combine(
                    pointListFlow,
                    swipePointListFlow,
                    curRotationFlow,
                    screenSizeFlow,
                ) { pointList, swipePointList, curRotation, screenSize ->
                    buildOverlayRegion(pointList, swipePointList, screenSize, curRotation)
                }.distinctUntilChanged().collect { region ->
                    if (region != null) {
                        applyOverlayRegion(region)
                    } else {
                        detachViewIfNeeded()
                    }
                }
            }
        }
        onDestroyed { detachViewIfNeeded() }
        onDestroyed { clearPosition() }
        onCreated {
            scope.launch {
                resizeFlow.collect {
                    screenSizeFlow.value = getScreenSize()
                }
            }
        }
    }

    companion object {
        private val pointListFlow = MutableStateFlow<List<TrackPoint>>(emptyList())
        private val swipePointListFlow = MutableStateFlow<List<SwipeTrackPoint>>(emptyList())
        private fun clearPosition() {
            pointListFlow.value = emptyList()
            swipePointListFlow.value = emptyList()
            autoIncreaseId.value = 0
        }

        private fun delayRemovePosition(id: Int) {
            runMainPost(7500) {
                pointListFlow.update { it.filter { v -> v.id != id } }
                swipePointListFlow.update { it.filter { v -> v.id != id } }
            }
        }

        val isRunning: StateFlow<Boolean>
            field = MutableStateFlow(false)

        fun start() = startForegroundServiceByClass(TrackService::class)
        fun stop() = stopServiceByClass(TrackService::class)
        fun addA11yNodePosition(node: AccessibilityNodeInfo) {
            if (!isRunning.value) return
            addXyPosition(
                node.casted.boundsInScreen.centerX().toFloat(),
                node.casted.boundsInScreen.centerY().toFloat(),
            )
        }

        fun addXyPosition(x: Float, y: Float) {
            if (!isRunning.value) return
            val p = TrackPoint(x, y)
            pointListFlow.update { it + p }
            delayRemovePosition(p.id)
        }

        fun addSwipePosition(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            duration: Long
        ) {
            if (!isRunning.value) return
            val p = SwipeTrackPoint(
                start = TrackPoint(startX, startY),
                end = TrackPoint(endX, endY),
                duration = duration,
            )
            swipePointListFlow.update { it + p }
        }
    }
}

private val autoIncreaseId = atomic(0)

private data class TrackPoint(
    val x: Float,
    val y: Float,
) {
    val id = autoIncreaseId.incrementAndGet()
    val screenWidth = ScreenUtils.getScreenWidth().toFloat()
    val screenHeight = ScreenUtils.getScreenHeight().toFloat()
    val rotation = app.compatDisplay.rotation

    fun getScreenCenter(size: Size, curRotation: Int): Offset {
        val curWidth = size.width
        val curHeight = size.height
        val (physX, physY) = screenToPhysical(x, y, screenWidth, screenHeight, rotation)
        return physicalToScreen(physX, physY, curWidth, curHeight, curRotation)
    }

    fun getCenter(size: Size, curRotation: Int, overlayOrigin: Offset): Offset {
        return getScreenCenter(size, curRotation) - overlayOrigin
    }

    private fun screenToPhysical(
        sx: Float, sy: Float,
        sw: Float, sh: Float,
        rot: Int,
    ): Offset = when (rot) {
        Surface.ROTATION_0 -> Offset(sx, sy)
        Surface.ROTATION_90 -> Offset(sh - sy, sx)
        Surface.ROTATION_180 -> Offset(sw - sx, sh - sy)
        Surface.ROTATION_270 -> Offset(sy, sw - sx)
        else -> Offset(sx, sy)
    }

    private fun physicalToScreen(
        px: Float, py: Float,
        sw: Float, sh: Float,
        rot: Int,
    ): Offset = when (rot) {
        Surface.ROTATION_0 -> Offset(px, py)
        Surface.ROTATION_90 -> Offset(py, sh - px)
        Surface.ROTATION_180 -> Offset(sw - px, sh - py)
        Surface.ROTATION_270 -> Offset(sw - py, px)
        else -> Offset(px, py)
    }
}

private data class SwipeTrackPoint(
    val start: TrackPoint,
    val end: TrackPoint,
    val duration: Long,
) {
    val id = autoIncreaseId.incrementAndGet()
}
