package io.github.umislat.aimesimulator.ui

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.umislat.aimesimulator.R
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.nfc.HceSession
import io.github.umislat.aimesimulator.root.PmmManager
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
class AdvancedModeUiTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var store: CardStore
    private val profile = requireNotNull(CardProfile.create(
        "Synthetic", "012E112233445566",
        spad0 = "00112233445566778899AABBCCDDEEFF",
        idBlock = "012E1122334455660078000000000000",
        accessCode = "12345678901234567890",
        profileId = "advanced-fixture"
    ))

    @Before fun createScreen() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE)
            .edit().clear().putBoolean("storage_migrated", true).commit()
        store = CardStore(context)
        store.put(profile)
        store.select(profile.profileId)
        // Do not resume: these layout tests must not run NFC activation or root inspection.
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().visible()
        activity = controller.get()
    }

    @After fun closeScreen() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller.stop().destroy()
    }

    @Test fun defaultModeHidesTechnicalCardData() {
        assertFalse(store.advancedMode())
        assertFalse(screenText().contains(profile.idm))
        assertTrue(screenText().contains(profile.formattedAccessCode()!!))
    }

    @Test fun ordinaryStatusCanOpenDeviceChecksWithoutSelectingACard() {
        store.select(null)
        showPage(1002)
        val check = visibleViews(screen()).filterIsInstance<MaterialButton>()
            .single { it.text == activity.getString(R.string.device_capabilities) }
        assertTrue(check.isEnabled)
        check.performClick()
        assertEquals(DeviceCapabilitiesActivity::class.java.name,
            org.robolectric.Shadows.shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test fun advancedModeRespectsExistingListPrivacySwitches() {
        store.setAdvancedMode(true)
        showPage(1001)
        assertTrue(screenText().contains(profile.idm))
        store.setShowIdm(false)
        store.setShowAccessCode(false)
        showPage(1001)
        assertFalse(screenText().contains(profile.idm))
        assertFalse(screenText().contains(profile.formattedAccessCode()!!))
        assertTrue(screenText().contains(activity.getString(R.string.idm_hidden)))
    }

    @Test fun settingsTogglePersistsWithoutChangingAnyOtherPreference() {
        store.setPersistedRouteOnlyMode(true)
        store.setShowIdm(false)
        val before = preferencesWithoutUiMode()
        showPage(1003)
        advancedSwitch().isChecked = true
        assertTrue(CardStore(activity).advancedMode())
        assertEquals(before, preferencesWithoutUiMode())
        assertTrue(visibleViews(screen()).filterIsInstance<MaterialSwitch>()
            .any { it.text == activity.getString(R.string.show_idm) })
        advancedSwitch().isChecked = false
        assertFalse(CardStore(activity).advancedMode())
        assertEquals(before, preferencesWithoutUiMode())
        assertFalse(visibleViews(screen()).filterIsInstance<MaterialSwitch>()
            .any { it.text == activity.getString(R.string.show_idm) })
    }

    @Test fun uiModeSurvivesActivityRecreation() {
        store.setAdvancedMode(true)
        controller.stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().visible()
        activity = controller.get()
        assertTrue(CardStore(activity).advancedMode())
        assertTrue(screenText().contains(profile.idm))
        assertEquals(profile.profileId, CardStore(activity).selectedProfile()?.profileId)
    }

    @Test fun ordinaryEditPreservesAllHiddenCardFields() {
        val root = openEditor(profile)
        assertEquals(2, visibleViews(root).filterIsInstance<EditText>().count())
        views(root).filterIsInstance<EditText>().first().setText("Renamed")
        button(root, R.string.save).performClick()
        assertEquals(profile.copy(label = "Renamed"), CardStore(activity).profiles().single())
    }

    @Test fun manualAddRetainsRequiredIdentityAndValidation() {
        val root = openEditor(null)
        val fields = visibleViews(root).filterIsInstance<EditText>().toList()
        assertEquals(3, fields.size)
        fields.last().setText("invalid")
        button(root, R.string.save).performClick()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        assertEquals(listOf(profile), store.profiles())
    }

    @Test fun scannedCardSavesRawDataEvenWhenTechnicalFieldsAreHidden() {
        val root = openEditor(null, profile.idm, profile.spad0, profile.idBlock)
        assertEquals(2, visibleViews(root).filterIsInstance<EditText>().count())
        views(root).filterIsInstance<EditText>().first().setText("Scanned")
        button(root, R.string.save).performClick()
        val saved = CardStore(activity).profiles().single { it.label == "Scanned" }
        assertEquals(profile.idm, saved.idm)
        assertEquals(profile.spad0, saved.spad0)
        assertEquals(profile.idBlock, saved.idBlock)
    }

    @Test fun cancelOrdinaryEditDoesNotSaveChanges() {
        val root = openEditor(profile)
        views(root).filterIsInstance<EditText>().first().setText("Discarded")
        button(root, android.R.string.cancel).performClick()
        assertEquals(listOf(profile), CardStore(activity).profiles())
    }

    @Test fun advancedEditorShowsAllFiveFields() {
        store.setAdvancedMode(true)
        assertEquals(5, visibleViews(openEditor(profile)).filterIsInstance<EditText>().count())
    }

    @Test fun ordinaryDetailsShowNameAndCardNumberWithoutRawParameters() {
        val text = detailsText()
        assertTrue(text.contains(profile.label))
        assertTrue(text.contains(profile.formattedAccessCode()!!))
        assertFalse(text.contains(profile.profileId))
        assertFalse(text.contains(groupHex(profile.idm)))
        assertFalse(text.contains(groupHex(profile.spad0!!)))
    }

    @Test fun advancedDetailsExposeAllSavedAndDeclaredParameters() {
        store.setAdvancedMode(true)
        val text = detailsText()
        listOf(profile.profileId, groupHex(profile.idm), profile.formattedAccessCode()!!,
            groupHex(profile.spad0!!), groupHex(profile.idBlock!!), "88B4", "00F1 0000 0001 4300"
        ).forEach { assertTrue("Missing detail: $it", text.contains(it)) }
    }

    @Test fun ordinaryStatusKeepsUsefulControlsAndHidesTechnicalSelectors() {
        showPage(1002)
        assertEquals(1, visibleViews(screen()).filterIsInstance<MaterialButtonToggleGroup>().count())
        assertTrue(screenText().contains(activity.getString(R.string.emulation_method)))
        assertTrue(screenText().contains(activity.getString(R.string.compatibility_patch)))
        assertTrue(screenText().contains(activity.getString(R.string.default_nfc_app)))
        store.setAdvancedMode(true)
        showPage(1002)
        assertEquals(1, visibleViews(screen()).filterIsInstance<MaterialButtonToggleGroup>().count())
        assertTrue(screenText().contains(activity.getString(R.string.enable_pmm_patch)))
    }

    @Test fun obsoleteIdentitySettingsDoNotAddRouteControlsOrRewriteTheCard() {
        store.setPersistedRouteOnlyMode(true)
        activity.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE).edit()
            .putString("idm_route_mode", "PREFIX_COMPATIBILITY")
            .putBoolean("compatibility_retired_notice", true).commit()
        controller.stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().visible()
        activity = controller.get()
        assertEquals(null, ShadowDialog.getLatestDialog())
        for (advanced in listOf(false, true)) {
            store.setAdvancedMode(advanced)
            showPage(1002)
            assertEquals(1, visibleViews(screen()).filterIsInstance<MaterialButtonToggleGroup>().count())
            assertFalse(screenText().contains("02FE"))
            button(screen(), R.string.emulation_start).performClick()
            assertEquals(profile.idm, org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
                .getStringExtra(PersistedEmulationActivity.EXTRA_NFCID2))
            assertEquals(profile, store.selectedProfile())
        }
    }

    @Test fun ordinaryStatusSimplifiesErrorsButAdvancedRetainsDiagnostics() {
        MainActivity::class.java.getDeclaredField("lastHceReport").apply { isAccessible = true }
            .set(activity, HceSession.Report(HceSession.Stage.ID))
        MainActivity::class.java.getDeclaredField("hceStatusText").apply { isAccessible = true }
            .set(activity, activity.getString(R.string.hce_nfcid2_rejected))
        showPage(1002)
        assertTrue(screenText().contains(activity.getString(R.string.simple_identity_rejected)))
        assertFalse(screenText().contains(activity.getString(R.string.hce_nfcid2_rejected)))
        store.setAdvancedMode(true)
        showPage(1002)
        assertTrue(screenText().contains(activity.getString(R.string.hce_nfcid2_rejected)))
    }

    @Test fun ordinaryCompatibilityStatusHidesRootLogsWithoutHidingFailure() {
        showPage(1002)
        val snapshot = PmmManager.Snapshot(PmmManager.Status.ERROR, "synthetic-root-detail", true)
        val render = MainActivity::class.java.getDeclaredMethod("renderPmm", PmmManager.Snapshot::class.java)
            .apply { isAccessible = true }
        render.invoke(activity, snapshot)
        assertTrue(screenText().contains(activity.getString(R.string.compatibility_patch_error)))
        assertFalse(screenText().contains(snapshot.detail))
        store.setAdvancedMode(true)
        showPage(1002)
        assertTrue(screenText().contains(snapshot.detail))
    }

    @Test fun uiModeDoesNotChangeBackupContents() {
        val before = store.backupSnapshot(123L)
        store.setAdvancedMode(true)
        assertEquals(before, store.backupSnapshot(123L))
        store.setAdvancedMode(false)
        assertEquals(before, store.backupSnapshot(123L))
    }

    @Test fun savedModeHasDailyActionsWithoutRootModuleControls() {
        store.setPersistedRouteOnlyMode(true)
        showPage(1002)
        assertTrue(screenText().contains(activity.getString(R.string.emulation_start)))
        assertTrue(screenText().contains(activity.getString(R.string.emulation_setup)))
        assertFalse(screenText().contains(activity.getString(R.string.compatibility_patch)))
        assertFalse(screenText().contains(activity.getString(R.string.enable_pmm_patch)))
        MainActivity::class.java.getDeclaredMethod("refreshPmm").apply { isAccessible = true }.invoke(activity)
        assertEquals(null, MainActivity::class.java.getDeclaredField("pmmSnapshot").apply { isAccessible = true }.get(activity))
        showPage(1001)
        button(screen(), R.string.emulation_start).performClick()
        val intent = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(PersistedEmulationActivity::class.java.name, intent.component!!.className)
        assertEquals(profile.idm, intent.getStringExtra(PersistedEmulationActivity.EXTRA_NFCID2))
    }

    private fun preferencesWithoutUiMode(): Map<String, *> =
        activity.getSharedPreferences("aime_simulator", Context.MODE_PRIVATE).all
            .filterKeys { it != "advanced_mode" }

    private fun advancedSwitch(): MaterialSwitch = visibleViews(screen())
        .filterIsInstance<MaterialSwitch>().single { it.text == activity.getString(R.string.advanced_mode) }

    private fun showPage(tab: Int) {
        MainActivity::class.java.getDeclaredMethod("showPage", Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, tab, false)
    }

    private fun openEditor(initial: CardProfile?, idm: String? = null, spad0: String? = null,
        idBlock: String? = null): View {
        MainActivity::class.java.getDeclaredMethod("showEditor", CardProfile::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(activity, initial, idm, spad0, idBlock, null)
        return requireNotNull(ShadowDialog.getLatestDialog().window).decorView
    }

    private fun detailsText(): String {
        MainActivity::class.java.getDeclaredMethod("showCardDetails", CardProfile::class.java)
            .apply { isAccessible = true }.invoke(activity, profile)
        return text(requireNotNull(ShadowDialog.getLatestDialog().window).decorView)
    }

    private fun button(root: View, label: Int): MaterialButton = visibleViews(root)
        .filterIsInstance<MaterialButton>().single { it.text == activity.getString(label) }

    private fun screen(): View = activity.findViewById(android.R.id.content)
    private fun screenText(): String = text(screen())
    private fun groupHex(value: String): String = value.chunked(4).joinToString(" ")
    private fun text(root: View): String = visibleViews(root).filterIsInstance<TextView>()
        .joinToString("\n") { it.text.toString() }

    private fun visibleViews(root: View): Sequence<View> = views(root).filter { view ->
        generateSequence(view) { it.parent as? View }.all { it.visibility == View.VISIBLE }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
        }
    }
}
