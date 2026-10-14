package io.github.umislat.aicecho.ui

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.github.umislat.aicecho.R
import io.github.umislat.aicecho.data.CardProfile
import io.github.umislat.aicecho.data.CardStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class, qualifiers = "w360dp-h800dp")
class CardEditorImeTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var dialog: BottomSheetDialog
    private lateinit var container: ViewGroup
    private val profile = requireNotNull(CardProfile.create(
        "Synthetic", "012E112233445566",
        spad0 = "00112233445566778899AABBCCDDEEFF",
        idBlock = "012E1122334455660078000000000000",
        accessCode = "12345678901234567890",
        profileId = "editor-fixture"
    ))

    @Before fun openEditor() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aicecho", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("storage_migrated", true).commit()
        CardStore(context).apply {
            put(profile)
            setAdvancedMode(true)
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        val method = MainActivity::class.java.getDeclaredMethod(
            "showEditor", CardProfile::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java
        )
        method.isAccessible = true
        method.invoke(activity, profile, null, null, null, null)
        dialog = ShadowDialog.getLatestDialog() as BottomSheetDialog
        // Robolectric shows the dialog without delivering this window attachment callback.
        dialog.onAttachedToWindow()
        container = requireNotNull(dialog.findViewById(com.google.android.material.R.id.container))
    }

    @After fun closeEditor() {
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Suppress("DEPRECATION")
    @Test fun windowHasOneKeyboardAdjustmentOwnerAndSheetUsesAvailableHeight() {
        val expectedMode = if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        assertEquals(expectedMode, requireNotNull(dialog.window).attributes.softInputMode and
            WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        val sheet = requireNotNull(dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, sheet.layoutParams.height)
    }

    @Test fun keyboardKeepsActionsInBoundsAndFieldsScrollable() {
        layoutWithKeyboard(360, 800, 320)
        assertEditableViewport(480)
    }

    @Test fun ordinaryEditorKeepsVisibleFieldsAndActionsReachableWithKeyboard() {
        dialog.dismiss()
        CardStore(activity).setAdvancedMode(false)
        MainActivity::class.java.getDeclaredMethod(
            "showEditor", CardProfile::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java
        ).apply { isAccessible = true }.invoke(activity, profile, null, null, null, null)
        dialog = ShadowDialog.getLatestDialog() as BottomSheetDialog
        dialog.onAttachedToWindow()
        container = requireNotNull(dialog.findViewById(com.google.android.material.R.id.container))
        assertEquals(2, fields().size)
        layoutWithKeyboard(360, 800, 320)
        assertEditableViewport(480)
    }

    @Test fun shortLandscapeWindowCanReachLastField() {
        layoutWithKeyboard(800, 360, 180)
        assertEditableViewport(180)
    }

    @Test fun repeatedKeyboardInsetsDoNotAccumulateAndClosingRestoresSpace() {
        layoutWithKeyboard(360, 800, 320)
        val scroll = views(container).filterIsInstance<NestedScrollView>().single()
        val keyboardHeight = scroll.height
        layoutWithKeyboard(360, 800, 320)
        assertEquals(keyboardHeight, scroll.height)
        layoutWithKeyboard(360, 800, 0)
        assertTrue(scroll.height > keyboardHeight)
        layoutWithKeyboard(360, 800, 320)
        assertEquals(keyboardHeight, scroll.height)
        assertEquals(profile.label, fields().first().text.toString())
    }

    @Test fun saveStillPersistsEditsWithKeyboardVisible() {
        layoutWithKeyboard(360, 800, 320)
        fields().first().setText("Renamed")
        action(R.string.save).performClick()
        assertFalse(dialog.isShowing)
        assertEquals(profile.copy(label = "Renamed"), CardStore(activity).profiles().single())
    }

    @Test fun cancelWithKeyboardVisibleDoesNotChangeCard() {
        layoutWithKeyboard(360, 800, 320)
        fields().first().setText("Discarded")
        action(android.R.string.cancel).performClick()
        assertFalse(dialog.isShowing)
        assertEquals(profile, CardStore(activity).profiles().single())
    }

    @Test fun invalidInputWithKeyboardVisibleStaysEditableWithoutSaving() {
        layoutWithKeyboard(360, 800, 320)
        fields()[2].setText("not-an-idm")
        action(R.string.save).performClick()
        assertTrue(dialog.isShowing)
        assertEquals(profile, CardStore(activity).profiles().single())
        assertEditableViewport(480)
    }

    private fun layoutWithKeyboard(width: Int, height: Int, ime: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
            .build()
        ViewCompat.dispatchApplyWindowInsets(container, insets)
        // API 29 delegates IME resizing to the window; API 30+ reserves it in the container.
        val windowHeight = if (android.os.Build.VERSION.SDK_INT < 30) height - ime else height
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(windowHeight, View.MeasureSpec.EXACTLY)
        )
        container.layout(0, 0, width, windowHeight)
    }

    private fun assertEditableViewport(keyboardTop: Int) {
        val scroll = views(container).filterIsInstance<NestedScrollView>().single()
        assertTrue("Editor scroll viewport disappeared", scroll.height > 0)
        val saveBounds = bounds(action(R.string.save))
        assertTrue("Save button moved above the window", saveBounds.top >= 0)
        assertTrue("Save button is covered by the keyboard", saveBounds.bottom <= keyboardTop)
        assertTrue("Save button lost its touch target", saveBounds.height() >= 48)
        fields().forEach { field ->
            field.requestFocus()
            field.requestRectangleOnScreen(Rect(0, 0, field.width, field.height), true)
            val fieldBounds = bounds(field)
            val viewport = bounds(scroll)
            assertTrue("Field $fieldBounds is outside viewport $viewport", viewport.contains(fieldBounds))
        }
    }

    private fun fields(): List<EditText> = views(container).filterIsInstance<EditText>()
        .filter { field ->
            generateSequence<View>(field) { it.parent as? View }
                .all { it.visibility == View.VISIBLE }
        }.toList()

    private fun action(label: Int): MaterialButton = views(container)
        .filterIsInstance<MaterialButton>().single { it.text == activity.getString(label) }

    private fun bounds(view: View): Rect = Rect(
        view.scrollX, view.scrollY, view.scrollX + view.width, view.scrollY + view.height
    ).also {
        container.offsetDescendantRectToMyCoords(view, it)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
    }
}
