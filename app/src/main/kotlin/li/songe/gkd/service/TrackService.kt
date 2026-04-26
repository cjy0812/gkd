package li.songe.gkd.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Track indicator sizes are defined in dp first, then converted to px at runtime.
// This keeps the overlay readable across devices with different screen densities.
private const val TRACK_STROKE_WIDTH_DP = 2f
private const val TRACK_LINE_MARGIN_DP = 40f
private const val TRACK_PADDING_DP = 8f
private const val TRACK_FILL_RADIUS_DP = 6f
private const val TRACK_INNER_RING_RADIUS_DP = 12f
private const val TRACK_OUTER_RING_RADIUS_DP = 18f
private const val TRACK_REMOVE_DELAY = 7500L

private fun dpToPx(dp: Float): Float = dp * ScreenUtils.getScreenDensityDpi() / 160f

private val trackStrokeWidthPx get() = dpToPx(TRACK_STROKE_WIDTH_DP)
private val trackLineMarginPx get() = dpToPx(TRACK_LINE_MARGIN_DP)
private val trackPaddingPx get() = dpToPx(TRACK_PADDING_DP)
private val trackFillRadiusPx get() = dpToPx(TRACK_FILL_RADIUS_DP)
private val trackInnerRingRadiusPx get() = dpToPx(TRACK_INNER_RING_RADIUS_DP)
private val trackOuterRingRadiusPx get() = dpToPx(TRACK_OUTER_RING_RADIUS_DP)

// One point overlay must contain the full crosshair span plus a small clip buffer.
private val trackPointSizePx get() = ((trackLineMarginPx + trackPaddingPx) * 2).roundToInt()

class TrackService : LifecycleService(), OnSimpleLife {
    override fun onCreate() {
        super.onCreate()
        onCreated()
    }

    override fun onDestroy() {
        super.onDestroy()
        onDestroyed()
    }

    override val scope: CoroutineScope
        get() = lifecycleScope

    // Service-scoped overlay host state.
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayEntries = linkedMapOf<Int, OverlayEntry>()

    private var currentRotation = app.compatDisplay.rotation
    private var currentScreenSize = getScreenSize()

    override fun onConfigurationChanged(newConfig: Configuration) {
        currentRotation = app.compatDisplay.rotation
        currentScreenSize = getScreenSize()
        overlayEntries.values.toList().forEach { it.updateLayout() }
    }

    private fun getScreenSize() = Size(
        ScreenUtils.getScreenWidth().toFloat(),
        ScreenUtils.getScreenHeight().toFloat(),
    )

    // All track overlays share the same window flags; only size/position differ.
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
            alpha = app.inputManager.maximumObscuringOpacityForTouch
        }
    }

    // Overlay lifecycle coordination.
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

    private fun clearOverlays() {
        overlayEntries.values.toList().forEach { it.remove() }
        overlayEntries.clear()
        autoIncreaseId.value = 0
    }

    // Service lifecycle wiring.
    init {
        useLogLifecycle()
        useAliveFlow(isRunning)
        useAliveToast("轨迹提示")
        StopServiceReceiver.autoRegister()
        onCreated {
            instance = this
            currentRotation = app.compatDisplay.rotation
            currentScreenSize = getScreenSize()
        }
        onCreated { trackNotif.notifyService() }
        onDestroyed {
            if (instance === this) {
                instance = null
            }
        }
        onDestroyed { clearOverlays() }
    }

    // One visible track entity maps to one overlay entry.
    private interface OverlayEntry {
        val id: Int
        fun attach()
        fun remove()
        fun updateLayout()
        fun scheduleRemoval(delayMillis: Long)
    }

    private abstract inner class BaseOverlayEntry(
        final override val id: Int,
    ) : OverlayEntry {
        protected abstract val view: View

        private var layoutParams: WindowManager.LayoutParams? = null
        private var attached = false
        private val removeRunnable = Runnable { removeOverlay(id) }

        override fun attach() {
            if (attached) return
            val params = createLayoutParams().also(::updateLayoutParams)
            try {
                windowManager.addView(view, params)
                layoutParams = params
                attached = true
                onAttached()
            } catch (e: Throwable) {
                overlayEntries.remove(id)
                LogUtils.d(e)
            }
        }

        override fun remove() {
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

        override fun updateLayout() {
            val params = layoutParams ?: return
            if (!attached) return
            updateLayoutParams(params)
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Throwable) {
                LogUtils.d(e)
            }
        }

        override fun scheduleRemoval(delayMillis: Long) {
            mainHandler.removeCallbacks(removeRunnable)
            mainHandler.postDelayed(removeRunnable, delayMillis)
        }

        protected open fun onAttached() {}

        protected open fun onBeforeRemove() {}

        protected abstract fun updateLayoutParams(params: WindowManager.LayoutParams)
    }

    // Fixed-size overlay centered on a single click/long-click point.
    private inner class PointOverlayEntry(
        private val point: TrackPoint,
    ) : BaseOverlayEntry(point.id) {
        override val view = PointOverlayView(this@TrackService)

        override fun onAttached() {
            scheduleRemoval(TRACK_REMOVE_DELAY)
        }

        override fun updateLayoutParams(params: WindowManager.LayoutParams) {
            val center = point.getScreenCenter(currentScreenSize, currentRotation)
            val halfSize = trackPointSizePx / 2f
            params.width = trackPointSizePx
            params.height = trackPointSizePx
            params.x = (center.x - halfSize).roundToInt()
            params.y = (center.y - halfSize).roundToInt()
        }
    }

    // One swipe owns one stable local window; only the animation progress changes.
    private inner class SwipeOverlayEntry(
        private val swipePoint: SwipeTrackPoint,
    ) : BaseOverlayEntry(swipePoint.id) {
        override val view = SwipeOverlayView(this@TrackService, swipePoint.duration)
        private var layout = buildSwipeOverlayLayout()

        override fun onAttached() {
            view.updateGeometry(layout.localStart, layout.localEnd)
            view.onAnimationFinished = { scheduleRemoval(TRACK_REMOVE_DELAY) }
            view.startAnimation()
        }

        override fun onBeforeRemove() {
            view.stopAnimation()
        }

        override fun updateLayoutParams(params: WindowManager.LayoutParams) {
            layout = buildSwipeOverlayLayout()
            params.width = layout.width
            params.height = layout.height
            params.x = layout.left
            params.y = layout.top
            view.updateGeometry(layout.localStart, layout.localEnd)
        }

        private fun buildSwipeOverlayLayout(): SwipeOverlayLayout {
            // The swipe overlay bounds are computed once from the absolute start/end
            // points, then converted into local coordinates for drawing.
            val start = swipePoint.start.getScreenCenter(currentScreenSize, currentRotation)
            val end = swipePoint.end.getScreenCenter(currentScreenSize, currentRotation)
            val minX = min(start.x, end.x) - trackPaddingPx
            val minY = min(start.y, end.y) - trackPaddingPx
            val maxX = max(start.x, end.x) + trackPaddingPx
            val maxY = max(start.y, end.y) + trackPaddingPx
            val left = minX.roundToInt()
            val top = minY.roundToInt()
            val width = max(1, ceil(maxX - minX).toInt())
            val height = max(1, ceil(maxY - minY).toInt())
            val origin = Offset(left.toFloat(), top.toFloat())
            return SwipeOverlayLayout(
                left = left,
                top = top,
                width = width,
                height = height,
                localStart = start - origin,
                localEnd = end - origin,
            )
        }
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

        // Public entry for point-style tracks. Rendering is posted onto the
        // service's main thread so WindowManager operations stay serialized.
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

        // Public entry for swipe-style tracks. One swipe corresponds to one
        // independent overlay and one independent lifetime.
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
    val localStart: Offset,
    val localEnd: Offset,
)

