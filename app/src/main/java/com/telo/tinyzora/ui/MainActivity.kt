package com.telo.tinyzora.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.telo.tinyzora.ui.theme.TinyZoraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🧠 INJECT MEMORIES (Runs only once)
        com.telo.tinyzora.data.MemorySeeder.seedDatabase(this)

        android.util.Log.i("TinyZora", "MainActivity: onCreate started")
        setContent {
            TinyZoraTheme {
                TinyZoraApp()
            }
        }
    }
}
