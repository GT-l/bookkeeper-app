package com.azu.bookkeeper.ui.unlock

import android.content.Context
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

/** 应用锁：BiometricPrompt 生物解锁 + 数字密码兜底。 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun AppLockScreen(
    navController: NavController,
    viewModel: AppLockViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    Scaffold(topBar = { TopAppBar(title = { Text("应用锁") }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("请输入数字密码，或使用指纹 / 面容解锁")
            OutlinedTextField(
                value = state.pinInput,
                onValueChange = viewModel::setPinInput,
                label = { Text("数字密码") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Button(onClick = { viewModel.submitPin() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("解锁")
            }
            OutlinedButton(
                onClick = {
                    if (activity != null) {
                        showBiometricPrompt(context, activity, onSuccess = viewModel::unlockByBiometric)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("使用生物识别") }
        }
    }

    if (state.unlocked) {
        LaunchedEffect(Unit) { navController.popBackStack() }
    }
}

private fun showBiometricPrompt(
    context: Context,
    activity: FragmentActivity,
    onSuccess: () -> Unit
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(context),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        }
    )
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("应用锁")
        .setSubtitle("验证指纹或面容以进入少花点儿")
        .setNegativeButtonText("使用数字密码")
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(promptInfo)
}
