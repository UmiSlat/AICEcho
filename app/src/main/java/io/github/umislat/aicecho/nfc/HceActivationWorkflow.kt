package io.github.umislat.aicecho.nfc

internal class HceActivationWorkflow(
    private val backend: Backend,
    private val selection: Selection,
    private val failureReporter: (RuntimeException) -> HceSession.Report
) {
    enum class Availability {
        READY, SERVICE_RESTARTING, NFC_DISABLED
    }

    interface Backend {
        fun isSupported(): Boolean
        fun availability(): Availability
        fun disable()
        fun setNfcid2(nfcid2: String): Boolean
        fun registerSystemCode(systemCode: String): Boolean
        fun enable(): Boolean
    }

    interface Selection {
        fun selectedProfileId(): String?
        fun select(profileId: String?)
    }

    fun activate(profileId: String, nfcid2: String, systemCode: String): HceSession.Report {
        if (!backend.isSupported()) return HceSession.Report(HceSession.Stage.UNSUPPORTED)

        val availability = try {
            backend.availability()
        } catch (error: RuntimeException) {
            return failureReporter(error)
        }
        when (availability) {
            Availability.SERVICE_RESTARTING -> {
                return HceSession.Report(HceSession.Stage.SERVICE_RESTARTING)
            }
            Availability.NFC_DISABLED -> return HceSession.Report(HceSession.Stage.NFC_DISABLED)
            Availability.READY -> Unit
        }

        val previousId = selection.selectedProfileId()
        val selectionChanged = previousId != profileId
        if (selectionChanged) selection.select(profileId)

        return try {
            backend.disable()
            if (!backend.setNfcid2(nfcid2)) {
                restore(previousId, selectionChanged)
                return HceSession.Report(HceSession.Stage.ID)
            }
            if (!backend.registerSystemCode(systemCode)) {
                backend.disable()
                restore(previousId, selectionChanged)
                return HceSession.Report(HceSession.Stage.SYSTEM_CODE)
            }
            if (!backend.enable()) {
                backend.disable()
                restore(previousId, selectionChanged)
                return HceSession.Report(HceSession.Stage.ENABLE)
            }
            HceSession.Report(HceSession.Stage.READY)
        } catch (error: RuntimeException) {
            restore(previousId, selectionChanged)
            failureReporter(error)
        }
    }

    private fun restore(profileId: String?, selectionChanged: Boolean) {
        if (selectionChanged) selection.select(profileId)
    }
}
