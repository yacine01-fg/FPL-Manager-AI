package com.fplmanagerai.presentation.calibration

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.fplmanagerai.domain.calibration.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionCalibrationScreen(
    viewModel: PredictionCalibrationViewModel = viewModel(),
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showResetDialog by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "محرك تحسين التوقعات ذاتياً",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Spacer(Modifier.width(8.dp))
                                ModelStatusBadge(state.activeModel.status)
                            }
                            Text(
                                "المعايرة الذاتية وإدارة أوزان التوقع ونماذج الخوارزمية",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    },
                    navigationIcon = {
                        if (onNavigateBack != null) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.loadCalibrationState() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث الحالة")
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
                if (state.isLoading && state.allVersions.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. شريط رسالة الحالة
                        state.statusMessage?.let { msg ->
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(msg, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        // 2. بطاقة كفاية البيانات وجاهزية العينة
                        item {
                            DataSufficiencyCard(
                                summary = state.datasetSummary,
                                isCalibrating = state.isCalibrating,
                                onRunCalibration = { viewModel.runCalibration() }
                            )
                        }

                        // 3. بطاقة النموذج النشط والأوزان الحالية
                        item {
                            ActiveModelCard(
                                model = state.activeModel,
                                onResetToBaseline = { showResetDialog = true }
                            )
                        }

                        // 4. بطاقة نتائج المعايرة الأخيرة والمقارنة قبل/بعد
                        state.latestRunResult?.let { result ->
                            item {
                                LatestCalibrationResultCard(
                                    result = result,
                                    onActivate = { versionId ->
                                        viewModel.activateModelVersion(versionId)
                                    }
                                )
                            }
                        }

                        // 5. سجل إصدارات النماذج وإمكانية الاسترجاع (Rollback)
                        item {
                            Text(
                                "سجل إصدارات النماذج والتحكم بالنسخ",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        items(state.allVersions, key = { it.id }) { version ->
                            ModelVersionHistoryItem(
                                version = version,
                                isActive = version.id == state.activeModel.id,
                                onActivate = { viewModel.activateModelVersion(version.id) },
                                onRollback = { viewModel.rollbackToVersion(version.id) }
                            )
                        }
                    }
                }

                // حوار تأكيد إعادة الضبط لخط الأساس
                if (showResetDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetDialog = false },
                        title = { Text("إعادة الضبط لخط الأساس؟", fontWeight = FontWeight.Bold) },
                        text = {
                            Text("سيتم إيقاف تفعيل أي أوزان أو معاملات معايرة تم تحسينها ذاتياً، والعودة إلى النموذج الافتراضي للمحرك (v2.0-baseline).")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.resetToBaseline()
                                    showResetDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("إعادة ضبط")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetDialog = false }) {
                                Text("إلغاء")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DataSufficiencyCard(
    summary: CalibrationDatasetSummary,
    isCalibrating: Boolean,
    onRunCalibration: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = if (summary.isSufficient) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "جاهزية العينة وحماية Overfitting",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    color = if (summary.isSufficient) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        if (summary.isSufficient) "العينة جاهزة" else "قيد التجميع",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (summary.isSufficient) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                }
            }

            Text(
                summary.sufficiencyStatusAr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("التقييمات المحفوظة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text("${summary.totalEvaluatedRecords} / ${summary.minimumRequiredRecords}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("الجولات المكتملة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text("${summary.evaluatedGameweeksCount} / ${summary.minimumRequiredGameweeks}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("نسبة الجاهزية", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text("${summary.readinessPercent}%", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            LinearProgressIndicator(
                progress = { summary.readinessPercent / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (summary.isSufficient) Color(0xFF10B981) else Color(0xFFF59E0B),
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )

            Button(
                onClick = onRunCalibration,
                enabled = !isCalibrating && summary.isSufficient,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isCalibrating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("جاري المعايرة واختبار Walk-Forward...")
                } else {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("بدء المعايرة والتحسين الذاتي الآن")
                }
            }

            if (!summary.isSufficient) {
                Text(
                    "شرط أمان: لن يبدأ تعديل أوزان التوقع حتى تكتمل بيانات الجولات المنتهية لحماية النموذج من القرارات المتسرعة.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun ActiveModelCard(
    model: ModelVersion,
    onResetToBaseline: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.displayNameAr, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        ModelStatusBadge(model.status)
                    }
                    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(model.createdAt))
                    Text("معرف الإصدار: ${model.id} • تم الإنشاء: $dateStr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }

                if (!model.isBaseline) {
                    TextButton(onClick = onResetToBaseline) {
                        Text("إعادة لخط الأساس", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            HorizontalDivider()

            Text("أوزان العوامل التنبؤية المعتمدة:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)

            WeightBarRow("الفورمة الحالية (Recent Form)", model.weights.formWeight, Color(0xFF3B82F6))
            WeightBarRow("الإنتاجية المتوقعة (Production / xGI)", model.weights.productionWeight, Color(0xFF10B981))
            WeightBarRow("معدل النقاط لكل 90 د (Points Per 90)", model.weights.pointsPer90Weight, Color(0xFF8B5CF6))
            WeightBarRow("صعوبة المواجهة (Fixture Difficulty)", model.weights.fixtureDifficultyWeight, Color(0xFFF59E0B))
            WeightBarRow("الجاهزية واستقرار الدقائق (Availability)", model.weights.availabilityWeight, Color(0xFFEC4899))

            HorizontalDivider()

            Text("معاملات المعايرة الحالية للمراكز:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                PositionFactorPill("GKP", model.calibrationFactors.gkpMultiplier)
                PositionFactorPill("DEF", model.calibrationFactors.defMultiplier)
                PositionFactorPill("MID", model.calibrationFactors.midMultiplier)
                PositionFactorPill("FWD", model.calibrationFactors.fwdMultiplier)
            }
        }
    }
}

@Composable
private fun WeightBarRow(label: String, weight: Double, color: Color) {
    val percent = (weight * 100).toInt()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text("$percent%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { weight.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun PositionFactorPill(pos: String, multiplier: Double) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(pos, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            val multStr = "x%.2f".format(Locale.US, multiplier)
            Text(
                multStr,
                style = MaterialTheme.typography.labelSmall,
                color = if (multiplier != 1.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun LatestCalibrationResultCard(
    result: CalibrationRunResult,
    onActivate: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (result.isApproved) Color(0xFF10B981).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
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
                    Icon(
                        if (result.isApproved) Icons.AutoMirrored.Filled.TrendingUp else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (result.isApproved) Color(0xFF10B981) else Color(0xFFEF4444)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (result.isApproved) "نتائج المعايرة: تم إثبات التفوق الإحصائي" else "نتائج المعايرة: تم رفض النموذج المرشح",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (result.isApproved) Color(0xFF047857) else MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = if (result.isApproved) Color(0xFF10B981) else Color(0xFFEF4444),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        if (result.isApproved) "+${result.liftAchieved} تفوق" else "غير مؤهل",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(result.messageAr, style = MaterialTheme.typography.bodySmall)

            val candidate = result.candidateModel
            if (candidate?.validationMetrics != null && candidate.baselineMetrics != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("خط الأساس (MAE)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text("±${candidate.baselineMetrics.mae}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("النموذج المعاير (MAE)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("±${candidate.validationMetrics.mae}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("الفارق الإيجابي (Lift)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981))
                        Text("+${result.liftAchieved} ن", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF10B981))
                    }
                }
            }

            if (result.diagnosticsAr.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        result.diagnosticsAr.forEach { diag ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("•", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(diag, style = MaterialTheme.typography.labelSmall, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }

            if (result.isApproved && candidate != null) {
                Button(
                    onClick = { onActivate(candidate.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("اعتماد وتفعيل هذا الإصدار المعاير الآن")
                }
            }
        }
    }
}

@Composable
private fun ModelVersionHistoryItem(
    version: ModelVersion,
    isActive: Boolean,
    onActivate: () -> Unit,
    onRollback: () -> Unit
) {
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
                    Text(version.displayNameAr, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    ModelStatusBadge(version.status)
                }

                if (isActive) {
                    Surface(color = Color(0xFF10B981).copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                        Text("النشط حالياً", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = if (version.status == ModelVersionStatus.ARCHIVED || version.status == ModelVersionStatus.ROLLED_BACK) onRollback else onActivate,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text(if (version.isBaseline) "الرجوع للأساس" else "استرجاع (Rollback)", fontSize = 12.sp)
                    }
                }
            }

            Text(version.changeReasonAr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            version.validationMetrics?.let { met ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("عينة التحقق: ${version.validationDatasetSize} لاعباً", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text("MAE: ±${met.mae} | Lift: %+.2f".format(Locale.US, met.liftVsBaseline), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun ModelStatusBadge(status: ModelVersionStatus) {
    val (bgColor, textColor) = when (status) {
        ModelVersionStatus.ACTIVE -> Color(0xFF10B981).copy(alpha = 0.2f) to Color(0xFF10B981)
        ModelVersionStatus.BASELINE -> Color(0xFF3B82F6).copy(alpha = 0.2f) to Color(0xFF3B82F6)
        ModelVersionStatus.CANDIDATE -> Color(0xFFF59E0B).copy(alpha = 0.2f) to Color(0xFFF59E0B)
        ModelVersionStatus.ARCHIVED -> MaterialTheme.colorScheme.outlineVariant to MaterialTheme.colorScheme.outline
        ModelVersionStatus.ROLLED_BACK -> Color(0xFF8B5CF6).copy(alpha = 0.2f) to Color(0xFF8B5CF6)
        ModelVersionStatus.REJECTED -> Color(0xFFEF4444).copy(alpha = 0.2f) to Color(0xFFEF4444)
        ModelVersionStatus.INSUFFICIENT_DATA -> Color(0xFFF59E0B).copy(alpha = 0.2f) to Color(0xFFF59E0B)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            status.titleAr,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

