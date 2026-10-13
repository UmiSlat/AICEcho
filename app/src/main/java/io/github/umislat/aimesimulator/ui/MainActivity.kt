package io.github.umislat.aimesimulator.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.nfc.NfcAdapter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.umislat.aimesimulator.R
import io.github.umislat.aimesimulator.ThemeSettings
import io.github.umislat.aimesimulator.data.CardBackupCodec
import io.github.umislat.aimesimulator.data.CardImportPlanner
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.CardStore
import io.github.umislat.aimesimulator.nfc.DefaultNfcAppChecker
import io.github.umislat.aimesimulator.nfc.DefaultNfcAppPolicy
import io.github.umislat.aimesimulator.nfc.HceActivationRetryPolicy
import io.github.umislat.aimesimulator.nfc.HceSession
import io.github.umislat.aimesimulator.nfc.NfcAdapterStatePolicy
import io.github.umislat.aimesimulator.nfc.NfcCardRegistration
import io.github.umislat.aimesimulator.provisioning.NfcProvisionStore
import io.github.umislat.aimesimulator.provisioning.ProbeJournal
import io.github.umislat.aimesimulator.nfc.RootlessAssessment
import io.github.umislat.aimesimulator.root.PmmManager
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var store: CardStore
    private lateinit var session: HceSession
    private lateinit var toolbar: MaterialToolbar
    private lateinit var contentHost: FrameLayout
    private lateinit var bottomNavigation: BottomNavigationView

    private var selectedTab = TAB_CARDS
    private var foreground = false
    private var hasResumed = false
    private var nfcStateReceiverRegistered = false
    private var activationRetry: Runnable? = null
    private var hceStatusText: CharSequence = ""
    private var cardPageStatusView: TextView? = null
    private var statusPageStatusView: TextView? = null
    private var hceRetryAction: MaterialButton? = null
    private var lastHceReport: HceSession.Report? = null
    private var cardRegistration = NfcCardRegistration(NfcCardRegistration.Availability.UNKNOWN)
    private var cardUseState = CardUseState.NO_CARD
    private var cardUseAction: MaterialButton? = null
    private var cardReadiness: TextView? = null
    private var launchingCardFlow = false
    private var rootlessStatusView: TextView? = null
    private var rootlessDetailView: TextView? = null

    private val nfcStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) return
            val reportedState = intent.getIntExtra(
                NfcAdapter.EXTRA_ADAPTER_STATE,
                NfcAdapter.STATE_OFF
            )
            val adapterEnabled = runCatching {
                NfcAdapter.getDefaultAdapter(context ?: this@MainActivity)?.isEnabled
            }.getOrNull()
            handleNfcAdapterStateChanged(reportedState, adapterEnabled)
        }
    }
    private var defaultNfcStatus = DefaultNfcAppChecker.Result.NFC_NOT_READY
    private var defaultNfcStatusView: TextView? = null
    private var defaultNfcAction: MaterialButton? = null

    private var pmmSnapshot: PmmManager.Snapshot? = null
    private var pmmSwitch: MaterialSwitch? = null
    private var pmmStatus: TextView? = null
    private var pmmProgress: LinearProgressIndicator? = null
    private val backupIo = Executors.newSingleThreadExecutor()

    private val backupExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) writeBackup(uri)
    }

    private val backupImporter = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) readBackup(uri)
    }

    private val reader = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val idm = data.getStringExtra(CardReaderActivity.EXTRA_IDM)
        val accessCode = data.getStringExtra(CardReaderActivity.EXTRA_ACCESS_CODE)
        if (idm == null && accessCode == null) return@registerForActivityResult
        showEditor(
            capturedIdm = idm,
            capturedSpad0 = data.getStringExtra(CardReaderActivity.EXTRA_SPAD0),
            capturedIdBlock = data.getStringExtra(CardReaderActivity.EXTRA_ID_BLOCK),
            capturedAccessCode = accessCode
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CardStore(this)
        session = HceSession(this)
        selectedTab = savedInstanceState?.getInt(STATE_SELECTED_TAB, TAB_CARDS) ?: TAB_CARDS
        pmmSnapshot = restorePmmSnapshot(savedInstanceState)
        hceStatusText = if (store.selectedProfile() == null) {
            getString(R.string.select_or_add_card)
        } else {
            getString(R.string.checking_hce_status)
        }
        setInsetAwareContentView(buildShell())
        bottomNavigation.menu.findItem(selectedTab).isChecked = true
        showPage(selectedTab, refreshPmm = selectedTab == TAB_STATUS && pmmSnapshot == null)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        launchingCardFlow = false
        registerNfcStateReceiver()
        if (hasResumed) showPage(selectedTab, refreshPmm = false)
        activateConfiguredRoute()
        if (hasResumed && selectedTab == TAB_STATUS) refreshPmm()
        hasResumed = true
        refreshDefaultNfcAppStatus()
    }

    override fun onPause() {
        foreground = false
        unregisterNfcStateReceiver()
        cancelActivationRetry()
        if (!store.persistedRouteOnlyMode()) session.deactivate(this)
        super.onPause()
    }

    override fun onDestroy() {
        backupIo.shutdown()
        super.onDestroy()
    }

    private fun registerNfcStateReceiver() {
        if (nfcStateReceiverRegistered) return
        // The NFC broadcast can originate from a privileged process with a non-system UID.
        ContextCompat.registerReceiver(
            this,
            nfcStateReceiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        nfcStateReceiverRegistered = true
    }

    private fun unregisterNfcStateReceiver() {
        if (!nfcStateReceiverRegistered) return
        unregisterReceiver(nfcStateReceiver)
        nfcStateReceiverRegistered = false
    }

    private fun handleNfcAdapterStateChanged(adapterState: Int, adapterEnabled: Boolean?) {
        if (!foreground) return
        when (NfcAdapterStatePolicy.actionFor(adapterState, adapterEnabled)) {
            NfcAdapterStatePolicy.Action.MARK_DISABLED -> {
                cancelActivationRetry()
                lastHceReport = HceSession.Report(HceSession.Stage.NFC_DISABLED)
                setHceStatus(getString(R.string.nfc_disabled))
            }
            NfcAdapterStatePolicy.Action.MARK_ENABLING -> {
                cancelActivationRetry()
                lastHceReport = HceSession.Report(HceSession.Stage.SERVICE_RESTARTING)
                setHceStatus(getString(R.string.hce_waiting_for_service))
            }
            NfcAdapterStatePolicy.Action.ACTIVATE -> activateConfiguredRoute()
            NfcAdapterStatePolicy.Action.IGNORE -> return
        }
        renderRootlessAssessment()
        refreshDefaultNfcAppStatus()
        if (store.persistedRouteOnlyMode()) {
            refreshCardUseState()
            if (selectedTab == TAB_CARDS) showPage(TAB_CARDS, refreshPmm = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab)
        pmmSnapshot?.let { snapshot ->
            outState.putString(STATE_PMM_STATUS, snapshot.status.name)
            outState.putString(STATE_PMM_DETAIL, snapshot.detail)
            outState.putBoolean(STATE_PMM_MODERN, snapshot.modernModule)
        }
        super.onSaveInstanceState(outState)
    }

    private fun buildShell(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        toolbar = MaterialToolbar(this@MainActivity).apply {
            title = getString(R.string.app_name)
            setTitleTextAppearance(this@MainActivity, R.style.TextAppearance_AimeSimulator_Toolbar)
        }
        addView(toolbar)

        contentHost = FrameLayout(this@MainActivity)
        addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))

        bottomNavigation = BottomNavigationView(this@MainActivity).apply {
            labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
            menu.add(Menu.NONE, TAB_CARDS, Menu.NONE, R.string.nav_cards)
                .setIcon(R.drawable.ic_nav_cards)
            menu.add(Menu.NONE, TAB_STATUS, Menu.NONE, R.string.nav_status)
                .setIcon(R.drawable.ic_nav_status)
            menu.add(Menu.NONE, TAB_SETTINGS, Menu.NONE, R.string.settings)
                .setIcon(R.drawable.ic_nav_settings)
            setOnItemSelectedListener { item ->
                showPage(item.itemId, refreshPmm = item.itemId == TAB_STATUS)
                true
            }
        }
        addView(bottomNavigation)
    }

    private fun showPage(tab: Int, refreshPmm: Boolean) {
        selectedTab = tab
        cardPageStatusView = null
        cardUseAction = null
        cardReadiness = null
        statusPageStatusView = null
        hceRetryAction = null
        rootlessStatusView = null
        rootlessDetailView = null
        defaultNfcStatusView = null
        defaultNfcAction = null
        pmmSwitch = null
        pmmStatus = null
        pmmProgress = null
        toolbar.title = getString(
            when (tab) {
                TAB_STATUS -> R.string.nav_status
                TAB_SETTINGS -> R.string.settings
                else -> R.string.app_name
            }
        )
        contentHost.removeAllViews()
        contentHost.addView(
            when (tab) {
                TAB_STATUS -> buildStatusPage()
                TAB_SETTINGS -> buildSettingsPage()
                else -> buildCardsPage()
            },
            FrameLayout.LayoutParams(-1, -1)
        )
        updateHceStatusViews()
        if (tab == TAB_STATUS) {
            renderRootlessAssessment()
            renderDefaultNfcAppStatus()
            pmmSnapshot?.let(::renderPmm)
            if (refreshPmm) refreshPmm()
        }
    }

    private fun buildCardsPage(): View {
        refreshCardUseState()
        val page = FrameLayout(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = verticalLayout(this)
        content.addView(statusSummary())
        content.addView(emulationActions())

        val profiles = store.profiles()
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle(R.string.card_profiles), LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = resources.getQuantityString(
                    R.plurals.card_profile_count,
                    profiles.size,
                    profiles.size
                )
                textSize = 13f
                alpha = 0.68f
            })
        })
        if (profiles.isEmpty()) {
            content.addView(TextView(this).apply {
                setText(R.string.no_cards)
                gravity = Gravity.CENTER
                alpha = 0.72f
                setPadding(dp(12), dp(48), dp(12), dp(96))
            })
        } else {
            val selectedId = store.selectedProfile()?.profileId
            profiles.forEach { profile ->
                content.addView(profileCard(profile, profile.profileId == selectedId))
            }
            content.setPadding(dp(20), dp(8), dp(20), dp(104))
        }
        scroll.addView(content)
        page.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        page.addView(ExtendedFloatingActionButton(this).apply {
            setText(R.string.add_card_action)
            setIconResource(android.R.drawable.ic_input_add)
            setOnClickListener { showAddOptions() }
            visibility = if (profiles.isEmpty() || store.selectedProfile() == null) View.GONE else View.VISIBLE
        }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(20)
            bottomMargin = dp(18)
        })
        return page
    }

    private fun statusSummary(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { bottomNavigation.selectedItemId = TAB_STATUS }
        setPadding(dp(4), dp(12), dp(4), dp(12))
        addView(TextView(this@MainActivity).apply {
            setText(R.string.simulation_status)
            textSize = 13f
            alpha = 0.7f
        })
        cardPageStatusView = TextView(this@MainActivity).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }
        addView(cardPageStatusView)
        store.selectedProfile()?.let { profile ->
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.current_card_summary, profile.label)
                textSize = 13f
                alpha = 0.72f
                setPadding(0, dp(8), 0, 0)
            })
        }
        if (store.persistedRouteOnlyMode()) {
            cardReadiness = TextView(this@MainActivity).apply {
                setText(cardUseState.status)
                textSize = 14f
                setPadding(0, dp(6), 0, 0)
            }
            addView(cardReadiness)
        }
    }

    private fun profileCard(profile: CardProfile, selected: Boolean): MaterialCardView =
        MaterialCardView(this).apply {
            strokeWidth = if (selected) dp(2) else dp(1)
            strokeColor = MaterialColors.getColor(
                this,
                if (selected) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOutlineVariant
            )
            radius = dp(CARD_RADIUS_DP).toFloat()
            setOnClickListener {
                store.select(profile.profileId)
                activateConfiguredRoute()
                showPage(TAB_CARDS, refreshPmm = false)
            }
            layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(15), dp(14), dp(12))
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = profile.label
                        textSize = 18f
                        setTypeface(typeface, Typeface.BOLD)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (selected) addView(Chip(this@MainActivity).apply {
                        setText(R.string.selected_profile)
                        isClickable = false
                        isCheckable = false
                        setEnsureMinTouchTargetSize(false)
                        chipMinHeight = dp(32).toFloat()
                        textSize = 12f
                    })
                })
                if (store.persistedRouteOnlyMode() && cardUseState != CardUseState.CONTINUE && cardRegistration.matches(profile.idm)) {
                    addView(TextView(this@MainActivity).apply {
                        setText(R.string.card_configured)
                        textSize = 13f
                        setPadding(0, dp(4), 0, dp(2))
                    })
                }
                addView(TextView(this@MainActivity).apply {
                    visibility = if (store.advancedMode()) View.VISIBLE else View.GONE
                    text = if (store.showIdm()) getString(R.string.idm_value, profile.idm)
                    else getString(R.string.idm_hidden)
                    textSize = 14f
                    alpha = 0.76f
                    setPadding(0, dp(4), 0, if (profile.accessCode == null) dp(6) else 0)
                })
                profile.accessCode?.let {
                    addView(TextView(this@MainActivity).apply {
                        text = getString(
                            R.string.access_code_value,
                            if (store.showAccessCode()) profile.formattedAccessCode()
                            else getString(R.string.access_code_hidden)
                        )
                        textSize = 14f
                        alpha = 0.76f
                        setPadding(0, dp(3), 0, dp(6))
                    })
                }
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.END
                    addView(compactTextButton(R.string.card_details).apply {
                        setOnClickListener { showCardDetails(profile) }
                    })
                    addView(compactTextButton(R.string.edit).apply {
                        setOnClickListener { showEditor(profile) }
                    })
                    addView(compactTextButton(R.string.delete).apply {
                        setOnClickListener { confirmDelete(profile) }
                    })
                })
            })
        }

    private fun buildStatusPage(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = verticalLayout(this)
        content.addView(sectionTitle(R.string.emulation_method))
        content.addView(emulationMethodSelector())
        content.addView(sectionTitle(R.string.simulation_status))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(12))
            statusPageStatusView = TextView(this@MainActivity).apply {
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            }
            addView(statusPageStatusView)
            addView(TextView(this@MainActivity).apply {
                visibility = if (store.advancedMode()) View.VISIBLE else View.GONE
                setText(R.string.hce_status_summary)
                textSize = 13f
                alpha = 0.7f
                setPadding(0, dp(6), 0, 0)
            })
            store.selectedProfile()?.let { profile ->
                addView(TextView(this@MainActivity).apply {
                    text = if (!store.advancedMode()) {
                        getString(R.string.current_card_summary, profile.label)
                    } else getString(
                        R.string.current_identity_summary,
                        profile.label,
                        if (store.showIdm()) profile.idm
                        else getString(R.string.hidden_value)
                    )
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(12), 0, 0)
                })
            }
            if (store.advancedMode()) {
                rootlessStatusView = TextView(this@MainActivity).apply {
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(14), 0, 0)
                }
                addView(rootlessStatusView)
                rootlessDetailView = TextView(this@MainActivity).apply {
                    textSize = 13f
                    alpha = 0.76f
                    setPadding(0, dp(4), 0, 0)
                }
                addView(rootlessDetailView)
            }
            val retryAction = MaterialButton(
                this@MainActivity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                setText(R.string.hce_retry_registration)
                visibility = hceRetryActionVisibility()
                setOnClickListener { recreate() }
            }
            hceRetryAction = retryAction
            addView(retryAction, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            })
        })
        content.addView(sectionTitle(R.string.system_requirements))
        content.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.device_capabilities)
            setIconResource(R.drawable.ic_nav_status)
            setOnClickListener { startActivity(Intent(this@MainActivity, DeviceCapabilitiesActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(defaultNfcAppSection())
        if (!store.persistedRouteOnlyMode()) content.addView(pmmSection().apply { applyMargins(vertical = 6) })
        scroll.addView(content)
        return scroll
    }

    private fun emulationActions(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(12))
        if (store.persistedRouteOnlyMode() || store.selectedProfile() == null) {
            cardUseAction = MaterialButton(this@MainActivity).apply {
                setText(cardUseState.action)
                setIconResource(cardUseIcon())
                setOnClickListener { useCurrentCard() }
            }
            addView(cardUseAction, LinearLayout.LayoutParams(-1, -2))
        }
        if (store.advancedMode()) addView(MaterialButton(this@MainActivity, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.emulation_setup)
            setIconResource(R.drawable.ic_nav_status)
            isEnabled = store.selectedProfile() != null
            setOnClickListener { startActivity(Intent(this@MainActivity, ShizukuProvisioningActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun refreshCardUseState() {
        cardRegistration = if (store.persistedRouteOnlyMode()) NfcCardRegistration.read(this)
            else NfcCardRegistration(NfcCardRegistration.Availability.UNKNOWN)
        val boot = if (store.persistedRouteOnlyMode()) runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1) else -1
        val unfinished = store.persistedRouteOnlyMode() && (listOf("shizuku_nfc_provision", "shizuku_nfc_snapshot",
            "shizuku_restore_probe").any { ProbeJournal(getSharedPreferences(it, Context.MODE_PRIVATE), boot).read().pending } ||
            NfcProvisionStore.requiresResume(NfcProvisionStore(noBackupFilesDir).read(applicationInfo.uid)))
        cardUseState = CardUseState.resolve(store.selectedProfile()?.idm, cardRegistration, unfinished)
        cardUseAction?.setText(cardUseState.action)
        cardUseAction?.setIconResource(cardUseIcon())
        cardReadiness?.setText(cardUseState.status)
    }

    private fun cardUseIcon() = when (cardUseState) {
        CardUseState.NO_CARD -> android.R.drawable.ic_input_add
        CardUseState.NFC_OFF -> R.drawable.ic_nav_settings
        CardUseState.UNSUPPORTED -> R.drawable.ic_nav_status
        else -> R.drawable.ic_read_nfc
    }

    private fun useCurrentCard() {
        if (launchingCardFlow) return
        refreshCardUseState()
        val destination = when (cardUseState) {
            CardUseState.NO_CARD -> { showAddOptions(); return }
            CardUseState.SETUP -> ShizukuProvisioningActivity.intent(this, beginSetup = true)
            CardUseState.CONTINUE -> ShizukuProvisioningActivity.intent(this, beginSetup = false)
            CardUseState.NFC_OFF -> Intent(Settings.ACTION_NFC_SETTINGS)
            CardUseState.UNSUPPORTED -> Intent(this, DeviceCapabilitiesActivity::class.java)
            CardUseState.READY, CardUseState.UNKNOWN -> {
                val selected = store.selectedProfile() ?: return
                PersistedEmulationActivity.intent(this, selected.label, selected.idm, store.advancedMode())
            }
        }
        launchingCardFlow = true
        startActivity(destination)
    }

    private fun emulationMethodSelector(): MaterialButtonToggleGroup = MaterialButtonToggleGroup(this).apply {
        isSingleSelection = true
        isSelectionRequired = true
        val modes = linkedMapOf<Int, Boolean>()
        for ((persisted, label) in listOf(true to R.string.emulation_method_shizuku, false to R.string.emulation_method_module)) {
            val button = MaterialButton(this@MainActivity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                setText(label)
                maxLines = 2
                minWidth = 0
                minimumWidth = 0
            }
            modes[button.id] = persisted
            addView(button, LinearLayout.LayoutParams(0, -2, 1f))
            if (persisted == store.persistedRouteOnlyMode()) check(button.id)
        }
        addOnButtonCheckedListener { _, id, checked ->
            val persisted = modes[id] ?: return@addOnButtonCheckedListener
            if (!checked || persisted == store.persistedRouteOnlyMode()) return@addOnButtonCheckedListener
            session.deactivate(this@MainActivity)
            store.setPersistedRouteOnlyMode(persisted)
            activateConfiguredRoute()
            showPage(TAB_STATUS, refreshPmm = !persisted)
        }
    }

    private fun defaultNfcAppSection(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(14), dp(4), dp(14))
        addView(TextView(this@MainActivity).apply {
            setText(R.string.default_nfc_app)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        defaultNfcStatusView = TextView(this@MainActivity).apply {
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        }
        addView(defaultNfcStatusView)
        defaultNfcAction = MaterialButton(
            this@MainActivity,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            setOnClickListener { handleDefaultNfcAction() }
        }
        addView(defaultNfcAction, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
    }

    private fun pmmSection(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(12), dp(4), dp(16))
        pmmProgress = LinearProgressIndicator(this@MainActivity).apply {
            isIndeterminate = true
            hide()
        }
        addView(pmmProgress, LinearLayout.LayoutParams(-1, dp(4)))
        pmmSwitch = MaterialSwitch(this@MainActivity).apply {
            setText(if (store.advancedMode()) R.string.enable_pmm_patch else R.string.compatibility_patch)
            isEnabled = false
            setOnCheckedChangeListener { button, enabled ->
                if (button.isPressed) changePmm(enabled)
            }
        }
        addView(pmmSwitch)
        pmmStatus = TextView(this@MainActivity).apply {
            setText(R.string.checking_status)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        addView(pmmStatus)
        addView(TextView(this@MainActivity).apply {
            visibility = if (store.advancedMode()) View.VISIBLE else View.GONE
            setText(R.string.pmm_explanation)
            textSize = 13f
            alpha = 0.72f
        })
    }

    private fun buildSettingsPage(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = verticalLayout(this)
        content.addView(MaterialSwitch(this).apply {
            setText(R.string.advanced_mode)
            isChecked = store.advancedMode()
            setOnCheckedChangeListener { _, enabled ->
                if (enabled == store.advancedMode()) return@setOnCheckedChangeListener
                store.setAdvancedMode(enabled)
                showPage(TAB_SETTINGS, refreshPmm = false)
            }
        })
        content.addView(sectionTitle(R.string.appearance))
        val dynamicAvailable = DynamicColors.isDynamicColorAvailable()
        content.addView(settingsGroup().apply {
            addView(TextView(this@MainActivity).apply {
                setText(R.string.theme_mode)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            })
            addView(buildThemeSelector())
            addView(MaterialSwitch(this@MainActivity).apply {
                setText(R.string.dynamic_colors)
                isChecked = dynamicAvailable &&
                    ThemeSettings.dynamicColorsEnabled(this@MainActivity)
                isEnabled = dynamicAvailable
                setPadding(0, dp(8), 0, 0)
                setOnCheckedChangeListener { button, enabled ->
                    if (button.isPressed) {
                        ThemeSettings.setDynamicColorsEnabled(this@MainActivity, enabled)
                        recreate()
                    }
                }
            })
            if (!dynamicAvailable) addView(settingsSummary(R.string.dynamic_colors_unavailable))
        })

        content.addView(sectionTitle(R.string.card_data))
        content.addView(cardDataSettings())

        content.addView(sectionTitle(R.string.privacy))
        content.addView(settingsGroup().apply {
            addView(MaterialSwitch(this@MainActivity).apply {
                visibility = if (store.advancedMode()) View.VISIBLE else View.GONE
                setText(R.string.show_idm)
                isChecked = store.showIdm()
                setOnCheckedChangeListener { button, value ->
                    if (button.isPressed) store.setShowIdm(value)
                }
            })
            addView(MaterialSwitch(this@MainActivity).apply {
                setText(R.string.show_access_code)
                isChecked = store.showAccessCode()
                setOnCheckedChangeListener { button, value ->
                    if (button.isPressed) store.setShowAccessCode(value)
                }
            })
        })

        content.addView(sectionTitle(R.string.application_section))
        content.addView(MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            setText(R.string.open_about)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AboutActivity::class.java))
            }
        }, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(content)
        return scroll
    }

    private fun settingsGroup(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(12))
    }

    private fun settingsSummary(@androidx.annotation.StringRes text: Int): TextView =
        TextView(this).apply {
            setText(text)
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(4), 0, 0)
        }

    private fun cardDataSettings(): LinearLayout = settingsGroup().apply {
        val profiles = store.profiles()
        val count = resources.getQuantityString(
            R.plurals.card_profile_count,
            profiles.size,
            profiles.size
        )
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.card_data_local_summary, count)
            textSize = 13f
            alpha = 0.76f
            setPadding(0, dp(5), 0, dp(10))
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(backupActionButton(R.string.export_backup, R.drawable.ic_export).apply {
                isEnabled = profiles.isNotEmpty()
                setOnClickListener { confirmBackupExport() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
            addView(backupActionButton(R.string.import_backup, R.drawable.ic_import).apply {
                setOnClickListener { backupImporter.launch(arrayOf("application/json", "text/json")) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        })
    }

    private fun backupActionButton(
        @androidx.annotation.StringRes text: Int,
        @androidx.annotation.DrawableRes icon: Int
    ): MaterialButton = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        setText(text)
        setIconResource(icon)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        textSize = 13f
        maxLines = 2
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
    }

    private fun buildThemeSelector(): MaterialButtonToggleGroup {
        val systemId = View.generateViewId()
        val lightId = View.generateViewId()
        val darkId = View.generateViewId()
        return MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            addView(themeButton(systemId, R.string.theme_follow_system))
            addView(themeButton(lightId, R.string.theme_light))
            addView(themeButton(darkId, R.string.theme_dark))
            check(when (ThemeSettings.mode(this@MainActivity)) {
                ThemeSettings.Mode.SYSTEM -> systemId
                ThemeSettings.Mode.LIGHT -> lightId
                ThemeSettings.Mode.DARK -> darkId
            })
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val mode = when (checkedId) {
                    lightId -> ThemeSettings.Mode.LIGHT
                    darkId -> ThemeSettings.Mode.DARK
                    else -> ThemeSettings.Mode.SYSTEM
                }
                if (mode != ThemeSettings.mode(this@MainActivity)) {
                    ThemeSettings.setMode(this@MainActivity, mode)
                    AppCompatDelegate.setDefaultNightMode(mode.nightMode)
                }
            }
        }
    }

    private fun themeButton(id: Int, text: Int): MaterialButton = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        this.id = id
        setText(text)
        isCheckable = true
        textSize = 12f
        maxLines = 2
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }

    private fun compactTextButton(text: Int): MaterialButton = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonStyle
    ).apply {
        setText(text)
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
        minWidth = 0
        minimumWidth = 0
        insetTop = 0
        insetBottom = 0
    }

    private fun showAddOptions() {
        val dialog = BottomSheetDialog(this)
        val content = verticalLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), dp(28))
            addView(sheetHandle(), LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
            })
            addView(sheetTitle(R.string.add_card_action))
            addView(sheetDescription(R.string.add_card_method_summary).apply {
                setPadding(0, dp(6), 0, dp(18))
            })
            addView(addMethodCard(
                R.drawable.ic_read_nfc,
                R.string.read_physical_card,
                R.string.read_physical_card_summary
            ) {
                dialog.dismiss()
                reader.launch(Intent(this@MainActivity, CardReaderActivity::class.java))
            })
            addView(addMethodCard(
                R.drawable.ic_import,
                R.string.add_from_backup,
                R.string.add_from_backup_summary
            ) {
                dialog.dismiss()
                backupImporter.launch(arrayOf("application/json", "text/json"))
            }.apply { applyMargins(vertical = 6) })
            addView(addMethodCard(
                R.drawable.ic_add_manual,
                R.string.add_manually,
                R.string.add_manually_summary
            ) {
                dialog.dismiss()
                showEditor()
            }.apply { applyMargins(vertical = 6) })
        }
        dialog.setContentView(content)
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener { dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        dialog.show()
    }

    private fun showCardDetails(profile: CardProfile) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (store.advancedMode()) {
                addView(TextView(this@MainActivity).apply {
                    setText(R.string.card_details_sensitive_summary)
                    textSize = 13f
                    alpha = 0.76f
                    setPadding(0, 0, 0, dp(8))
                })
                addView(cardDetailLine(R.string.card_name, profile.label, monospace = false))
                addView(cardDetailLine(R.string.card_details_profile_id, profile.profileId))
                addView(cardDetailLine(
                    R.string.card_details_original_idm,
                    formatCardDetailHex(profile.idm)
                ))
                addView(cardDetailLine(
                    R.string.access_code,
                    profile.formattedAccessCode() ?: getString(R.string.card_details_not_saved)
                ))
                addView(cardDetailLine(
                    R.string.spad0_optional,
                    profile.spad0?.let(::formatCardDetailHex)
                        ?: getString(R.string.card_details_not_saved)
                ))
                addView(cardDetailLine(
                    R.string.id_block_optional,
                    profile.idBlock?.let(::formatCardDetailHex)
                        ?: getString(R.string.card_details_not_saved)
                ))
                addView(cardDetailLine(
                    R.string.card_details_system_code,
                    HceSession.SYSTEM_CODE
                ))
                addView(cardDetailLine(
                    R.string.card_details_pmm,
                    formatCardDetailHex(CardBackupCodec.PMM)
                ))
            } else {
                addView(cardDetailLine(R.string.card_name, profile.label, monospace = false))
                addView(cardDetailLine(
                    R.string.access_code,
                    profile.formattedAccessCode() ?: getString(R.string.card_details_not_saved)
                ))
            }
        }
        val scroll = androidx.core.widget.NestedScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(20), 0, dp(20), 0)
            addView(content)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.card_details_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun cardDetailLine(
        @androidx.annotation.StringRes label: Int,
        value: String,
        monospace: Boolean = true
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        addView(TextView(this@MainActivity).apply {
            setText(label)
            textSize = 12f
            alpha = 0.68f
        })
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 14f
            setTextIsSelectable(true)
            if (monospace) typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun formatCardDetailHex(value: String): String =
        value.chunked(4).joinToString(" ")

    private fun confirmBackupExport() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_backup)
            .setMessage(R.string.export_backup_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                backupExporter.launch(defaultBackupFileName())
            }
            .show()
    }

    private fun defaultBackupFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        return "AimeSimulator-backup-$timestamp.json"
    }

    private fun writeBackup(uri: Uri) {
        val snapshot = store.backupSnapshot()
        backupIo.execute {
            val success = runCatching {
                val encoded = CardBackupCodec.encode(snapshot)
                require(encoded.toByteArray(Charsets.UTF_8).size <= CardBackupCodec.MAX_BACKUP_BYTES)
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(encoded.toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: error("Unable to open backup destination")
            }.isSuccess
            postBackupResult(
                if (success) R.string.export_backup_complete else R.string.export_backup_failed
            )
        }
    }

    private fun readBackup(uri: Uri) {
        backupIo.execute {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (output.size() <= CardBackupCodec.MAX_BACKUP_BYTES) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Unable to open backup")
            }.fold(
                onSuccess = CardBackupCodec::decode,
                onFailure = {
                    CardBackupCodec.DecodeResult.Failure(
                        CardBackupCodec.FailureReason.MALFORMED
                    )
                }
            )
            if (!isFinishing && !isDestroyed) {
                runOnUiThread { handleBackupDecodeResult(result) }
            }
        }
    }

    private fun postBackupResult(@androidx.annotation.StringRes message: Int) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            Snackbar.make(contentHost, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun handleBackupDecodeResult(result: CardBackupCodec.DecodeResult) {
        when (result) {
            is CardBackupCodec.DecodeResult.Success -> showImportPreview(result.snapshot)
            is CardBackupCodec.DecodeResult.Failure -> Snackbar.make(
                contentHost,
                backupFailureMessage(result.reason),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    @androidx.annotation.StringRes
    private fun backupFailureMessage(reason: CardBackupCodec.FailureReason): Int = when (reason) {
        CardBackupCodec.FailureReason.EMPTY,
        CardBackupCodec.FailureReason.MALFORMED,
        CardBackupCodec.FailureReason.INVALID_PROFILE,
        CardBackupCodec.FailureReason.DUPLICATE_PROFILE,
        CardBackupCodec.FailureReason.INVALID_SELECTION -> R.string.import_backup_invalid
        CardBackupCodec.FailureReason.TOO_LARGE,
        CardBackupCodec.FailureReason.TOO_MANY_PROFILES -> R.string.import_backup_too_large
        CardBackupCodec.FailureReason.UNSUPPORTED_VERSION ->
            R.string.import_backup_unsupported_version
        CardBackupCodec.FailureReason.INTEGRITY_CHECK_FAILED ->
            R.string.import_backup_integrity_failed
    }

    private fun showImportPreview(snapshot: CardBackupCodec.Snapshot) {
        val exportedAt = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        ).format(Date(snapshot.exportedAtEpochMillis))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_backup)
            .setMessage(getString(
                R.string.import_backup_preview,
                snapshot.profiles.size,
                exportedAt
            ))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.import_backup_merge) { _, _ ->
                applyBackupImport(snapshot, CardImportPlanner.Mode.MERGE)
            }
            .setPositiveButton(R.string.import_backup_replace) { _, _ ->
                confirmBackupReplacement(snapshot)
            }
            .show()
    }

    private fun confirmBackupReplacement(snapshot: CardBackupCodec.Snapshot) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_backup_replace_title)
            .setMessage(R.string.import_backup_replace_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_backup_replace) { _, _ ->
                applyBackupImport(snapshot, CardImportPlanner.Mode.REPLACE)
            }
            .show()
    }

    private fun applyBackupImport(
        snapshot: CardBackupCodec.Snapshot,
        mode: CardImportPlanner.Mode
    ) {
        val previousSelectedId = store.selectedProfile()?.profileId
        val plan = store.importProfiles(snapshot, mode)
        if (previousSelectedId != null && plan.selectedProfileId == null) {
            cancelActivationRetry()
            session.deactivate(this)
            lastHceReport = null
            setHceStatus(getString(R.string.select_or_add_card))
        }
        showPage(TAB_SETTINGS, refreshPmm = false)
        Snackbar.make(
            contentHost,
            resources.getQuantityString(
                R.plurals.import_backup_complete,
                snapshot.profiles.size,
                snapshot.profiles.size
            ),
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun addMethodCard(
        icon: Int,
        title: Int,
        summary: Int,
        action: () -> Unit
    ): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(CARD_RADIUS_DP).toFloat()
        strokeWidth = dp(1)
        cardElevation = 0f
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(16), dp(16))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
                )
                contentDescription = getString(title)
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(18) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    setText(title)
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(sheetDescription(summary).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun activateConfiguredRoute(
        attempt: Int = 0,
        retryKind: ActivationRetryKind? = null
    ) {
        if (store.persistedRouteOnlyMode()) {
            cancelActivationRetry()
            lastHceReport = null
            setHceStatus(getString(if (store.selectedProfile() == null)
                R.string.select_or_add_card else R.string.emulation_stopped))
            renderRootlessAssessment()
            refreshCardUseState()
        } else {
            activateSelected(attempt, retryKind)
        }
    }

    private fun activateSelected(
        attempt: Int = 0,
        retryKind: ActivationRetryKind? = null
    ) {
        cancelActivationRetry()
        val selected = store.selectedProfile()
        if (selected == null) {
            session.deactivate(this)
            lastHceReport = null
            setHceStatus(getString(R.string.select_or_add_card))
            renderRootlessAssessment()
            return
        }
        val previousReport = lastHceReport
        val report = session.activate(this, selected)
        val likelyActiveLink = HceActivationRetryPolicy.isLikelyActiveLink(
            report,
            previousReport,
            retryKind == ActivationRetryKind.LINK_RELEASE
        )
        lastHceReport = if (likelyActiveLink) {
            HceSession.Report(HceSession.Stage.LINK_ACTIVE, report.stage.name)
        } else {
            report
        }
        val message = if (likelyActiveLink) {
            val linkAttempt = if (retryKind == ActivationRetryKind.LINK_RELEASE) attempt else 0
            if (linkAttempt < MAX_LINK_RELEASE_RETRIES) {
                scheduleActivationRetry(
                    linkAttempt + 1,
                    ActivationRetryKind.LINK_RELEASE
                )
                getString(R.string.hce_waiting_for_link_release)
            } else {
                getString(R.string.hce_link_release_timeout)
            }
        } else when (report.stage) {
            HceSession.Stage.READY -> getString(R.string.hce_ready, selected.label)
            HceSession.Stage.UNSUPPORTED -> getString(R.string.hce_unsupported)
            HceSession.Stage.NFC_DISABLED -> getString(R.string.nfc_disabled)
            HceSession.Stage.SERVICE_RESTARTING -> {
                val serviceAttempt = if (
                    retryKind == ActivationRetryKind.SERVICE_RESTART
                ) attempt else 0
                if (serviceAttempt < MAX_SERVICE_RESTART_RETRIES) {
                    scheduleActivationRetry(
                        serviceAttempt + 1,
                        ActivationRetryKind.SERVICE_RESTART
                    )
                    getString(R.string.hce_waiting_for_service)
                } else getString(R.string.hce_service_restart_timeout)
            }
            HceSession.Stage.LINK_ACTIVE -> getString(R.string.hce_waiting_for_link_release)
            HceSession.Stage.ID -> getString(R.string.hce_nfcid2_rejected)
            HceSession.Stage.SYSTEM_CODE -> getString(R.string.hce_system_code_rejected)
            HceSession.Stage.ENABLE -> getString(R.string.hce_enable_failed)
            HceSession.Stage.EXCEPTION -> getString(
                R.string.hce_failed,
                report.detail.ifBlank { getString(R.string.rootless_unknown_error) }
            )
        }
        setHceStatus(message)
        renderRootlessAssessment()
    }

    private fun setHceStatus(message: CharSequence) {
        hceStatusText = message
        updateHceStatusViews()
    }

    private fun updateHceStatusViews() {
        val message = if (store.advancedMode()) hceStatusText else simpleHceStatus()
        cardPageStatusView?.text = message
        statusPageStatusView?.text = message
        hceRetryAction?.visibility = hceRetryActionVisibility()
    }

    private fun simpleHceStatus(): CharSequence = when (lastHceReport?.stage) {
        HceSession.Stage.ID -> getString(R.string.simple_identity_rejected)
        HceSession.Stage.SYSTEM_CODE -> getString(R.string.simple_card_type_rejected)
        HceSession.Stage.ENABLE, HceSession.Stage.EXCEPTION ->
            getString(R.string.simple_emulation_failed)
        HceSession.Stage.UNSUPPORTED -> getString(R.string.simple_emulation_unsupported)
        else -> hceStatusText
    }

    private fun hceRetryActionVisibility(): Int = if (
        lastHceReport?.stage == HceSession.Stage.LINK_ACTIVE && activationRetry == null
    ) View.VISIBLE else View.GONE

    private fun renderRootlessAssessment() {
        if (store.persistedRouteOnlyMode()) {
            rootlessStatusView?.visibility = View.VISIBLE
            rootlessDetailView?.visibility = View.VISIBLE
            rootlessStatusView?.setText(R.string.persisted_route_only_title)
            rootlessDetailView?.setText(R.string.persisted_route_only_detail)
            return
        }
        val assessment = RootlessAssessment.from(
            lastHceReport,
            store.selectedProfile() != null
        )
        val showDiagnostic = assessment.outcome !=
            RootlessAssessment.Outcome.REGISTRATION_ACCEPTED
        rootlessStatusView?.visibility = if (showDiagnostic) View.VISIBLE else View.GONE
        rootlessDetailView?.visibility = if (showDiagnostic) View.VISIBLE else View.GONE
        val (title, detail) = when (assessment.outcome) {
            RootlessAssessment.Outcome.PROFILE_REQUIRED ->
                R.string.rootless_profile_required_title to R.string.rootless_profile_required_detail
            RootlessAssessment.Outcome.REGISTRATION_ACCEPTED ->
                R.string.rootless_accepted_title to R.string.rootless_accepted_normal_detail
            RootlessAssessment.Outcome.UNSUPPORTED ->
                R.string.rootless_unsupported_title to R.string.rootless_unsupported_detail
            RootlessAssessment.Outcome.NFC_DISABLED ->
                R.string.rootless_nfc_disabled_title to R.string.rootless_nfc_disabled_detail
            RootlessAssessment.Outcome.SERVICE_RESTARTING ->
                R.string.rootless_checking_title to R.string.rootless_checking_detail
            RootlessAssessment.Outcome.LINK_ACTIVE ->
                R.string.rootless_link_active_title to R.string.rootless_link_active_detail
            RootlessAssessment.Outcome.DYNAMIC_ID_REJECTED ->
                R.string.rootless_dynamic_id_title to R.string.rootless_dynamic_id_detail
            RootlessAssessment.Outcome.SYSTEM_CODE_REJECTED ->
                R.string.rootless_system_code_title to R.string.rootless_system_code_detail
            RootlessAssessment.Outcome.ENABLE_FAILED ->
                R.string.rootless_enable_title to R.string.rootless_enable_detail
            RootlessAssessment.Outcome.ERROR ->
                R.string.rootless_error_title to R.string.rootless_error_detail
        }
        rootlessStatusView?.setText(title)
        rootlessDetailView?.text = if (assessment.outcome == RootlessAssessment.Outcome.ERROR) {
            getString(detail, assessment.detail.ifBlank { getString(R.string.rootless_unknown_error) })
        } else {
            getString(detail)
        }
    }

    private fun scheduleActivationRetry(attempt: Int, kind: ActivationRetryKind) {
        activationRetry = Runnable {
            activationRetry = null
            if (foreground) activateConfiguredRoute(attempt, kind)
        }.also {
            val delay = when (kind) {
                ActivationRetryKind.SERVICE_RESTART -> SERVICE_RESTART_RETRY_DELAY_MS
                ActivationRetryKind.LINK_RELEASE -> LINK_RELEASE_RETRY_DELAY_MS
            }
            contentHost.postDelayed(it, delay)
        }
    }

    private fun cancelActivationRetry() {
        activationRetry?.let(contentHost::removeCallbacks)
        activationRetry = null
    }

    private fun refreshDefaultNfcAppStatus() {
        defaultNfcStatus = DefaultNfcAppChecker.check(this)
        renderDefaultNfcAppStatus()
        maybeShowDefaultNfcGuidance()
    }

    private fun renderDefaultNfcAppStatus() {
        defaultNfcStatusView?.setText(when (defaultNfcStatus) {
            DefaultNfcAppChecker.Result.ALREADY_DEFAULT -> R.string.default_nfc_app_active
            DefaultNfcAppChecker.Result.NOT_DEFAULT -> R.string.default_nfc_app_inactive
            DefaultNfcAppChecker.Result.REQUESTED -> R.string.default_nfc_app_requested
            DefaultNfcAppChecker.Result.NFC_NOT_READY -> R.string.default_nfc_app_not_ready
            DefaultNfcAppChecker.Result.UNSUPPORTED -> R.string.default_nfc_app_unsupported
            DefaultNfcAppChecker.Result.FAILED -> R.string.default_nfc_app_failed
        })
        val action = DefaultNfcAppPolicy.actionFor(defaultNfcPolicyState())
        defaultNfcAction?.apply {
            visibility = if (action == DefaultNfcAppPolicy.Action.NONE) View.GONE else View.VISIBLE
            setText(when (action) {
                DefaultNfcAppPolicy.Action.REQUEST_DEFAULT -> R.string.set_default_nfc_app
                DefaultNfcAppPolicy.Action.RESTORE_WALLET -> R.string.restore_wallet_app
                DefaultNfcAppPolicy.Action.NONE -> R.string.set_default_nfc_app
            })
        }
    }

    private fun defaultNfcPolicyState(): DefaultNfcAppPolicy.State = when (defaultNfcStatus) {
        DefaultNfcAppChecker.Result.ALREADY_DEFAULT -> DefaultNfcAppPolicy.State.DEFAULT
        DefaultNfcAppChecker.Result.NOT_DEFAULT -> DefaultNfcAppPolicy.State.NOT_DEFAULT
        else -> DefaultNfcAppPolicy.State.UNAVAILABLE
    }

    private fun handleDefaultNfcAction() {
        when (DefaultNfcAppPolicy.actionFor(defaultNfcPolicyState())) {
            DefaultNfcAppPolicy.Action.REQUEST_DEFAULT -> {
                defaultNfcStatus = DefaultNfcAppChecker.request(this)
                renderDefaultNfcAppStatus()
            }
            DefaultNfcAppPolicy.Action.RESTORE_WALLET -> {
                if (!DefaultNfcAppChecker.openWalletSettings(this)) {
                    Snackbar.make(
                        contentHost,
                        R.string.restore_wallet_settings_failed,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
            DefaultNfcAppPolicy.Action.NONE -> Unit
        }
    }

    private fun maybeShowDefaultNfcGuidance() {
        val state = defaultNfcPolicyState()
        if (!DefaultNfcAppPolicy.shouldShowGuidance(
                state,
                store.defaultNfcGuidanceShown()
            )) {
            return
        }
        store.markDefaultNfcGuidanceShown()

        val alreadyDefault = state == DefaultNfcAppPolicy.State.DEFAULT
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.default_nfc_guidance_title)
            .setMessage(
                if (alreadyDefault) R.string.default_nfc_guidance_active
                else R.string.default_nfc_guidance_inactive
            )
            .setNegativeButton(
                if (alreadyDefault) android.R.string.ok else R.string.not_now,
                null
            )
            .setPositiveButton(
                if (alreadyDefault) R.string.restore_wallet_app
                else R.string.choose_default_nfc_now
            ) { _, _ -> handleDefaultNfcAction() }
            .show()
    }

    private fun refreshPmm() {
        if (!store.persistedRouteOnlyMode()) runPmm { PmmManager.inspect() }
    }

    private fun changePmm(enabled: Boolean) = runPmm { PmmManager.setEnabled(enabled) }

    private fun runPmm(action: () -> PmmManager.Snapshot) {
        pmmSwitch?.isEnabled = false
        pmmProgress?.show()
        Thread {
            val snapshot = action()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pmmSnapshot = snapshot
                if (selectedTab == TAB_STATUS) renderPmm(snapshot)
            }
        }.apply { name = "aimesim-pmm-state"; start() }
    }

    private fun renderPmm(snapshot: PmmManager.Snapshot) {
        pmmSnapshot = snapshot
        pmmProgress?.hide()
        pmmSwitch?.apply {
            setOnCheckedChangeListener(null)
            isChecked = snapshot.status == PmmManager.Status.ACTIVE ||
                snapshot.status == PmmManager.Status.WAITING
            isEnabled = snapshot.status != PmmManager.Status.UNAVAILABLE
            setOnCheckedChangeListener { button, enabled ->
                if (button.isPressed) changePmm(enabled)
            }
        }
        val mode = if (snapshot.modernModule) getString(R.string.pmm_mode_module)
            else getString(R.string.pmm_mode_framework)
        pmmStatus?.text = if (store.advancedMode()) {
            getString(R.string.pmm_status, mode, snapshot.detail)
        } else getString(when (snapshot.status) {
            PmmManager.Status.ACTIVE -> R.string.compatibility_patch_active
            PmmManager.Status.WAITING -> R.string.compatibility_patch_waiting
            PmmManager.Status.DISABLED -> R.string.compatibility_patch_disabled
            PmmManager.Status.UNAVAILABLE -> R.string.compatibility_patch_unavailable
            PmmManager.Status.ERROR -> R.string.compatibility_patch_error
        })
    }

    private fun restorePmmSnapshot(state: Bundle?): PmmManager.Snapshot? {
        val statusName = state?.getString(STATE_PMM_STATUS) ?: return null
        val status = runCatching { PmmManager.Status.valueOf(statusName) }.getOrNull() ?: return null
        val detail = state.getString(STATE_PMM_DETAIL) ?: return null
        return PmmManager.Snapshot(status, detail, state.getBoolean(STATE_PMM_MODERN))
    }

    private fun showEditor(
        initial: CardProfile? = null,
        capturedIdm: String? = null,
        capturedSpad0: String? = null,
        capturedIdBlock: String? = null,
        capturedAccessCode: String? = null
    ) {
        val name = editorField(
            R.string.card_name,
            R.string.card_name_helper,
            initial?.label.orEmpty(),
            hex = false
        )
        val idm = editorField(
            R.string.idm,
            R.string.idm_helper,
            capturedIdm ?: initial?.idm ?: CardProfile.DEFAULT_IDM
        )
        val spad0 = editorField(
            R.string.spad0_optional,
            R.string.optional_block_helper,
            capturedSpad0 ?: initial?.spad0.orEmpty()
        )
        val idBlock = editorField(
            R.string.id_block_optional,
            R.string.optional_block_helper,
            capturedIdBlock ?: initial?.idBlock.orEmpty()
        )
        val accessCode = editorField(
            R.string.access_code,
            R.string.access_code_helper,
            capturedAccessCode?.chunked(4)?.joinToString(" ")
                ?: initial?.formattedAccessCode().orEmpty(),
            hex = false,
            numeric = true
        )
        val advanced = store.advancedMode()
        idm.layout.visibility = if (advanced || (initial == null && capturedIdm == null)) {
            View.VISIBLE
        } else View.GONE
        spad0.layout.visibility = if (advanced) View.VISIBLE else View.GONE
        idBlock.layout.visibility = if (advanced) View.VISIBLE else View.GONE
        val form = verticalLayout(this).apply {
            setPadding(dp(20), dp(10), dp(20), dp(12))
            listOf(name, accessCode, idm, spad0, idBlock).forEach { field ->
                addView(field.layout, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(8)
                })
            }
        }
        val save = MaterialButton(this).apply { setText(R.string.save) }
        val cancel = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply { setText(android.R.string.cancel) }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(cancel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(save, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        }
        val editorContent = verticalLayout(this).apply {
            setPadding(0, 0, 0, 0)
            addView(sheetTitle(if (initial == null) R.string.add_card else R.string.edit_card).apply {
                setPadding(dp(20), 0, dp(20), 0)
            })
            addView(sheetDescription(R.string.card_editor_summary).apply {
                visibility = if (advanced) View.VISIBLE else View.GONE
                setPadding(dp(20), dp(6), dp(20), dp(8))
            })
            addView(form)
        }
        val scroll = androidx.core.widget.NestedScrollView(this).apply {
            isFillViewport = true
            addView(editorContent)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setPadding(0, dp(12), 0, 0)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(actions)
        }
        val dialog = CardEditorDialog(this)
        dialog.setContentView(root)
        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val idmValid = validateHexField(idm, 16, optional = false)
            val spad0Valid = validateHexField(spad0, 32, optional = true)
            val idBlockValid = validateHexField(idBlock, 32, optional = true)
            val accessCodeValid = validateAccessCodeField(accessCode)
            if (idmValid && spad0Valid && idBlockValid && accessCodeValid) {
                val profile = CardProfile.create(
                    label = name.input.text.toString(),
                    idm = idm.input.text.toString(),
                    spad0 = spad0.input.text.toString().ifBlank { null },
                    idBlock = idBlock.input.text.toString().ifBlank { null },
                    accessCode = accessCode.input.text.toString().ifBlank { null },
                    profileId = initial?.profileId ?: java.util.UUID.randomUUID().toString()
                )
                if (profile == null) {
                    Snackbar.make(root, R.string.invalid_card_fields, Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                store.put(profile)
                if (initial == null && store.selectedProfile() == null) store.select(profile.profileId)
                dialog.dismiss()
                activateConfiguredRoute()
                showPage(TAB_CARDS, refreshPmm = false)
                bottomNavigation.selectedItemId = TAB_CARDS
            }
        }
        dialog.show()
    }

    private fun editorField(
        label: Int,
        helper: Int,
        initial: String,
        hex: Boolean = true,
        numeric: Boolean = false
    ): EditorField {
        val input = TextInputEditText(this).apply {
            setText(initial)
            isSingleLine = true
            setHorizontallyScrolling(true)
            if (hex) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                typeface = Typeface.MONOSPACE
            } else if (numeric) {
                inputType = InputType.TYPE_CLASS_NUMBER
                typeface = Typeface.MONOSPACE
            }
        }
        val layout = TextInputLayout(
            this,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(label)
            helperText = getString(helper)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        return EditorField(layout, input)
    }

    private fun validateHexField(field: EditorField, expectedLength: Int, optional: Boolean): Boolean {
        val compact = field.input.text.toString().filterNot(Char::isWhitespace)
        val valid = (optional && compact.isEmpty()) ||
            (compact.length == expectedLength && compact.all { it.digitToIntOrNull(16) != null })
        field.layout.error = if (valid) null else getString(
            if (expectedLength == 16) R.string.idm_field_error else R.string.block_field_error
        )
        return valid
    }

    private fun validateAccessCodeField(field: EditorField): Boolean {
        val compact = field.input.text.toString().filterNot { it.isWhitespace() || it == '-' }
        val valid = compact.isEmpty() || (compact.length == 20 && compact.all(Char::isDigit))
        field.layout.error = if (valid) null else getString(R.string.access_code_field_error)
        return valid
    }

    private fun sheetHandle(): View = View(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(MaterialColors.getColor(contentHost, com.google.android.material.R.attr.colorOutline))
        }
    }

    private fun sheetTitle(text: Int): TextView = TextView(this).apply {
        setText(text)
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
    }

    private fun sheetDescription(text: Int): TextView = TextView(this).apply {
        setText(text)
        textSize = 14f
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun confirmDelete(profile: CardProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_card)
            .setMessage(getString(R.string.delete_card_message, profile.label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                store.remove(profile.profileId)
                activateConfiguredRoute()
                showPage(TAB_CARDS, refreshPmm = false)
            }
            .show()
    }

    private enum class ActivationRetryKind {
        SERVICE_RESTART,
        LINK_RELEASE
    }

    private companion object {
        const val TAB_CARDS = 1001
        const val TAB_STATUS = 1002
        const val TAB_SETTINGS = 1003
        const val MAX_SERVICE_RESTART_RETRIES = 30
        const val SERVICE_RESTART_RETRY_DELAY_MS = 1_000L
        const val MAX_LINK_RELEASE_RETRIES = 20
        const val LINK_RELEASE_RETRY_DELAY_MS = 500L
        const val CARD_RADIUS_DP = 8
        const val STATE_SELECTED_TAB = "main.selected.tab"
        const val STATE_PMM_STATUS = "main.pmm.status"
        const val STATE_PMM_DETAIL = "main.pmm.detail"
        const val STATE_PMM_MODERN = "main.pmm.modern"
    }

    private data class EditorField(
        val layout: TextInputLayout,
        val input: TextInputEditText
    )
}
