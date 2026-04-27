package li.songe.gkd.service

import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import li.songe.gkd.app
import li.songe.gkd.notif.StopServiceReceiver
import li.songe.gkd.notif.trackNotif
import li.songe.gkd.shizuku.casted
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.OnSimpleLife
import li.songe.gkd.util.ScreenUtils
import li.songe.gkd.util.startForegroundServiceByClass
import li.songe.gkd.util.stopServiceByClass
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val BASE_STROKE_WIDTH = 2f
private const val BASE_LINE_MARGIN = 75f
private const val BASE_CENTER_RADIUS = 10f
private const val BASE_INNER_RING_RADIUS = 20f
private const val BASE_OUTER_RING_RADIUS = 30f
private const val BASE_RING_STROKE_WIDTH = 4f
private const val BASE_OVERLAY_PADDING = 8f

private const val TRACK_BASELINE_DENSITY = 440f
private const val TRACK_BASELINE_SHORT_SIDE = 1080f
private const val TRACK_MIN_SCALE = 0.65f
private const val TRACK_MAX_SCALE = 1.15f
private const val TRACK_REMOVE_DELAY = 7500L

private data class TrackMetrics(
    val strokeWidth: Float,
    val lineMargin: Float,
    val centerRadius: Float,
    val innerRingRadius: Float,
    val outerRingRadius: Float,
    val ringStrokeWidth: Float,
    val overlayPadding: Float,
    val trackReach: Float,
    val pointOverlaySize: Int,
)

// 以 1080 宽 / 440dpi 设备作为基准，优先参考 dpi；
// 对更窄的屏幕额外缩小，避免同 dpi 下窄屏设备视觉占比过大。
// 最终再钳制到 0.65 ~ 1.15，避免极端设备把指示器缩放得过小或过大。
private fun getTrackScale(): Float {
    val densityScale = ScreenUtils.getScreenDensityDpi() / TRACK_BASELINE_DENSITY
    val shortSide = min(ScreenUtils.getScreenWidth(), ScreenUtils.getScreenHeight()).toFloat()
    val shortSideScale = shortSide / TRACK_BASELINE_SHORT_SIDE
    val rawScale = densityScale * min(1f, shortSideScale)
    return rawScale.coerceIn(TRACK_MIN_SCALE, TRACK_MAX_SCALE)
}

