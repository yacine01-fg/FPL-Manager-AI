package com.fplmanagerai.presentation.accuracy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fplmanagerai.data.model.*
import com.fplmanagerai.presentation.calibration.PredictionCalibrationScreen
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionAccuracyScreen(
    viewModel: PredictionAccuracyViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCalibrationDialog by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "محرك قياس دقة التوقعات",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        "v2.0-engine",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                "التحقق العلمي والمقارنة مع النتائج الرسمية الفعلية",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showCalibrationDialog = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "معايرة وتحسين التوقعات", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(
                            onClick = {
                                val gw = state.selectedGameweek ?: state.availableGameweeks.lastOrNull()
                                if (gw != null) {
                                    viewModel.syncActualResultsForGameweek(gw)
                                } else {
                                    viewModel.loadAccuracyData()
                                }
                            },
                            enabled = !state.isSyncingActuals
                        ) {
                            if (state.isSyncingActuals) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "تحديث النتائج الفعلية")
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when {
                    state.isLoading && state.metrics == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text("جاري تحليل دقة التوقعات ومقارنة النتائج...")
                            }
                        }
                    }

                    state.errorMessage != null && state.metrics == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(state.errorMessage ?: "حدث خطأ", color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { viewModel.loadAccuracyData() }) {
                                    Text("إعادة المحاولة")
                                }
                            }
                        }
                    }

                    else -> {
                        Column(Modifier.fillMaxSize()) {
                            // 1. شريط اختيار الجولة والحالة
                            GameweekSelectorBar(
                                availableGameweeks = state.availableGameweeks,
                                selectedGameweek = state.selectedGameweek,
                                onSelectGameweek = { viewModel.selectGameweek(it) },
                                totalSnapshots = state.totalSnapshotsCount,
                                totalEvaluated = state.totalEvaluatedCount,
                                totalPending = state.totalPendingCount
                            )

                            // شريط رسالة الحالة إن وجد
                            state.statusMessage?.let { msg ->
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(msg, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            // 2. تبويبات التنقل
                            TabRow(
                                selectedTabIndex = state.activeTab.ordinal,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AccuracyScreenTab.values().forEach { tab ->
                                    Tab(
                                        selected = state.activeTab == tab,
                                        onClick = { viewModel.selectTab(tab) },
                                        text = { Text(tab.titleAr, fontWeight = if (state.activeTab == tab) FontWeight.Bold else FontWeight.Normal) }
                                    )
                                }
                            }

                            // 3. المحتوى حسب التبويب المختار
                            val metrics = state.metrics
                            if (metrics != null) {
                                when (state.activeTab) {
                                    AccuracyScreenTab.OVERVIEW -> OverviewTabContent(metrics)
                                    AccuracyScreenTab.CAPTAINCY -> CaptaincyTabContent(metrics, state.comparisonRecords)
                                    AccuracyScreenTab.POSITIONS -> PositionsTabContent(metrics)
                                    AccuracyScreenTab.DETAILS -> DetailsTabContent(
                                        records = state.filteredRecords,
                                        selectedPosition = state.selectedPositionFilter,
                                        selectedSort = state.selectedSortOption,
                                        onSelectPosition = { viewModel.selectPositionFilter(it) },
                                        onSelectSort = { viewModel.selectSortOption(it) }
                                    )
                                    AccuracyScreenTab.CALIBRATION -> PredictionCalibrationScreen()
                                }
                            }
                        }
                    }
                }

                // حوار شاشة كاملة للمعايرة والتحسين الذاتي
                if (showCalibrationDialog) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showCalibrationDialog = false },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            PredictionCalibrationScreen(
                                onNavigateBack = { showCalibrationDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameweekSelectorBar(
    availableGameweeks: List<Int>,
    selectedGameweek: Int?,
    onSelectGameweek: (Int?) -> Unit,
    totalSnapshots: Int,
    totalEvaluated: Int,
    totalPending: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(vertical = 8.dp)
    ) {
        // شريط الجولات
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedGameweek == null,
                onClick = { onSelectGameweek(null) },
                label = { Text("الموسم بالكامل (${availableGameweeks.size} جولات)") },
                leadingIcon = {
                    if (selectedGameweek == null) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            )

            availableGameweeks.forEach { gw ->
                FilterChip(
                    selected = selectedGameweek == gw,
                    onClick = { onSelectGameweek(gw) },
                    label = { Text("الجولة $gw") },
                    leadingIcon = {
                        if (selectedGameweek == gw) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                )
            }
        }

        // إحصائيات اللقطات
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "اللقطات المسجلة: $totalSnapshots",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = Color(0xFF10B981).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "مكتملة ومُقيمة: $totalEvaluated",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.Bold
                    )
                }
                if (totalPending > 0) {
                    Surface(
                        color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "قيد الانتظار: $totalPending",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFF59E0B),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewTabContent(metrics: PredictionAccuracyMetrics) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. بطاقة التفوق العلمي على خط الأساس (AI Value Added)
        AiValueAddedCard(metrics)

        // 2. شبكة المقاييس الأساسية
        Text(
            "المقاييس الإحصائية المعتمدة",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "MAE (متوسط الخطأ)",
                value = "±${metrics.meanAbsoluteError}",
                unit = "نقطة",
                description = "متوسط الفارق المطلق بين التوقع والنتيجة",
                color = if (metrics.meanAbsoluteError <= 2.2) Color(0xFF10B981) else Color(0xFFF59E0B)
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "RMSE (جذر تربيعي)",
                value = "${metrics.rootMeanSquareError}",
                unit = "",
                description = "حساس للانحرافات الكبيرة والمفاجآت",
                color = if (metrics.rootMeanSquareError <= 3.2) Color(0xFF10B981) else Color(0xFFF59E0B)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val biasTitle = when {
                metrics.predictionBias > 0.3 -> "تفاؤلي (+${metrics.predictionBias})"
                metrics.predictionBias < -0.3 -> "تحفظي (${metrics.predictionBias})"
                else -> "متوازن (${metrics.predictionBias})"
            }
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "انحياز التوقع (Bias)",
                value = biasTitle,
                unit = "",
                description = if (metrics.predictionBias > 0) "النموذج يميل لتقدير نقاط أعلى قليلاً" else "النموذج يميل لتقدير نقاط أقل",
                color = if (Math.abs(metrics.predictionBias) <= 0.5) Color(0xFF10B981) else Color(0xFF3B82F6)
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "خطأ الدقائق (Minutes MAE)",
                value = "±${metrics.minutesMeanAbsoluteError}",
                unit = "دقيقة",
                description = "دقة توقع وقت المشاركة والمداورة",
                color = if (metrics.minutesMeanAbsoluteError <= 15.0) Color(0xFF10B981) else Color(0xFFEF4444)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                modifier = Modifier.fillMaxWidth(),
                title = "معايرة احتمالية البدء (Brier Score)",
                value = String.format(Locale.US, "%.3f", metrics.startProbabilityBrierScore),
                unit = "",
                description = "كلما اقترب من 0.000 زادت دقة توقع البدء أساسياً (0.00-0.25 ممتاز)",
                color = if (metrics.startProbabilityBrierScore <= 0.15) Color(0xFF10B981) else Color(0xFFF59E0B)
            )
        }

        // 3. اتجاه الدقة عبر الجولات
        if (metrics.gameweekTrends.isNotEmpty()) {
            Text(
                "تطور الدقة عبر الجولات التاريخية",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metrics.gameweekTrends.forEach { trend ->
                        val isAdvantage = trend.baselineMae >= trend.mae
                        val diff = trend.baselineMae - trend.mae
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("الجولة ${trend.gameweek}", fontWeight = FontWeight.Bold)
                            Text("MAE: ±${trend.mae} نقطة", style = MaterialTheme.typography.bodySmall)
                            Surface(
                                color = if (isAdvantage) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "فارق AI: %+.2f ن".format(Locale.US, diff),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isAdvantage) Color(0xFF10B981) else Color(0xFFEF4444)
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }

        // 4. الملاحظات التشخيصية باللغة العربية
        Text(
            "الملاحظات والتشخيص العلمي",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                metrics.diagnosticNotesAr.forEach { note ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("•", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            note,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiValueAddedCard(metrics: PredictionAccuracyMetrics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = Color(0xFF10B981))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "القيمة المضافة للذكاء الاصطناعي (AI Lift)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    color = if (metrics.aiValueAdded >= 0) Color(0xFF10B981) else Color(0xFFEF4444),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        if (metrics.aiValueAdded >= 0) "+${metrics.aiValueAdded} نقطة دقة" else "${metrics.aiValueAdded} نقطة",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                "مقارنة نموذج الذكاء الاصطناعي (MAE: ±${metrics.meanAbsoluteError}) مع خط الأساس التبسيطي (Baseline MAE: ±${metrics.baselineMae}):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("خط الأساس (PPG)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text("±${metrics.baselineMae}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("محرك AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("±${metrics.meanAbsoluteError}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("نسبة التفوق المباشر", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                    Text("${metrics.aiWinRatePercent}%", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF10B981))
                }
            }

            LinearProgressIndicator(
                progress = { (metrics.aiWinRatePercent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF10B981),
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )

            Text(
                "يثبت هذا الفارق إحصائياً أن المحرك لا يعتمد على أرقام سطحية، بل يضيف قيمة حقيقية في خفض أخطاء التوقع واقتناص عوائد الجولات.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    unit: String,
    description: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun CaptaincyTabContent(
    metrics: PredictionAccuracyMetrics,
    records: List<AccuracyComparisonRecord>
) {
    val cap = metrics.captaincyAccuracy
    val scrollState = rememberScrollState()
    val capRecords = records.filter { it.snapshot.isCaptain }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("أداء خيارات الكابتن الموصى بها", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("معدل نقاط الكابتن", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text("${cap.averageActualPoints}", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("متوقع الكابتن", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text("${cap.averageExpectedPoints}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("نسبة نجاح الخيار (6+)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                        Text("${cap.successRatePercent}%", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF10B981))
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("إجمالي الجولات المقيمة:", style = MaterialTheme.typography.bodySmall)
                    Text("${cap.totalPicks} جولات", fontWeight = FontWeight.Bold)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("مرات تفوق الكابتن على نائبه:", style = MaterialTheme.typography.bodySmall)
                    Text("${cap.captainOutscoredViceCount} من ${cap.totalPicks}", fontWeight = FontWeight.Bold)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("مرات تفوق الكابتن على معدل التشكيلة:", style = MaterialTheme.typography.bodySmall)
                    Text("${cap.captainOutscoredAverageCount} من ${cap.totalPicks}", fontWeight = FontWeight.Bold)
                }
            }
        }

        Text("سجل ترشيحات الكابتن عبر الجولات", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

        if (capRecords.isEmpty()) {
            Text("لا توجد جولات مكتملة مسجلة للكابتن بعد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        } else {
            capRecords.forEach { item ->
                val vice = records.firstOrNull { it.snapshot.gameweek == item.snapshot.gameweek && it.snapshot.isViceCaptain }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("الجولة ${item.snapshot.gameweek}", fontWeight = FontWeight.Bold)
                            Surface(
                                color = if (item.actual.actualPoints >= 6) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFEF4444).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    if (item.actual.actualPoints >= 6) "خيار ناجح (${item.actual.actualPoints} ن)" else "عائد منخفض (${item.actual.actualPoints} ن)",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.actual.actualPoints >= 6) Color(0xFF10B981) else Color(0xFFEF4444)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("الكابتن: ${item.snapshot.playerName} (${item.snapshot.playerTeam})", style = MaterialTheme.typography.bodySmall)
                            Text("المتوقع: ${item.snapshot.expectedPoints} | الفعلي: ${item.actual.actualPoints}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }

                        if (vice != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("النائب: ${vice.snapshot.playerName} (${vice.snapshot.playerTeam})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                Text("الفعلي: ${vice.actual.actualPoints} نقطة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionsTabContent(metrics: PredictionAccuracyMetrics) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("دقة التوقعات حسب مراكز اللاعبين", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        metrics.positionBreakdown.values.forEach { posSummary ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        posSummary.position.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(posSummary.position.nameAr, fontWeight = FontWeight.Bold)
                        }

                        Text("${posSummary.count} لاعب مُقيم", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("MAE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text("±${posSummary.mae}", fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("الانحياز (Bias)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text("${posSummary.bias}", fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("خطأ الدقائق", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Text("±${posSummary.minutesMae} د", fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val advColor = if (posSummary.aiAdvantage >= 0) Color(0xFF10B981) else Color(0xFFEF4444)
                            Text("فارق AI", style = MaterialTheme.typography.labelSmall, color = advColor)
                            Text("%+.2f ن".format(Locale.US, posSummary.aiAdvantage), fontWeight = FontWeight.Bold, color = advColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsTabContent(
    records: List<AccuracyComparisonRecord>,
    selectedPosition: PlayerPosition?,
    selectedSort: AccuracySortOption,
    onSelectPosition: (PlayerPosition?) -> Unit,
    onSelectSort: (AccuracySortOption) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // فلاتر المركز والترتيب
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedPosition == null,
                onClick = { onSelectPosition(null) },
                label = { Text("الكل (${records.size})") }
            )
            PlayerPosition.values().forEach { pos ->
                FilterChip(
                    selected = selectedPosition == pos,
                    onClick = { onSelectPosition(pos) },
                    label = { Text(pos.nameAr) }
                )
            }
        }

        // شريط الترتيب
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AccuracySortOption.values().forEach { sort ->
                FilterChip(
                    selected = selectedSort == sort,
                    onClick = { onSelectSort(sort) },
                    label = { Text(sort.titleAr) }
                )
            }
        }

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("لا توجد سجلات مطابقة للفلاتر المحددة.", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(records, key = { "${it.snapshot.id}_${it.actual.gameweek}" }) { record ->
                    PlayerAccuracyComparisonItem(record)
                }
            }
        }
    }
}

@Composable
private fun PlayerAccuracyComparisonItem(record: AccuracyComparisonRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.snapshot.playerName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("${record.snapshot.playerTeam} • ${record.snapshot.position.singularAr}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (record.snapshot.isCaptain) {
                        Surface(color = Color(0xFFF59E0B), shape = RoundedCornerShape(4.dp)) {
                            Text("كابتن", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                    if (record.snapshot.isViceCaptain) {
                        Surface(color = Color(0xFF3B82F6), shape = RoundedCornerShape(4.dp)) {
                            Text("نائب", modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                    Surface(
                        color = if (record.aiBeatsBaseline) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            if (record.aiBeatsBaseline) "تفوق AI" else "تطابق/أفضلية Base",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (record.aiBeatsBaseline) Color(0xFF10B981) else Color(0xFFEF4444)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "المتوقع: ${record.snapshot.expectedPoints} ن | الفعلي: ${record.actual.actualPoints} ن",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                val diff = record.pointsError
                val sign = if (diff > 0) "+${String.format(Locale.US, "%.1f", diff)}" else String.format(Locale.US, "%.1f", diff)
                Text(
                    "الخطأ: $sign ن (مطلق: ${String.format(Locale.US, "%.1f", record.absolutePointsError)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (record.absolutePointsError <= 2.0) Color(0xFF10B981) else Color(0xFFF59E0B)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "الدقائق: ${record.snapshot.expectedMinutes.toInt()} متوقع vs ${record.actual.actualMinutes} فعلي",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    "الجولة ${record.snapshot.gameweek}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
