package com.rushify.mobile

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rushify.mobile.ui.AppRoot
import androidx.compose.runtime.CompositionLocalProvider
import com.rushify.mobile.ui.LocalIconLoader
import com.rushify.mobile.ui.RushifyTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as RushifyApp).container
        setContent {
            RushifyTheme {
                CompositionLocalProvider(LocalIconLoader provides container.icons) {
                    AppRoot(container, this)
                }
            }
        }
    }

    fun applyScreenPrivacy(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
