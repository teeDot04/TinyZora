package com.telo.tinyzora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.telo.tinyzora.ui.chat.ChatScreen
import com.telo.tinyzora.ui.chat.ChatViewModel
import com.telo.tinyzora.data.llm.InferenceManager
import com.telo.tinyzora.ui.lock.PinLockScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TinyZoraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "/lock") {
                        composable("/lock") {
                            PinLockScreen(
                                onPinSuccess = {
                                    navController.navigate("/chat") {
                                        popUpTo("/lock") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("/chat") {
                            val context = LocalContext.current
                            val inferenceManager = InferenceManager.getInstance(context)

                            val factory = object : ViewModelProvider.Factory {
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    @Suppress("UNCHECKED_CAST")
                                    return ChatViewModel(inferenceManager) as T
                                }
                            }
                            val viewModel: ChatViewModel = viewModel(factory = factory)
                            
                            ChatScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TinyZoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
