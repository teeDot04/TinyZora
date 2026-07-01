package com.telo.tinyzora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
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
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import androidx.activity.OnNewIntentListener

class MainActivity : ComponentActivity() {

    // Conflated flow ensures the latest intent is always available without silent drops
    val reminderEvents = MutableSharedFlow<Intent>(replay = 1, extraBufferCapacity = 1)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
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

        setContent {
            TinyZoraTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route?.substringBefore("?")
                
                val context = LocalContext.current
                val appCtx = context.applicationContext
                val activity = context as? MainActivity
                
                val userPrefs = remember { UserPreferences(appCtx) }
                val startDest = if (userPrefs.isPinSet()) "/lockscreen" else "/chat"
                
                // Extract cold start data ONCE without mutating the original Intent
                val coldStartReminder = remember { 
                    intent?.getStringExtra("REMINDER_CONTEXT") 
                }

                // Handle hot start intents at the NavHost level to navigate if needed
                LaunchedEffect(Unit) {
                    activity?.reminderEvents?.collect { newIntent ->
                        val reminderContext = newIntent.getStringExtra("REMINDER_CONTEXT") ?: return@collect
                        if (currentRoute != "/chat") {
                            navController.navigate("/chat?reminderContext=$reminderContext")
                        }
                    }
                }

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
                        LaunchedEffect(Unit) {
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
                                // Pass cold start reminder when unlocking
                                val targetRoute = if (coldStartReminder != null) {
                                    "/chat?reminderContext=$coldStartReminder"
                                } else {
                                    "/chat"
                                }
                                navController.navigate(targetRoute) {
                                    popUpTo("/lockscreen") { inclusive = true }
                                }
                            },
                            onResetComplete = {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    com.telo.tinyzora.core.chat.ChatRepository(appCtx).getChatHistoryFile().delete()
                                    File(appCtx.filesDir, "memory.json").delete()
                                    com.telo.tinyzora.core.security.UserPreferences(appCtx).clearPin()

                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        val pm = appCtx.packageManager
                                        val launchIntent = pm.getLaunchIntentForPackage(appCtx.packageName)
                                        val mainIntent = Intent.makeRestartActivityTask(launchIntent?.component)
                                        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        appCtx.startActivity(mainIntent)
                                        Runtime.getRuntime().exit(0)
                                    }
                                }
                            }
                        )
                    }

                    composable(
                        route = "/chat?reminderContext={reminderContext}",
                        arguments = listOf(navArgument("reminderContext") { nullable = true; defaultValue = null })
                    ) { backStackEntry ->
                        val viewModel: ChatViewModel = viewModel()
                        val reminderContext = backStackEntry.arguments?.getString("reminderContext")
                        
                        // Track handled context to prevent duplicate injections on re-entry
                        var handledContext by remember { mutableStateOf<String?>(null) }
                        
                        LaunchedEffect(reminderContext) {
                            if (!reminderContext.isNullOrBlank() && handledContext != reminderContext) {
                                viewModel.injectReminderContext(reminderContext)
                                handledContext = reminderContext
                            }
                        }

                        // Handle hot start intents when already on this screen
                        DisposableEffect(Unit) {
                            val listener = OnNewIntentListener { intent ->
                                intent.getStringExtra("REMINDER_CONTEXT")?.let {
                                    viewModel.injectReminderContext(it)
                                }
                            }
                            activity?.addOnNewIntentListener(listener)
                            onDispose { activity?.removeOnNewIntentListener(listener) }
                        }

                        ChatScreen(
                            viewModel = viewModel,
                            onOpenSettings = {
                                navController.navigate("/settings")
                            }
                        )
                    }

                    composable("/settings") {
                        val chatRepo = com.telo.tinyzora.core.chat.ChatRepository(appCtx)
                        SettingsScreen(
                            chatHistoryFile = chatRepo.getChatHistoryFile(),
                            onBack = { navController.popBackStack() },
                            onOpenMemory = { navController.navigate("/memory") },
                            onOpenAIConfig = { navController.navigate("/ai_config") }
                        )
                    }

                    composable("/memory") {
                        MemoryScreen(
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Emit to the conflated flow. No silent drops, survives UI lifecycle.
        reminderEvents.tryEmit(intent)
    }
}
