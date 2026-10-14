package io.github.umislat.aicecho.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import io.github.umislat.aicecho.R

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Activity.setInsetAwareContentView(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val initialPadding = intArrayOf(
        root.paddingLeft,
        root.paddingTop,
        root.paddingRight,
        root.paddingBottom
    )
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(
            initialPadding[0] + insets.left,
            initialPadding[1] + insets.top,
            initialPadding[2] + insets.right,
            initialPadding[3] + insets.bottom
        )
        WindowInsetsCompat.CONSUMED
    }
    setContentView(root)
    ViewCompat.requestApplyInsets(root)
}

internal fun View.applyMargins(horizontal: Int = 0, vertical: Int = 0) {
    layoutParams = (layoutParams as? ViewGroup.MarginLayoutParams ?: ViewGroup.MarginLayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )).apply {
        setMargins(context.dp(horizontal), context.dp(vertical), context.dp(horizontal), context.dp(vertical))
    }
}

internal fun Context.sectionTitle(@StringRes text: Int): TextView = TextView(this).apply {
    setText(text)
    textSize = 14f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
    setPadding(dp(4), dp(20), dp(4), dp(8))
}

internal fun Context.bodyText(@StringRes text: Int): TextView = TextView(this).apply {
    setText(text)
    textSize = 16f
    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
}

internal fun verticalLayout(context: Context): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(24))
}

internal fun Context.stateLabel(active: Boolean): String = getString(
    if (active) R.string.state_active else R.string.state_inactive
)
