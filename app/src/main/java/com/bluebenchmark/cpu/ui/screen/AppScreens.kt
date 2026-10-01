package com.bluebenchmark.cpu.ui.screen

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluebenchmark.cpu.BenchmarkViewModel
import com.bluebenchmark.cpu.CoreMode
import com.bluebenchmark.cpu.R
import com.bluebenchmark.cpu.RunState
import com.bluebenchmark.cpu.data.local.BenchmarkRecord
import com.bluebenchmark.cpu.engine.CpuSuiteScore
import com.bluebenchmark.cpu.engine.SuiteRunConfig
import com.bluebenchmark.cpu.telemetry.TelemetrySnapshot
import com.bluebenchmark.cpu.ui.theme.AppAccent
import com.bluebenchmark.cpu.ui.theme.AppAccentDeep
import com.bluebenchmark.cpu.ui.theme.AppBorder
import com.bluebenchmark.cpu.ui.theme.AppError
import com.bluebenchmark.cpu.ui.theme.AppFocus
import com.bluebenchmark.cpu.ui.theme.AppMuted
import com.bluebenchmark.cpu.ui.theme.AppOrange
import com.bluebenchmark.cpu.ui.theme.AppSurface
import com.bluebenchmark.cpu.ui.theme.AppText
import com.bluebenchmark.cpu.ui.theme.AppWarning
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val mono = FontFamily.Monospace
private val pixel = FontFamily(Font(R.font.press_start_2p))
private enum class Page(val label: String) {
    BENCHMARK("Benchmark"), RESULTS("Results"), DEVICE("Device"), COMPARE("Compare")
}
private enum class HeaderAction { SETTINGS, BACK }

@Composable
fun AppRoot(vm: BenchmarkViewModel, onStart: () -> Unit, onExport: () -> Unit) {
    val dark by vm.darkTheme.collectAsState()
    val records by vm.history.collectAsState(initial = emptyList())
    var page by remember { mutableStateOf(Page.BENCHMARK) }
    var settings by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<BenchmarkRecord?>(null) }
    val context = LocalContext.current

    BackHandler(enabled = privacy || settings || selectedRecord != null || page != Page.BENCHMARK) {
        when {
            privacy -> privacy = false
            selectedRecord != null -> selectedRecord = null
            settings -> settings = false
            else -> page = Page.BENCHMARK
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (selectedRecord == null && !settings && !privacy) {
                BottomNavigationBar(page, onSelect = { page = it })
            }
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            PaperTexture(Modifier.fillMaxSize().padding(insets))
            Column(Modifier.fillMaxSize().padding(insets)) {
            AppHeader(
                title = when {
                    privacy -> "Privacy Policy"
                    settings -> "Settings"
                    selectedRecord != null -> "Result detail"
                    else -> "BE Benchmark"
                },
                subtitle = when {
                    privacy -> "HOW YOUR DATA IS HANDLED"
                    settings -> "PREFERENCES"
                    selectedRecord != null -> selectedRecord?.mode?.replace('_', ' ') ?: ""
                    else -> when (page) {
                        Page.BENCHMARK -> "CPU PERFORMANCE ANALYZER"
                        Page.RESULTS -> "YOUR BENCHMARK HISTORY"
                        Page.DEVICE -> "DEVICE & LIVE TELEMETRY"
                        Page.COMPARE -> "COMPARE PERFORMANCE"
                    }
                },
                action = if (privacy || settings || selectedRecord != null) HeaderAction.BACK else HeaderAction.SETTINGS,
                isHome = !settings && selectedRecord == null && page == Page.BENCHMARK,
                onAction = {
                    if (privacy) {
                        privacy = false
                    } else if (settings || selectedRecord != null) {
                        settings = false
                        selectedRecord = null
                    } else settings = true
                }
            )
            Box(Modifier.weight(1f)) {
                when {
                    privacy -> PrivacyPolicyScreen {
                        val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:help@lapetlo.com"))
                        runCatching { context.startActivity(emailIntent) }
                    }
                    settings -> SettingsScreen(vm, records.size, onExport, onPrivacy = { privacy = true })
                    selectedRecord != null -> ResultDetailScreen(selectedRecord!!)
                    page == Page.BENCHMARK -> CompositionLocalProvider(
                        LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = 12.sp)
                    ) {
                        BenchmarkHome(vm, records, onStart) { selectedRecord = it }
                    }
                    page == Page.RESULTS -> ResultsScreen(records, onSelect = { selectedRecord = it })
                    page == Page.DEVICE -> DeviceScreen(vm)
                    else -> CompareScreen(records)
                }
            }
        }
        }
    }
}

@Composable
private fun AppHeader(title: String, subtitle: String, action: HeaderAction, isHome: Boolean, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = if (isHome) 5.dp else 9.dp, bottom = if (isHome) 4.dp else 8.dp)) {
        if (isHome) {
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = 12.sp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("BE", color = AppAccent, fontFamily = pixel, fontSize = 18.sp, lineHeight = 22.sp, maxLines = 1)
                    Spacer(Modifier.width(7.dp))
                    Text("Benchmark", color = MaterialTheme.colorScheme.onBackground, fontFamily = pixel, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("v1.0.0", color = AppMuted, fontFamily = mono, fontSize = 7.sp, lineHeight = 9.sp)
                        Box(
                            Modifier.size(28.dp).border(1.dp, AppBorder)
                                .clickable(role = Role.Button, onClick = onAction)
                                .semantics { contentDescription = "Open settings" },
                            contentAlignment = Alignment.Center
                        ) { GearGlyph(Modifier.size(15.dp)) }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(subtitle, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground,
                        fontFamily = mono, fontSize = 8.sp, lineHeight = 10.sp, letterSpacing = 1.2.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    Text("TEST · COMPARE · UNDERSTAND", color = AppMuted, fontFamily = mono, fontSize = 6.sp,
                        lineHeight = 8.sp, maxLines = 1, softWrap = false)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title.uppercase(), color = MaterialTheme.colorScheme.onBackground, fontFamily = pixel, fontWeight = FontWeight.Bold, fontSize = 8.sp)
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 8.sp, letterSpacing = 1.sp)
                }
                Box(
                    Modifier.size(34.dp).border(1.dp, AppBorder)
                        .clickable(role = Role.Button, onClick = onAction)
                        .semantics { contentDescription = if (action == HeaderAction.SETTINGS) "Open settings" else "Go back" },
                    contentAlignment = Alignment.Center
                ) {
                    if (action == HeaderAction.SETTINGS) GearGlyph(Modifier.size(18.dp))
                    else BackGlyph(Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(if (isHome) 4.dp else 8.dp))
        DottedRule()
    }
}

