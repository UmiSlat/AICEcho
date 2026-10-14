package io.github.umislat.aicecho.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import io.github.umislat.aicecho.BuildConfig
import io.github.umislat.aicecho.R

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setInsetAwareContentView(buildScreen())
    }

    private fun buildScreen(): LinearLayout {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.about)
            setTitleTextAppearance(this@AboutActivity, R.style.TextAppearance_AICEcho_Toolbar)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = verticalLayout(this)
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, dp(16))
            addView(ImageView(this@AboutActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                contentDescription = getString(R.string.app_name)
            }, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(TextView(this@AboutActivity).apply {
                setText(R.string.app_name)
                textSize = 26f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(2))
            })
            addView(TextView(this@AboutActivity).apply {
                text = getString(
                    R.string.about_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                )
                textSize = 14f
                gravity = Gravity.CENTER
                alpha = 0.72f
            })
            addView(TextView(this@AboutActivity).apply {
                setText(R.string.about_tagline)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), 0)
            })
        })

        content.addView(infoCard(R.string.about_overview_title, R.string.about_overview_body))
        content.addView(infoCard(R.string.about_capabilities_title, R.string.about_capabilities_body))
        content.addView(infoCard(R.string.about_privacy_title, R.string.about_privacy_body))
        content.addView(infoCard(R.string.about_safety_title, R.string.about_safety_body))
        content.addView(infoCard(R.string.about_scope_title, R.string.about_scope_body))
        content.addView(MaterialButton(this).apply {
            setText(R.string.open_repository)
            setOnClickListener { openRepository(root) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun infoCard(@StringRes title: Int, @StringRes body: Int): MaterialCardView =
        MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            strokeWidth = dp(1)
            layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            addView(LinearLayout(this@AboutActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(TextView(this@AboutActivity).apply {
                    setText(title)
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@AboutActivity).apply {
                    setText(body)
                    textSize = 14f
                    setLineSpacing(0f, 1.12f)
                    setPadding(0, dp(8), 0, 0)
                    alpha = 0.82f
                })
            })
        }

    private fun openRepository(anchor: android.view.View) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)))
        }.onFailure {
            Snackbar.make(anchor, R.string.open_repository_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REPOSITORY_URL = "https://github.com/UmiSlat/AICEcho"
    }
}
