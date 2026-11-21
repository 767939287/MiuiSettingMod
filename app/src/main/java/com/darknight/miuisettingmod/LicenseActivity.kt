package com.darknight.miuisettingmod

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.darknight.miuisettingmod.ui.screen.about.LicensePageContent
import com.darknight.miuisettingmod.ui.theme.AppTheme
import com.darknight.miuisettingmod.ui.util.applyWindowBackground
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.basic.Scaffold

class LicenseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val savedSettings = AppSettings.load(this)
        val themeMode = try {
            ColorSchemeMode.valueOf(savedSettings.themeMode)
        } catch (_: Exception) {
            ColorSchemeMode.System
        }
        val isBlurEnabled = savedSettings.isBlurEnabled

        applyWindowBackground(savedSettings.themeMode)

        setContent {
            AppTheme(themeMode = themeMode) {
                Scaffold {
                    LicensePageContent(onBack = { finish() }, isBlurEnabled = isBlurEnabled)
                }
            }
        }
    }
}
