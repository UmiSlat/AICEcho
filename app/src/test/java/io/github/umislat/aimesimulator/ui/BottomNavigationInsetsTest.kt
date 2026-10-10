package io.github.umislat.aimesimulator.ui

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.github.umislat.aimesimulator.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36], application = Application::class, qualifiers = "w360dp-h800dp")
class BottomNavigationInsetsTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var root: ViewGroup
    private lateinit var navigation: BottomNavigationView
    private var initialBottomPadding = 0

    @Before fun createScreen() {
        RuntimeEnvironment.getApplication().getSharedPreferences("aime_simulator", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("storage_migrated", true).commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        navigation = (0 until root.childCount).map { root.getChildAt(it) }
            .filterIsInstance<BottomNavigationView>().single()
        initialBottomPadding = navigation.paddingBottom
        // Measure the real shell in a synthetic viewport without Robolectric's empty root insets.
        (root.parent as ViewGroup).removeView(root)
    }

    @After fun destroyScreen() {
        controller.pause().stop().destroy()
    }

    @Test fun gestureNavigationKeepsOriginalHeightAndReportedSafeArea() {
        applyInsets(gestureInsets(), 360, 800)
        assertEquals(24, root.paddingBottom)
        assertEquals(initialBottomPadding, navigation.paddingBottom)
        assertTargetsInSafeArea()
    }

    @Test fun hiddenGestureIndicatorDoesNotIntroduceArtificialPadding() {
        applyInsets(gestureInsets(bottom = 0), 360, 800)
        assertEquals(0, root.paddingBottom)
        assertEquals(initialBottomPadding, navigation.paddingBottom)
        assertTargetsInSafeArea()
    }

    @Test fun compactLandscapeKeepsTargetsInBoundsAndContentVisible() {
        applyInsets(gestureInsets(), 800, 360)
        assertTargetsInSafeArea()
        assertTrue(root.getChildAt(1).height > 0)
    }

    @Test fun buttonNavigationDoesNotAddRotationClearance() {
        applyInsets(buttonInsets(), 360, 800)
        assertEquals(48, root.paddingBottom)
        assertEquals(initialBottomPadding, navigation.paddingBottom)
    }

    @Test fun sideButtonNavigationUsesOnlyTheReportedSystemInset() {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 48, 0))
            .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 0, 48, 0))
            .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(0, 0, 48, 0))
            .build()
        applyInsets(insets, 800, 360)
        assertEquals(48, root.paddingRight)
        assertEquals(initialBottomPadding, navigation.paddingBottom)
    }

    @Test fun repeatedInsetsAndNavigationModeChangesDoNotAccumulatePadding() {
        applyInsets(gestureInsets(), 360, 800)
        val paddedHeight = navigation.height
        applyInsets(gestureInsets(), 360, 800)
        assertEquals(paddedHeight, navigation.height)
        assertEquals(initialBottomPadding, navigation.paddingBottom)
        applyInsets(buttonInsets(), 360, 800)
        assertEquals(initialBottomPadding, navigation.paddingBottom)
        applyInsets(gestureInsets(), 360, 800)
        assertEquals(paddedHeight, navigation.height)
        assertEquals(initialBottomPadding, navigation.paddingBottom)
    }

    @Test fun largerExistingSafeInsetIsNotPaddedAgain() {
        val insets = WindowInsetsCompat.Builder(gestureInsets())
            .setDisplayCutout(DisplayCutoutCompat(Rect(0, 0, 0, 100),
                listOf(Rect(170, 700, 190, 800))))
            .build()
        applyInsets(insets, 360, 800)
        assertEquals(100, root.paddingBottom)
        assertEquals(initialBottomPadding, navigation.paddingBottom)
    }

    @Test fun navigationStillSelectsSettingsAndCards() {
        applyInsets(gestureInsets(), 360, 800)
        val settingsId = (0 until navigation.menu.size()).map { navigation.menu.getItem(it) }
            .single { it.title == activity.getString(R.string.settings) }.itemId
        requireNotNull(navigation.findViewById<View>(settingsId)).performClick()
        assertEquals(settingsId, navigation.selectedItemId)
        val cardsId = navigation.menu.getItem(0).itemId
        requireNotNull(navigation.findViewById<View>(cardsId)).performClick()
        assertEquals(cardsId, navigation.selectedItemId)
    }

    private fun gestureInsets(bottom: Int = 24): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
        .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(24, 0, 24, 32))
        .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 24, 0, 0))
        .build()

    private fun buttonInsets(): WindowInsetsCompat = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
        .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(0, 0, 0, 48))
        .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 24, 0, 48))
        .build()

    private fun applyInsets(insets: WindowInsetsCompat, width: Int, height: Int) {
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, width, height)
    }

    private fun assertTargetsInSafeArea() {
        for (index in 0 until navigation.menu.size()) {
            val target = requireNotNull(navigation.findViewById<View>(navigation.menu.getItem(index).itemId))
            val bounds = Rect(0, 0, target.width, target.height)
            root.offsetDescendantRectToMyCoords(target, bounds)
            assertTrue("Navigation target too small: $bounds", bounds.width() >= 48 && bounds.height() >= 48)
            assertTrue("Navigation target outside screen: $bounds",
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.width && bounds.bottom <= root.height)
            assertTrue("Navigation target crossed the system safe area: $bounds",
                bounds.bottom <= root.height - root.paddingBottom &&
                    bounds.right <= root.width - root.paddingRight)
        }
    }
}
