package com.fplmanagerai.presentation.chips

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fplmanagerai.data.model.DataSyncStatus
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.domain.chips.*
import com.fplmanagerai.presentation.components.FplAccountLinkDialog
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipStrategyDashboardScreen(
    viewModel: ChipStrategyViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "إدارة رقاقات الفانتازي الذكية",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "نظام المجموعتين (موسم 2026/27) • 8 رقاقات",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        val gwText = uiState.currentGameweek?.let { "GW ${it.id}" } ?: "GW --"
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = gwText,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        IconButton(onClick = { viewModel.setAccountDialogVisible(true) }) {
                            if (uiState.accountProfile != null) {
                                Icon(Icons.Default.AccountCircle, contentDescription = "حساب FPL", tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Icon(Icons.Default.AddLink, contentDescription = "ربط حساب FPL")
                            }
                        }
                        IconButton(onClick = { viewModel.loadStrategy(forceRefresh = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث البيانات")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when {
                    uiState.isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "جارٍ تحليل استراتيجية الرقاقات والمعايرة التاريخية...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    uiState.errorMessage != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = uiState.errorMessage ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { viewModel.loadStrategy(forceRefresh = true) }) {
                                Text("إعادة المحاولة")
                            }
                        }
                    }

                    uiState.portfolioPlan != null -> {
                        val plan = uiState.portfolioPlan!!
                        val currentGwNumber = uiState.currentGameweek?.id ?: 12

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // 0. Account Status
                            item {
                                val profile = uiState.accountProfile
                                if (profile != null) {
                                    val isHistoryConfirmed = uiState.userHistory.isChipHistoryConfirmed
                                    val statusText = if (isHistoryConfirmed) {
                                        "الحساب مرتبط: ${profile.teamName} • سجل الشرائح مؤكد"
                                    } else {
                                        "الحساب مرتبط: ${profile.teamName} • بانتظار تأكيد سجل الشرائح"
                                    }
                                    val statusColor = if (isHistoryConfirmed) Color(0xFF047857) else Color(0xFFB45309)
                                    val iconTint = if (isHistoryConfirmed) Color(0xFF10B981) else Color(0xFFD97706)

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.setAccountDialogVisible(true) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(if (isHistoryConfirmed) Icons.Default.CheckCircle else Icons.Default.SyncProblem, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = statusText,
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                    color = statusColor
                                                )
                                            }
                                            Text(
                                                text = "إدارة",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.setAccountDialogVisible(true) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.AddLink, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = "اربط Team ID لمزامنة الشرائح المستهلكة رسمياً",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                                )
                                            }
                                            Text(
                                                text = "ربط الآن",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }

                            // 0b. Data Sync Status Pill
                            item {
                                val syncStatus = uiState.dataSyncStatus
                                val timeText = uiState.lastUpdatedTimeFormatted
                                val (bgColor, textColor, icon) = when (syncStatus) {
                                    DataSyncStatus.LIVE -> Triple(Color(0xFF10B981).copy(alpha = 0.15f), Color(0xFF047857), Icons.Default.CloudDone)
                                    DataSyncStatus.CACHED -> Triple(Color(0xFF3B82F6).copy(alpha = 0.15f), Color(0xFF1D4ED8), Icons.Default.Inventory2)
                                    DataSyncStatus.STALE -> Triple(Color(0xFFF59E0B).copy(alpha = 0.15f), Color(0xFFB45309), Icons.Default.Update)
                                    DataSyncStatus.ERROR -> Triple(Color(0xFFEF4444).copy(alpha = 0.15f), Color(0xFFB91C1C), Icons.Default.CloudOff)
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = bgColor,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "${syncStatus.badgeAr} • $timeText",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = textColor
                                            )
                                        }
                                        Text(
                                            text = "تحديث الآن",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = textColor,
                                            modifier = Modifier.clickable { viewModel.loadStrategy(forceRefresh = true) }
                                        )
                                    }
                                }
                            }

                            // Feedback / Rule Violation Alert Bar
                            if (uiState.actionAlertMessageAr != null) {
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = uiState.actionAlertMessageAr ?: "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                            IconButton(
                                                onClick = { viewModel.dismissAlert() },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "إغلاق",
                                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 1. First-Half vs Second-Half Tabs
                            item {
                                TabRow(
                                    selectedTabIndex = if (uiState.selectedHalf == ChipHalf.FIRST_HALF) 0 else 1,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                ) {
                                    Tab(
                                        selected = uiState.selectedHalf == ChipHalf.FIRST_HALF,
                                        onClick = { viewModel.selectHalf(ChipHalf.FIRST_HALF) },
                                        text = {
                                            Text(
                                                text = "المجموعة الأولى (GW 1-19)",
                                                fontWeight = if (uiState.selectedHalf == ChipHalf.FIRST_HALF) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    )
                                    Tab(
                                        selected = uiState.selectedHalf == ChipHalf.SECOND_HALF,
                                        onClick = { viewModel.selectHalf(ChipHalf.SECOND_HALF) },
                                        text = {
                                            Text(
                                                text = "المجموعة الثانية (GW 20-38)",
                                                fontWeight = if (uiState.selectedHalf == ChipHalf.SECOND_HALF) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    )
                                }
                            }

                            // 2. Set 1 Expiration Alert Banner (if viewing Set 1 and GW >= 18)
                            if (uiState.selectedHalf == ChipHalf.FIRST_HALF && currentGwNumber in 17..19) {
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF7F1D1D).copy(alpha = 0.4f),
                                        border = CardDefaults.outlinedCardBorder().copy(
                                            brush = Brush.horizontalGradient(listOf(Color(0xFFEF4444), Color(0xFFB91C1C)))
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = Color(0xFFFCA5A5),
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "تنبيه موعد انتهاء المجموعة الأولى (Set 1 Expiration)",
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = Color(0xFFFEE2E2)
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = "تنتهي صلاحية رقاقات المجموعة الأولى مع بداية الجولة 20 ولا تُرحّل للنصف الثاني! تأكد من استهلاكها لتجنب خسارتها.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFFFECACA)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 3. Season Roadmap Summary Card
                            item {
                                SeasonRoadmapCard(plan = plan)
                            }

                            // 4. Historical Learning & Calibration Insight
                            uiState.calibrationMetrics?.let { metrics ->
                                item {
                                    CalibrationInsightCard(metrics = metrics)
                                }
                            }

                            // 5. Active Recommendation Alert (if a chip is recommended for NOW)
                            val activeChip = plan.activeChipThisGameweek
                            if (activeChip != null) {
                                val activeAnalysis = plan.chipAnalyses[activeChip]
                                if (activeAnalysis != null && activeAnalysis.isRecommendedNow && uiState.selectedHalf == plan.currentHalf) {
                                    item {
                                        ActiveChipBanner(analysis = activeAnalysis)
                                    }
                                }
                            }

                            // 6. Detailed Chip Cards for Selected Half
                            item {
                                Text(
                                    text = "رقاقات ${uiState.selectedHalf.titleAr}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // Display the 4 chips
                            val selectedHalfAnalyses = plan.allChipAnalyses.filter { it.half == uiState.selectedHalf }
                            val effectiveMap = if (selectedHalfAnalyses.isNotEmpty()) {
                                selectedHalfAnalyses.associateBy { it.chip }
                            } else {
                                plan.chipAnalyses
                            }

                            item {
                                effectiveMap[ChipType.TRIPLE_CAPTAIN]?.let { tc ->
                                    val state = plan.userChipHistory.getChipState(ChipType.TRIPLE_CAPTAIN, uiState.selectedHalf, currentGwNumber)
                                    ChipCardView(
                                        analysis = tc,
                                        status = state,
                                        onSetPending = { viewModel.setPending(ChipType.TRIPLE_CAPTAIN, uiState.selectedHalf) },
                                        onActivate = { viewModel.confirmActivation(ChipType.TRIPLE_CAPTAIN, uiState.selectedHalf) },
                                        onCancelPending = { viewModel.cancelPending() },
                                        onToggleUsed = { viewModel.toggleChipUsedInHalf(ChipType.TRIPLE_CAPTAIN, uiState.selectedHalf) }
                                    )
                                }
                            }

                            item {
                                effectiveMap[ChipType.BENCH_BOOST]?.let { bb ->
                                    val state = plan.userChipHistory.getChipState(ChipType.BENCH_BOOST, uiState.selectedHalf, currentGwNumber)
                                    ChipCardView(
                                        analysis = bb,
                                        status = state,
                                        onSetPending = { viewModel.setPending(ChipType.BENCH_BOOST, uiState.selectedHalf) },
                                        onActivate = { viewModel.confirmActivation(ChipType.BENCH_BOOST, uiState.selectedHalf) },
                                        onCancelPending = { viewModel.cancelPending() },
                                        onToggleUsed = { viewModel.toggleChipUsedInHalf(ChipType.BENCH_BOOST, uiState.selectedHalf) }
                                    )
                                }
                            }

                            item {
                                effectiveMap[ChipType.FREE_HIT]?.let { fh ->
                                    val state = plan.userChipHistory.getChipState(ChipType.FREE_HIT, uiState.selectedHalf, currentGwNumber)
                                    ChipCardView(
                                        analysis = fh,
                                        status = state,
                                        onSetPending = { viewModel.setPending(ChipType.FREE_HIT, uiState.selectedHalf) },
                                        onActivate = { viewModel.confirmActivation(ChipType.FREE_HIT, uiState.selectedHalf) },
                                        onCancelPending = { viewModel.cancelPending() },
                                        onToggleUsed = { viewModel.toggleChipUsedInHalf(ChipType.FREE_HIT, uiState.selectedHalf) },
                                        onPreviewSquad = {
                                            fh.temporarySquad?.let { squad ->
                                                viewModel.openSquadPreview("تشكيلة الفري هيت المقترحة (${uiState.selectedHalf.titleEn})", squad)
                                            }
                                        }
                                    )
                                }
                            }

                            item {
                                effectiveMap[ChipType.WILDCARD]?.let { wc ->
                                    val state = plan.userChipHistory.getChipState(ChipType.WILDCARD, uiState.selectedHalf, currentGwNumber)
                                    ChipCardView(
                                        analysis = wc,
                                        status = state,
                                        onSetPending = { viewModel.setPending(ChipType.WILDCARD, uiState.selectedHalf) },
                                        onActivate = { viewModel.confirmActivation(ChipType.WILDCARD, uiState.selectedHalf) },
                                        onCancelPending = { viewModel.cancelPending() },
                                        onToggleUsed = { viewModel.toggleChipUsedInHalf(ChipType.WILDCARD, uiState.selectedHalf) },
                                        onPreviewSquad = {
                                            wc.temporarySquad?.let { squad ->
                                                viewModel.openSquadPreview("تشكيلة الوايلد كارد المقترحة (${uiState.selectedHalf.titleEn})", squad)
                                            }
                                        }
                                    )
                                }
                            }

                            item {
                                Spacer(Modifier.height(32.dp))
                            }
                        }
                    }
                }

                // Modal Dialog for Squad Preview
                if (uiState.showSquadPreviewDialog) {
                    SquadPreviewDialog(
                        titleAr = uiState.previewSquadTitleAr,
                        players = uiState.previewSquadPlayers,
                        onDismiss = { viewModel.closeSquadPreview() }
                    )
                }

                // FPL Team ID Account Link Dialog
                if (uiState.showAccountDialog) {
                    FplAccountLinkDialog(
                        currentProfile = uiState.accountProfile,
                        isLoading = uiState.isAccountLinkingLoading,
                        errorMessage = uiState.accountLinkingError,
                        onLink = { teamId -> viewModel.linkFplAccount(teamId) },
                        onUnlink = { viewModel.unlinkFplAccount() },
                        onDismiss = { viewModel.setAccountDialogVisible(false) }
                    )
                }
            }
        }
    }
}

/**
 * Historical Calibration & Learning Insight Card
 */
@Composable
fun CalibrationInsightCard(metrics: CalibrationMetrics) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), Color.Transparent))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Analytics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "محرك التعلم التاريخي والمعايرة (Historical Learning)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "دقة ${metrics.accuracyPercent}%",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = metrics.summaryAr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * Universal Unified Chip Card with 2026/27 lifecycle and actions
 */
