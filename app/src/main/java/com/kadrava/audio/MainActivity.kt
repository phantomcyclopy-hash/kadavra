package com.kadrava.audio

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kadrava.audio.ui.HomeScreen
import com.kadrava.audio.ui.KadavraTheme
import com.kadrava.audio.ui.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        Palette.light = getSharedPreferences("kadavra", MODE_PRIVATE).getBoolean("light", false)
        setContent { KadavraTheme { HomeScreen() } }
    }
}