private class PointOverlayView(
    context: Context,
) : BaseTrackOverlayView(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTrackPoint(canvas, width / 2f, height / 2f)
    }
}

private class SwipeOverlayView(
    context: Context,
    private val duration: Long,
) : BaseTrackOverlayView(context) {
    private var start = Offset.Zero
    private var end = Offset.Zero
    private var progress = 0f
    private var animator: ValueAnimator? = null
    var onAnimationFinished: (() -> Unit)? = null

    fun updateGeometry(start: Offset, end: Offset) {
        this.start = start
        this.end = end
        invalidate()
    }

    fun startAnimation() {
        stopAnimation()
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = this@SwipeOverlayView.duration
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        onAnimationFinished?.invoke()
                    }
                }
            })
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTrackPoint(canvas, start.x, start.y)
        val midX = start.x + (end.x - start.x) * progress
        val midY = start.y + (end.y - start.y) * progress
        drawTrackPoint(canvas, midX, midY)
        canvas.drawLine(start.x, start.y, midX, midY, trailLinePaint)
    }
}

private abstract class BaseTrackOverlayView(
    context: Context,
) : View(context) {
    // Yellow/red marker paints for the point indicator itself.
    private val markerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = trackStrokeWidthPx
        style = Paint.Style.STROKE
    }
    private val fillCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val strokeCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = trackStrokeWidthPx * 2
        style = Paint.Style.STROKE
    }
    protected val trailLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE
        strokeWidth = trackStrokeWidthPx
        style = Paint.Style.STROKE
    }

    // Shared point indicator drawing used by both point and swipe overlays.
    protected fun drawTrackPoint(canvas: Canvas, x: Float, y: Float) {
        canvas.drawLine(x, y - trackLineMarginPx, x, y + trackLineMarginPx, markerLinePaint)
        canvas.drawLine(x - trackLineMarginPx, y, x + trackLineMarginPx, y, markerLinePaint)
        canvas.drawCircle(x, y, trackFillRadiusPx, fillCirclePaint)
        canvas.drawCircle(x, y, trackInnerRingRadiusPx, strokeCirclePaint)
        canvas.drawCircle(x, y, trackOuterRingRadiusPx, strokeCirclePaint)
    }
}

private val autoIncreaseId = atomic(0)

private data class TrackPoint(
    val x: Float,
    val y: Float,
) {
    val id = autoIncreaseId.incrementAndGet()
    private val screenWidth = ScreenUtils.getScreenWidth().toFloat()
    private val screenHeight = ScreenUtils.getScreenHeight().toFloat()
    private val rotation = app.compatDisplay.rotation

    // Convert the captured screen point into the current screen orientation so
    // overlays still align after rotation changes.
    fun getScreenCenter(size: Size, curRotation: Int): Offset {
        val (physX, physY) = screenToPhysical(x, y, screenWidth, screenHeight, rotation)
        return physicalToScreen(physX, physY, size.width, size.height, curRotation)
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