@Composable
private fun GearGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val innerRadius = 5.5.dp.toPx()
        val outerRadius = 8.dp.toPx()
        drawCircle(AppAccent, radius = innerRadius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        repeat(8) { tooth ->
            val angle = Math.PI * tooth / 4.0
            drawLine(
                AppAccent,
                Offset(center.x + innerRadius * kotlin.math.cos(angle).toFloat(), center.y + innerRadius * kotlin.math.sin(angle).toFloat()),
                Offset(center.x + outerRadius * kotlin.math.cos(angle).toFloat(), center.y + outerRadius * kotlin.math.sin(angle).toFloat()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Square
            )
        }
        drawCircle(AppAccent, radius = 2.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()))
    }
}

@Composable
private fun BackGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val middleY = size.height / 2f
        val left = size.width * .25f
        val right = size.width * .75f
        drawLine(AppAccent, Offset(right, middleY), Offset(left, middleY), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Square)
        drawLine(AppAccent, Offset(left, middleY), Offset(size.width * .5f, size.height * .25f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Square)
        drawLine(AppAccent, Offset(left, middleY), Offset(size.width * .5f, size.height * .75f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Square)
    }
}

@Composable
private fun BottomNavigationBar(selected: Page, onSelect: (Page) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            Page.entries.forEach { page ->
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { onSelect(page) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (page == selected) AppAccent else androidx.compose.ui.graphics.Color.Transparent))
                    Spacer(Modifier.height(5.dp))
                    NavGlyph(page, page == selected)
                    Spacer(Modifier.height(2.dp))
                    Text(page.label, color = if (page == selected) AppAccent else MaterialTheme.colorScheme.onSurface,
                        fontFamily = mono, fontSize = 9.sp, fontWeight = if (page == selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun PaperTexture(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val ink = AppBorder.copy(alpha = .065f)
        repeat(310) { index ->
            val x = ((index * 7919L % 10007L).toFloat() / 10007f) * size.width
            val y = ((index * 3571L % 10009L).toFloat() / 10009f) * size.height
            drawCircle(ink, radius = if (index % 7 == 0) 1.15f else .55f, center = Offset(x, y))
        }
        val step = 9.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(AppBorder.copy(alpha = .2f), Offset(0f, y), Offset(if ((y / step).toInt() % 5 == 0) 5.dp.toPx() else 2.dp.toPx(), y), .65f)
            drawLine(AppBorder.copy(alpha = .2f), Offset(size.width, y), Offset(size.width - if ((y / step).toInt() % 5 == 0) 5.dp.toPx() else 2.dp.toPx(), y), .65f)
            y += step
        }
    }
}

@Composable
private fun DottedRule(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        var x = 0f
        val pitch = 4.dp.toPx()
        while (x < size.width) {
            drawCircle(AppBorder.copy(alpha = .72f), radius = .55.dp.toPx(), center = Offset(x, size.height / 2f))
            x += pitch
        }
    }
}

