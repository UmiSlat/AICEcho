package io.github.umislat.aicecho.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import io.github.umislat.aicecho.R
import io.github.umislat.aicecho.data.CardProfile
import io.github.umislat.aicecho.data.CardStore
import io.github.umislat.aicecho.provisioning.NfcProvisionWorkflow
import io.github.umislat.aicecho.provisioning.ShizukuProbeModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardSetupLayoutTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp")
    fun narrowPhone() = preview("setup-narrow-zh")

    @Test @Config(qualifiers = "en-w360dp-h640dp")
    fun largerEnglishText() = preview("setup-large-en", fontScale = 1.3f)

    @Test @Config(qualifiers = "zh-rCN-w600dp-h900dp")
    fun tablet() = preview("setup-tablet-zh")

    private fun preview(name: String, fontScale: Float = 1f) {
        RuntimeEnvironment.setFontScale(fontScale)
        val app = RuntimeEnvironment.getApplication()
        for (preference in listOf("aicecho", "shizuku_nfc_provision", "shizuku_nfc_snapshot", "shizuku_restore_probe")) {
            app.getSharedPreferences(preference, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(app.noBackupFilesDir, "nfc-provision-plan.json").delete()
        val card = requireNotNull(CardProfile.create("My Aime card", "012E112233445566"))
        CardStore(app).apply { put(card); select(card.profileId) }
        val controller = Robolectric.buildActivity(ShizukuProvisioningActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val model = ViewModelProvider(activity)[ShizukuProbeModel::class.java]
            (model.screen as MutableLiveData).value = ShizukuProbeModel.Screen(
                connection = ShizukuProbeModel.Connection.READY, uid = 2000, bu = true,
                provisionCandidate = true, provisionIdm = card.idm, planSaved = true,
                provision = NfcProvisionWorkflow.Result(NfcProvisionWorkflow.State.PREPARED))
            val root = activity.findViewById<View>(android.R.id.content)
            val metrics = activity.resources.displayMetrics
            val configuration = activity.resources.configuration
            val width = (configuration.screenWidthDp * metrics.density).toInt()
            val height = (configuration.screenHeightDp * metrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val visible = views(root).filter { generateSequence(it) { view -> view.parent as? View }.all { view -> view.visibility == View.VISIBLE } }.toList()
            val buttons = visible.filterIsInstance<MaterialButton>()
            assertEquals(listOf(activity.getString(R.string.setup_apply)), buttons.map { it.text.toString() })
            for (view in visible.filterIsInstance<TextView>()) {
                val layout = view.layout ?: continue
                assertTrue("Clipped text: ${view.text}", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface))
            root.draw(canvas)
            val colors = (0 until height step 8).flatMap { y -> (0 until width step 8).map { x -> bitmap.getPixel(x, y) } }.toSet()
            assertTrue("Blank preview", colors.size > 8)
            val file = File("build/ui-previews/$name.png")
            requireNotNull(file.parentFile).mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { controller.pause().stop().destroy() }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
