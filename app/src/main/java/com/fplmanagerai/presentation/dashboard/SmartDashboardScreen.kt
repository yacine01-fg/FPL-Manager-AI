package com.fplmanagerai.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.fplmanagerai.data.model.FplAccountProfile
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.presentation.components.FixtureChip
import com.fplmanagerai.presentation.components.FplAccountLinkDialog
import com.fplmanagerai.presentation.accuracy.PredictionAccuracyScreen
import com.fplmanagerai.presentation.decision.DecisionIntelligenceScreen
import com.fplmanagerai.presentation.components.PositionBadge
import com.fplmanagerai.presentation.components.PredictionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartDashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAccuracyDialog by remember { mutableStateOf(false) }
    var showDecisionDialog by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("لوحة التحكم الذكية", fontWeight = FontWeight.Bold)
                            Text(
                                state.currentGameweek?.name ?: "الدوري الإنجليزي الممتاز",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setAccountDialogVisible(true) }) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "ربط وتعديل حساب FPL",
                                tint = if (state.accountProfile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { showDecisionDialog = true }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "قرارات الجولة الذكية", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { showAccuracyDialog = true }) {
                            Icon(Icons.Default.Insights, contentDescription = "محرك قياس دقة التوقعات", tint = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { viewModel.loadDashboardData(forceRefresh = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when {
                    state.isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text("جاري إعداد وتحليل لوحة التحكم الذكية...")
                            }
                        }
                    }

                    state.errorMessage != null -> {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(state.errorMessage ?: "حدث خطأ غير متوقع", color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { viewModel.loadDashboardData(forceRefresh = true) }) {
                                    Text("إعادة المحاولة")
                                }
                            }
                        }
                    }

                    else -> {
                        DashboardContent(
                            state = state,
                            viewModel = viewModel,
                            onOpenAccuracy = { showAccuracyDialog = true },
                            onOpenDecisions = { showDecisionDialog = true }
                        )
                    }
                }

                // Decision Intelligence Fullscreen Dialog
                if (showDecisionDialog) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showDecisionDialog = false },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize()) {
                                DecisionIntelligenceScreen()
                                IconButton(
                                    onClick = { showDecisionDialog = false },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "إغلاق")
                                }
                            }
                        }
                    }
                }

                // Prediction Accuracy Fullscreen Dialog
                if (showAccuracyDialog) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showAccuracyDialog = false },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize()) {
                                PredictionAccuracyScreen()
                                IconButton(
                                    onClick = { showAccuracyDialog = false },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "إغلاق")
                                }
                            }
                        }
                    }
                }

                // FPL Account Link Dialog
                if (state.showAccountDialog) {
                    FplAccountLinkDialog(
                        currentProfile = state.accountProfile,
                        isLoading = state.isAccountLinkingLoading,
                        errorMessage = state.accountLinkingError,
                        onLink = viewModel::linkFplAccount,
                        onUnlink = viewModel::unlinkFplAccount,
                        onDismiss = { viewModel.setAccountDialogVisible(false) }
                    )
                }

                // Player Detail Bottom Sheet
                state.selectedPlayerForDetails?.let { player ->
                    player.prediction?.let { prediction ->
                        ModalBottomSheet(
                            onDismissRequest = { viewModel.selectPlayerForDetails(null) }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                PredictionCard(player = player, prediction = prediction)
                                Spacer(Modifier.height(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardContent(
    state: SmartDashboardUiState,
    viewModel: DashboardViewModel,
    onOpenAccuracy: () -> Unit = {},
    onOpenDecisions: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 0. FPL Account Connection Status Card
        item {
            FplAccountStatusCard(
                profile = state.accountProfile,
                onOpenLinkDialog = { viewModel.setAccountDialogVisible(true) }
            )
        }

        // 1. Gameweek & Deadline Countdown Card
        item {
            GameweekCountdownCard(state)
        }

        // 2. Financials & Health Overview (Squad Value, Bank, Free Transfers)
        item {
            FinancialsOverviewCard(state)
        }

        // 3. Decision Intelligence Banner Card
        item {
            DecisionIntelligenceBannerCard(onClick = onOpenDecisions)
        }

        // 4. Prediction Accuracy Banner Card
        item {
            PredictionAccuracyBannerCard(onClick = onOpenAccuracy)
        }

        // 3. Injury & Availability Alerts Banner
        item {
            InjuryAlertsSection(state.injuryAlerts) { player ->
                viewModel.selectPlayerForDetails(player)
            }
        }

        // 4. Captain Recommendation Widget
        item {
            CaptainRecommendationWidget(state.captainResult) { player ->
                viewModel.selectPlayerForDetails(player)
            }
        }

        // 5. Recommended Transfers Quick Action
        if (state.recommendedTransfers.isNotEmpty()) {
            item {
                RecommendedTransfersCard(state.recommendedTransfers.first())
            }
        }

        // 6. Current Squad (Starting XI & Bench)
        val squad = state.mySquad
        if (squad != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "تشكيلتك الحالية (${squad.formation.name})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "متوقع: %.1f ن".format(java.util.Locale.US, squad.projectedStartingPoints),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Starting XI Players
            items(squad.starting11) { player ->
                DashboardPlayerRow(
                    player = player,
                    isCaptain = squad.captain.id == player.id,
                    isViceCaptain = squad.viceCaptain.id == player.id,
                    isBench = false,
                    onClick = { viewModel.selectPlayerForDetails(player) }
                )
            }

            // Bench Players
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "دكة البدلاء (4 لاعبين)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(squad.bench) { player ->
                DashboardPlayerRow(
                    player = player,
                    isCaptain = false,
                    isViceCaptain = false,
                    isBench = true,
                    onClick = { viewModel.selectPlayerForDetails(player) }
                )
            }
        }
    }
}

@Composable
fun FplAccountStatusCard(
    profile: FplAccountProfile?,
    onOpenLinkDialog: () -> Unit
) {
    if (profile == null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenLinkDialog() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Text(
                            "ربط حساب فانتازي الدوري الإنجليزي (FPL)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "أدخل Team ID لمزامنة تشكيلتك الرسمية، ترتيب الدكة، وأسعار البيع",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FilledTonalButton(
                    onClick = onOpenLinkDialog,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("ربط", fontWeight = FontWeight.Bold)
                }
            }
        }
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenLinkDialog() },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                profile.teamName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    "متصل",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF10B981),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            "المدرب: ${profile.managerFullName} | ID: ${profile.teamId} | الترتيب: #${profile.overallRank ?: "-"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onOpenLinkDialog) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "إدارة الحساب",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun GameweekCountdownCard(state: SmartDashboardUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        state.currentGameweek?.name ?: "الجولة الحالية",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        if (state.deadlinePassed) "بدأت أحداث الجولة" else "العد التنازلي للموعد النهائي (Deadline):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (state.deadlinePassed) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (state.deadlinePassed) "مغلقة" else "مفتوحة للتعديل",
                        color = if (state.deadlinePassed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = state.deadlineCountdown,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun FinancialsOverviewCard(state: SmartDashboardUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("قيمة التشكيلة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("£%.1fm".format(java.util.Locale.UK, state.squadValue), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }

            VerticalDivider(modifier = Modifier.height(30.dp), color = MaterialTheme.colorScheme.outlineVariant)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("المتبقي في البنك", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("£%.1fm".format(java.util.Locale.UK, state.moneyInBank), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }

            VerticalDivider(modifier = Modifier.height(30.dp), color = MaterialTheme.colorScheme.outlineVariant)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("التبديلات المجانية", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${state.freeTransfersAvailable}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun InjuryAlertsSection(
    alerts: List<PlayerModel>,
    onPlayerClick: (PlayerModel) -> Unit
) {
    if (alerts.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.10f))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                Text(
                    "جميع لاعبي التشكيلة جاهزون بنسبة 100% ولا توجد غيابات مؤكدة",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF10B981)
                )
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Text(
                        "تنبيهات الإصابة والشكوك (${alerts.size} لاعبين)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(6.dp))

                alerts.forEach { player ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onPlayerClick(player) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${player.webName} (${player.teamShortName})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            Text(
                                player.availability.labelAr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CaptainRecommendationWidget(
    capResult: com.fplmanagerai.domain.team.CaptainSelectionResult?,
    onPlayerClick: (PlayerModel) -> Unit
) {
    if (capResult == null) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("توصية شارة الكابتن للجولة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            // Primary Captain (C)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayerClick(capResult.captain) },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFFEF4444), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("C", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        Column {
                            Text(capResult.captain.webName, fontWeight = FontWeight.Bold)
                            Text("${capResult.captain.teamShortName} • كابتن أساسي", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "%.1f ن متوقعة".format(java.util.Locale.US, capResult.projectedCaptainPoints),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("${capResult.captain.totalPoints * 2} ن فعلية", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Vice-Captain (V)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayerClick(capResult.viceCaptain) },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF3B82F6), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("V", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        Column {
                            Text(capResult.viceCaptain.webName, fontWeight = FontWeight.Bold)
                            Text("${capResult.viceCaptain.teamShortName} • نائب الكابتن (احتياطي)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "%.1f ن".format(java.util.Locale.US, capResult.viceCaptain.prediction?.expectedPoints ?: 0.0),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecommendedTransfersCard(suggestion: com.fplmanagerai.domain.team.TransferSuggestion) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("الصفقة المقترحة الأكثر فائدة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        "+%.1f ن صافي".format(java.util.Locale.US, suggestion.netExpectedGain),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "بيع: ${suggestion.playerOut.webName} ⬅️ شراء: ${suggestion.playerIn.webName}",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(2.dp))
            Text(suggestion.explanationAr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DashboardPlayerRow(
    player: PlayerModel,
    isCaptain: Boolean,
    isViceCaptain: Boolean,
    isBench: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isBench) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PositionBadge(player.position)
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(player.webName, fontWeight = FontWeight.Bold)
                        if (isCaptain) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(Color(0xFFEF4444), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("C", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (isViceCaptain) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(Color(0xFF3B82F6), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("V", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(
                        "${player.teamShortName} • ${player.formattedPrice}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                player.upcomingFixtures.firstOrNull()?.let { fix ->
                    FixtureChip(fix)
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "%.1f xPts".format(java.util.Locale.US, player.prediction?.expectedPoints ?: 0.0),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "${player.totalPoints} ن",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PredictionAccuracyBannerCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Insights, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "محرك قياس دقة التوقعات",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = Color(0xFF10B981).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "تحقق علمي",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF10B981),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        "فحص MAE وRMSE ونسبة التفوق المضاف على النماذج التبسيطية",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun DecisionIntelligenceBannerCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "قرارات الجولة الذكية",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "ذكاء تكتيكي",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        "ما أفضل القرارات لتشكيلتك لهذه الجولة ولماذا؟ مقارنة 5 سيناريوهات",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}
