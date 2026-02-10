package li.songe.gkd.data

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import li.songe.gkd.a11y.A11yContext
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.service.A11yService
import li.songe.gkd.shizuku.casted
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.util.ScreenUtils
import li.songe.selector.Selector
import kotlin.math.max
import kotlin.math.min

class GestureActionExecutor(
    private val rule: ResolvedRule,
    private val action: GestureAction,
) {
    suspend fun perform(node: AccessibilityNodeInfo): ActionResult {
        return performAction(action, node)
    }

    private suspend fun performAction(
        action: GestureAction,
        node: AccessibilityNodeInfo,
    ): ActionResult {
        return when (action.type) {
            GestureAction.GestureChain -> performGestureChain(action, node)
            GestureAction.AwaitState -> performAwaitState(action)
            GestureAction.SwipeRelative -> performSwipeRelative(action, node, dragDrop = false)
            GestureAction.LongPressThenSwipe -> performSwipeRelative(action, node, dragDrop = true)
            GestureAction.OffsetClick -> performOffsetClick(action, node)
            else -> ActionResult(action = action.type, result = false)
        }
    }

    private suspend fun performGestureChain(
        action: GestureAction,
        node: AccessibilityNodeInfo,
    ): ActionResult {
        val steps = action.steps ?: return ActionResult(action = action.type, result = false)
        var lastResult = ActionResult(action = action.type, result = true)
        for (step in steps) {
            lastResult = performAction(step, node)
            if (!lastResult.result) {
                return ActionResult(action = action.type, result = false)
            }
        }
        return ActionResult(action = action.type, result = true)
    }

    private suspend fun performAwaitState(action: GestureAction): ActionResult {
        val selector = action.selector ?: return ActionResult(action = action.type, result = false)
        val selectorObj = Selector.parseOrNull(selector) ?: return ActionResult(action = action.type, result = false)
        val engine = A11yRuleEngine.instance ?: return ActionResult(action = action.type, result = false)
        val context = A11yContext(engine, interruptable = false)
        val timeout = (action.timeoutMs ?: 2000L).coerceAtLeast(200L)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeout) {
            val root = engine.safeActiveWindow
            if (root != null) {
                val hit = context.querySelfOrSelector(
                    root,
                    selectorObj,
                    rule.matchOption,
                )
                if (hit != null) {
                    return ActionResult(action = action.type, result = true)
                }
            }
            delay(100L)
        }
        return ActionResult(action = action.type, result = false)
    }

    private suspend fun performOffsetClick(
        action: GestureAction,
        node: AccessibilityNodeInfo,
    ): ActionResult {
        val anchorNode = resolveAnchorNode(action.anchor, node) ?: return ActionResult(
            action = action.type,
            result = false
        )
        val rect = anchorNode.casted.boundsInScreen
        val xRatio = action.xRatio ?: return ActionResult(action = action.type, result = false)
        val yRatio = action.yRatio ?: return ActionResult(action = action.type, result = false)
        val x = rect.left + rect.width() * xRatio
        val y = rect.top + rect.height() * yRatio
        val (cx, cy) = clampPoint(x, y)
        return performTap(action.type, cx, cy)
    }

    private suspend fun performSwipeRelative(
        action: GestureAction,
        node: AccessibilityNodeInfo,
        dragDrop: Boolean,
    ): ActionResult {
        val anchorNode = resolveAnchorNode(action.anchor, node) ?: return ActionResult(
            action = action.type,
            result = false
        )
        val rect = anchorNode.casted.boundsInScreen
        val direction = action.direction ?: return ActionResult(action = action.type, result = false)
        val distanceRatio = (action.distanceRatio ?: 0.5f).coerceIn(0.1f, 1.5f)
        val startX = rect.exactCenterX()
        val startY = rect.exactCenterY()
        val (endX, endY) = when (direction) {
            GestureDirection.up -> startX to (startY - rect.height() * distanceRatio)
            GestureDirection.down -> startX to (startY + rect.height() * distanceRatio)
            GestureDirection.left -> (startX - rect.width() * distanceRatio) to startY
            GestureDirection.right -> (startX + rect.width() * distanceRatio) to startY
        }
        val duration = (action.durationMs ?: 350L).coerceAtLeast(100L)
        val holdDuration = if (dragDrop) {
            action.holdMs?.coerceAtLeast(ViewConfiguration.getLongPressTimeout().toLong())
                ?: 500L
        } else {
            0L
        }
        return performSwipe(action.type, startX, startY, endX, endY, duration, holdDuration, dragDrop)
    }

    private suspend fun performSwipe(
        actionType: String,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
        holdDuration: Long,
        dragDrop: Boolean,
    ): ActionResult {
        val (sx, sy) = clampPoint(startX, startY)
        val (ex, ey) = clampPoint(endX, endY)
        val shizukuResult = shizukuContextFlow.value.swipe(
            sx,
            sy,
            ex,
            ey,
            duration,
            dragDrop,
        )
        if (shizukuResult) {
            return ActionResult(action = actionType, result = true, shizuku = true, position = sx to sy)
        }
        val gestureDuration = if (dragDrop && holdDuration > 0) duration + holdDuration else duration
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val gestureDescription = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, gestureDuration))
            .build()
        val result = A11yService.instance?.dispatchGesture(gestureDescription, null, null) != null
        return ActionResult(action = actionType, result = result, position = sx to sy)
    }

    private suspend fun performTap(
        actionType: String,
        x: Float,
        y: Float,
    ): ActionResult {
        if (shizukuContextFlow.value.tap(x, y)) {
            return ActionResult(action = actionType, result = true, shizuku = true, position = x to y)
        }
        val gestureDescription = GestureDescription.Builder().apply {
            val path = Path().apply { moveTo(x, y) }
            addStroke(GestureDescription.StrokeDescription(path, 0, ViewConfiguration.getTapTimeout().toLong()))
        }.build()
        val result = A11yService.instance?.dispatchGesture(gestureDescription, null, null) != null
        return ActionResult(action = actionType, result = result, position = x to y)
    }

    private fun resolveAnchorNode(
        anchorSelector: String?,
        fallback: AccessibilityNodeInfo,
    ): AccessibilityNodeInfo? {
        if (anchorSelector == null) return fallback
        val selector = Selector.parseOrNull(anchorSelector) ?: return null
        val engine = A11yRuleEngine.instance ?: return null
        val root = engine.safeActiveWindow ?: return null
        val context = A11yContext(engine, interruptable = false)
        return context.querySelfOrSelector(root, selector, rule.matchOption)
    }

    private fun clampPoint(x: Float, y: Float): Pair<Float, Float> {
        val width = ScreenUtils.getScreenWidth()
        val height = ScreenUtils.getScreenHeight()
        val cx = min(max(0f, x), width.toFloat())
        val cy = min(max(0f, y), height.toFloat())
        return cx to cy
    }
}
