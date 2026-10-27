package com.jolsha.tv.ui.common

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * TV Focus animation utility for Android TV D-pad navigation.
 *
 * Provides smooth scale and elevation transitions on focus changes:
 * - Scales the view up on focus (1.05x by default)
 * - Raises elevation and translationZ so focused cards float above neighboring cards
 * - Restores default scale and elevation when focus is lost
 */
object FocusHighlightHelper {

    private const val DURATION_FOCUS_MS = 150L
    private val interpolator = DecelerateInterpolator()

    /**
     * Attaches focus scale and elevation animation to any [View].
     *
     * @param view The target View.
     * @param scale The focus scale factor (default 1.05f).
     * @param focusedElevation The elevation in pixels when focused (default 12f).
     */
    fun attachFocusAnimation(
        view: View,
        scale: Float = 1.05f,
        focusedElevation: Float = 12f,
        onFocusChange: ((View, Boolean) -> Unit)? = null
    ) {
        view.setOnFocusChangeListener { v, hasFocus ->
            animateFocus(v, hasFocus, scale, focusedElevation)
            onFocusChange?.invoke(v, hasFocus)
        }
    }

    /**
     * Immediately applies focus animation to a view.
     */
    fun animateFocus(
        view: View,
        hasFocus: Boolean,
        scale: Float = 1.05f,
        focusedElevation: Float = 12f
    ) {
        val targetScale = if (hasFocus) scale else 1.0f
        val targetElevation = if (hasFocus) focusedElevation else 0f

        // Bring focused item to the visual foreground
        view.z = if (hasFocus) 10f else 0f

        val scaleXAnim = ObjectAnimator.ofFloat(view, View.SCALE_X, targetScale)
        val scaleYAnim = ObjectAnimator.ofFloat(view, View.SCALE_Y, targetScale)
        val elevationAnim = ObjectAnimator.ofFloat(view, View.TRANSLATION_Z, targetElevation)

        AnimatorSet().apply {
            playTogether(scaleXAnim, scaleYAnim, elevationAnim)
            duration = DURATION_FOCUS_MS
            this.interpolator = FocusHighlightHelper.interpolator
            start()
        }
    }
}
