package io.github.umislat.aimesimulator.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.NfcFCardEmulation
import android.os.DeadObjectException
import android.util.Log
import io.github.umislat.aimesimulator.data.CardProfile
import io.github.umislat.aimesimulator.data.CardStore

internal class HceSession(private val context: Context) {
    enum class Stage {
        READY, UNSUPPORTED, NFC_DISABLED, SERVICE_RESTARTING, LINK_ACTIVE, ID,
        SYSTEM_CODE, ENABLE, EXCEPTION
    }

    data class Report(val stage: Stage, val detail: String = "") {
        val succeeded: Boolean get() = stage == Stage.READY
    }

    private val component = ComponentName(context, AimeHostService::class.java)
    // Retain the adapter for Android's adapter-level recovery. Some vendor builds keep
    // a separate stale NFC-F Binder, so this does not guarantee NFC-F recovery.
    private var adapter: NfcAdapter? = runCatching {
        NfcAdapter.getDefaultAdapter(context)
    }.getOrNull()

    fun activate(
        activity: Activity,
        profile: CardProfile,
        systemCode: String = SYSTEM_CODE
    ): Report = activateProfile(
        activity,
        profile.profileId,
        profile.idm,
        systemCode
    )

    fun activatePersistedRoute(
        activity: Activity,
        nfcid2: String,
        systemCode: String = SYSTEM_CODE
    ): PersistedHceRouteWorkflow.Result {
        val workflow = PersistedHceRouteWorkflow(
            backend = androidBackend(activity, component),
            failureReporter = ::persistedRouteFailure
        )
        return workflow.activate(nfcid2, systemCode)
    }

    fun inspectPersistedRoute(activity: Activity, nfcid2: String): PersistedHceRouteWorkflow.Result =
        PersistedHceRouteWorkflow(androidBackend(activity, component), ::persistedRouteFailure)
            .inspect(nfcid2, SYSTEM_CODE)

    private fun activateProfile(
        activity: Activity,
        profileId: String,
        nfcid2: String,
        systemCode: String
    ): Report {
        val store by lazy(LazyThreadSafetyMode.NONE) { CardStore(context) }
        val workflow = HceActivationWorkflow(
            backend = androidBackend(activity, component),
            selection = object : HceActivationWorkflow.Selection {
                override fun selectedProfileId(): String? = store.selectedProfile()?.profileId

                override fun select(profileId: String?) = store.select(profileId)
            },
            failureReporter = ::runtimeFailure
        )
        return workflow.activate(profileId, nfcid2, systemCode)
    }

    private fun androidBackend(
        activity: Activity,
        serviceComponent: ComponentName
    ): AndroidHceBackend = AndroidHceBackend(activity, serviceComponent)

    private inner class AndroidHceBackend(
        private val activity: Activity,
        private val serviceComponent: ComponentName
    ) : HceActivationWorkflow.Backend, PersistedHceRouteWorkflow.Backend {
        private var activeAdapter: NfcAdapter? = null
        private var cachedManager: NfcFCardEmulation? = null

        override fun isSupported(): Boolean = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF
        )

        override fun availability(): HceActivationWorkflow.Availability {
            val nfcAdapter = resolveAdapter()
                ?: return HceActivationWorkflow.Availability.SERVICE_RESTARTING
            activeAdapter = nfcAdapter
            return if (nfcAdapter.isEnabled) {
                HceActivationWorkflow.Availability.READY
            } else {
                HceActivationWorkflow.Availability.NFC_DISABLED
            }
        }

        override fun disable() {
            manager().disableService(activity)
        }

        override fun setNfcid2(nfcid2: String): Boolean =
            manager().setNfcid2ForService(serviceComponent, nfcid2)

        override fun registerSystemCode(systemCode: String): Boolean =
            manager().registerSystemCodeForService(serviceComponent, systemCode)

        override fun currentSystemCode(): String? =
            manager().getSystemCodeForService(serviceComponent)

        override fun currentNfcid2(): String? =
            manager().getNfcid2ForService(serviceComponent)

        override fun enable(): Boolean = manager().enableService(activity, serviceComponent)

        private fun manager(): NfcFCardEmulation = cachedManager ?: NfcFCardEmulation.getInstance(
            checkNotNull(activeAdapter) { "NFC adapter was not prepared" }
        ).also { cachedManager = it }
    }

    fun deactivate(activity: Activity) {
        val nfcAdapter = resolveAdapter() ?: return
        runCatching { NfcFCardEmulation.getInstance(nfcAdapter).disableService(activity) }
    }

    private fun resolveAdapter(): NfcAdapter? {
        adapter?.let { return it }
        return runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()?.also {
            adapter = it
        }
    }

    private fun report(stage: Stage, detail: String = ""): Report = Report(stage, detail)

    private fun runtimeFailure(error: RuntimeException): Report {
        val cause = rootCause(error)
        Log.w(TAG, "HCE-F activation failed", error)
        return if (cause is DeadObjectException ||
            cause.javaClass.name == "android.os.DeadSystemException" ||
            cause.message.orEmpty().contains("DeadObjectException", ignoreCase = true)
        ) {
            report(Stage.SERVICE_RESTARTING)
        } else {
            val detail = cause.message?.takeIf(String::isNotBlank)
                ?.let { "${cause.javaClass.simpleName}: $it" }
                ?: cause.javaClass.simpleName
            report(Stage.EXCEPTION, detail)
        }
    }

    private fun persistedRouteFailure(error: RuntimeException): PersistedHceRouteWorkflow.Result {
        val report = runtimeFailure(error)
        return if (report.stage == Stage.SERVICE_RESTARTING) {
            PersistedHceRouteWorkflow.Result(
                PersistedHceRouteWorkflow.Outcome.SERVICE_RESTARTING
            )
        } else {
            PersistedHceRouteWorkflow.Result(
                PersistedHceRouteWorkflow.Outcome.ERROR,
                report.detail
            )
        }
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    companion object {
        private const val TAG = "AimeHceSession"
        const val SYSTEM_CODE = "88B4"
    }
}
