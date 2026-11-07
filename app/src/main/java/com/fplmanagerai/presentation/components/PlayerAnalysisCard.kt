package com.fplmanagerai.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPosition
import com.fplmanagerai.data.model.RiskLevel
import com.fplmanagerai.data.model.TransferRecommendation
import com.fplmanagerai.data.model.UpcomingFixture

@Composable
fun PlayerAnalysisCard(
    player: PlayerModel,
    modifier: Modifier = Modifier,
    isCaptainEligible: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val pred = player.prediction

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                if (onClick != null) onClick() else expanded = !expanded
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Row 1: Position chip, Club & Name, Recommendation badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PositionBadge(player.position)
                    Column {
                        Text(
                            text = player.webName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${player.teamShortName} • ${player.formattedPrice}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (pred != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RiskBadge(pred.riskLevel)
                        RecommendationBadge(pred.recommendation)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Row 2: Actual Points vs Expected Points (Clear distinction)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        "النقاط الفعلية",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${player.totalPoints} ن",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                VerticalDivider(
                    modifier = Modifier.height(28.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "النقاط المتوقعة (xPts)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "%.1f".format(java.util.Locale.US, pred?.expectedPoints ?: 0.0),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                VerticalDivider(
                    modifier = Modifier.height(28.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "مؤشر الأداء",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "%.0f/100".format(java.util.Locale.US, pred?.performanceScore ?: 0.0),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor(pred?.performanceScore ?: 0.0)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Row 3: Availability & Fixture Run Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvailabilityStatusChip(player.availability.labelAr, player.availability.isAvailable)

                // Upcoming fixtures (next 3)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "المباريات:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    player.upcomingFixtures.take(3).forEach { fixture ->
                        FixtureChip(fixture)
                    }
                }
            }

            // Expand / Collapse details
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "تفاصيل التحليل الإحصائي المتقدم:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(6.dp))

                    val stats = player.statistics
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("الأهداف", "${stats.goals}")
                        MetricItem("التمريرات", "${stats.assists}")
                        MetricItem("الأهداف المتوقعة xG", stats.expectedGoals?.let { "%.2f".format(java.util.Locale.US, it) } ?: "غير متاح")
                        MetricItem("صناعة متوقعة xA", stats.expectedAssists?.let { "%.2f".format(java.util.Locale.US, it) } ?: "غير متاح")
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("الشباك النظيفة", "${stats.cleanSheets}")
                        MetricItem("الفورمة", "%.1f".format(java.util.Locale.US, player.form))
                        MetricItem("الدقائق", "${player.minutesPlayed}")
                        MetricItem("نسبة الملكية", "%.1f%%".format(java.util.Locale.US, player.selectedByPercent))
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricItem("مؤشر الموثوقية", "%.0f/100".format(java.util.Locale.US, pred?.reliabilityScore ?: 0.0))
                        MetricItem("فارق الإنهاء", pred?.finishingVariance?.let { "%+.1f".format(java.util.Locale.US, it) } ?: "—")
                        MetricItem("مساهمة الأهداف", pred?.goalInvolvementPercent?.let { "%.0f%%".format(java.util.Locale.US, it) } ?: "—")
                        MetricItem("احتمال البدء", "${((pred?.startProbability ?: 0.0) * 100).toInt()}%")
                    }

                    if (pred != null && pred.recommendationReason.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = "ملاحظة المحرك: ${pred.recommendationReason}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Toggle Expand Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "طي" else "توسيع",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PositionBadge(position: PlayerPosition) {
    val (bg, text) = when (position) {
        PlayerPosition.GKP -> Color(0xFFF59E0B) to "حارس"
        PlayerPosition.DEF -> Color(0xFF3B82F6) to "دفاع"
        PlayerPosition.MID -> Color(0xFF10B981) to "وسط"
        PlayerPosition.FWD -> Color(0xFFEF4444) to "هجوم"
    }

    Box(
        modifier = Modifier
            .background(bg.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = bg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RiskBadge(riskLevel: RiskLevel) {
    val (color, text) = when (riskLevel) {
        RiskLevel.LOW -> Color(0xFF10B981) to "مخاطرة منخفضة"
        RiskLevel.MEDIUM -> Color(0xFF3B82F6) to "مخاطرة متوسطة"
        RiskLevel.HIGH -> Color(0xFFF97316) to "مخاطرة عالية"
        RiskLevel.CRITICAL -> Color(0xFFEF4444) to "مخاطرة حرجة"
    }

    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RecommendationBadge(rec: TransferRecommendation) {
    val (color, text) = when (rec) {
        TransferRecommendation.BUY -> Color(0xFF10B981) to "شراء"
        TransferRecommendation.HOLD -> Color(0xFFF59E0B) to "إبقاء"
        TransferRecommendation.SELL -> Color(0xFFEF4444) to "بيع"
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun AvailabilityStatusChip(label: String, isAvailable: Boolean) {
    val color = if (isAvailable) Color(0xFF10B981) else Color(0xFFEF4444)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun FixtureChip(fixture: UpcomingFixture) {
    val fdrColor = when (fixture.difficulty) {
        1, 2 -> Color(0xFF10B981) // Green
        3 -> Color(0xFF6B7280)    // Grey
        4 -> Color(0xFFF97316)    // Orange
        5 -> Color(0xFFDC2626)    // Red
        else -> Color(0xFF6B7280)
    }

    val loc = if (fixture.isHome) "(H)" else "(A)"
    Box(
        modifier = Modifier
            .background(fdrColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = "${fixture.opponentShortName}$loc",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

fun scoreColor(score: Double): Color = when {
    score >= 70.0 -> Color(0xFF10B981)
    score >= 45.0 -> Color(0xFFF59E0B)
    else -> Color(0xFFEF4444)
}
