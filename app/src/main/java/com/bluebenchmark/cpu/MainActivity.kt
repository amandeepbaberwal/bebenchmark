package com.bluebenchmark.cpu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bluebenchmark.cpu.ui.screen.AppRoot
import com.bluebenchmark.cpu.ui.theme.MonoTheme

class MainActivity : ComponentActivity() {
    private val vm: BenchmarkViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.start() }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(vm::writeExport) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        setContent {
            val dark by vm.darkTheme.collectAsState()
            androidx.compose.runtime.LaunchedEffect(dark) {
                val barColor = if (dark) android.graphics.Color.rgb(23, 21, 17) else android.graphics.Color.rgb(244, 240, 229)
                window.statusBarColor = barColor
                window.navigationBarColor = barColor
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MonoTheme(dark = dark) {
                Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AppRoot(
                        vm = vm,
                        onStart = ::startBenchmark,
                        onExport = { exportLauncher.launch("be_benchmark_${System.currentTimeMillis()}.json") }
                    )
                }
            }
        }
    }

    private fun startBenchmark() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.start()
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.rgb(244, 240, 229)
        window.navigationBarColor = android.graphics.Color.rgb(244, 240, 229)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }
}
