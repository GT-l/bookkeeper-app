package com.azu.bookkeeper.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azu.bookkeeper.BuildConfig
import com.azu.bookkeeper.core.update.GithubUpdateChecker
import com.azu.bookkeeper.core.update.UpdateInfo
import com.azu.bookkeeper.core.update.UpdateInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 关于页「检查更新」的状态机：检查 → 下载（带进度）→ 拉起系统安装器。 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class UiState(
        val checking: Boolean = false,
        val downloading: Boolean = false,
        val progress: Int = 0,
        val message: String? = null,
        val messageIsError: Boolean = false,
        val update: UpdateInfo? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun checkForUpdate() {
        if (_ui.value.checking || _ui.value.downloading) return
        // v1.3.5：每次检查更新前清掉上次遗留的安装包（已在安装后自动删除，这里兜底进程被杀的情况）
        runCatching { UpdateInstaller.cleanupOldApks(context) }
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    checking = true, downloading = false, progress = 0,
                    update = null, message = "正在检查 GitHub 更新…", messageIsError = false
                )
            }
            val outcome = withContext(Dispatchers.IO) {
                GithubUpdateChecker.check(BuildConfig.VERSION_NAME)
            }
            if (outcome.error != null) {
                _ui.update {
                    it.copy(
                        checking = false, messageIsError = true,
                        message = "检查失败：${outcome.error}"
                    )
                }
                return@launch
            }
            val update = outcome.update
            if (update == null) {
                _ui.update {
                    it.copy(
                    checking = false, messageIsError = false,
                    message = "已是最新版本 v${BuildConfig.VERSION_NAME}"
                )
                }
                return@launch
            }

            _ui.update {
                it.copy(
                    checking = false,
                    downloading = true,
                    update = update,
                    messageIsError = false,
                    message = "发现 v${update.version}（${GithubUpdateChecker.formatSize(update.sizeBytes)}），正在下载…"
                )
            }
            val dir = File(context.filesDir, "updates")
            val target = File(dir, "bookkeeper-${update.version}-debug.apk")
            val result = withContext(Dispatchers.IO) {
                GithubUpdateChecker.download(update, target) { percent ->
                    _ui.update { it.copy(progress = percent) }
                }
            }
            val apk = result.file
            if (apk == null) {
                _ui.update {
                    it.copy(
                        downloading = false, messageIsError = true,
                        message = "下载失败：${result.error}\n" +
                            "可点下方「用浏览器下载」或「选本地 APK 安装」。Clash 用户请放行 " +
                            "githubusercontent.com，并使用 TUN / 全局模式。"
                    )
                }
                return@launch
            }
            val opened = UpdateInstaller.install(context, apk)
            _ui.update {
                it.copy(
                    downloading = false,
                    messageIsError = !opened,
                    message = if (opened) {
                        "已下载完成，正在打开系统安装器。点系统页面的「安装」即可（同签名可直接覆盖）"
                    } else {
                        "已下载完成，但系统未允许打开安装器。请检查「安装未知应用」权限后重试检查更新"
                    }
                )
            }
        }
    }

    /** 兜底：交给系统浏览器下载，浏览器更容易沿用用户已配置好的代理。 */
    fun openInBrowser() {
        val url = _ui.value.update?.browserUrl ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            _ui.update {
                it.copy(
                    messageIsError = true,
                    message = "没有可用的浏览器，请复制下载链接后到浏览器打开"
                )
            }
        }
    }

    /** 兜底：用户在 PC 上用代理下载 APK 后传进手机，从文件选择器直接安装。 */
    fun installLocalApk(uri: Uri) {
        val ok = UpdateInstaller.installFromUri(context, uri)
        _ui.update {
            it.copy(
                messageIsError = !ok,
                message = if (ok) "已打开系统安装器，点「安装」即可" else "这个文件不是可安装的 APK"
            )
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null, messageIsError = false) }
}
