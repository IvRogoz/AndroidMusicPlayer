package com.example.audiobookplayer

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.abs

class ReliableDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DrawerLayout(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeSizePx = (EDGE_SIZE_DP * context.resources.displayMetrics.density).toInt()
    private val hitRect = Rect()
    private var edgeGestureActive = false
    private var initialX = 0f
    private var initialY = 0f

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = event.x
                initialY = event.y
                edgeGestureActive = initialX <= edgeSizePx && !isTouchOnControl(initialX, initialY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (edgeGestureActive) {
                    val deltaX = event.x - initialX
                    val deltaY = abs(event.y - initialY)
                    if (deltaX > touchSlop && deltaX > deltaY) {
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                edgeGestureActive = false
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (!edgeGestureActive) {
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    private fun isTouchOnControl(x: Float, y: Float): Boolean {
        for (viewId in CONTROL_VIEW_IDS) {
            val view = findViewById<View>(viewId) ?: continue
            if (view.visibility != View.VISIBLE) {
                continue
            }
            view.getHitRect(hitRect)
            if (hitRect.contains(x.toInt(), y.toInt())) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val EDGE_SIZE_DP = 72
        private val CONTROL_VIEW_IDS = intArrayOf(
            R.id.previewView,
            R.id.seekInput,
            R.id.seekButton,
            R.id.playPauseButton,
            R.id.stopButton,
            R.id.previousButton,
            R.id.nextButton,
            R.id.skipBackButton,
            R.id.skipForwardButton,
            R.id.autoplaySwitch,
            R.id.openDrawerButton
        )
    }
}
