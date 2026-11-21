package com.pitichicken.instaguard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shows whether the accessibility service is enabled and opens the system settings so the user can toggle it
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText=TextView(this).apply {
            textSize=18f
            gravity=Gravity.CENTER
        }
        val button=Button(this).apply {
            text="Ouvrir les réglages d'accessibilité"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                addView(statusText)
                addView(button)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        statusText.text = if (isServiceEnabled()) {
            "InstaGuard est actif "
        } else {
            "InstaGuard est désactivé\nActive-le dans Accessibilité/Applications installées"
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected=ComponentName(this, InstaGuardService::class.java).flattenToString()
        val enabled=Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