@Composable
private fun NavGlyph(page: Page, selected: Boolean) {
    val color = if (selected) AppAccentDeep else AppMuted
    Canvas(Modifier.size(21.dp)) {
        val stroke = 1.8.dp.toPx()
        when (page) {
            Page.BENCHMARK -> {
                drawCircle(color, size.minDimension * .39f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(color, Offset(size.width * .5f, size.height * .5f), Offset(size.width * .76f, size.height * .26f), stroke, cap = StrokeCap.Round)
                drawCircle(color, size.minDimension * .06f)
            }
            Page.RESULTS -> {
                drawRect(color, Offset(size.width * .2f, size.height * .1f), androidx.compose.ui.geometry.Size(size.width * .6f, size.height * .8f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                for (line in 0..2) {
                    val y = size.height * (.34f + line * .18f)
                    drawLine(color, Offset(size.width * .32f, y), Offset(size.width * .68f, y), stroke)
                }
            }
            Page.DEVICE -> {
                drawRoundRect(color, Offset(size.width * .22f, size.height * .06f), androidx.compose.ui.geometry.Size(size.width * .56f, size.height * .88f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .08f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawCircle(color, size.minDimension * .045f, Offset(size.width * .5f, size.height * .82f))
            }
            Page.COMPARE -> {
                drawLine(color, Offset(size.width * .16f, size.height * .34f), Offset(size.width * .82f, size.height * .34f), stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * .16f, size.height * .68f), Offset(size.width * .82f, size.height * .68f), stroke, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * .66f, size.height * .18f), Offset(size.width * .82f, size.height * .34f), stroke)
                drawLine(color, Offset(size.width * .66f, size.height * .5f), Offset(size.width * .82f, size.height * .34f), stroke)
                drawLine(color, Offset(size.width * .34f, size.height * .52f), Offset(size.width * .18f, size.height * .68f), stroke)
                drawLine(color, Offset(size.width * .34f, size.height * .84f), Offset(size.width * .18f, size.height * .68f), stroke)
            }
        }
    }
}

@Composable
private fun BenchmarkHome(
    vm: BenchmarkViewModel,
    records: List<BenchmarkRecord>,
    onStart: () -> Unit,
    onViewDetails: (BenchmarkRecord) -> Unit
) {
    val snap by vm.telemetry.state.collectAsState()
    val coreMode by vm.coreMode.collectAsState()
    val state by vm.runState.collectAsState()
    val progress by vm.progress.collectAsState()
    val result by vm.result.collectAsState()
    val error by vm.lastError.collectAsState()
    val fahrenheit by vm.useFahrenheit.collectAsState()
    val running = state == RunState.RUNNING
    val latestSavedRecord = records.firstOrNull { !it.partial }
    var runStartedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    val temps = remember { mutableStateListOf<Float>() }
    val freqs = remember { mutableStateListOf<Float>() }
    val loads = remember { mutableStateListOf<Float>() }
    LaunchedEffect(snap.cpuTempC, snap.freqsMhz, snap.cpuBusyPct) {
        snap.cpuTempC?.let { temps.add(it); if (temps.size > 36) temps.removeAt(0) }
        snap.freqsMhz.filter { it > 0 }.averageOrNullFloat()?.let { freqs.add(it); if (freqs.size > 36) freqs.removeAt(0) }
        snap.cpuBusyPct.filter { it >= 0 }.averageOrNullFloat()?.let { loads.add(it); if (loads.size > 36) loads.removeAt(0) }
    }
    LaunchedEffect(running) {
        if (running) {
            runStartedAt = SystemClock.elapsedRealtime() - (progress.elapsedSeconds * 1000.0).toLong()
            while (true) {
                elapsed = (SystemClock.elapsedRealtime() - runStartedAt) / 1000.0
                delay(500)
            }
        }
    }
    LaunchedEffect(progress.elapsedSeconds, running) {
        if (!running) elapsed = progress.elapsedSeconds
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 3.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModeChoice("SINGLE CORE", coreMode == CoreMode.SINGLE, Modifier.weight(1f), enabled = !running) { vm.setCoreMode(CoreMode.SINGLE) }
            ModeChoice("ALL CORES", coreMode == CoreMode.MULTI, Modifier.weight(1f), enabled = !running) { vm.setCoreMode(CoreMode.MULTI) }
        }

        Row(Modifier.fillMaxWidth().weight(.78f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            SectionCard(
                title = "DEVICE INFORMATION",
                modifier = Modifier.weight(1.1f).fillMaxHeight(),
                fillContentHeight = true,
                contentSpacing = 4.dp
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipGlyph(Modifier.width(30.dp).height(38.dp), AppBorder)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }} ${Build.MODEL.orEmpty()}", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER.orEmpty()} ${Build.SOC_MODEL.orEmpty()}".trim() else Build.HARDWARE.orEmpty().ifBlank { "Android CPU" },
                            color = AppMuted, fontFamily = mono, fontSize = 7.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Hairline()
                CompactDeviceRow("CPU CORES", snap.coreCount.toString())
                CompactDeviceRow("ARCH", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown")
                CompactDeviceRow("ANDROID", "v${Build.VERSION.RELEASE}")
            }
            SectionCard(
                title = "LIVE STATUS",
                modifier = Modifier.weight(.9f).fillMaxHeight(),
                fillContentHeight = true,
                contentSpacing = 3.dp
            ) {
                LiveValue("CPU TEMP", snap.cpuTempC?.let { temperatureText(it, fahrenheit) } ?: "—", temps, AppOrange)
                Hairline()
                LiveValue("CPU CLOCK", snap.freqsMhz.filter { it > 0 }.averageOrNullFloat()?.let { "${it.toInt()} MHz" } ?: "—", freqs, AppAccent)
                Hairline()
                LiveValue("CPU USAGE", snap.cpuBusyPct.filter { it >= 0 }.averageOrNullFloat()?.let { "${it.toInt()}%" } ?: "—", loads, AppText)
            }
        }

        SectionCard(
            title = "CPU CORES",
            modifier = Modifier.fillMaxWidth().weight(.82f),
            fillContentHeight = true,
            contentSpacing = 3.dp
        ) {
            if (snap.cpuIds.isEmpty()) {
                Text("Waiting for per-core clock data", color = AppMuted, fontFamily = mono, fontSize = 7.sp)
            } else {
                Text("CLOCK MHz · BAR = CORE LOAD", color = AppMuted, fontFamily = mono, fontSize = 6.sp, maxLines = 1)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 66.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 82.dp),
                    contentPadding = PaddingValues(bottom = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(snap.cpuIds.size) { index ->
                        CoreClockTile(
                            id = snap.cpuIds[index],
                            currentMhz = snap.freqsMhz.getOrNull(index)?.takeIf { it > 0 },
                            maxMhz = snap.maxFreqsMhz.getOrNull(index)?.takeIf { it > 0 },
                            loadPct = snap.cpuBusyPct.getOrNull(index)?.takeIf { it >= 0 }
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().weight(1.42f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            SectionCard(
                title = if (running) "RUNNING BENCHMARK" else "CPU BENCHMARK",
                modifier = Modifier.weight(1.35f).fillMaxHeight(),
                fillContentHeight = true,
                contentSpacing = 6.dp
            ) {
                if (running) {
                    Text("TEST ${minOf(progress.completedTests + 1, progress.totalTests)} / ${progress.totalTests}", color = AppMuted, fontFamily = mono, fontSize = 7.sp)
                    Text(progress.currentTest.uppercase(), color = MaterialTheme.colorScheme.onSurface, fontFamily = pixel, fontSize = 6.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FlatProgressBar(
                        progress = ((progress.completedTests + progress.testProgress.coerceIn(0f, 1f)) / progress.totalTests.coerceAtLeast(1)).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                        height = 6.dp
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${formatElapsed(elapsed)} elapsed", color = AppMuted, fontFamily = mono, fontSize = 7.sp)
                        Text("${(progress.testProgress * 100).toInt()}%", color = AppOrange, fontFamily = mono, fontSize = 7.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth().height(48.dp), contentPadding = PaddingValues(4.dp)) {
                        Text("■  STOP", fontFamily = mono, fontWeight = FontWeight.Bold, color = AppOrange, fontSize = 10.sp)
                    }
                } else {
                    Text("FULL CPU SUITE", color = MaterialTheme.colorScheme.onSurface, fontFamily = pixel, fontSize = 6.sp)
                    Text("INT · FP · SIMD · MEMORY\nCRYPTO · COMPRESSION · AI",
                        color = AppMuted, fontFamily = mono, fontSize = 8.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when {
                        state == RunState.ERROR && error != null -> Text(error!!, color = AppError, fontFamily = mono, fontSize = 7.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        state == RunState.CANCELED -> Text("Run stopped. Incomplete results are not saved.", color = AppMuted, fontFamily = mono, fontSize = 7.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        else -> Text("Warm-up excluded · timed suite\n60-second sustained workload", color = AppMuted, fontFamily = mono, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppOrange, contentColor = androidx.compose.ui.graphics.Color.White),
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text("START CPU BENCHMARK", fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
            SectionCard(
                title = "LATEST SCORE",
                modifier = Modifier.weight(.9f).fillMaxHeight(),
                fillContentHeight = true,
                contentSpacing = 5.dp
            ) {
                val completedRecord = remember(result, state) {
                    result?.takeIf { state == RunState.COMPLETE }?.let { completed ->
                        val completedConfig = SuiteRunConfig(
                            preset = completed.preset,
                            singleCore = completed.singleCore,
                            sustainedDurationMinutes = 1
                        )
                        BenchmarkRecord(
                            timestamp = System.currentTimeMillis(),
                            mode = if (completed.singleCore) "SINGLE_CORE" else "MULTI_CORE",
                            durationSec = completed.elapsedSeconds,
                            peakTempC = completed.peakTemperatureC?.toFloat() ?: 0f,
                            finalScore = completed.score,
                            suitePreset = completed.preset.name,
                            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                            scoreVersion = CpuSuiteScore.profileId(completedConfig),
                            rawMetricsJson = completed.rawJson
                        )
                    }
                }
                val detailRecord = completedRecord ?: latestSavedRecord
                val matchingSavedRecords = remember(detailRecord, records) {
                    detailRecord?.let { latest ->
                        records.filter { run ->
                            !run.partial && run.mode == latest.mode &&
                                run.scoreVersion == latest.scoreVersion &&
                                run.deviceName.equals(latest.deviceName, ignoreCase = true)
                        }
                    }.orEmpty()
                }
                val latestIsSaved = detailRecord?.id?.let { id ->
                    id != 0L && matchingSavedRecords.any { it.id == id }
                } == true
                val historicalScores = matchingSavedRecords
                    .filterNot { latestIsSaved && it.id == detailRecord.id }
                    .map { it.finalScore }
                val comparableScores = if (detailRecord == null) emptyList() else
                    matchingSavedRecords.map { it.finalScore } + if (latestIsSaved) emptyList() else listOf(detailRecord.finalScore)
                Text(detailRecord?.finalScore?.toString() ?: "--", color = MaterialTheme.colorScheme.onSurface, fontFamily = pixel, fontSize = 13.sp, maxLines = 1, softWrap = false)
                Text(
                    detailRecord?.let { "${if (it.mode == "SINGLE_CORE") "SINGLE CORE" else "ALL CORES"} RESULT · ${formatElapsed(it.durationSec)}" } ?: "NO SAVED RUN",
                    color = AppAccent,
                    fontFamily = mono,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                if (detailRecord != null) {
                    if (historicalScores.isNotEmpty()) {
                        val referenceAverage = historicalScores.average()
                        val change = ((detailRecord.finalScore - referenceAverage) / referenceAverage.coerceAtLeast(1.0) * 100.0).roundToInt()
                        Text(
                            "VS THIS PHONE AVG  ${if (change > 0) "+" else ""}$change% · ${historicalScores.size} ${if (historicalScores.size == 1) "RUN" else "RUNS"}",
                            color = AppMuted,
                            fontFamily = mono,
                            fontSize = 6.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        ScoreComparisonBar(detailRecord.finalScore, referenceAverage)
                    } else {
                        Text(
                            "FIRST RUN · RUN AGAIN TO COMPARE",
                            color = AppMuted,
                            fontFamily = mono,
                            fontSize = 6.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (comparableScores.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        ScoreRangeRow("Min", comparableScores.minOrNull())
                        ScoreRangeRow("Avg", comparableScores.average().roundToInt())
                        ScoreRangeRow("Max", comparableScores.maxOrNull())
                    }
                }
                if (detailRecord != null) {
                    OutlinedButton(
                        onClick = { onViewDetails(detailRecord) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(2.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 1.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppAccent)
                    ) {
                        Text("VIEW DETAILS  →", fontFamily = mono, fontSize = 8.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipGlyph(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = AppBorder) {
    Canvas(modifier) {
        val left = size.width * .2f
        val top = size.height * .22f
        val body = androidx.compose.ui.geometry.Size(size.width * .6f, size.height * .56f)
        drawRect(color, Offset(left, top), body, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        drawRect(color, Offset(left + body.width * .2f, top + body.height * .2f),
            androidx.compose.ui.geometry.Size(body.width * .6f, body.height * .6f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        repeat(4) { i ->
            val x = left + body.width * (i + .5f) / 4f
            drawLine(color, Offset(x, top - 4.dp.toPx()), Offset(x, top), 1.dp.toPx())
            drawLine(color, Offset(x, top + body.height), Offset(x, top + body.height + 4.dp.toPx()), 1.dp.toPx())
        }
        repeat(3) { i ->
            val y = top + body.height * (i + .5f) / 3f
            drawLine(color, Offset(left - 4.dp.toPx(), y), Offset(left, y), 1.dp.toPx())
            drawLine(color, Offset(left + body.width, y), Offset(left + body.width + 4.dp.toPx(), y), 1.dp.toPx())
        }
    }
}

@Composable
private fun CompactDeviceRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppMuted, fontFamily = mono, fontSize = 6.sp, maxLines = 1, softWrap = false)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 7.sp,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LiveValue(label: String, value: String, points: List<Float>, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = AppMuted, fontFamily = mono, fontSize = 6.sp, maxLines = 1)
            Text(value, color = if (label == "CPU TEMP") AppOrange else MaterialTheme.colorScheme.onSurface,
                fontFamily = mono, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        val graphScale = when (label) {
            "CPU TEMP", "CPU USAGE" -> 100f
            "CPU CLOCK" -> 3_000f
            else -> null
        }
        MiniGraph("", points, Modifier.width(36.dp), color, graphScale)
    }
}

@Composable
private fun CoreClockTile(id: Int, currentMhz: Int?, maxMhz: Int?, loadPct: Int?) {
    val loadRatio = loadPct?.coerceIn(0, 100)?.div(100f) ?: 0f
    Column(
        Modifier.fillMaxWidth().height(34.dp).border(1.dp, AppBorder).padding(horizontal = 4.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("CORE $id", color = AppMuted, fontFamily = mono, fontSize = 6.sp, maxLines = 1, softWrap = false)
            Text(
                if (currentMhz == null) "—" else "${currentMhz}/${maxMhz ?: "—"}",
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = mono,
                fontSize = 6.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
        FlatProgressBar(loadRatio, Modifier.fillMaxWidth(), height = 3.dp, color = if (loadPct != null) AppAccent else AppBorder)
    }
}

@Composable
private fun CoreTable(snap: TelemetrySnapshot) {
    Column(Modifier.horizontalScroll(rememberScrollState()).width(312.dp)) {
        Row(Modifier.fillMaxWidth().background(AppSurface).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            CoreCell("#", 18.dp, true); CoreCell("TYPE", 39.dp, true); CoreCell("CURRENT", 60.dp, true)
            CoreCell("MAX", 56.dp, true); CoreCell("TEMP", 37.dp, true); CoreCell("USAGE", 102.dp, true)
        }
        snap.cpuIds.forEachIndexed { i, id ->
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                CoreCell(id.toString(), 18.dp); CoreCell("CPU", 39.dp)
                CoreCell(snap.freqsMhz.getOrNull(i)?.takeIf { it > 0 }?.let { "${it} MHz" } ?: "—", 60.dp)
                CoreCell(snap.maxFreqsMhz.getOrNull(i)?.takeIf { it > 0 }?.let { "${it} MHz" } ?: "—", 56.dp)
                CoreCell("—", 37.dp, color = AppMuted)
                Row(Modifier.width(102.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    val usage = snap.cpuBusyPct.getOrNull(i)?.takeIf { it >= 0 }
                    FlatProgressBar(usage?.div(100f) ?: 0f, Modifier.width(67.dp), 7.dp)
                    Text(usage?.let { "${it}%" } ?: "—", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 8.sp)
                }
            }
            if (i < snap.cpuIds.lastIndex) Hairline()
        }
    }
}

@Composable
private fun CoreCell(value: String, width: androidx.compose.ui.unit.Dp, header: Boolean = false, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Text(value, Modifier.width(width).padding(horizontal = 3.dp), color = if (header) AppMuted else color, fontFamily = mono,
        fontSize = if (header) 7.sp else 8.sp, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
}

@Composable
private fun FlatProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 8.dp,
    color: androidx.compose.ui.graphics.Color = AppOrange
) {
    Box(modifier.height(height).background(AppSurface)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
private fun ScoreComparisonBar(score: Int, referenceAverage: Double) {
    val difference = (score - referenceAverage) / referenceAverage.coerceAtLeast(1.0)
    val marker = (.5 + (difference / .5).coerceIn(-.5, .5)).toFloat().coerceIn(0f, 1f)
    val indicator = if (difference >= 0) AppFocus else AppOrange
    Canvas(Modifier.fillMaxWidth().height(7.dp)) {
        val centerY = size.height / 2f
        val centerX = size.width / 2f
        drawLine(AppBorder.copy(alpha = .45f), Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 2.dp.toPx())
        drawLine(AppBorder, Offset(centerX, 0f), Offset(centerX, size.height), strokeWidth = 1.dp.toPx())
        drawLine(indicator, Offset(centerX, centerY), Offset(size.width * marker, centerY), strokeWidth = 3.dp.toPx())
        drawCircle(indicator, radius = 3.dp.toPx(), center = Offset(size.width * marker, centerY))
    }
}

@Composable
private fun ScoreRangeRow(label: String, value: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppMuted, fontFamily = mono, fontSize = 8.sp)
        Text(value?.toString() ?: "—", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 8.sp)
    }
}

@Composable
private fun DeviceScreen(vm: BenchmarkViewModel) {
    val snap by vm.telemetry.state.collectAsState()
    val fahrenheit by vm.useFahrenheit.collectAsState()
    val maxValues = snap.maxFreqsMhz
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard(title = "DEVICE") {
                DeviceValue("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                DeviceValue("SoC", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else "Not exposed by this Android version")
                DeviceValue("CPU cores", "${snap.coreCount} logical")
                DeviceValue("Architecture", Build.SUPPORTED_ABIS.joinToString(", "))
                DeviceValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                DeviceValue("Runtime", "${System.getProperty("java.vm.name") ?: "Android Runtime"}")
            }
        }
        item {
            SectionCard(title = "CORE TELEMETRY") {
                DeviceValue("CPU / SoC sensor", snap.cpuTempC?.let { temperatureText(it, fahrenheit) } ?: "Unavailable")
                DeviceValue("Battery sensor", snap.batteryTempC?.let { temperatureText(it, fahrenheit) } ?: "Unavailable")
                Text("Thermal zones may be hidden by the device vendor. CPU temperature is shown only when an identified CPU / SoC sensor is readable.", color = AppMuted, fontFamily = mono, fontSize = 9.sp, lineHeight = 13.sp)
                Spacer(Modifier.height(3.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CORE", color = AppMuted, fontFamily = mono, fontSize = 9.sp, modifier = Modifier.weight(0.8f))
                    Text("CURRENT", color = AppMuted, fontFamily = mono, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    Text("MAX", color = AppMuted, fontFamily = mono, fontSize = 9.sp, modifier = Modifier.weight(0.8f))
                    Text("LOAD", color = AppMuted, fontFamily = mono, fontSize = 9.sp, modifier = Modifier.weight(0.7f))
                }
                snap.cpuIds.forEachIndexed { index, id ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("CPU $id", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, modifier = Modifier.weight(0.8f))
                        Text(snap.freqsMhz.getOrNull(index)?.takeIf { it > 0 }?.let { "$it MHz" } ?: "—", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        Text(maxValues.getOrNull(index)?.takeIf { it > 0 }?.let { "$it MHz" } ?: "—", color = AppMuted, fontFamily = mono, fontSize = 10.sp, modifier = Modifier.weight(0.8f))
                        Text(snap.cpuBusyPct.getOrNull(index)?.takeIf { it >= 0 }?.let { "$it%" } ?: "—", color = AppMuted, fontFamily = mono, fontSize = 10.sp, modifier = Modifier.weight(0.7f))
                    }
                }
            }
        }
        item {
            SectionCard(title = "HARDWARE COUNTERS") {
                Text("Cycles, retired instructions, IPC, cache hits/misses, CPU power, and per-core temperature are unavailable to ordinary apps on many retail Android builds. These fields stay blank in raw results rather than being estimated.", color = AppMuted, fontFamily = mono, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun ResultsScreen(records: List<BenchmarkRecord>, onSelect: (BenchmarkRecord) -> Unit) {
    var filter by remember { mutableStateOf("ALL") }
    val visible = records.filter { filter == "ALL" || it.mode == filter }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ALL", "SINGLE_CORE", "MULTI_CORE").forEach { key ->
                SmallChoice(key.replace("_CORE", ""), filter == key, Modifier.weight(1f)) { filter = key }
            }
        }
        if (visible.isEmpty()) {
            EmptyState("No results yet", "Completed runs appear here with their raw test measurements.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
                items(visible, key = { it.id }) { record ->
                    ResultCard(record, Modifier.clickable { onSelect(record) })
                }
            }
        }
    }
}

@Composable
private fun ResultDetailScreen(record: BenchmarkRecord) {
    val raw = remember(record.id) { runCatching { JSONObject(record.rawMetricsJson) }.getOrNull() }
    val categoryScores = remember(raw) { raw?.optJSONObject("categoryScores") }
    val measurements = remember(raw) { raw?.optJSONArray("measurements") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("${record.finalScore}", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                        Text("${record.mode.replace('_', ' ')} SCORE", color = AppMuted, fontFamily = mono, fontSize = 9.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(record.suitePreset, color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("${"%.1f".format(record.durationSec)} s", color = AppMuted, fontFamily = mono, fontSize = 10.sp)
                        Text(SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(record.timestamp)), color = AppMuted, fontFamily = mono, fontSize = 9.sp)
                    }
                }
                Text(record.deviceName.ifBlank { "This device" }, color = AppMuted, fontFamily = mono, fontSize = 10.sp)
            }
        }
        item {
            SectionCard(title = "CATEGORY SCORES") {
                if (categoryScores == null || categoryScores.length() == 0) Text("Detailed metrics aren't available for this legacy run.", color = AppMuted, fontFamily = mono, fontSize = 10.sp)
                else categoryScores.keys().asSequence().forEach { key -> DeviceValue(key, categoryScores.optInt(key).toString()) }
            }
        }
        item {
            SectionCard(title = "TEST MEASUREMENTS") {
                if (measurements == null || measurements.length() == 0) Text("No raw measurements stored for this run.", color = AppMuted, fontFamily = mono, fontSize = 10.sp)
                else for (index in 0 until measurements.length()) {
                    val item = measurements.optJSONObject(index) ?: continue
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(item.optString("name"), color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("${formatMetric(item.optDouble("value"))} ${item.optString("unit")}  ·  ${"%.2f".format(item.optLong("elapsedNs") / 1e6)} ms", color = AppMuted, fontFamily = mono, fontSize = 9.sp)
                        Text("ops ${item.optLong("operations")}  ·  ${item.optInt("threadCount")} threads  ·  ${item.optLong("bytesProcessed")} B", color = AppMuted, fontFamily = mono, fontSize = 9.sp)
                        val latency = listOfNotNull(
                            item.optLong("latencyP50Ns").takeIf { it > 0 }?.let { "P50 " + formatMetric(it.toDouble()) + " ns" },
                            item.optLong("latencyP90Ns").takeIf { it > 0 }?.let { "P90 " + formatMetric(it.toDouble()) + " ns" }
                        ).joinToString(" · ")
                        if (latency.isNotBlank()) Text(latency, color = AppMuted, fontFamily = mono, fontSize = 9.sp)
                        val temperature = item.opt("temperatureC")?.takeIf { it !is org.json.JSONObject && it != JSONObject.NULL }
                        val frequency = item.opt("frequencyMhz")?.takeIf { it !is org.json.JSONObject && it != JSONObject.NULL }
                        Text("${if (frequency == null) "freq —" else "freq ${formatMetric(frequency.toString().toDoubleOrNull() ?: 0.0)} MHz"}  ·  ${if (temperature == null) "temp —" else "temp ${formatMetric(temperature.toString().toDoubleOrNull() ?: 0.0)} °C"}", color = AppMuted, fontFamily = mono, fontSize = 9.sp)
                        if (item.optString("detail").isNotBlank()) Text(item.optString("detail"), color = AppMuted, fontFamily = mono, fontSize = 9.sp)
                    }
                    if (index < measurements.length() - 1) Hairline()
                }
            }
        }
        item {
            SectionCard(title = "SCORING & RAW DATA") {
                Text("${record.scoreVersion}: each category rate is compared with its fixed reference rate on a 1–100× log scale, then category scores use a geometric mean. Exact reference rates and raw timings are stored for recalculation.", color = AppMuted, fontFamily = mono, fontSize = 9.sp, lineHeight = 13.sp)
                Text("Cycle, instruction, IPC, cache-counter, and power fields are null when the device doesn't expose them.", color = AppMuted, fontFamily = mono, fontSize = 9.sp, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
private fun CompareScreen(records: List<BenchmarkRecord>) {
    var filter by remember { mutableStateOf("CPU") }
    val thisDevice = "${Build.MANUFACTURER} ${Build.MODEL}"
    val anchor = records.firstOrNull { !it.partial && it.deviceName.equals(thisDevice, ignoreCase = true) }
    val comparable = if (anchor == null) emptyList() else records.filter {
        !it.partial && it.deviceName.equals(thisDevice, ignoreCase = true) &&
            it.scoreVersion == anchor.scoreVersion && it.mode == anchor.mode
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("CPU", "GPU", "MY DEVICE").forEach { key -> SmallChoice(key, filter == key, Modifier.weight(1f)) { filter = key } }
        }
        when (filter) {
            "CPU" -> {
                Text(
                    anchor?.let { "Local ${it.suitePreset.lowercase()} · ${it.mode.replace('_', ' ').lowercase()} · same profile" }
                        ?: "Local benchmark runs",
                    color = AppMuted, fontFamily = mono, fontSize = 9.sp, modifier = Modifier.padding(vertical = 5.dp)
                )
                val ranked = comparable.sortedByDescending { it.finalScore }
                if (ranked.isEmpty()) EmptyState("No CPU runs to compare", "Run a benchmark and your score will be ranked against your own history.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ranked, key = { it.id }) { record -> ResultCard(record, rank = ranked.indexOf(record) + 1) }
                }
            }
            "GPU" -> EmptyState("GPU tests aren't included", "This redesign focuses on CPU performance. GPU scores are not inferred from CPU results.")
            else -> {
                val latest = comparable.firstOrNull()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionCard(title = "MY DEVICE") {
                        DeviceValue("Device", thisDevice)
                        DeviceValue("Latest CPU score", latest?.finalScore?.toString() ?: "No runs yet")
                        DeviceValue("Completed runs", records.size.toString())
                        latest?.let { DeviceValue("Best ${it.suitePreset.lowercase()} score", comparable.maxOf { row -> row.finalScore }.toString()) }
                    }
                    Text("Scores are stored locally. Online device rankings are not connected in this build.", color = AppMuted, fontFamily = mono, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: BenchmarkViewModel, resultCount: Int, onExport: () -> Unit, onPrivacy: () -> Unit) {
    val fahrenheit by vm.useFahrenheit.collectAsState()
    val dark by vm.darkTheme.collectAsState()
    val runState by vm.runState.collectAsState()
    val benchmarkRunning = runState == RunState.RUNNING
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard(title = "APPEARANCE") {
            SettingRow("Dark theme", "Use a low-light color palette", dark) { vm.setDarkTheme(it) }
            Hairline()
            SettingRow("Temperature in Fahrenheit", "Default is Celsius", fahrenheit) { vm.setFahrenheit(it) }
        }
        SectionCard(title = "RESULTS & STORAGE") {
            DeviceValue("Saved runs", resultCount.toString())
            Text("Raw per-test timings are kept in the local benchmark database.", color = AppMuted, fontFamily = mono, fontSize = 9.sp)
            if (benchmarkRunning) {
                Text("History can be cleared after the active run finishes.", color = AppMuted, fontFamily = mono, fontSize = 9.sp)
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("EXPORT RAW RESULTS", fontFamily = mono, fontSize = 10.sp) }
            OutlinedButton(onClick = { confirmClear = true }, enabled = resultCount > 0 && !benchmarkRunning, modifier = Modifier.fillMaxWidth()) {
                Text("CLEAR RESULT HISTORY", fontFamily = mono, fontSize = 10.sp, color = AppError)
            }
        }
        SectionCard(title = "ABOUT") {
            DeviceValue("App", "BE Benchmark · CPU")
            DeviceValue("Score version", "CPU Suite ${CpuSuiteScore.VERSION.substringAfterLast('-')}")
            Text("This benchmark uses finite, deterministic workloads. Android may schedule other work during a run; core pinning is best effort.", color = AppMuted, fontFamily = mono, fontSize = 9.sp, lineHeight = 13.sp)
            OutlinedButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) {
                Text("PRIVACY POLICY", fontFamily = mono, fontSize = 10.sp)
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all results?", fontFamily = mono, fontWeight = FontWeight.Bold) },
        text = { Text("This permanently removes $resultCount locally saved benchmark runs.", fontFamily = mono, fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = { vm.deleteAllHistory(); confirmClear = false }) { Text("CLEAR", color = AppError, fontFamily = mono) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("CANCEL", fontFamily = mono) } }
    )
}

@Composable
private fun PrivacyPolicyScreen(onContact: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard(title = "BE BENCHMARK PRIVACY") {
            Text("Last updated: October 1, 2026", color = AppMuted, fontFamily = mono, fontSize = 9.sp)
            Text("BE Benchmark is an offline CPU benchmark published by Amandeep. It does not require an account and does not send benchmark results to a BE Benchmark server.", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, lineHeight = 15.sp)
        }
        SectionCard(title = "DATA STORED ON THIS DEVICE") {
            Text("The app stores completed scores, test mode and suite version, run time and duration, device model, and available performance measurements such as CPU frequency and temperature. Live CPU load is read to draw the on-screen core graphs. These results stay in the app's private database unless you export them.", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, lineHeight = 15.sp)
        }
        SectionCard(title = "AI TESTS AND SHARING") {
            Text("AI inference uses model files bundled with the app and runs on the device CPU. The app does not upload input data or inference results. BE Benchmark has no ads, account service, analytics, or developer-operated result sharing.", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, lineHeight = 15.sp)
        }
        SectionCard(title = "BACKUP, EXPORT AND DELETION") {
            Text("Android device backup may include app data according to your system backup settings. You can export a JSON file through Android's document picker; the file is saved only to the destination you choose, which may be a cloud provider. Use Clear Result History in Settings to remove saved runs from the app. Exported files and existing system backups are managed separately.", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, lineHeight = 15.sp)
        }
        SectionCard(title = "CONTACT") {
            Text("For privacy questions, contact Amandeep at help@lapetlo.com.", color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, lineHeight = 15.sp)
            OutlinedButton(onClick = onContact, modifier = Modifier.fillMaxWidth()) {
                Text("EMAIL HELP@LAPETLO.COM", fontFamily = mono, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    fillContentHeight: Boolean = false,
    contentSpacing: androidx.compose.ui.unit.Dp = 7.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().then(if (fillContentHeight) Modifier.fillMaxHeight() else Modifier).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(contentSpacing)
        ) {
            if (title != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        title.uppercase(),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = pixel,
                        fontSize = 6.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                    DottedRule(Modifier.weight(1f))
                    Text(
                        sectionNumber(title),
                        color = AppMuted,
                        fontFamily = mono,
                        fontSize = 8.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            content()
        }
    }
}

private fun sectionNumber(title: String): String = when (title.uppercase()) {
    "DEVICE INFORMATION" -> "01"
    "LIVE STATUS", "LIVE TELEMETRY" -> "02"
    "CPU CORES", "CORE TELEMETRY" -> "03"
    "RUNNING BENCHMARK", "CPU BENCHMARK", "TEST RUN" -> "04"
    "CURRENT SCORE", "LATEST SCORE", "LATEST RESULT", "CATEGORY SCORES" -> "05"
    else -> "··"
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)).padding(7.dp)) {
        Text(label, color = AppMuted, fontFamily = mono, fontSize = 8.sp, maxLines = 1)
        Text(value, color = valueColor, fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun MiniGraph(label: String, values: List<Float>, modifier: Modifier, color: androidx.compose.ui.graphics.Color, scaleMax: Float? = null) {
    Column(modifier) {
        if (label.isNotBlank()) Text(label.uppercase(), color = AppMuted, fontFamily = mono, fontSize = 8.sp)
        Canvas(Modifier.fillMaxWidth().height(28.dp).padding(top = 3.dp)) {
            val centerY = size.height / 2f
            drawLine(AppBorder, Offset(0f, centerY), Offset(size.width, centerY), 1f)
            if (values.isNotEmpty()) {
                val minVal = if (scaleMax == null) values.minOrNull() ?: 0f else 0f
                val maxVal = scaleMax ?: values.maxOrNull() ?: 1f
                val range = (maxVal - minVal).coerceAtLeast(1f)
                fun yFor(value: Float): Float {
                    val normalized = ((value - minVal) / range).coerceIn(0f, 1f)
                    return size.height - (normalized * (size.height * .76f)) - size.height * .12f
                }
                if (values.size == 1) {
                    drawCircle(color, 2.dp.toPx(), Offset(size.width, yFor(values.single())))
                } else {
                    val path = Path()
                    values.forEachIndexed { index, value ->
                        val x = size.width * index / (values.size - 1)
                        if (index == 0) path.moveTo(x, yFor(value)) else path.lineTo(x, yFor(value))
                    }
                    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
    }
}

@Composable
private fun ModeChoice(label: String, selected: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(40.dp)
            .border(1.dp, if (selected) AppAccentDeep else AppBorder, RoundedCornerShape(2.dp))
            .background(if (selected) AppAccent else MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChipGlyph(Modifier.width(19.dp).height(24.dp), if (selected) androidx.compose.ui.graphics.Color.White else AppBorder)
            Text(label, color = if (selected) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onBackground,
                fontFamily = mono, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PresetChoice(label: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (selected) AppAccent.copy(alpha = .16f) else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (selected) AppAccent else MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = AppMuted, fontFamily = mono, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmallChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(5.dp))
            .background(if (selected) AppAccent.copy(alpha = .16f) else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (selected) AppAccent else MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 9.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip) }
}

@Composable
private fun ResultCard(record: BenchmarkRecord, modifier: Modifier = Modifier, rank: Int? = null) {
    SectionCard {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rank != null) Text("#$rank", color = AppMuted, fontFamily = mono, fontSize = 10.sp)
            Column(Modifier.weight(1f)) {
                Text(record.deviceName.ifBlank { record.mode.replace('_', ' ') }, color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${record.suitePreset} · ${record.mode.replace('_', ' ')} · ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(record.timestamp))}", color = AppMuted, fontFamily = mono, fontSize = 8.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(record.finalScore.toString(), color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("${"%.1f".format(record.durationSec)}s", color = AppMuted, fontFamily = mono, fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun DeviceValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = AppMuted, fontFamily = mono, fontSize = 9.sp, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 9.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.3f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = AppMuted, fontFamily = mono, fontSize = 8.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = AppMuted, fontFamily = mono, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun InfoBanner(message: String, color: androidx.compose.ui.graphics.Color) {
    Text(message, color = color, fontFamily = mono, fontSize = 10.sp, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(10.dp))
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun Hairline() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}

private fun temperatureText(celsius: Float, fahrenheit: Boolean): String =
    if (fahrenheit) "${"%.1f".format(celsius * 9f / 5f + 32f)} °F" else "${"%.1f".format(celsius)} °C"

@JvmName("averageIntsOrNullFloat")
private fun List<Int>.averageOrNullFloat(): Float? = filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
private fun List<Float>.averageOrNullFloat(): Float? = filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
private fun formatElapsed(seconds: Double): String = if (seconds >= 60) "%d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60) else "%.1f s".format(seconds)
private fun formatMetric(value: Double): String = if (value >= 1000) "%.0f".format(value) else "%.2f".format(value)
