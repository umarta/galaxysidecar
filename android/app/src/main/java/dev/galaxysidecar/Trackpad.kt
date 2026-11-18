package dev.galaxysidecar

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

internal class Trackpad(
    private val slop: Float,
    private val density: Float,
    private val emit: (Int, Float, Float) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var moving = false
    private var twoFingers = false
    private var extraFingers = false
    private var dragging = false
    private val hold = Runnable {
        dragging = true
        emit(14, 0f, 0f)
    }

    fun handle(event: MotionEvent, width: Int, height: Int): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                lastX = event.x
                lastY = event.y
                startX = lastX
                startY = lastY
                handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(hold)
                if (dragging) {
                    emit(15, 0f, 0f)
                    dragging = false
                }
                if (event.pointerCount == 2) {
                    twoFingers = true
                    lastX = (event.getX(0) + event.getX(1)) / 2
                    lastY = (event.getY(0) + event.getY(1)) / 2
                    startX = lastX
                    startY = lastY
                } else {
                    extraFingers = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (extraFingers || (twoFingers && event.pointerCount < 2)) return true
                val x = if (twoFingers) (event.getX(0) + event.getX(1)) / 2 else event.x
                val y = if (twoFingers) (event.getY(0) + event.getY(1)) / 2 else event.y
                if (!moving && hypot(x - startX, y - startY) >= slop) {
                    moving = true
                    handler.removeCallbacks(hold)
                }
                if (moving || dragging) {
                    if (twoFingers) {
                        val dx = (x - lastX) / density
                        val dy = (y - lastY) / density
                        if (abs(dx) >= 1 || abs(dy) >= 1) emit(11, dx, dy)
                        else return true
                    } else {
                        emit(10, (x - lastX) / width.coerceAtLeast(1), (y - lastY) / height.coerceAtLeast(1))
                    }
                    lastX = x
                    lastY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!moving && !dragging && !extraFingers) emit(if (twoFingers) 12 else 13, 0f, 0f)
                reset()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    fun reset() {
        handler.removeCallbacks(hold)
        if (dragging) emit(15, 0f, 0f)
        moving = false
        twoFingers = false
        extraFingers = false
        dragging = false
    }
}
