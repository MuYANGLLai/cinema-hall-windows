package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.cinemahall.core.account.AccountProfile
import com.cinemahall.core.account.DeviceInfo
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.app.Log
import com.cinemahall.desktop.app.account.AccountPhase
import com.cinemahall.desktop.app.account.DesktopAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页里的「账号」面板（与安卓端 `AccountSettingsSection` 对齐）。
 *
 * 能做的四件事：改昵称、看设备列表并移除、生成恢复码、退出登录。
 *
 * ── 为什么昵称要在这里改而不是在影厅大厅 ────────────────────────────────────
 * 2026-11-07 起「房间里的名字 = 账号昵称」是一条硬口径（大厅的输入框已删掉）。
 * 于是「想换名字」这件事只剩这一个出口 —— 改完账号名，两台设备上看到的都变，
 * 不会出现「手机叫小张、电脑叫 DESKTOP-XXX」这种对端认不出同一个人的情况。
 *
 * ── 为什么所有动作都包 `runCatching` ────────────────────────────────────────
 * 这些调用都会走网络。失败时最要紧的是**说清楚是哪种失败**（token 失效 / 网络 /
 * 服务端拒绝），所以错误一律进 [error] 显示在原地面板里，而不是弹一个只闪一下的 toast。
 */
@Composable
fun AccountPanel(account: DesktopAccount) {
    val colors = LocalCinemaColors.current
    val scope = rememberCoroutineScope()
    val phase = account.phase

    val profileFlow = remember(account) {
        account.profile ?: MutableStateFlow<AccountProfile?>(null)
    }
    val profile by profileFlow.collectAsState()

    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 进面板拉一次资料：设备列表不在 `phase` 里，不拉就只有名字没有设备。
    LaunchedEffect(account, phase) {
        if (phase is AccountPhase.Ready) {
            runCatching { account.refreshProfile() }
                .onFailure { error = "设备列表拉取失败：${it.message ?: it::class.java.simpleName}" }
        }
    }

    fun act(success: String? = null, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            notice = null
            runCatching { block() }
                .onSuccess { if (success != null) notice = success }
                .onFailure { error = it.message ?: it::class.java.simpleName }
            busy = false
        }
    }

    when (val current = phase) {
        AccountPhase.Loading -> Text("账号：校验中…", color = colors.ink3)

        is AccountPhase.NeedsActivation -> {
            // 走到这里说明已经回激活页了（`AppRoot` 的门在更外层），面板只做说明 ——
            // 正常情况下用户看不到这一支。
            Text(
                "当前没有登录。重新激活后会回到这里。",
                color = colors.ink3,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }

        is AccountPhase.Ready -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(current.displayName, color = colors.ink, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                    Text(
                        "设备 ${shortId(current.deviceId)}" + if (current.verified) " · 已在服务端校验" else " · 离线启动（用本地凭据）",
                        color = colors.ink3,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        renameText = current.displayName
                        renaming = true
                    },
                ) { Text("改昵称") }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "房间成员表里显示的就是这个名字（两端一致）。",
                color = colors.ink3,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )

            Spacer(Modifier.height(12.dp))
            val devices = profile?.devices ?: emptyList()
            Text(
                "设备 · ${devices.size}" + if (profile == null) "（拉取中…）" else "",
                color = colors.ink2,
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            devices.forEach { device ->
                DeviceRow(
                    device = device,
                    busy = busy,
                    onRevoke = {
                        act("已移除设备 ${device.name.ifBlank { shortId(device.deviceId) }}") {
                            account.revokeDevice(device.deviceId)
                        }
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        act(block = {
                            recoveryCode = account.createRecoveryCode()
                        })
                    },
                ) { Text("生成恢复码") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(enabled = !busy, onClick = { confirmLogout = true }) {
                    Text("退出登录", color = colors.danger)
                }
            }
        }
    }

    if (notice != null) {
        Spacer(Modifier.height(8.dp))
        Text(notice!!, color = colors.success, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(error!!, color = colors.danger, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("改昵称") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("昵称") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "改完所有设备都会用新名字进房。",
                        color = colors.ink3,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        val next = renameText.trim()
                        renaming = false
                        act("昵称已改为 $next") { account.updateDisplayName(next) }
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }

    recoveryCode?.let { code ->
        AlertDialog(
            onDismissRequest = { recoveryCode = null },
            title = { Text("新的恢复码") },
            text = {
                Column {
                    Text(code, color = colors.accent, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "抄下来存好。换设备时用它恢复账号，只能用一次。",
                        color = colors.ink3,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        copyToClipboardText(code)
                        recoveryCode = null
                    },
                ) { Text("复制并关闭") }
            },
            dismissButton = { TextButton(onClick = { recoveryCode = null }) { Text("关闭") } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录？") },
            text = { Text("本机会清除凭据并回到激活页；其他设备不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        act("已退出登录") { account.logout() }
                    },
                ) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DeviceRow(device: DeviceInfo, busy: Boolean, onRevoke: () -> Unit) {
    val colors = LocalCinemaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface2)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                device.name.ifBlank { shortId(device.deviceId) },
                color = colors.ink,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            Text(
                buildString {
                    if (device.platform.isNotBlank()) append(device.platform)
                    val seen = device.lastSeenAt?.let { relativeTime(it) }
                    if (seen != null) {
                        if (isNotEmpty()) append(" · ")
                        append(seen)
                    }
                }.ifBlank { "-" },
                color = colors.ink3,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
        if (device.current) {
            Text(
                "本机",
                color = colors.accent,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            TextButton(enabled = !busy, onClick = onRevoke) {
                Text("移除", color = colors.danger, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun shortId(value: String): String = if (value.length <= 8) value else value.take(8) + "…"

/** 「3 分钟前 / 2 天前」——设备列表里只需这个精度。 */
private fun relativeTime(epochMs: Long): String {
    val delta = System.currentTimeMillis() - epochMs
    if (delta < 0) return "刚刚"
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / (60 * 24)} 天前"
    }
}

/** 剪贴板写入（与影厅页的 `copyToClipboard` 同一个动作，那边是 private）。 */
private fun copyToClipboardText(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
        Log.info("[account] 恢复码已复制")
    }
}
