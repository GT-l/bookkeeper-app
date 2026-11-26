package com.azu.bookkeeper.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azu.bookkeeper.ui.common.AppTopBar
import com.azu.bookkeeper.core.QuickGestureCodes
import com.azu.bookkeeper.core.ShakeActions
import com.azu.bookkeeper.core.ShakeSensitivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 快捷手势选择页：只列本机真正支持的手势，不启用触摸探索模式，不干扰正常操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickGestureSettingsScreen(
    viewModel: QuickGestureViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val systemSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshPermission() }

    Scaffold(topBar = { AppTopBar("快捷手势") }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                "选一个手势触发「记一笔」。摇一摇独立运行；指纹手势需开启读屏服务。不会再出现系统悬浮球。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = state.selected == option.code,
                            enabled = option.supported,
                            onClick = { viewModel.select(option.code) }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.selected == option.code,
                        onClick = null,
                        enabled = option.supported
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(
                            text = option.label + if (option.supported) "" else "（本机不支持）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (state.selected == QuickGestureCodes.SHAKE) {
                Text(
                    "摇一摇触发后：",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    listOf(ShakeActions.OPEN_FORM, ShakeActions.SCREEN_CAPTURE).forEach { action ->
                        OutlinedButton(
                            onClick = { viewModel.setShakeAction(action) },
                            modifier = Modifier.weight(1f).padding(end = 6.dp)
                        ) {
                            Text(
                                ShakeActions.label(action),
                                color = if (state.shakeAction == action) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
                Text(
                    "识屏失败或未授权时会自动打开表单，让你手动输入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "灵敏度（容易误触就调「稳」）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    ShakeSensitivity.ALL.forEach { level ->
                        OutlinedButton(
                            onClick = { viewModel.setShakeSensitivity(level) },
                            modifier = Modifier.weight(1f).padding(end = 6.dp)
                        ) {
                            Text(
                                ShakeSensitivity.label(level),
                                color = if (state.shakeSensitivity == level) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
                Text(
                    ShakeSensitivity.description(state.shakeSensitivity) +
                        " · 只在前台亮屏时响应，熄屏不会触发",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.needsAccessibility) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "指纹手势需要「读屏服务」在系统中保持开启。摇一摇不用开无障碍。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { systemSettingsLauncher.launch(viewModel.accessibilitySettingsIntent()) },
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("去开启读屏服务") }
                    }
                }
            }

            state.message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp).clickable { viewModel.clearMessage() }
                )
            }
        }
    }
}