private fun getTrackMetrics(): TrackMetrics {
    val scale = getTrackScale()
    val strokeWidth = BASE_STROKE_WIDTH * scale
    val lineMargin = BASE_LINE_MARGIN * scale
    val centerRadius = BASE_CENTER_RADIUS * scale
    val innerRingRadius = BASE_INNER_RING_RADIUS * scale
    val outerRingRadius = BASE_OUTER_RING_RADIUS * scale
    val ringStrokeWidth = BASE_RING_STROKE_WIDTH * scale
    val overlayPadding = BASE_OVERLAY_PADDING * scale
    val trackReach = max(lineMargin, outerRingRadius + ringStrokeWidth / 2f)
    val pointOverlaySize = ((trackReach + overlayPadding) * 2f).roundToInt().coerceAtLeast(1)
    return TrackMetrics(
        strokeWidth = strokeWidth,
        lineMargin = lineMargin,
        centerRadius = centerRadius,
        innerRingRadius = innerRingRadius,
        outerRingRadius = outerRingRadius,
        ringStrokeWidth = ringStrokeWidth,
        overlayPadding = overlayPadding,
        trackReach = trackReach,
        pointOverlaySize = pointOverlaySize,
    )
}

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
    override val scope: CoroutineScope
        get() = lifecycleScope

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayEntries = linkedMapOf<Int, OverlayEntry>()
    private val resizeFlow = MutableSharedFlow<Unit>()
    private var currentScreenSize = getScreenSize()
    private var currentTrackMetrics = getTrackMetrics()

    override fun onConfigurationChanged(newConfig: Configuration) {
        lifecycleScope.launch { resizeFlow.emit(Unit) }
    }

    val curRotationFlow = MutableStateFlow(app.compatDisplay.rotation)

    init {
        useLogLifecycle()
        useAliveFlow(isRunning)
        useAliveToast("轨迹提示")
        StopServiceReceiver.autoRegister()
        onCreated {
            instance = this
        }
        onCreated { trackNotif.notifyService() }
        onCreated {
            scope.launch {
                resizeFlow.collect {
                    curRotationFlow.value = app.compatDisplay.rotation
                    currentScreenSize = getScreenSize()
                    currentTrackMetrics = getTrackMetrics()
                    overlayEntries.values.toList().forEach { it.updateLayout() }
                }
            }
        }
        onDestroyed {
            if (instance === this) {
                instance = null
            }
        }
        onDestroyed { clearPosition() }
    }

    private fun getScreenSize() = Size(
        ScreenUtils.getScreenWidth().toFloat(),
        ScreenUtils.getScreenHeight().toFloat(),
    )

    private fun createLayoutParams() = WindowManager.LayoutParams(
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

    private fun createComposeView(content: @Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeSavedStateRegistryOwner(this@TrackService)
        setViewTreeLifecycleOwner(this@TrackService)
        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }

    // 点位绘制统一读取同一份尺寸数据，保证普通点和 swipe 风格一致。
    private fun DrawScope.drawTrackPoint(center: Offset, metrics: TrackMetrics = currentTrackMetrics) {
        drawLine(
            color = Color.Yellow,
            start = Offset(center.x, center.y - metrics.lineMargin),
            end = Offset(center.x, center.y + metrics.lineMargin),
            strokeWidth = metrics.strokeWidth,
        )
        drawLine(
            color = Color.Yellow,
            start = Offset(center.x - metrics.lineMargin, center.y),
            end = Offset(center.x + metrics.lineMargin, center.y),
            strokeWidth = metrics.strokeWidth,
        )
        drawCircle(
            color = Color.Red,
            radius = metrics.centerRadius,
            center = center,
        )
        drawCircle(
            color = Color.Red,
            radius = metrics.innerRingRadius,
            center = center,
            style = Stroke(metrics.ringStrokeWidth),
        )
        drawCircle(
            color = Color.Red,
            radius = metrics.outerRingRadius,
            center = center,
            style = Stroke(metrics.ringStrokeWidth),
        )
    }

    @Composable
    private fun ComposeContent() {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawTrackPoint(Offset(size.width / 2f, size.height / 2f))
        }
    }

    @Composable
    private fun SwipePointCanvas(
        swipePoint: SwipeTrackPoint,
        layout: SwipeOverlayLayout,
        onAnimationFinished: () -> Unit,
    ) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(swipePoint.duration.toInt(), easing = LinearEasing),
            )
            onAnimationFinished()
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val startCenter = layout.startCenter
            drawTrackPoint(startCenter)
            val endCenter = layout.endCenter
            val midCenter = Offset(
                lerp(startCenter.x, endCenter.x, progress.value),
                lerp(startCenter.y, endCenter.y, progress.value),
            )
            drawTrackPoint(midCenter)
            drawLine(
                color = Color.Blue,
                start = startCenter,
                end = midCenter,
                strokeWidth = currentTrackMetrics.strokeWidth,
            )
        }
    }

    // 每个轨迹实体持有一个独立 overlay,只在各自的小窗口中绘制
    private abstract inner class OverlayEntry(
        val id: Int,
    ) {
        protected abstract val view: ComposeView

        private var attached = false
        private var layoutParams: WindowManager.LayoutParams? = null
        private val removeRunnable = Runnable { removeOverlay(id) }

        fun attach() {
            if (attached) return
            val params = createLayoutParams().also(::updateLayoutParams)
            var added = false
            try {
                windowManager.addView(view, params)
                added = true
                layoutParams = params
                attached = true
                onAttached()
            } catch (e: Throwable) {
                if (added) {
                    try {
                        windowManager.removeView(view)
                    } catch (_: Throwable) {
                    }
                }
                attached = false
                layoutParams = null
                overlayEntries.remove(id)
                LogUtils.d(e)
            }
        }

        fun remove() {
            mainHandler.removeCallbacks(removeRunnable)
            onBeforeRemove()
            if (attached) {
                try {
                    windowManager.removeView(view)
                } catch (e: Throwable) {
                    LogUtils.d(e)
                }
            }
            attached = false
            layoutParams = null
        }

        fun updateLayout() {
            val params = layoutParams ?: return
            if (!attached) return
            updateLayoutParams(params)
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Throwable) {
                LogUtils.d(e)
            }
        }

        protected fun scheduleRemoval() {
            mainHandler.removeCallbacks(removeRunnable)
            mainHandler.postDelayed(removeRunnable, TRACK_REMOVE_DELAY)
        }

        protected open fun onAttached() {}

        protected open fun onBeforeRemove() {}

        protected abstract fun updateLayoutParams(params: WindowManager.LayoutParams)
    }

    private inner class PointOverlayEntry(
        private val point: TrackPoint,
    ) : OverlayEntry(point.id) {
        override val view by lazy {
            createComposeView { ComposeContent() }
        }

        override fun onAttached() {
            scheduleRemoval()
        }

        override fun updateLayoutParams(params: WindowManager.LayoutParams) {
            val center = point.getCenter(currentScreenSize, curRotationFlow.value)
            val halfSize = currentTrackMetrics.pointOverlaySize / 2f
            params.width = currentTrackMetrics.pointOverlaySize
            params.height = currentTrackMetrics.pointOverlaySize
            params.x = (center.x - halfSize).roundToInt()
            params.y = (center.y - halfSize).roundToInt()
        }
    }

    private inner class SwipeOverlayEntry(
        private val swipePoint: SwipeTrackPoint,
    ) : OverlayEntry(swipePoint.id) {
        private val layoutFlow = MutableStateFlow(buildLayout())

        override val view by lazy {
            createComposeView {
                val layout by layoutFlow.collectAsState()
                SwipePointCanvas(
                    swipePoint = swipePoint,
                    layout = layout,
                    onAnimationFinished = { scheduleRemoval() },
                    )
                }
            }

        override fun updateLayoutParams(params: WindowManager.LayoutParams) {
            val layout = buildLayout()
            layoutFlow.value = layout
            params.width = layout.width
            params.height = layout.height
            params.x = layout.left
            params.y = layout.top
        }

        private fun buildLayout(): SwipeOverlayLayout {
            // 轨迹窗口只包住起点、终点和十字线可见范围，不再合并其他轨迹。
            val startCenter = swipePoint.start.getCenter(currentScreenSize, curRotationFlow.value)
            val endCenter = swipePoint.end.getCenter(currentScreenSize, curRotationFlow.value)
            val reach = currentTrackMetrics.trackReach + currentTrackMetrics.overlayPadding
            val left = floor(min(startCenter.x, endCenter.x) - reach).toInt()
            val top = floor(min(startCenter.y, endCenter.y) - reach).toInt()
            val right = ceil(max(startCenter.x, endCenter.x) + reach).toInt()
            val bottom = ceil(max(startCenter.y, endCenter.y) + reach).toInt()
            val origin = Offset(left.toFloat(), top.toFloat())
            return SwipeOverlayLayout(
                left = left,
                top = top,
                width = max(1, right - left),
                height = max(1, bottom - top),
                startCenter = startCenter - origin,
                endCenter = endCenter - origin,
            )
        }
    }

    private fun addPointOverlay(point: TrackPoint) {
        val entry = PointOverlayEntry(point)
        overlayEntries[entry.id] = entry
        entry.attach()
    }

    private fun addSwipeOverlay(swipePoint: SwipeTrackPoint) {
        val entry = SwipeOverlayEntry(swipePoint)
        overlayEntries[entry.id] = entry
        entry.attach()
    }

    private fun removeOverlay(id: Int) {
        overlayEntries.remove(id)?.remove()
    }

    private fun clearPosition() {
        overlayEntries.values.toList().forEach { it.remove() }
        overlayEntries.clear()
        autoIncreaseId.value = 0
    }

    companion object {
        @Volatile
        private var instance: TrackService? = null

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
            val point = TrackPoint(x, y)
            val service = instance ?: return
            service.mainHandler.post {
                if (instance === service && isRunning.value) {
                    service.addPointOverlay(point)
                }
            }
        }

        fun addSwipePosition(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            duration: Long,
        ) {
            if (!isRunning.value) return
            val swipePoint = SwipeTrackPoint(
                start = TrackPoint(startX, startY),
                end = TrackPoint(endX, endY),
                duration = duration,
            )
            val service = instance ?: return
            service.mainHandler.post {
                if (instance === service && isRunning.value) {
                    service.addSwipeOverlay(swipePoint)
                }
            }
        }
    }
}

