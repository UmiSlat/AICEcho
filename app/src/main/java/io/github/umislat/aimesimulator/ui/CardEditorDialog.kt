package io.github.umislat.aimesimulator.ui

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

internal class CardEditorDialog(context: Context) : BottomSheetDialog(context) {
    @Suppress("DEPRECATION") // API 29 still needs the window-managed resize path.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val ownsImeInsets = Build.VERSION.SDK_INT >= 30
        window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, !ownsImeInsets)
            it.setSoftInputMode(
                if (ownsImeInsets) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        val container = requireNotNull(findViewById<View>(
            com.google.android.material.R.id.container
        ))
        val coordinator = requireNotNull(findViewById<View>(
            com.google.android.material.R.id.coordinator
        ))
        container.fitsSystemWindows = false
        coordinator.fitsSystemWindows = false
        // Legacy windows already fit/resize; modern windows reserve space here, once.
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            if (ownsImeInsets) {
                val safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, ime.bottom))
            }
            WindowInsetsCompat.CONSUMED
        }
        val sheet = requireNotNull(findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ))
        sheet.layoutParams = sheet.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
        behavior.apply {
            isDraggable = false
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        ViewCompat.requestApplyInsets(container)
    }
}
