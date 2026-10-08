package io.github.umislat.aimesimulator

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import rikka.shizuku.ShizukuProvider

class AimeApplication : Application() {
    init {
        // This experiment must not silently select an installed Root/Sui backend.
        ShizukuProvider.disableAutomaticSuiInitialization()
    }

    override fun onCreate() {
        super.onCreate()
        ThemeSettings.applyMode(this)
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { activity, _ -> ThemeSettings.dynamicColorsEnabled(activity) }
                .build()
        )
    }
}