private data class SwipeOverlayLayout(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val startCenter: Offset,
    val endCenter: Offset,
)

private val autoIncreaseId = atomic(0)

private data class TrackPoint(
    val x: Float,
    val y: Float,
) {
    val id = autoIncreaseId.incrementAndGet()
    val screenWidth = ScreenUtils.getScreenWidth().toFloat()
    val screenHeight = ScreenUtils.getScreenHeight().toFloat()
    val rotation = app.compatDisplay.rotation

    // 坐标换算逻辑,旋转后仍能映射回当前屏幕方向
    fun getCenter(size: Size, curRotation: Int): Offset {
        val curWidth = size.width
        val curHeight = size.height
        val (physX, physY) = screenToPhysical(x, y, screenWidth, screenHeight, rotation)
        return physicalToScreen(physX, physY, curWidth, curHeight, curRotation)
    }

    private fun screenToPhysical(
        sx: Float,
        sy: Float,
        sw: Float,
        sh: Float,
        rot: Int,
    ): Offset = when (rot) {
        Surface.ROTATION_0 -> Offset(sx, sy)
        Surface.ROTATION_90 -> Offset(sh - sy, sx)
        Surface.ROTATION_180 -> Offset(sw - sx, sh - sy)
        Surface.ROTATION_270 -> Offset(sy, sw - sx)
        else -> Offset(sx, sy)
    }

    private fun physicalToScreen(
        px: Float,
        py: Float,
        sw: Float,
        sh: Float,
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
