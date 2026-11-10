package com.fplmanagerai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fplmanagerai.data.*
import com.fplmanagerai.data.cache.LocalDataCache
import com.fplmanagerai.presentation.accuracy.PredictionAccuracyScreen
import com.fplmanagerai.presentation.analysis.PlayerAnalysisScreen
import com.fplmanagerai.presentation.chips.ChipStrategyDashboardScreen
import com.fplmanagerai.presentation.dashboard.SmartDashboardScreen
import com.fplmanagerai.presentation.decision.DecisionIntelligenceScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // استخدام filesDir لضمان حفظ لقطات التوقعات وإصدارات النماذج بشكل دائم وعدم مسحها مع ذاكرة الكاش المؤقتة
        LocalDataCache.initialize(filesDir)
        setContent { FplManagerApp() }
    }
}

data class DashboardState(
    val loading: Boolean = true,
    val data: DashboardData? = null,
    val error: String? = null
)

class DashboardViewModel : ViewModel() {
    private val repository = FplRepository()
    var state by mutableStateOf(DashboardState())
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            state = DashboardState(loading = true)
            try {
                state = DashboardState(loading = false, data = repository.loadDashboard())
            } catch (e: Exception) {
                state = DashboardState(
                    loading = false,
                    error = "تعذر تحميل بيانات FPL. تحقق من الإنترنت وحاول مجدداً."
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FplManagerApp(vm: DashboardViewModel = viewModel()) {
    var selectedNavTab by remember { mutableIntStateOf(1) } // Default to Decision Intelligence

    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedNavTab == 0,
                            onClick = { selectedNavTab = 0 },
                            icon = { Icon(Icons.Default.Dashboard, contentDescription = "لوحة التحكم") },
                            label = { Text("لوحة التحكم") }
                        )
                        NavigationBarItem(
                            selected = selectedNavTab == 1,
                            onClick = { selectedNavTab = 1 },
                            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "قرارات الجولة") },
                            label = { Text("القرارات") }
                        )
                        NavigationBarItem(
                            selected = selectedNavTab == 2,
                            onClick = { selectedNavTab = 2 },
                            icon = { Icon(Icons.Default.Analytics, contentDescription = "محرك التحليل") },
                            label = { Text("التحليل") }
                        )
                        NavigationBarItem(
                            selected = selectedNavTab == 3,
                            onClick = { selectedNavTab = 3 },
                            icon = { Icon(Icons.Default.Stars, contentDescription = "استراتيجية الشرائح") },
                            label = { Text("الشرائح") }
                        )
                        NavigationBarItem(
                            selected = selectedNavTab == 4,
                            onClick = { selectedNavTab = 4 },
                            icon = { Icon(Icons.Default.Insights, contentDescription = "دقة التوقعات") },
                            label = { Text("دقة التوقعات") }
                        )
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (selectedNavTab) {
                        0 -> SmartDashboardScreen()
                        1 -> DecisionIntelligenceScreen()
                        2 -> PlayerAnalysisScreen()
                        3 -> ChipStrategyDashboardScreen()
                        4 -> PredictionAccuracyScreen()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardView(vm: DashboardViewModel) {
    val state = vm.state
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FPL Manager AI", fontWeight = FontWeight.Bold)
                        Text("لوحة المعلومات الرئيسية", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = vm::refresh) { Text("تحديث") }
                }
            )
        }
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.error != null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(state.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = vm::refresh) { Text("إعادة المحاولة") }
            }

            state.data != null -> DashboardScreen(state.data!!, Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(data: DashboardData, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("لوحة التحكم", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(data.gameweek, style = MaterialTheme.typography.headlineMedium)
                    Text("موعد الإغلاق: ${data.deadline}")
                    Text("عدد مديري اللعبة: ${data.totalPlayers}")
                }
            }
        }
        item {
            Text("أعلى اللاعبين نقاطاً هذا الموسم", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("بيانات رسمية من واجهة FPL العامة؛ لا تمثل توصية شراء تلقائية.", style = MaterialTheme.typography.bodySmall)
        }
        items(data.topPlayers) { player ->
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(player.name, fontWeight = FontWeight.SemiBold)
                        Text("${player.team} • ${player.position}", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${player.points} نقطة", fontWeight = FontWeight.Bold)
                        Text(player.price, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Text(
                "انتقل إلى تبويب «محرك التحليل الذكي» لتوقع النقاط واقتراح التشكيلة المثالية والكابتن.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
