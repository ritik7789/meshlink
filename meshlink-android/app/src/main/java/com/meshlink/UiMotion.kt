package com.meshlink

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Small, shared interaction touches.
 *
 * Chat interfaces live or die on feedback: a tap that produces no visible
 * response reads as a tap that did not register, and on a mesh where delivery is
 * genuinely uncertain that ambiguity is worse than usual. These are deliberately
 * short — under 200 ms — so they confirm an action without ever delaying it.
 */
object UiMotion {

    /** Presses in while held and springs back on release. */
    fun attachPressFeedback(view: View, scale: Float = 0.88f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(scale).scaleY(scale)
                        .setDuration(90).setInterpolator(DecelerateInterpolator()).start()

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(140).setInterpolator(OvershootInterpolator(2.5f)).start()
            }
            // Never consume: the view's own click handling must still run.
            false
        }
    }

    /** A brief pop confirming the message left the composer. */
    fun sendPulse(view: View) {
        view.animate().cancel()
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.animate().scaleX(1f).scaleY(1f)
            .setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
    }

    /** Rises into place, so a new message reads as arriving rather than blinking in. */
    fun animateIncoming(view: View, fromEnd: Boolean) {
        view.alpha = 0f
        view.translationY = 24f
        view.translationX = if (fromEnd) 20f else -20f
        view.animate()
            .alpha(1f).translationY(0f).translationX(0f)
            .setDuration(180).setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    /** Confirms a long-press registered before the menu appears. */
    fun longPressTick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Fades a view in or out, skipping the work when already in that state. */
    fun fade(view: View, visible: Boolean) {
        if (visible && view.visibility == View.VISIBLE) return
        if (!visible && view.visibility != View.VISIBLE) return

        if (visible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(140).start()
        } else {
            view.animate().alpha(0f).setDuration(140)
                .withEndAction { view.visibility = View.GONE }.start()
        }
    }
}
