package io.github.umislat.aimesimulator.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import io.github.umislat.aimesimulator.R
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.nfc.PhysicalCardReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class, qualifiers = "w360dp-h800dp")
class CardReaderModeTest {
    private lateinit var controller: ActivityController<CardReaderActivity>
    private lateinit var activity: CardReaderActivity
    private lateinit var store: CardStore
    private val capture = PhysicalCardReader.Capture(
        "012E112233445566", "88B4", "00112233445566778899AABBCCDDEEFF",
        "012E1122334455660078000000000000"
    )

    @Before fun createScreen() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("storage_migrated", true).commit()
        store = CardStore(context)
        controller = Robolectric.buildActivity(CardReaderActivity::class.java).create().start().visible()
        activity = controller.get()
    }

    @After fun closeScreen() { controller.stop().destroy() }

    @Test fun ordinaryFelicaResultHidesRawDataButReturnsEveryCapturedField() {
        showFelica(capture)
        val text = screenText()
        assertTrue(text.contains(activity.getString(R.string.felica_card_read_simple)))
        assertFalse(text.contains("01 2E 11 22 33 44 55 66"))
        assertFalse(text.contains("88 B4"))
        useResult()
        val result = shadowOf(activity).resultIntent
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(capture.idm, result.getStringExtra(CardReaderActivity.EXTRA_IDM))
        assertEquals(capture.systemCode, result.getStringExtra(CardReaderActivity.EXTRA_SYSTEM_CODE))
        assertEquals(capture.spad0, result.getStringExtra(CardReaderActivity.EXTRA_SPAD0))
        assertEquals(capture.idBlock, result.getStringExtra(CardReaderActivity.EXTRA_ID_BLOCK))
        assertFalse(result.hasExtra(CardReaderActivity.EXTRA_ACCESS_CODE))
    }

    @Test fun advancedFelicaResultShowsCapturedBlocks() {
        store.setAdvancedMode(true)
        showFelica(capture)
        val text = screenText()
        assertTrue(text.contains("01 2E 11 22 33 44 55 66"))
        assertTrue(text.contains("88 B4"))
        assertTrue(text.contains(capture.spad0!!.chunked(4).joinToString(" ")))
        assertTrue(text.contains(capture.idBlock!!.chunked(4).joinToString(" ")))
    }

    @Test fun missingBlocksAreNotPresentedOrReturnedAsCapturedData() {
        store.setAdvancedMode(true)
        showFelica(capture.copy(spad0 = null, idBlock = null))
        assertTrue(screenText().contains(activity.getString(R.string.card_details_not_saved)))
        useResult()
        assertNull(shadowOf(activity).resultIntent.getStringExtra(CardReaderActivity.EXTRA_SPAD0))
        assertNull(shadowOf(activity).resultIntent.getStringExtra(CardReaderActivity.EXTRA_ID_BLOCK))
    }

    @Test fun ordinaryMifareResultHidesUidAndStillReturnsCardNumber() {
        showMifare()
        assertFalse(screenText().contains("12 34 AB CD"))
        assertTrue(screenText().contains("1234 5678 9012 3456 7890"))
        useResult()
        val result = shadowOf(activity).resultIntent
        assertEquals("12345678901234567890", result.getStringExtra(CardReaderActivity.EXTRA_ACCESS_CODE))
        assertFalse(result.hasExtra(CardReaderActivity.EXTRA_IDM))
    }

    @Test fun advancedMifareResultShowsUidWithoutChangingReturnedData() {
        store.setAdvancedMode(true)
        showMifare()
        assertTrue(screenText().contains("12 34 AB CD"))
        useResult()
        assertEquals("12345678901234567890",
            shadowOf(activity).resultIntent.getStringExtra(CardReaderActivity.EXTRA_ACCESS_CODE))
    }

    private fun showFelica(value: PhysicalCardReader.Capture) {
        CardReaderActivity::class.java.getDeclaredMethod("showFelicaCapture",
            PhysicalCardReader.Capture::class.java).apply { isAccessible = true }.invoke(activity, value)
    }

    private fun showMifare() {
        CardReaderActivity::class.java.getDeclaredMethod("showMifareRecognition",
            String::class.java, String::class.java).apply { isAccessible = true }
            .invoke(activity, "1234ABCD", "12345678901234567890")
    }

    private fun useResult() {
        views(activity.findViewById(android.R.id.content)).filterIsInstance<MaterialButton>()
            .single { it.text == activity.getString(R.string.use_in_card_profile) }.performClick()
    }

    private fun screenText(): String = views(activity.findViewById(android.R.id.content))
        .filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
    }
}
