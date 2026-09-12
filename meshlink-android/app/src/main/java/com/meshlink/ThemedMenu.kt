package com.meshlink

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Dark, icon-led menus matching the rest of the app.
 *
 * The platform's own overflow and list dialogs render as light, square-cornered
 * surfaces regardless of the activity's palette, which left every menu in this
 * app looking borrowed from a different one. This reuses the panel styling the
 * attachment menu already established, so menus are one consistent surface.
 */
object ThemedMenu {

    data class Item(
        val label: String,
        val iconRes: Int,
        /** Tint for the icon; destructive actions read red. */
        val tint: String = "#E9EDEF",
        val onClick: () -> Unit
    )

    /** Anchored menu, used for the toolbar overflow. */
    fun showAnchored(activity: Activity, anchor: View, items: List<Item>) {
        val panel = build(activity, items)
        val popup = newPopup(panel)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        wire(panel, items, popup)
        popup.showAsDropDown(anchor, -panel.measuredWidth + anchor.width, 8)
        animateIn(panel, fromTop = true)
    }

    /** Centred menu, used where there is no sensible anchor such as a long-press. */
    fun showCentred(activity: Activity, title: String?, items: List<Item>) {
        val panel = build(activity, items, title)
        val popup = newPopup(panel)
        wire(panel, items, popup)
        popup.showAtLocation(
            activity.window.decorView, Gravity.CENTER, 0, 0
        )
        animateIn(panel, fromTop = false)
    }

    private fun newPopup(panel: View) = PopupWindow(
        panel,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    ).apply {
        elevation = 24f
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        animationStyle = 0
    }

    private fun build(activity: Activity, items: List<Item>, title: String? = null): LinearLayout {
        val panel = activity.layoutInflater
            .inflate(R.layout.popup_menu, null) as LinearLayout

        if (title != null) {
            val header = TextView(activity).apply {
                text = title
                setTextColor(Color.parseColor("#8696A0"))
                textSize = 12f
                setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8))
            }
            panel.addView(header)
        }

        items.forEach { item ->
            val row = activity.layoutInflater
                .inflate(R.layout.item_popup_menu, panel, false)
            row.findViewById<TextView>(R.id.tvMenuLabel).apply {
                text = item.label
                setTextColor(Color.parseColor(item.tint))
            }
            row.findViewById<ImageView>(R.id.ivMenuIcon).apply {
                setImageResource(item.iconRes)
                imageTintList = android.content.res.ColorStateList
                    .valueOf(Color.parseColor(item.tint))
            }
            panel.addView(row)
        }
        return panel
    }

    private fun wire(panel: LinearLayout, items: List<Item>, popup: PopupWindow) {
        // Skip the optional title, which is not an item.
        val offset = panel.childCount - items.size
        items.forEachIndexed { index, item ->
            panel.getChildAt(offset + index).setOnClickListener {
                popup.dismiss()
                item.onClick()
            }
        }
    }

    /** Grows from the edge it belongs to, so the motion points back at its origin. */
    private fun animateIn(panel: View, fromTop: Boolean) {
        panel.alpha = 0f
        panel.scaleX = 0.85f
        panel.scaleY = 0.85f
        panel.pivotX = panel.measuredWidth.toFloat()
        panel.pivotY = if (fromTop) 0f else panel.measuredHeight / 2f
        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
