package com.telo.tinyzora.ui

import androidx.compose.runtime.*

@Composable
fun TinyZoraApp() {
    var showSplash by remember { mutableStateOf(true) }

    if (showSplash) {
        SplashScreen { showSplash = false }
    } else {
        ChatScreen()
    }
}
