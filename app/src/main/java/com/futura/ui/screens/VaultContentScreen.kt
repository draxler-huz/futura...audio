// app/src/main/java/com/futura/ui/screens/VaultContentScreen.kt
package com.futura.ui.screens

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.futura.ui.theme.FuturaColors
import com.futura.ui.viewmodel.VaultContentViewModel

/**
 * Contents of an unlocked vault. Records are not stored yet, so the screen
 * shows an empty state rather than sample data. Locking returns to the list.
 */
@Composable
fun VaultContentScreen(
    onLocked: () -> Unit,
    viewModel: VaultContentViewModel = hiltViewModel()
) {
    val activity = LocalContext.current.findFragmentActivity()

    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FuturaColors.OLEDBlack)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "VAULT · UNLOCKED",
            color = FuturaColors.Emerald,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(16.dp))

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(FuturaColors.SurfaceRaised)
                .border(1.dp, FuturaColors.TextDisabled, RoundedCornerShape(16.dp))
                .padding(32.dp)
        ) {
            Text(
                text = "No protected records yet.",
                color = FuturaColors.TextTertiary,
                fontSize = 14.sp
            )
        }

        Spacer(Modifier.height(24.dp))
        VaultSecuritySettings(activity = activity, viewModel = viewModel)
        Spacer(Modifier.height(24.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, FuturaColors.Cyan, RoundedCornerShape(24.dp))
                .clickable { viewModel.lock(onLocked) }
                .padding(vertical = 14.dp)
        ) {
            Text(text = "Lock vault", color = FuturaColors.Cyan, fontSize = 15.sp)
        }
    }
}
