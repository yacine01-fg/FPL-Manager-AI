package com.fplmanagerai.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fplmanagerai.data.model.PlayerModel
import com.fplmanagerai.data.model.PlayerPrediction

@Composable
fun PredictionCard(
    player: PlayerModel,
    prediction: PlayerPrediction,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Player Name + Position + Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        player.webName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${player.teamName} • ${player.position.singularAr}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RiskBadge(prediction.riskLevel)
                    RecommendationBadge(prediction.recommendation)
                }
            }

            Spacer(Modifier.height(14.dp))

            // Main xPts Highlight Box
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "مجموع النقاط المتوقعة (xPts)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "نموذج إحصائي تقديري؛ لا يمثل نتيجة مؤكدة",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            "%.1f".format(java.util.Locale.US, prediction.expectedPoints),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Uncertainty Range (Floor ↔ Ceiling)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "مدى التوقع (أدنى عائد ↔ أقصى انفجار):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${prediction.formattedRange} ن (±${prediction.uncertaintyMargin})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Captain Points Distinction Section (Strict requirement)
            Text(
                "مقارنة الكابتن (مضاعفة النقاط)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("كابتن متوقع (تنبؤ)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(
                        "%.1f ن".format(java.util.Locale.US, prediction.predictedCaptainPoints),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                VerticalDivider(
                    modifier = Modifier.height(30.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text("كابتن فعلي (الموسم)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${prediction.actualCaptainPoints} ن",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Phase 4 Engine Metrics Card
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "مؤشرات المحرك التحليلي والتنبؤي:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("مؤشر الأداء", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("%.0f/100".format(java.util.Locale.US, prediction.performanceScore), fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("مؤشر الموثوقية", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("%.0f/100".format(java.util.Locale.US, prediction.reliabilityScore), fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("الدقائق المتوقعة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${prediction.expectedMinutes.toInt()} د", fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("مستوى الأمان", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(prediction.riskLevel.titleAr, fontWeight = FontWeight.Bold, color = when(prediction.riskLevel) {
                                com.fplmanagerai.data.model.RiskLevel.LOW -> Color(0xFF10B981)
                                com.fplmanagerai.data.model.RiskLevel.MEDIUM -> Color(0xFF3B82F6)
                                com.fplmanagerai.data.model.RiskLevel.HIGH -> Color(0xFFF97316)
                                com.fplmanagerai.data.model.RiskLevel.CRITICAL -> Color(0xFFEF4444)
                            })
                        }
                    }

                    if (prediction.calculationSummaryAr.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            prediction.calculationSummaryAr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Multi-Gameweek Horizon Predictions
            if (prediction.horizonPredictions.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "توقعات الجولات المقبلة (Planning Horizon):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    prediction.horizonPredictions.forEach { hp ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("GW${hp.gameweek}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "${hp.opponentShortName} ${if (hp.isHome) "(H)" else "(A)"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "%.1f ن".format(java.util.Locale.US, hp.expectedPoints),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Breakdown of components
            Text(
                "تفكيك حساب النقاط المتوقعة:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            PredictionBreakdownRow("المشاركة (دقائق ${prediction.expectedMinutes.toInt()})", "+%.2f".format(java.util.Locale.US, prediction.expectedAppearancePoints))
            PredictionBreakdownRow("المساهمة الهجومية (الأهداف والصناعة)", "+%.2f".format(java.util.Locale.US, prediction.expectedAttackingPoints))
            PredictionBreakdownRow("المساهمة الدفاعية والشباك النظيفة", "%.2f".format(java.util.Locale.US, prediction.expectedDefensivePoints))
            PredictionBreakdownRow("نقاط البونص المتوقعة (Bonus)", "+%.2f".format(java.util.Locale.US, prediction.expectedBonusPoints))

            Spacer(Modifier.height(10.dp))

            // Confidence Progress
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "مؤشر موثوقية البيانات التقديرية",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "%.0f%%".format(java.util.Locale.US, prediction.confidenceScore * 100),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { prediction.confidenceScore.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
fun PredictionBreakdownRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
