package com.fplmanagerai.presentation.analysis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.domain.analysis.RankingCriteria
import com.fplmanagerai.domain.team.Formation
import com.fplmanagerai.domain.team.OptimizedSquad
import com.fplmanagerai.domain.team.RiskPreference
import com.fplmanagerai.presentation.components.PlayerAnalysisCard
import com.fplmanagerai.presentation.components.PositionBadge
import com.fplmanagerai.presentation.components.PredictionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerAnalysisScreen(
    viewModel: AnalysisViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("تحليل اللاعبين", "التشكيلة المثالية", "الكابتن والانتقالات")

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("محرك تحليل FPL الذكي", fontWeight = FontWeight.Bold)
                            if (state.gameweekName.isNotEmpty()) {
                                Text(
                                    state.gameweekName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.loadData(forceRefresh = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Main Navigation Tabs
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }
                }

                // Content based on state
                when {
                    state.isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text("جاري تحليل بيانات وإحصائيات الدوري الإنجليزي...")
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
                                Button(onClick = { viewModel.loadData(forceRefresh = true) }) {
                                    Text("إعادة المحاولة")
                                }
                            }
                        }
                    }

                    else -> {
                        when (selectedTab) {
                            0 -> PlayerAnalysisTab(state, viewModel)
                            1 -> SquadOptimizerTab(state, viewModel)
                            2 -> CaptainAndTransfersTab(state, viewModel)
                        }
                    }
                }
            }

            // Bottom sheet modal for player details
            state.selectedPlayer?.let { player ->
                player.prediction?.let { prediction ->
                    ModalBottomSheet(
                        onDismissRequest = { viewModel.selectPlayer(null) }
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

@Composable
fun PlayerAnalysisTab(
    state: AnalysisUiState,
    viewModel: AnalysisViewModel
) {
    Column(Modifier.fillMaxSize()) {
        // Search Bar
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::onSearchQueryChanged,
            placeholder = { Text("ابحث عن لاعب أو نادي...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "مسح")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp)
        )

        // Position Filter Chips
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = state.selectedPosition == null,
                    onClick = { viewModel.onPositionSelected(null) },
                    label = { Text("جميع المراكز") }
                )
            }
            items(PlayerPosition.values()) { pos ->
                FilterChip(
                    selected = state.selectedPosition == pos,
                    onClick = { viewModel.onPositionSelected(pos) },
                    label = { Text(pos.nameAr) }
                )
            }
        }

        // Sorting Chips
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(RankingCriteria.values()) { criteria ->
                FilterChip(
                    selected = state.selectedCriteria == criteria,
                    onClick = { viewModel.onCriteriaChanged(criteria) },
                    label = { Text(criteria.titleAr, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        // Player List
        if (state.filteredPlayers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا يوجد لاعبون مطابقون للبحث الحالي", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "النتائج (${state.filteredPlayers.size} لاعب)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(state.filteredPlayers, key = { it.id }) { player ->
                    PlayerAnalysisCard(
                        player = player,
                        onClick = { viewModel.selectPlayer(player) }
                    )
                }
            }
        }
    }
}

@Composable
fun SquadOptimizerTab(
    state: AnalysisUiState,
    viewModel: AnalysisViewModel
) {
    val squad = state.optimizedSquad
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Optimizer Control Panel Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("إعدادات محرك بناء التشكيلة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                // Budget Selector
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("الميزانية المتاحة: £%.1fm".format(java.util.Locale.UK, state.budget), fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = state.budget.toFloat(),
                    onValueChange = { viewModel.onBudgetChanged(Math.round(it * 10.0) / 10.0) },
                    valueRange = 90.0f..105.0f,
                    steps = 15
                )

                // Formation Selector
                Text("الخطة المفضلة:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = state.preferredFormation == null,
                        onClick = { viewModel.onFormationChanged(null) },
                        label = { Text("تلقائي (الأفضل)") }
                    )
                    Formation.ALL_VALID_FORMATIONS.forEach { form ->
                        FilterChip(
                            selected = state.preferredFormation == form,
                            onClick = { viewModel.onFormationChanged(form) },
                            label = { Text(form.name) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Strategy Selector
                Text("استراتيجية بناء التشكيلة:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    com.fplmanagerai.domain.team.OptimizationStrategy.values().forEach { strat ->
                        FilterChip(
                            selected = state.riskPreference == strat,
                            onClick = { viewModel.onRiskPreferenceChanged(strat) },
                            label = { Text(strat.titleAr, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Optimize Button
                Button(
                    onClick = viewModel::runOptimization,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !state.isOptimizing
                ) {
                    if (state.isOptimizing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("جاري الحساب والتحسين...")
                    } else {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("بناء التشكيلة المثالية الآن", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (squad != null) {
            // Squad Overview KPI Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("النقاط المتوقعة للتشكيلة الأساسية", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "%.1f ن".format(java.util.Locale.US, squad.projectedStartingPoints),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("الخطة: ${squad.formation.name} • الكابتن: ${squad.captain.webName} (C)", style = MaterialTheme.typography.bodySmall)
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("التكلفة الإجمالية: £%.1fm".format(java.util.Locale.UK, squad.totalCost), style = MaterialTheme.typography.bodySmall)
                        Text("المتبقي في البنك: £%.1fm".format(java.util.Locale.UK, squad.remainingBudget), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        Text("نقاط الدكة: %.1f".format(java.util.Locale.US, squad.projectedBenchPoints), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Pitch View (Starting 11)
            Text("التشكيلة الأساسية (11 لاعباً):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            // Starting GKP
            SectionHeader("حراسة المرمى (1)")
            squad.starting11.filter { it.position == PlayerPosition.GKP }.forEach { player ->
                PitchPlayerRow(player, squad.captain.id == player.id, squad.viceCaptain.id == player.id) {
                    viewModel.selectPlayer(player)
                }
            }

            // Starting DEF
            SectionHeader("خط الدفاع (${squad.formation.defenders})")
            squad.starting11.filter { it.position == PlayerPosition.DEF }.forEach { player ->
                PitchPlayerRow(player, squad.captain.id == player.id, squad.viceCaptain.id == player.id) {
                    viewModel.selectPlayer(player)
                }
            }

            // Starting MID
            SectionHeader("خط الوسط (${squad.formation.midfielders})")
            squad.starting11.filter { it.position == PlayerPosition.MID }.forEach { player ->
                PitchPlayerRow(player, squad.captain.id == player.id, squad.viceCaptain.id == player.id) {
                    viewModel.selectPlayer(player)
                }
            }

            // Starting FWD
            SectionHeader("خط الهجوم (${squad.formation.forwards})")
            squad.starting11.filter { it.position == PlayerPosition.FWD }.forEach { player ->
                PitchPlayerRow(player, squad.captain.id == player.id, squad.viceCaptain.id == player.id) {
                    viewModel.selectPlayer(player)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bench Section
            Text("دكة البدلاء مرتبة حسب الأولوية (4 لاعبين):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            squad.bench.forEachIndexed { index, player ->
                val label = if (player.position == PlayerPosition.GKP) "حارس بديل" else "بديل ${index}"
                BenchPlayerRow(player, label) {
                    viewModel.selectPlayer(player)
                }
            }
        }
    }
}

@Composable
fun CaptainAndTransfersTab(
    state: AnalysisUiState,
    viewModel: AnalysisViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Captaincy Section
        Text("توصيات شارة الكابتن", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "مقارنة دقيقة بين النقاط المتوقعة (مضاعفة للتنبؤ) والنقاط الفعلية للموسم",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val capResult = state.captainResult
        if (capResult != null) {
            capResult.topCandidates.forEachIndexed { index, candidate ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.selectPlayer(candidate.player) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (index == 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (index == 0) "C" else if (index == 1) "V" else "${index + 1}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column {
                                Text(candidate.player.webName, fontWeight = FontWeight.Bold)
                                Text("${candidate.player.teamShortName} • ${candidate.recommendationReasonAr}", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "%.1f ن متوقعة".format(java.util.Locale.US, candidate.predictedCaptainPoints),
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "(${candidate.actualCaptainPoints} ن فعلية)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Transfer Settings & Free Transfers Tracking
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("إعدادات التبديلات المجانية والأفق الزمني", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                // Free transfers counter (1 to 5)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("التبديلات المجانية المتاحة:", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..5).forEach { count ->
                            FilterChip(
                                selected = state.freeTransfers == count,
                                onClick = {
                                    viewModel.onFreeTransfersChanged(count)
                                    viewModel.runOptimization()
                                },
                                label = { Text("$count") }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Planning Horizon (1 to 5 GWs)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("أفق التخطيط (جولات):", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1, 2, 3, 5).forEach { h ->
                            FilterChip(
                                selected = state.planningHorizon == h,
                                onClick = {
                                    viewModel.onHorizonChanged(h)
                                    viewModel.runOptimization()
                                },
                                label = { Text("$h") }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Chips Planning Section (Phase 6)
        Text("تخطيط استخدام الرقائق (Chips Planning)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("تقييم ذكي لتوقيت تفعيل الوايلد كارد، الفري هيت، البينش بوست، والتريبل كابتن", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val chipRecs = state.transferAnalysis?.chipRecommendations ?: emptyList()
        if (chipRecs.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chipRecs.forEach { chipRec ->
                    Card(
                        modifier = Modifier.width(260.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (chipRec.isRecommended) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        border = if (chipRec.isRecommended) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(chipRec.chip.titleAr, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (chipRec.isRecommended) Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                ) {
                                    Text(
                                        text = if (chipRec.isRecommended) "موصى بها الآن" else "احتفظ بها",
                                        color = if (chipRec.isRecommended) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(chipRec.justificationAr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (chipRec.projectedGain > 0.0) {
                                Spacer(Modifier.height(4.dp))
                                Text("العائد النقطي المقدر: +%.1f ن".format(java.util.Locale.US, chipRec.projectedGain), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Keep vs Sell Decision Analysis
        Text("تحليل قرار البيع مقابل الإبقاء (Keep vs Sell)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val keepOrSell = state.transferAnalysis?.keepOrSellEvaluations ?: emptyList()
        if (keepOrSell.isNotEmpty()) {
            keepOrSell.take(4).forEach { eval ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${eval.playerToSell.webName} ⬅️ ${eval.recommendedReplacement.webName}", fontWeight = FontWeight.Bold)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (eval.shouldTransfer) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = if (eval.shouldTransfer) "نفذ التبديل (+%.1f ن)".format(java.util.Locale.US, eval.netAdvantage)
                                    else "احتفظ باللاعب الحالي",
                                    color = if (eval.shouldTransfer) Color(0xFF10B981) else Color(0xFFF59E0B),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(eval.reasoningAr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "الاحتفاظ: %.1f ن • الشراء: %.1f ن • خصم النقاط: -%d".format(java.util.Locale.US, eval.keepProjectedPoints, eval.buyProjectedPoints, eval.hitDeduction),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Transfer Suggestions Section
        Text("قائمة صفقات الانتقال المقترحة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        val transfers = state.transferAnalysis
        if (transfers != null && transfers.suggestions.isNotEmpty()) {
            transfers.suggestions.take(5).forEach { sug ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "بيع: ${sug.playerOut.webName} ⬅️ شراء: ${sug.playerIn.webName}",
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    "+%.1f ن صافي".format(java.util.Locale.US, sug.netExpectedGain),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(sug.explanationAr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sug.hitPenalty > 0) {
                            Text("تتطلب هذه الصفقة سالب 4 نقاط (-4)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("التشكيلة الحالية متوازنة ولا توجد حاجة ملحة لتبديلات إجبارية حالياً.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
fun PitchPlayerRow(
    player: PlayerModel,
    isCaptain: Boolean,
    isViceCaptain: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PositionBadge(player.position)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(player.webName, fontWeight = FontWeight.Bold)
                        if (isCaptain) {
                            BadgeIndicator("C", Color(0xFFEF4444))
                        } else if (isViceCaptain) {
                            BadgeIndicator("V", Color(0xFF3B82F6))
                        }
                    }
                    Text("${player.teamShortName} • ${player.formattedPrice}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "%.1f xPts".format(java.util.Locale.US, player.prediction?.expectedPoints ?: 0.0),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${player.totalPoints} ن فعلي",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun BenchPlayerRow(player: PlayerModel, label: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column {
                    Text(player.webName, fontWeight = FontWeight.SemiBold)
                    Text("${player.teamShortName} • ${player.position.singularAr}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "%.1f xPts".format(java.util.Locale.US, player.prediction?.expectedPoints ?: 0.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun BadgeIndicator(text: String, color: Color) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}
