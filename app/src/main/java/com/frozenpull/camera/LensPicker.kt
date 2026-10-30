package com.frozenpull.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Wide / Normal / Zoom buttons; only the lenses this phone has are shown. */
@Composable
fun LensPicker(plans: Map<Lens, LensPlan>, current: Lens, onPick: (Lens) -> Unit, modifier: Modifier = Modifier) {
    if (plans.size <= 1) return
    Row(
        modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp)).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        plans.forEach { (lens, plan) ->
            FilterChip(
                selected = lens == current, onClick = { onPick(lens) },
                label = { Text("${lens.label} ${plan.shown}") },
                colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = Color(0xFFF5B041), selectedLabelColor = Color.Black),
            )
        }
    }
}
