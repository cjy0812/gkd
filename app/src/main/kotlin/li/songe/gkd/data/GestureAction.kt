package li.songe.gkd.data

import kotlinx.serialization.Serializable

@Serializable
enum class GestureDirection {
    up,
    down,
    left,
    right,
}

@Serializable
data class GestureAction(
    val type: String,
    val anchor: String? = null,
    val selector: String? = null,
    val direction: GestureDirection? = null,
    val distanceRatio: Float? = null,
    val xRatio: Float? = null,
    val yRatio: Float? = null,
    val steps: List<GestureAction>? = null,
    val timeoutMs: Long? = null,
    val holdMs: Long? = null,
    val durationMs: Long? = null,
) {
    fun selectorStrings(): List<String> {
        val selectors = mutableListOf<String>()
        anchor?.let(selectors::add)
        selector?.let(selectors::add)
        steps?.forEach { step ->
            selectors.addAll(step.selectorStrings())
        }
        return selectors
    }

    companion object {
        const val SwipeRelative = "swipeRelative"
        const val LongPressThenSwipe = "longPressThenSwipe"
        const val OffsetClick = "offsetClick"
        const val GestureChain = "gestureChain"
        const val AwaitState = "awaitState"
    }
}
