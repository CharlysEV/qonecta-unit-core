package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.fragment.NavHostFragment
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemUI

class SettingsActivity : BaseActivity() {

    var keyListener: MainActivity.KeyListener? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (keyListener?.onKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun attachBaseContext(newBase: Context) {
        val settings = Settings(newBase)
        val scale = settings.uiScaleSettingsPercent / 100.0f
        if (scale != 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val cfg = Configuration(newBase.resources.configuration)
            val metrics = newBase.resources.displayMetrics
            cfg.densityDpi = (metrics.densityDpi * scale).toInt()
            val ctx = newBase.createConfigurationContext(cfg)
            super.attachBaseContext(ctx)
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val appSettings = Settings(this)
        requestedOrientation = appSettings.screenOrientation.androidOrientation
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Pure Qonecta Obsidian Cockpit theme across Settings & every nested sub-screen
        theme.applyStyle(R.style.ThemeOverlay_DiAutoSettings, true)
        window.setBackgroundDrawableResource(R.color.hql_bg)
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF070B11.toInt()
        @Suppress("DEPRECATION")
        window.navigationBarColor = 0xFF070B11.toInt()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_settings)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.settings_nav_host) as NavHostFragment
        val navController = navHostFragment.navController

        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        navGraph.startDestination = R.id.qonectaSettingsFragment
        navController.graph = navGraph

        val requestedDestination = intent?.getIntExtra(EXTRA_DESTINATION, 0) ?: 0
        val restoredDestination = if (requestedDestination != 0) requestedDestination
            else savedInstanceState?.getInt(KEY_CURRENT_DESTINATION, 0) ?: 0
        if (restoredDestination != 0 && restoredDestination != R.id.qonectaSettingsFragment) {
            try {
                navController.navigate(restoredDestination)
            } catch (_: Exception) {}
        }

        val root = findViewById<View>(R.id.settings_nav_host)
        SystemUI.apply(window, root, appSettings.fullscreenMode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.settings_nav_host) as? NavHostFragment
        val currentDest = navHostFragment?.navController?.currentDestination?.id ?: 0
        outState.putInt(KEY_CURRENT_DESTINATION, currentDest)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val appSettings = Settings(this)
            val root = findViewById<View>(R.id.settings_nav_host)
            SystemUI.apply(window, root, appSettings.fullscreenMode)
        }
    }

    companion object {
        private const val KEY_CURRENT_DESTINATION = "current_nav_destination"
        const val EXTRA_DESTINATION = "extra_destination"
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
    }
}
