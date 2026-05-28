package com.telo.tinyzora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import com.telo.tinyzora.ui.chat.ChatScreen
import com.telo.tinyzora.ui.chat.ChatViewModel
import com.telo.tinyzora.ui.settings.SettingsScreen
import com.telo.tinyzora.ui.memory.MemoryScreen
import com.telo.tinyzora.core.notifications.NotificationHelper
import com.telo.tinyzora.core.training.NightWorkerScheduler
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.telo.tinyzora.ui.theme.TinyZoraTheme
import com.telo.tinyzora.core.security.UserPreferences
import com.telo.tinyzora.ui.lockscreen.LockScreen
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Handle if needed
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        NotificationHelper.createChannels(this)
        NightWorkerScheduler.scheduleNightWorker(this)
        askNotificationPermission()
        com.telo.tinyzora.util.ConsoleLogger.init()
        com.telo.tinyzora.core.inference.LlamaServerService.start(this)

        setContent {
            TinyZoraTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route?.substringBefore("?")

                val userPrefs = remember { UserPreferences(this@MainActivity) }
                val startDest = if (userPrefs.isPinSet()) "/lockscreen" else "/chat"

                NavHost(
                    navController = navController, 
                    startDestination = "/splash",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    composable("/splash") {
                        val scale = remember { androidx.compose.animation.core.Animatable(0.5f) }
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            scale.animateTo(
                                targetValue = 1.0f,
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                )
                            )
                            kotlinx.coroutines.delay(800)
                            navController.navigate(startDest) {
                                popUpTo("/splash") { inclusive = true }
                            }
                        }
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = R.mipmap.ic_splash_logo),
                                contentDescription = "App Logo",
                                modifier = Modifier.size(280.dp).graphicsLayer(
                                    scaleX = scale.value, 
                                    scaleY = scale.value
                                )
                            )
                        }
                    }

                    composable("/lockscreen") {
                        LockScreen(
                            onUnlock = {
                                navController.navigate("/chat") {
                                    popUpTo("/lockscreen") { inclusive = true }
                                }
                            },
                            onResetComplete = {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    val context = this@MainActivity
                                    com.telo.tinyzora.core.chat.ChatRepository(context).getChatHistoryFile().delete()
                                    File(context.filesDir, "memory.json").delete()
                                    com.telo.tinyzora.core.security.UserPreferences(context).clearPin()
                                    
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        val pm = context.packageManager
                                        val intent = pm.getLaunchIntentForPackage(context.packageName)
                                        val mainIntent = Intent.makeRestartActivityTask(intent?.component)
                                        context.startActivity(mainIntent)
                                        Runtime.getRuntime().exit(0)
                                    }
                                }
                            }
                        )
                    }

                    composable("/chat") {
                        val viewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                        ChatScreen(
                            viewModel = viewModel,
                            onOpenSettings = {
                                navController.navigate("/settings")
                            }
                        )
                    }
                    
                    composable("/settings") {
                        val chatRepo = com.telo.tinyzora.core.chat.ChatRepository(this@MainActivity)
                        SettingsScreen(
                            chatHistoryFile = chatRepo.getChatHistoryFile(),
                            onBack = { navController.popBackStack() },
                            onOpenMemory = { navController.navigate("/memory") },
                            onOpenAIConfig = { navController.navigate("/ai_config") }
                        )
                    }
                    
                    composable("/memory") {
                        com.telo.tinyzora.ui.memory.MemoryScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("/ai_config") {
                        com.telo.tinyzora.ui.settings.ModelManagerScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                }
            }
        }
        
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    override fun onDestroy() {
              com.telo.tinyzora.core.inference.LlamaServerService.stop(this)
                      super.onDestroy()
                          
    }

    private fun handleIntent(intent: Intent?) {
        val reminderContext = intent?.getStringExtra("REMINDER_CONTEXT")
        if (reminderContext != null) {
            val viewModel: ChatViewModel = ViewModelProvider(this)[ChatViewModel::class.java]
            viewModel.injectReminderContext(reminderContext)
        }
    }
}


