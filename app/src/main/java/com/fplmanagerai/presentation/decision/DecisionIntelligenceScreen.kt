package com.fplmanagerai.presentation.decision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
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
import com.fplmanagerai.domain.chips.UnifiedChipDecision
import com.fplmanagerai.domain.decision.*
import com.fplmanagerai.domain.team.Formation
import com.fplmanagerai.domain.team.TransferSuggestion
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionIntelligenceScreen(
    viewModel: DecisionIntelligenceViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("قرارات الجولة الذكية", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        "GW ${state.currentGameweek}",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Text(
                                if (state.isRealUserSquad) "تشكيلة حسابك الرسمي • نموذج ${state.activeModelVersionId}"
                                else "تشكيلة استرشادية ذكية • نموذج ${state.activeModelVersionId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.loadDecisions(forceRefresh = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث القرارات")
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
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "جاري تحليل تشكيلتك وتوليد أفضل القرارات التكتيكية...",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    state.errorMessage != null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .padding(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text("تنبيه في معالجة القرارات", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.height(6.dp))
                                    Text(state.errorMessage ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.height(16.dp))
                                    Button(onClick = { viewModel.loadDecisions(forceRefresh = true) }) {
                                        Text("إعادة المحاولة")
                                    }
                                }
                            }
                        }
                    }

                    state.plan != null -> {
                        DecisionContent(
                            plan = state.plan!!,
                            selectedScenarioId = state.selectedScenarioId ?: state.plan!!.scenariosComparison.bestScenario.id,
                            onSelectScenario = viewModel::selectScenario,
                            squadSyncWarning = state.squadSyncWarningAr
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DecisionContent(
    plan: GameweekDecisionPlan,
    selectedScenarioId: ScenarioId,
    onSelectScenario: (ScenarioId) -> Unit,
    squadSyncWarning: String?
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // تنبيه عدم مزامنة التشكيلة إذا وجد
        if (squadSyncWarning != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        squadSyncWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // =========================================================================
        // 1. البطاقة البطل (Hero Decision Card) - إجابة السؤال الجوهري
        // =========================================================================
        HeroDecisionCard(decision = plan.heroDecision)

        // =========================================================================
        // 2. مقارنة السيناريوهات التكتيكية الخمسة (Scenario Comparison Matrix)
        // =========================================================================
        ScenarioComparisonSection(
            comparison = plan.scenariosComparison,
            selectedId = selectedScenarioId,
            onSelectScenario = onSelectScenario
        )

        // =========================================================================
        // 3. التشكيلة الأساسية الذكية (Smart Starting XI)
        // =========================================================================
        StartingLineupSection(
            starting11 = plan.startingLineup,
            formation = plan.formation,
            captain = plan.captain,
            viceCaptain = plan.viceCaptain,
            projectedPoints = plan.projectedStartingPoints
        )

        // =========================================================================
        // 4. دكة البدلاء وقواعد التبديل التلقائي (Bench Intelligence & Auto-Sub)
        // =========================================================================
        BenchIntelligenceSection(benchIntelligence = plan.benchIntelligence)

        // =========================================================================
        // 5. شارة القيادة ونائب الكابتن (Captaincy Intelligence)
        // =========================================================================
        CaptaincySection(
            captain = plan.captain,
            viceCaptain = plan.viceCaptain,
            projectedCaptainPoints = plan.projectedCaptainPoints
        )

        // =========================================================================
        // 6. سوق الانتقالات والميزانية (Transfers & Budget)
        // =========================================================================
        TransfersSection(
            suggestions = plan.transferSuggestions,
            bank = plan.bank,
            freeTransfers = plan.freeTransfers,
            squadValue = plan.squadValue
        )

        // =========================================================================
        // 7. استراتيجية الشرائح التكتيكية (Chip Strategy Card)
        // =========================================================================
        ChipStrategySection(
            chipDecision = plan.unifiedChipDecision,
            gameweek = plan.gameweek
        )

        // =========================================================================
        // 8. التنبيهات والتحذيرات التكتيكية والطبية (Tactical Warnings)
        // =========================================================================
        TacticalWarningsSection(
            warnings = plan.tacticalWarnings,
            activeModelVersionId = plan.activeModelVersionId
        )

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * البطاقة البطل التي تجيب المدرب عن: «ما أفضل قرار لهذه الجولة ولماذا؟»
 */
@Composable
private fun HeroDecisionCard(decision: DecisionRecommendation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // شريط العنوان والشارات
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "القرار التكتيكي الأبرز للجولة",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                RiskBadge(risk = decision.risk)
            }

            Spacer(Modifier.height(12.dp))

            // العنوان الرئيسي للقرار
            Text(
                decision.titleAr,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(8.dp))

            // ملخص القرار
            Text(
                decision.summaryAr,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(14.dp))

            // شريط المؤشرات: الثقة والعائد المتوقع
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricChip(
                    title = "مستوى الثقة",
                    value = "${decision.confidence.scorePercent}% (${decision.confidence.level.titleAr})",
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )

                MetricChip(
                    title = "المكسب الصافي المتوقع",
                    value = "+%.1f ن".format(Locale.US, decision.expectedGain),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            // قسم "لماذا هذا القرار؟"
            Text(
                "لماذا هذا القرار؟",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                decision.explanation.mainReasonAr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (decision.explanation.keyDriversAr.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    decision.explanation.keyDriversAr.forEach { driver ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("• ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(
                                driver,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // الموازنة التكتيكية (Trade-offs)
            if (decision.tradeOffs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                decision.tradeOffs.forEach { tradeOff ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tradeOff.advantageAr, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFED6C02), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tradeOff.downsideOrRiskAr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * مقارنة السيناريوهات التكتيكية الخمسة
 */
@Composable
private fun ScenarioComparisonSection(
    comparison: ScenarioComparisonResult,
    selectedId: ScenarioId,
    onSelectScenario: (ScenarioId) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "مقارنة السيناريوهات التكتيكية (5 بدائل)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                comparison.comparisonSummaryAr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(Modifier.height(14.dp))

            // شريط التمرير لاختيار السيناريو
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(comparison.scenarios) { scenario ->
                    val isSelected = scenario.id == selectedId
                    val isBest = scenario.isRecommended

                    Surface(
                        modifier = Modifier.clickable { onSelectScenario(scenario.id) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isBest) borderStrokeHighlight() else null
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isBest) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                scenario.id.shortNameAr,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // تفاصيل السيناريو المحدد حالياً
            val activeScenario = comparison.scenarios.firstOrNull { it.id == selectedId } ?: comparison.bestScenario

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            activeScenario.titleAr,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (activeScenario.isRecommended) {
                            Surface(
                                color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "الخيار الموصى به",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        activeScenario.descriptionAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(12.dp))

                    // بطاقات الأرقام والمقاييس المقارنة
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NumberCard(
                            label = "نقاط الجولة",
                            value = "%.1f ن".format(Locale.US, activeScenario.projectedGameweekPoints),
                            modifier = Modifier.weight(1f)
                        )

                        NumberCard(
                            label = "ضريبة الخصم",
                            value = if (activeScenario.hitCost > 0) "-${activeScenario.hitCost} ن" else "مجاناً",
                            modifier = Modifier.weight(1f),
                            textColor = if (activeScenario.hitCost > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )

                        NumberCard(
                            label = "صافي الجولة",
                            value = "%.1f ن".format(Locale.US, activeScenario.netGameweekPoints),
                            modifier = Modifier.weight(1f)
                        )

                        NumberCard(
                            label = "ميزة 5 جولات",
                            value = "%+.1f ن".format(Locale.US, activeScenario.netHorizonAdvantage),
                            modifier = Modifier.weight(1f),
                            textColor = if (activeScenario.netHorizonAdvantage > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    if (activeScenario.transfersCount > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "عدد التبديلات: ${activeScenario.transfersCount} (${if (activeScenario.extraTransfersCount > 0) "${activeScenario.extraTransfersCount} بخصم -${activeScenario.hitCost} نقاط" else "مجاناً بالكامل"})",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        "التقييم: ${activeScenario.verdictAr}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/**
 * التشكيلة الأساسية الذكية وخطة اللعب الموصى بها
 */
@Composable
private fun StartingLineupSection(
    starting11: List<PlayerModel>,
    formation: Formation,
    captain: PlayerModel,
    viceCaptain: PlayerModel,
    projectedPoints: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "التشكيل الأساسي الموصى به",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "خطة ${formation.name} • %.1f نقطة متوقعة".format(Locale.US, projectedPoints),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        formation.name,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ترتيب الخطوط الأربعة
            val gkps = starting11.filter { it.position == PlayerPosition.GKP }
            val defs = starting11.filter { it.position == PlayerPosition.DEF }
            val mids = starting11.filter { it.position == PlayerPosition.MID }
            val fwds = starting11.filter { it.position == PlayerPosition.FWD }

            PositionLineGroup(title = "حراسة المرمى (1)", players = gkps, captain = captain, viceCaptain = viceCaptain)
            Spacer(Modifier.height(8.dp))
            PositionLineGroup(title = "خط الدفاع (${defs.size})", players = defs, captain = captain, viceCaptain = viceCaptain)
            Spacer(Modifier.height(8.dp))
            PositionLineGroup(title = "خط الوسط (${mids.size})", players = mids, captain = captain, viceCaptain = viceCaptain)
            Spacer(Modifier.height(8.dp))
            PositionLineGroup(title = "خط الهجوم (${fwds.size})", players = fwds, captain = captain, viceCaptain = viceCaptain)
        }
    }
}

@Composable
private fun PositionLineGroup(
    title: String,
    players: List<PlayerModel>,
    captain: PlayerModel,
    viceCaptain: PlayerModel
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(4.dp))
        players.forEach { player ->
            StartingPlayerRow(
                player = player,
                isCaptain = player.id == captain.id,
                isViceCaptain = player.id == viceCaptain.id
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun StartingPlayerRow(
    player: PlayerModel,
    isCaptain: Boolean,
    isViceCaptain: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // شارة المركز
                Surface(
                    color = getPositionColor(player.position).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        player.position.shortName,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = getPositionColor(player.position)
                    )
                }

                Spacer(Modifier.width(8.dp))

                // اسم اللاعب والنادي
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            player.webName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isCaptain) {
                            Spacer(Modifier.width(6.dp))
                            CaptainBadge(text = "C", color = Color(0xFFD32F2F))
                        } else if (isViceCaptain) {
                            Spacer(Modifier.width(6.dp))
                            CaptainBadge(text = "VC", color = Color(0xFF1976D2))
                        }
                    }
                    Text(
                        "${player.teamShortName} • £%.1fm".format(Locale.US, player.price),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // الجولة القادمة ومعدل النقاط
            Row(verticalAlignment = Alignment.CenterVertically) {
                val nextFix = player.upcomingFixtures.firstOrNull()
                if (nextFix != null) {
                    val homeAway = if (nextFix.isHome) "H" else "A"
                    Surface(
                        color = getFdrColor(nextFix.difficulty).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "${nextFix.opponentShortName} ($homeAway)",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = getFdrColor(nextFix.difficulty)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }

                val xpts = player.prediction?.expectedPoints ?: 0.0
                Text(
                    "%.1f ن".format(Locale.US, xpts),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * ذكاء دكة البدلاء وقواعد التبديل التلقائي (مع عرض الترتيب الفعلي للحساب مقابل المقترح منفصلين)
 */
@Composable
private fun BenchIntelligenceSection(benchIntelligence: BenchIntelligenceResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "دكة البدلاء وقواعد التبديل التلقائي",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(Icons.Default.AirlineSeatReclineNormal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(8.dp))

            // 1. تنبيه حماية التشكيلة
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        benchIntelligence.formationProtectionNoteAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // 2. إذا كانت تشكيلة حساب حقيقي ووجد اختلاف بين ترتيب المستخدم والمقترح
            if (benchIntelligence.userOriginalBench.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                if (benchIntelligence.hasBenchOrderChanged) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "تعديل مقترح لترتيب دكتك: ترتيبك الحالي في حسابك يختلف عن الترتيب التكتيكي الأمثل لضمان دخول البديل الأضمن نقاطاً وحماية خط الدفاع.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "ترتيب الدكة المحفوظ حالياً في حسابك على FPL:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(4.dp))
                benchIntelligence.userOriginalBench.forEach { sub ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "[${sub.slotLabelAr}]",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${sub.player.webName} (${sub.player.position.shortName})",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            val xpts = sub.player.prediction?.expectedPoints ?: 0.0
                            Text(
                                "%.1f ن".format(Locale.US, xpts),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "الترتيب التكتيكي المقترح للدكة (Auto-Sub Optimized):",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))

            // قائمة البدلاء الأربعة مرتبين تكتيكياً
            benchIntelligence.substitutes.forEach { sub ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = if (sub.isGoalkeeper) Color(0xFFFFA000).copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    sub.slotLabelAr,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (sub.isGoalkeeper) Color(0xFFFFA000) else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "${sub.player.webName} (${sub.player.position.shortName})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    sub.roleExplanationAr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        val xpts = sub.player.prediction?.expectedPoints ?: 0.0
                        Text(
                            "%.1f ن".format(Locale.US, xpts),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // شرح آلية Auto-Sub الرسمية
            Text(
                benchIntelligence.autoSubRulesExplanationAr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(Modifier.height(8.dp))

            // إخلاء مسؤولية واعتماد المدرب
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        benchIntelligence.disclaimerAr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * شارة القيادة ونائب الكابتن
 */
@Composable
private fun CaptaincySection(
    captain: PlayerModel,
    viceCaptain: PlayerModel,
    projectedCaptainPoints: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "شارة القيادة ونائب الكابتن",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(Icons.Default.MilitaryTech, contentDescription = null, tint = Color(0xFFFFB300))
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // الكابتن الأساسي
                CaptainCard(
                    role = "الكابتن (C)",
                    player = captain,
                    badgeColor = Color(0xFFD32F2F),
                    pointsText = "%.1f ن (مضاعفة 2x)".format(Locale.US, projectedCaptainPoints),
                    modifier = Modifier.weight(1f)
                )

                // نائب الكابتن
                val vcXpts = viceCaptain.prediction?.expectedPoints ?: 0.0
                CaptainCard(
                    role = "نائب الكابتن (VC)",
                    player = viceCaptain,
                    badgeColor = Color(0xFF1976D2),
                    pointsText = "%.1f ن (طوارئ)".format(Locale.US, vcXpts),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CaptainCard(
    role: String,
    player: PlayerModel,
    badgeColor: Color,
    pointsText: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = badgeColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        role,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }

                Text(
                    player.teamShortName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                player.webName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))
            Text(
                pointsText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            val nextFix = player.upcomingFixtures.firstOrNull()
            if (nextFix != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "المواجهة: ${nextFix.opponentShortName} (${if (nextFix.isHome) "ملعبه" else "خارج"})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * سوق الانتقالات والميزانية
 */
@Composable
private fun TransfersSection(
    suggestions: List<TransferSuggestion>,
    bank: Double,
    freeTransfers: Int,
    squadValue: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "سوق الانتقالات والميزانية",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(10.dp))

            // شريط المعلومات المالية
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricChip(
                    title = "في البنك",
                    value = "£%.1fm".format(Locale.US, bank),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                MetricChip(
                    title = "تبديلات مجانية",
                    value = "$freeTransfers (من 5)",
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                MetricChip(
                    title = "قيمة التشكيلة",
                    value = "£%.1fm".format(Locale.US, squadValue),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(14.dp))

            if (suggestions.isEmpty()) {
                Text(
                    "لا توجد تبديلات ملحة حالياً؛ التشكيلة متماسكة وتوفير التبديل للجولة القادمة هو الخيار الأفضل.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Text(
                    "أفضل الاقتراحات وفق العائد الممتد (أفق 5 جولات):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(6.dp))
                suggestions.take(2).forEach { sug ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        sug.playerOut.webName,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        sug.playerIn.webName,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32)
                                    )
                                }

                                Text(
                                    "+%.1f ن / 5GW".format(Locale.US, sug.netExpectedGain),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                            }

                            Spacer(Modifier.height(4.dp))
                            Text(
                                "سعر البيع: £%.1fm • الشراء: £%.1fm • التكلفة: £%+.1fm".format(
                                    Locale.US,
                                    sug.playerOut.effectiveSellingPrice,
                                    sug.playerIn.price,
                                    sug.netCost
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * استراتيجية الشرائح التكتيكية
 */
@Composable
private fun ChipStrategySection(
    chipDecision: UnifiedChipDecision?,
    gameweek: Int
) {
    if (chipDecision == null) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "استراتيجية الشرائح (قواعد 2026/27)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(Icons.Default.Stars, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (chipDecision.shouldActivateNow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            chipDecision.chip.titleAr,
                            fontWeight = FontWeight.Bold,
                            color = if (chipDecision.shouldActivateNow) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = if (chipDecision.shouldActivateNow) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                if (chipDecision.shouldActivateNow) "تفعيل الآن (GW $gameweek)" else "احتفظ بها (GW ${chipDecision.recommendedGameweek})",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        chipDecision.recommendationReasonAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chipDecision.shouldActivateNow) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * التنبيهات والتحذيرات التكتيكية والطبية
 */
@Composable
private fun TacticalWarningsSection(
    warnings: List<String>,
    activeModelVersionId: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "تنبيهات وتوجيهات نهائية قبل الديدلاين",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Icon(Icons.Default.NotificationImportant, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(8.dp))

            if (warnings.isNotEmpty()) {
                warnings.forEach { warn ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("• ", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        Text(
                            warn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Text(
                "• كافة التوصيات تم احتسابها عبر النموذج المعاير ($activeModelVersionId). قرارات الفانتازي تخضع لمستجدات اللحظات الأخيرة والمؤتمرات الصحفية للمدربين.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// =========================================================================
// المكونات المساعدة والشارات الصغيرة (Helper UI Components)
// =========================================================================

@Composable
private fun RiskBadge(risk: DecisionRisk) {
    val color = when (risk.category) {
        RiskCategory.SAFE -> Color(0xFF2E7D32)
        RiskCategory.BALANCED -> Color(0xFF0288D1)
        RiskCategory.AGGRESSIVE -> Color(0xFFED6C02)
        RiskCategory.SPECULATIVE -> Color(0xFFD32F2F)
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            risk.titleAr,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun CaptainBadge(text: String, color: Color) {
    Surface(
        color = color,
        shape = CircleShape
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun MetricChip(
    title: String,
    value: String,
    color: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.8f))
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = textColor)
        }
    }
}

@Composable
private fun NumberCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = textColor)
        }
    }
}

@Composable
private fun borderStrokeHighlight() = androidx.compose.foundation.BorderStroke(
    width = 1.5.dp,
    color = Color(0xFFFFB300)
)

private fun getPositionColor(pos: PlayerPosition): Color = when (pos) {
    PlayerPosition.GKP -> Color(0xFFFFA000)
    PlayerPosition.DEF -> Color(0xFF1976D2)
    PlayerPosition.MID -> Color(0xFF388E3C)
    PlayerPosition.FWD -> Color(0xFFD32F2F)
}

private fun getFdrColor(difficulty: Int): Color = when (difficulty) {
    1, 2 -> Color(0xFF2E7D32)
    3 -> Color(0xFF757575)
    4 -> Color(0xFFED6C02)
    5 -> Color(0xFFD32F2F)
    else -> Color(0xFF757575)
}
