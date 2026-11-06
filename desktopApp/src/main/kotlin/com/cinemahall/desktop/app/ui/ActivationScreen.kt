package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cinemahall.desktop.app.DEFAULT_SIGNAL_ENDPOINT
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.app.account.AccountMessages
import com.cinemahall.desktop.app.account.AccountPhase
import com.cinemahall.desktop.app.account.DesktopAccount
import kotlinx.coroutines.launch

/**
 * 设备激活页（W7-C7）：启动门。
 *
 * ── 为什么它是「门」而不是「设置里的一项」──────────────────────────────────
 * 生产 `AUTH_MODE=required`，没有 bearer token 的 WebSocket 握手会被
 * `verifyCallback` 直接 401。若照旧先建 `DesktopModule` 再连，用户看到的是一串
 * 无意义的「正在重连」，而真正的原因（没有激活）从头到尾没出现过。所以：
 * **没有有效 token 就不建模块、不连信令**，界面停在这一页。
 *
 * ── 为什么这里要能改服务器地址 ──────────────────────────────────────────────
 * 门挡在设置页前面。若内网自建/地址变更，用户必须能在这里改，否则就死锁在
 * 「连不上 → 改不了地址 → 还是连不上」。默认值仍是 `DEFAULT_SIGNAL_ENDPOINT`。
 */
@Composable
fun ActivationScreen(
    account: DesktopAccount,
    currentEndpoint: String,
    onEndpointChange: (String) -> Unit,
    onActivated: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(defaultDeviceName()) }
    var nickname by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showServer by remember { mutableStateOf(false) }
    var serverDraft by remember(currentEndpoint) { mutableStateOf(currentEndpoint) }

    val phase = account.phase
    val notice = when (phase) {
        is AccountPhase.NeedsActivation -> phase.message ?: AccountMessages.notice(phase.reason)
        else -> null
    }

    Box(
        Modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(28.dp)
                .border(1.dp, colors.line2, RoundedCornerShape(16.dp))
                .background(colors.surface, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("设备激活", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Text(
                "这是给朋友用的包：每台设备需要一个入场码才能进影厅。" +
                    "入过码之后会记住，不用每次输入。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )

            if (notice != null) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.danger.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }

            TextField(
                value = code,
                onValueChange = { code = it; error = null },
                singleLine = true,
                label = { Text("入场码") },
                placeholder = { Text("8 位，例如 ABCD-EFGH") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )

            TextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                singleLine = true,
                label = { Text("设备名") },
                placeholder = { Text("这台电脑在账号里显示的名字") },
                modifier = Modifier.fillMaxWidth(),
            )

            TextField(
                value = nickname,
                onValueChange = { nickname = it },
                singleLine = true,
                label = { Text("昵称（可选）") },
                placeholder = { Text("留空则由服务器生成「影友XXXX」") },
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = colors.danger)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (busy) return@Button
                        val cleaned = code.trim()
                        if (cleaned.isEmpty()) {
                            error = "请先填入场码"
                            return@Button
                        }
                        busy = true
                        error = null
                        scope.launch {
                            val failure = account.activate(
                                code = cleaned,
                                deviceName = deviceName.trim().ifBlank { defaultDeviceName() },
                                nickname = nickname.trim().ifBlank { null },
                            )
                            busy = false
                            if (failure == null) onActivated() else error = failure
                        }
                    },
                    enabled = !busy,
                ) { Text(if (busy) "正在激活…" else "激活") }
                if (busy) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showServer = !showServer }, enabled = !busy) {
                    Text(if (showServer) "收起服务器设置" else "服务器地址")
                }
            }

            if (showServer) {
                Text(
                    "默认是内置的生产地址；本机开发服务可用 ws://127.0.0.1:8080。" +
                        "改完需要重新激活。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = serverDraft,
                        onValueChange = { serverDraft = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            val target = serverDraft.trim()
                            if (target.isNotEmpty() && target != currentEndpoint) {
                                error = null
                                code = ""
                                onEndpointChange(target)
                            }
                        },
                        enabled = !busy,
                    ) { Text("应用") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            serverDraft = DEFAULT_SIGNAL_ENDPOINT
                            if (DEFAULT_SIGNAL_ENDPOINT != currentEndpoint) {
                                error = null
                                code = ""
                                onEndpointChange(DEFAULT_SIGNAL_ENDPOINT)
                            }
                        },
                        enabled = !busy,
                    ) { Text("恢复内置") }
                }
            }

            Text(
                "当前服务器：$currentEndpoint",
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3,
            )
        }
    }
}

/**
 * 设备名的默认值：用计算机名。拿不到就退化成「Windows」——
 * 这个值会出现在账号的设备列表里，用户能认出「这是哪台机器」才有意义。
 */
private fun defaultDeviceName(): String =
    System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.name")?.takeIf { it.isNotBlank() }
        ?: "Windows"

/** 启动态（读凭据 / 校验 token）：不连信令，只给一个「正在准备」的反馈。 */
@Composable
fun AccountLoadingScreen() {
    val colors = LocalCinemaColors.current
    Box(
        Modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.height(28.dp).width(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text("正在检查登录状态…", style = MaterialTheme.typography.bodyMedium, color = colors.ink2)
        }
    }
}
