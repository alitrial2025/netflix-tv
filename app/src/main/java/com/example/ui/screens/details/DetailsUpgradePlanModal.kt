package com.example.ui.screens.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.example.R
import com.example.model.SubscriptionPlans
import com.example.ui.theme.NetflixRed
import kotlinx.coroutines.delay

@Composable
fun UpgradePlanModal(
    currentPlanName: String,
    lockReason: String,
    onDismiss: () -> Unit,
    onUpgradeConfirm: (planId: String, planName: String) -> Unit,
    onWatchTrailer: () -> Unit
) {
    var selectedPlanId by remember { mutableStateOf("plan_standard") }
    val plans = remember {
        SubscriptionPlans.PLANS.filter { it.id == "plan_standard" || it.id == "plan_premium" }
    }
    val initialPlanFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(200)
        try { initialPlanFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            onClick = {},
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color(0xFF181818),
                focusedContainerColor = Color(0xFF181818)
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.5.dp, Color(0xFF333333))),
                focusedBorder = Border(BorderStroke(2.dp, Color.White))
            ),
            modifier = Modifier
                .width(520.dp)
                .wrapContentHeight()
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = stringResource(R.string.upgrade_modal_lock_icon_desc),
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.upgrade_modal_title),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = lockReason,
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for ((index, plan) in plans.withIndex()) {
                        val isSelected = selectedPlanId == plan.id
                        Surface(
                            onClick = { selectedPlanId = plan.id },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(initialPlanFocusRequester) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (isSelected) Color(0xFF2B2B2B) else Color(0xFF202020),
                                focusedContainerColor = Color(0xFF383838)
                            ),
                            border = ClickableSurfaceDefaults.border(
                                border = Border(BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) NetflixRed else Color(0xFF444444))),
                                focusedBorder = Border(BorderStroke(2.dp, Color.White))
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = plan.name,
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (plan.isPopular) {
                                            Box(
                                                modifier = Modifier
                                                    .background(NetflixRed, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.upgrade_modal_most_popular),
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.upgrade_modal_plan_summary, plan.resolution, plan.supportedDevices),
                                        color = Color.Gray,
                                        fontSize = 13.sp
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.upgrade_modal_price_per_month, "KSh ${plan.priceKes}"),
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    var isUpgradeFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = {
                            val targetPlan = plans.find { it.id == selectedPlanId } ?: plans.first()
                            onUpgradeConfirm(targetPlan.id, targetPlan.name)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { isUpgradeFocused = it.isFocused },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = NetflixRed,
                            focusedContainerColor = Color.White
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(if (isUpgradeFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                            focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.upgrade_modal_cta),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    var isWatchTrailerFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = onWatchTrailer,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { isWatchTrailerFocused = it.isFocused },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFF333333),
                            focusedContainerColor = Color.White
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(if (isWatchTrailerFocused) 2.dp else 0.dp, Color.White.copy(alpha = 0.6f))),
                            focusedBorder = Border(BorderStroke(2.5.dp, Color.White))
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.upgrade_modal_watch_trailer),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