@Composable
fun ChipCardView(
    analysis: SingleChipAnalysis,
    status: ChipStatus,
    onSetPending: () -> Unit,
    onActivate: () -> Unit,
    onCancelPending: () -> Unit,
    onToggleUsed: () -> Unit,
    onPreviewSquad: (() -> Unit)? = null
) {
    val icon = when (analysis.chip) {
        ChipType.TRIPLE_CAPTAIN -> Icons.Default.Stars
        ChipType.BENCH_BOOST -> Icons.Default.Groups
        ChipType.FREE_HIT -> Icons.Default.Autorenew
        ChipType.WILDCARD -> Icons.Default.Build
    }

    val iconTint = when (analysis.chip) {
        ChipType.TRIPLE_CAPTAIN -> Color(0xFFFBBF24)
        ChipType.BENCH_BOOST -> Color(0xFF60A5FA)
        ChipType.FREE_HIT -> Color(0xFFA78BFA)
        ChipType.WILDCARD -> Color(0xFFF472B6)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                when (status) {
                    ChipStatus.ACTIVE_THIS_GW -> listOf(Color(0xFF22C55E), Color(0xFF15803D))
                    ChipStatus.PENDING -> listOf(Color(0xFF38BDF8), Color(0xFF0284C7))
                    ChipStatus.EXPIRED -> listOf(Color(0xFFEF4444), Color(0xFF7F1D1D))
                    else -> if (analysis.isRecommendedNow) listOf(Color(0xFF22C55E), Color(0xFF15803D))
                    else listOf(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), Color.Transparent)
                }
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Title & Lifecycle Status Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = analysis.chip.titleAr,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "الجولة المستهدفة: GW ${analysis.recommendedGameweek} • ${analysis.half.titleEn}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Status pill
                val (pillText, pillColor) = when (status) {
                    ChipStatus.AVAILABLE -> "متاح" to Color(0xFF10B981)
                    ChipStatus.PENDING -> "معلّق للديدلاين" to Color(0xFF38BDF8)
                    ChipStatus.ACTIVE_THIS_GW -> "نشط حالياً" to Color(0xFF22C55E)
                    ChipStatus.USED -> "مُستخدمة" to MaterialTheme.colorScheme.outline
                    ChipStatus.EXPIRED -> "منتهية الصلاحية" to Color(0xFFEF4444)
                    ChipStatus.UNKNOWN -> "غير مؤكد" to Color(0xFFD97706)
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = pillColor.copy(alpha = 0.15f),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(pillColor, pillColor)))
                ) {
                    Text(
                        text = pillText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = pillColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Metrics Row: Gain, Confidence, Decision
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "الفارق النقطي المتوقع", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (status == ChipStatus.USED || status == ChipStatus.EXPIRED) "0.0 نقطة" else "+%.1f نقطة".format(Locale.US, analysis.expectedPointsGain),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "مستوى الثقة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = analysis.confidence.titleAr,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "قرار المحرك", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (status == ChipStatus.USED || status == ChipStatus.EXPIRED) "غير متاحة" else analysis.verdict.shortAr,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (analysis.isRecommendedNow) Color(0xFF22C55E) else Color(0xFFF59E0B)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Expiration warning if present
            if (analysis.expirationWarningAr != null && status == ChipStatus.AVAILABLE) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF7F1D1D).copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = analysis.expirationWarningAr,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFFFECACA)
                        )
                    }
                }
            }

            // Target Player for Triple Captain
            if (analysis.targetPlayer != null) {
                val captain = analysis.targetPlayer
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "الكابتن المرشح: ${captain.webName}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                            Text(text = "${captain.teamName} • ${captain.position.shortName} • ${captain.formattedPrice}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = "%.1f xPts".format(Locale.US, captain.prediction?.expectedPoints ?: 0.0),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Primary reason
            Text(
                text = analysis.primaryReasonAr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(8.dp))

            // Squad Preview Button (if applicable)
            if (onPreviewSquad != null && analysis.temporarySquad != null) {
                OutlinedButton(
                    onClick = onPreviewSquad,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("معاينة التشكيلة المقترحة (15 لاعباً)")
                }
                Spacer(Modifier.height(4.dp))
            }

            // Bullet Points
            BulletPointsSection(reasons = analysis.detailedPointsAr)

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(8.dp))

            // Interactive Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                when (status) {
                    ChipStatus.AVAILABLE -> {
                        Row {
                            FilledTonalButton(
                                onClick = onSetPending,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("تعليق للجولة", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(6.dp))
                            Button(
                                onClick = onActivate,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("تفعيل الآن", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    ChipStatus.PENDING -> {
                        Row {
                            OutlinedButton(
                                onClick = onCancelPending,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("إلغاء التعليق", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(6.dp))
                            Button(
                                onClick = onActivate,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("تأكيد التفعيل", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = if (status == ChipStatus.ACTIVE_THIS_GW) "الرقاقة نشطة في تشكيلتك حالياً" else "الرقاقة مسجلة كمُستهلكة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Toggle manually used status
                TextButton(onClick = onToggleUsed) {
                    Text(
                        text = if (status == ChipStatus.USED) "إلغاء الاستخدام" else "تحديد كمُستخدمة",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/**
 * Seasonal Roadmap Card showing planned schedule for all chips
 */
@Composable
fun SeasonRoadmapCard(plan: ChipPortfolioPlan) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "خريطة الرقاقات لموسم 2026/27 (8 رقاقات)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = plan.overallStrategySummaryAr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))

            // Quick Status Pill Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(ChipType.values()) { chip ->
                    val analysis = plan.chipAnalyses[chip]
                    val isUsed = plan.userChipHistory.isChipUsed(chip, plan.currentGameweek)
                    val isNow = analysis?.isRecommendedNow == true

                    val bgColor = when {
                        isUsed -> MaterialTheme.colorScheme.surfaceVariant
                        isNow -> Color(0xFF0F5132).copy(alpha = 0.3f)
                        else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    }

                    val borderColor = when {
                        isUsed -> Color.Transparent
                        isNow -> Color(0xFF22C55E)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    }

                    val textColor = when {
                        isUsed -> MaterialTheme.colorScheme.onSurfaceVariant
                        isNow -> Color(0xFF4ADE80)
                        else -> MaterialTheme.colorScheme.onSurface
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = bgColor,
                        modifier = Modifier.border(1.dp, borderColor, RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = when (chip) {
                                    ChipType.TRIPLE_CAPTAIN -> "3XC"
                                    ChipType.BENCH_BOOST -> "BB"
                                    ChipType.FREE_HIT -> "FH"
                                    ChipType.WILDCARD -> "WC"
                                },
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = textColor
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    isUsed -> "مُستخدمة"
                                    isNow -> "تفعيل الآن!"
                                    analysis != null -> "GW ${analysis.recommendedGameweek}"
                                    else -> "متاح"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Immediate Active Recommendation Banner
 */
@Composable
fun ActiveChipBanner(analysis: SingleChipAnalysis) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF064E3B).copy(alpha = 0.4f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(Color(0xFF10B981), Color(0xFF059669)))
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Stars,
                        contentDescription = null,
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "توصية الجولة الحالية: ${analysis.chip.titleAr}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFD1FAE5)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF10B981)
                ) {
                    Text(
                        text = "+%.1f نقطة".format(Locale.US, analysis.expectedPointsGain),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.Black,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = analysis.primaryReasonAr,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFA7F3D0),
                lineHeight = 18.sp
            )
        }
    }
}

/**
 * Bullet points list for detailed reasons
 */
@Composable
fun BulletPointsSection(reasons: List<String>) {
    if (reasons.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        reasons.forEach { reason ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/**
 * Dialog for previewing the 15-player squad (Free Hit / Wildcard)
 */
@Composable
fun SquadPreviewDialog(
    titleAr: String,
    players: List<PlayerModel>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = titleAr,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(PlayerPosition.GKP, PlayerPosition.DEF, PlayerPosition.MID, PlayerPosition.FWD).forEach { pos ->
                        val posPlayers = players.filter { it.position == pos }
                        if (posPlayers.isNotEmpty()) {
                            item {
                                Text(
                                    text = pos.nameAr,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                            items(posPlayers) { p ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = p.webName,
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                            )
                                            Text(
                                                text = "${p.teamName} • ${p.formattedPrice}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = "%.1f xPts".format(Locale.US, p.prediction?.expectedPoints ?: 0.0),
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("إغلاق المعاينة")
                }
            }
        }
    }
}
