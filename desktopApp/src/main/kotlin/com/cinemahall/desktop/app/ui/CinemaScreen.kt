package com.cinemahall.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cinemahall.core.media.MediaCheck
import com.cinemahall.core.media.MismatchReason
import com.cinemahall.core.model.Member
import com.cinemahall.core.model.Room
import com.cinemahall.core.model.RoomSummary
import com.cinemahall.core.network.SignalErrors
import com.cinemahall.core.network.SignalException
import com.cinemahall.core.network.SignalState
import com.cinemahall.core.rtc.CallAudioState
import com.cinemahall.core.rtc.PeerState
import com.cinemahall.core.session.CinemaMediaCheck
import com.cinemahall.desktop.app.DEFAULT_SIGNAL_ENDPOINT
import com.cinemahall.desktop.app.LocalCinemaColors
import com.cinemahall.desktop.app.Log
import com.cinemahall.desktop.app.PlayerController
import com.cinemahall.desktop.app.media.DesktopFileSource
import com.cinemahall.desktop.app.net.DesktopModule
import com.cinemahall.desktop.library.DesktopMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * 影厅页（W7-C5）。
 *
 * ── 页面三段，按「有没有连上 / 有没有进房」自动切换 ─────────────────────────
 *  1. 没连信令 → 地址 + 连接按钮；
 *  2. 连上但不在房里 → 建房 / 加入 / 房间清单；
 *  3. 在房里 → **左侧播放区 + 右侧常驻成员栏**（这一页的主角）。
 *
 * ── 右侧栏为什么必须常驻而不是抽屉 ──────────────────────────────────────────
 * 影厅里有三件事需要**一直看得见**：还有谁在、谁能控制、我是不是在旁听。
 * 做成抽屉就等于「出问题时先猜一下是不是自己把面板关了」。宽度固定 332dp —— 再窄
 * 放不下「昵称 + 房主徽标 + 在线点」一行，再宽会把画面挤成 4:3。
 *
 * ── 本页**不做**什么（避免读代码的人误判）─────────────────────────────────────
 * 播放同步引擎（`CinemaSyncEngine`）本轮**没有接入**：本页的控制条只动本机播放，
 * 不会广播给房主/成员。所以这一轮能承诺的是三件事 —— 成员看得见、控制权看得见、
 * 片源不一致的人被真的挡住（不许本地自己播）。真正的「一起播」在 C6 之后单独做，
 * 见 `docs/Windows-W7-交接审核.md` 的已知限制。
 */
@Composable
fun CinemaScreen(
    module: DesktopModule?,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    connecting: Boolean,
    connectError: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    controller: PlayerController,
    movie: DesktopMovie?,
    volume: Float,
    autoplay: Boolean,
    onVolumeChange: (Float) -> Unit,
    onOpenFile: () -> Unit,
) {
    val colors = LocalCinemaColors.current

    if (module == null) {
        ConnectPanel(
            endpoint = endpoint,
            onEndpointChange = onEndpointChange,
            connecting = connecting,
            error = connectError,
            onConnect = onConnect,
        )
        return
    }

    val signalState by module.signaler.state.collectAsState()
    val room by module.signaler.room.collectAsState()

    // 房间视图每次变化都要喂给玩法（判房主、换房复位通话意图）。放在这里而不是
    // 各个按钮的回调里：`peer_joined` / `room_closed` 不会经过任何按钮。
    // 顺带把每次变化记一行：出问题时「房主看不到刚进来的人」这类症状，第一个要看的
    // 就是「本机这份 room 到底有过几个人」。
    LaunchedEffect(room) {
        Log.info(
            "[cinema] room 更新 room=${room?.roomId ?: "-"} members=${room?.members?.size ?: 0} " +
                "host=${room?.hostUserId?.value ?: "-"}",
        )
        module.onRoomChanged()
    }

    val current = room
    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (current == null) {
            LobbyPanel(
                module = module,
                signalState = signalState,
                nickname = nickname,
                onNicknameChange = onNicknameChange,
                onDisconnect = onDisconnect,
                connectError = connectError,
            )
        } else {
            HallPanel(
                module = module,
                room = current,
                controller = controller,
                movie = movie,
                volume = volume,
                autoplay = autoplay,
                onVolumeChange = onVolumeChange,
                onOpenFile = onOpenFile,
            )
        }
    }
}

// ── ① 未连接 ────────────────────────────────────────────────────────────────

@Composable
private fun ConnectPanel(
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    connecting: Boolean,
    error: String?,
    onConnect: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.width(520.dp).padding(top = 48.dp)) {
            Text("影厅", style = MaterialTheme.typography.headlineMedium, color = colors.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "还没有连接信令服务器。连上之后才能建房或加入房间；只在本机看电影不需要这一步。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
            )
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChange,
                label = { Text("信令服务地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "默认 $DEFAULT_SIGNAL_ENDPOINT（本机开发服务）。连朋友那台机器就填它的局域网地址，" +
                    "形如 ws://192.168.1.10:8080。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onConnect, enabled = !connecting) {
                    Text(if (connecting) "连接中…" else "连接信令服务器")
                }
                if (error != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(error, color = colors.danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ── ② 大厅：建房 / 加入 / 房间清单 ──────────────────────────────────────────

@Composable
private fun LobbyPanel(
    module: DesktopModule,
    signalState: SignalState,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    onDisconnect: () -> Unit,
    connectError: String?,
) {
    val colors = LocalCinemaColors.current
    val scope = rememberCoroutineScope()

    var roomCode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    var rooms by remember { mutableStateOf<List<RoomSummary>?>(null) }
    var roomsLoading by remember { mutableStateOf(false) }
    var roomsError by remember { mutableStateOf<String?>(null) }

    suspend fun loadRooms() {
        roomsLoading = true
        roomsError = null
        runCatching { module.signaler.listRooms() }
            .onSuccess { rooms = it }
            .onFailure { roomsError = friendlySignalError(it) }
        roomsLoading = false
    }

    // 连上并且不在房里才拉清单：在房里拉没有意义，而「刚退房」恰恰必须重拉
    // （房间表刚刚变过，手上这份已经过期）。
    LaunchedEffect(signalState) {
        if (signalState is SignalState.Online && module.signaler.room.value == null) loadRooms()
    }

    fun act(success: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            lastError = null
            runCatching { block() }
                .onSuccess { notice = success }
                .onFailure { lastError = friendlySignalError(it) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("大厅", style = MaterialTheme.typography.headlineSmall, color = colors.ink)
            Spacer(Modifier.width(12.dp))
            Text(signalStateText(signalState), style = MaterialTheme.typography.labelMedium, color = colors.ink3)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDisconnect) { Text("断开") }
        }
        Spacer(Modifier.height(16.dp))

        // 连接期 / 自动进房的失败也要在这里说一声：这一页没有「连接」按钮，
        // 错误只挂在连接面板上的话，用户会看到一片空大厅而不知道为什么。
        if (connectError != null) {
            Text(connectError, style = MaterialTheme.typography.bodySmall, color = colors.danger)
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // ── 建房 / 加入 ────────────────────────────────────────────────
            Column(Modifier.width(430.dp)) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onNicknameChange,
                    label = { Text("昵称（对方看到的名字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = roomCode,
                    onValueChange = { roomCode = it.uppercase().filter { c -> !c.isWhitespace() } },
                    label = { Text("房间号（6 位，留空 = 服务端随机）") },
                    singleLine = true,
                    isError = roomCode.isNotEmpty() && !isRoomCode(roomCode),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !busy && (roomCode.isEmpty() || isRoomCode(roomCode)),
                        onClick = {
                            // 昵称必须在进房**之前**交给玩法：`cinema.hello` 里带的就是它，
                            // 晚一步发出去的就是空名字（Android 侧踩过同一条）。
                            module.handler.localNickname = nickname
                            act("已创建房间") {
                                val created = module.signaler.createRoom(
                                    nickname = nickname,
                                    password = password.takeIf { it.isNotBlank() },
                                    roomId = roomCode.takeIf { it.isNotBlank() },
                                )
                                copyToClipboard(created.roomId)
                            }
                        },
                    ) { Text("创建房间") }

                    OutlinedButton(
                        enabled = !busy && isRoomCode(roomCode),
                        onClick = {
                            module.handler.localNickname = nickname
                            act("已加入房间") {
                                module.signaler.joinRoom(
                                    roomCode,
                                    nickname,
                                    password.takeIf { it.isNotBlank() },
                                )
                            }
                        },
                    ) { Text("加入房间") }

                    if (busy) {
                        Text("处理中…", style = MaterialTheme.typography.labelSmall, color = colors.ink3)
                    }
                }
                if (roomCode.isNotEmpty() && !isRoomCode(roomCode)) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "房号必须 6 位，且不含 0 / O / 1 / I / L（这几个字符在别的字体里会认错）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.danger,
                    )
                }
                if (notice != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(notice!!, style = MaterialTheme.typography.bodySmall, color = colors.success)
                }
                if (lastError != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(lastError!!, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                }
            }

            // ── 房间清单 ───────────────────────────────────────────────────
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("当前房间", style = MaterialTheme.typography.titleSmall, color = colors.ink)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (roomsLoading) "拉取中…" else "${rooms?.size ?: 0} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.ink3,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !roomsLoading, onClick = { scope.launch { loadRooms() } }) {
                        Text("刷新")
                    }
                }
                if (roomsError != null) {
                    Text(roomsError!!, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                } else if (rooms.isNullOrEmpty()) {
                    Text(
                        if (signalState is SignalState.Online) "现在没有开着的房间。建房之后把房号念给对方。" else "连上信令服务器之后才会列出房间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.ink3,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rooms!!, key = { it.roomId }) { summary ->
                            RoomRow(summary) {
                                module.handler.localNickname = nickname
                                roomCode = summary.roomId
                                lastError = null
                                notice = "已选中房间 ${summary.roomId}；有密码的话填上再点「加入房间」。"
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomRow(summary: RoomSummary, onPick: () -> Unit) {
    val colors = LocalCinemaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(10.dp))
            .border(1.dp, colors.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(summary.roomId, fontFamily = FontFamily.Monospace, color = colors.ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(
            "房主 ${summary.hostNickname} · ${summary.memberCount}/${summary.maxSeats} 人" +
                if (summary.hasPassword) " · 有密码" else "",
            style = MaterialTheme.typography.labelSmall,
            color = colors.ink3,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onPick) { Text("选中") }
    }
}

// ── ③ 影厅：播放区 + 右侧常驻成员栏 ────────────────────────────────────────

@Composable
private fun HallPanel(
    module: DesktopModule,
    room: Room,
    controller: PlayerController,
    movie: DesktopMovie?,
    volume: Float,
    autoplay: Boolean,
    onVolumeChange: (Float) -> Unit,
    onOpenFile: () -> Unit,
) {
    val colors = LocalCinemaColors.current
    val callAudio by module.handler.callAudio.collectAsState()
    val mediaCheck by module.handler.mediaCheck.collectAsState()
    val linkState by module.link.state.collectAsState()
    val signalState by module.signaler.state.collectAsState()

    val isHost = room.hostUserId == module.myUserId
    val listening = !isHost && mediaCheck.verdict is MediaCheck.Mismatch

    // 本机片源摘要：进房、换片、模块就绪都会重跑一次。
    // 判据放在 `movie.path` 上而不是「点了选片按钮」上 —— 从影片库挑片进来的路径
    // 根本不会经过影厅页的按钮。
    LaunchedEffect(movie?.path, module) {
        val picked = movie ?: return@LaunchedEffect
        val signature = withContext(Dispatchers.IO) { DesktopFileSource.fingerprint(File(picked.path)) }
        if (signature == null) {
            Log.warn("[cinema] 算不出片源摘要：${picked.path}")
            return@LaunchedEffect
        }
        // `onLocalSignatureReady` 会顺手向对端宣告一次；链路还没通时它返回 false，
        // 由协调者在 DataChannel OPEN 时补发，这里不用重试。
        withContext(Dispatchers.Swing) { module.handler.onLocalSignatureReady(signature) }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            SourceBanner(
                isHost = isHost,
                check = mediaCheck,
                onOpenFile = onOpenFile,
            )
            PlayerPane(
                controller = controller,
                movie = movie,
                volume = volume,
                autoplay = autoplay,
                onVolumeChange = onVolumeChange,
                onOpenFile = onOpenFile,
                modifier = Modifier.weight(1f),
                // 旁听者**不许本地自己播**（《多人房间改造方案》§6 的硬规则）：
                // 边听语音边看自己那份不同的片源，比停播更糟 —— 两人看到的时间轴对不上，
                // 却都以为自己看的是同一场。
                interactive = !listening,
            )
        }

        Column(
            Modifier
                .width(332.dp)
                .fillMaxHeight()
                .background(colors.surface2)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("房间号", style = MaterialTheme.typography.labelMedium, color = colors.ink3)
                Spacer(Modifier.width(10.dp))
                Text(
                    room.roomId,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.ink,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { copyToClipboard(room.roomId) }) { Text("复制房间号") }
                TextButton(onClick = { module.resendSignature() }) { Text("重发片源") }
            }
            Text(
                "玩法 ${room.mode} · ${room.members.size} 人 · " + signalStateText(signalState),
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3,
            )

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = colors.line)
            Spacer(Modifier.height(10.dp))

            Text("成员 · ${room.members.size}", style = MaterialTheme.typography.titleSmall, color = colors.ink)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(room.members, key = { it.userId.value }) { member ->
                    MemberRow(
                        member = member,
                        isHost = member.userId == room.hostUserId,
                        isMe = member.userId == module.myUserId,
                        // 房主自己那一格在 `room_created` 快照里可能没有昵称（服务端只保证
                        // 加入者带昵称），此时用本机昵称兜住 —— 总比显示一串 userId 好读。
                        myNickname = module.handler.localNickname,
                        awaitingSource = !isHostOf(member, room) && mediaCheck.remote == null,
                    )
                }
            }
            if (room.members.size < 2) {
                Text(
                    "还差一个人。把房间号念给对方，让他在另一台机器上「加入房间」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink3,
                )
                Spacer(Modifier.height(8.dp))
            }

            HorizontalDivider(color = colors.line)
            Spacer(Modifier.height(10.dp))
            CallPanel(
                audio = callAudio,
                onToggle = { enabled -> module.handler.setCallEnabled(enabled, "影厅面板") },
                onVolume = { module.handler.setCallVolume(it) },
            )

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = colors.line)
            Spacer(Modifier.height(10.dp))
            StatusLine("链路", peerStateText(linkState), if (linkState is PeerState.Failed) colors.danger else colors.ink2)
            StatusLine(
                "控制权",
                if (isHost) "房主 · 可控制" else "成员 · 受控",
                if (isHost) colors.accent else colors.ink2,
            )
            StatusLine("片源", sourceStatusText(mediaCheck, isHost), colors.ink2)
            TextButton(onClick = { module.leaveRoom() }) { Text("离开房间") }
        }
    }
}

@Composable
private fun MemberRow(
    member: Member,
    isHost: Boolean,
    isMe: Boolean,
    myNickname: String,
    awaitingSource: Boolean,
) {
    val colors = LocalCinemaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(if (member.online) colors.success else colors.ink3, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            member.nickname.ifBlank {
                if (isMe && myNickname.isNotBlank()) myNickname else member.userId.value.take(8)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (member.online) colors.ink else colors.ink3,
            fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.width(6.dp))
        if (isHost) Badge("房主", colors.accent)
        if (isMe) Badge("我", colors.ink3)
        Spacer(Modifier.weight(1f))
        Text(
            when {
                !member.online -> "离线"
                awaitingSource -> "等片源"
                else -> "在看"
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.ink3,
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * 片源一致性横幅 —— 影厅页唯一会**改变玩法**的提示。
 *
 * 三种「不匹配」说三句不同的话，因为用户要做的事不同：房主要知道「他只能旁听」，
 * 成员要知道「不是你的播放器坏了，是片源不同」，还没上报的人要知道「再等等」。
 */
@Composable
private fun SourceBanner(isHost: Boolean, check: CinemaMediaCheck, onOpenFile: () -> Unit) {
    val colors = LocalCinemaColors.current
    val verdict = check.verdict

    val tone: Color
    val title: String
    val detail: String
    when {
        verdict is MediaCheck.Mismatch -> {
            tone = colors.danger
            val reason = mismatchText(verdict.reason)
            if (isHost) {
                title = "对方片源与你不一致，他将只能旁听"
                detail = "$reason。语音不受影响；他换一部一样的片之后会自动回到同步组。"
            } else {
                title = "你的片源与房主不一致，无法同步观看"
                detail = "$reason。你仍可语音旁听；换一部和房主一样的片就能加入。"
            }
        }

        check.bothKnown -> {
            tone = colors.success
            title = "片源一致：${check.local?.fileName ?: "-"}"
            detail = "两端文件名、大小、首/中/尾三段抽样哈希全部相同。"
        }

        check.local == null -> {
            tone = colors.ink3
            title = "还没有选片"
            detail = "先在影片库选一部片，本机会把摘要发给${if (isHost) "对方" else "房主"}核对。"
        }

        else -> {
            tone = colors.ink3
            title = "已上报本机片源，等待对方"
            detail = "对端还没把摘要发过来（链路没通、或他还没选片）。"
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = tone, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.ink2)
        }
        if (verdict is MediaCheck.Mismatch && !isHost) {
            OutlinedButton(onClick = onOpenFile) { Text("换一部片") }
        }
    }
}

@Composable
private fun CallPanel(
    audio: CallAudioState,
    onToggle: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
) {
    val colors = LocalCinemaColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("语音", style = MaterialTheme.typography.titleSmall, color = colors.ink)
        Spacer(Modifier.weight(1f))
        Button(onClick = { onToggle(!audio.requested) }) {
            Text(if (audio.requested) "关闭麦克风" else "开启通话")
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        callStateText(audio),
        style = MaterialTheme.typography.labelSmall,
        color = when {
            audio.error != null -> colors.danger
            audio.sending && audio.receiving -> colors.success
            else -> colors.ink3
        },
    )
    // 对端的音频 m 行是 recvonly（协商成了「只收不发」）时，再怎么等也不会有声音：
    // 这一格就是把「听不见」从「再试试」变成「去对面那台机器上看」。
    if (audio.remoteSending == false) {
        Text(
            "对端的音频协商成「只收不发」，等再久也不会有声音 —— 问题在他那台机器上。",
            style = MaterialTheme.typography.labelSmall,
            color = colors.danger,
        )
    }
    Text(
        "本机发送 ${audio.audioPacketsSent ?: "-"} · 收到 ${audio.audioPacketsReceived ?: "-"} 包",
        style = MaterialTheme.typography.labelSmall,
        color = colors.ink3,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("对端音量", style = MaterialTheme.typography.labelSmall, color = colors.ink3)
        Slider(
            value = audio.volume,
            onValueChange = onVolume,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text("${(audio.volume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = colors.ink2)
    }
}

@Composable
private fun StatusLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalCinemaColors.current.ink3)
        Spacer(Modifier.width(10.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

// ── 文案 ────────────────────────────────────────────────────────────────────

private fun isHostOf(member: Member, room: Room): Boolean = member.userId == room.hostUserId

private fun isRoomCode(value: String): Boolean =
    value.length == 6 && value.all { it in '2'..'9' || (it in 'A'..'Z' && it != 'O' && it != 'I' && it != 'L') }

private fun signalStateText(state: SignalState): String = when (state) {
    is SignalState.Connecting -> "信令：连接中"
    is SignalState.Disconnected -> "信令：未连接"
    is SignalState.Reconnecting -> "信令：重连中"
    is SignalState.Online -> "信令：在线"
}

private fun peerStateText(state: PeerState): String = when (state) {
    is PeerState.Idle -> "未建立"
    is PeerState.Negotiating -> "协商中"
    is PeerState.Connected -> "已连接（P2P）"
    is PeerState.Failed -> "失败：${state.reason}"
    is PeerState.Closed -> "已断开"
}

private fun callStateText(audio: CallAudioState): String {
    audio.error?.let { return it }
    return when {
        !audio.requested -> "未开启（对端听不到你）"
        audio.sending && audio.receiving -> "双向通话中"
        audio.sending -> "已开麦 · 还没收到对端语音"
        audio.receiving -> "只能听（本机麦克风没接上）"
        else -> "已请求开启 · 等链路"
    }
}

private fun sourceStatusText(check: CinemaMediaCheck, isHost: Boolean): String = when (val verdict = check.verdict) {
    is MediaCheck.Match -> "一致 · ${check.local?.fileName ?: "-"}"
    is MediaCheck.Mismatch -> if (isHost) "对端不一致（他旁听）" else "不一致（你旁听）"
    null -> if (check.local == null) "未上报" else "等对端"
}

private fun mismatchText(reason: MismatchReason): String = when (reason) {
    is MismatchReason.FileName -> "文件名不同：本机「${reason.local}」/ 对端「${reason.remote}」"
    is MismatchReason.Size -> "大小不同：本机 ${formatBytes(reason.localBytes)} / 对端 ${formatBytes(reason.remoteBytes)}"
    is MismatchReason.Block -> "${reason.label}的抽样哈希不同"
    is MismatchReason.BlockCount -> "分块数不同：本机 ${reason.localCount} / 对端 ${reason.remoteCount}"
}

private fun friendlySignalError(error: Throwable): String = when (error) {
    is SignalException -> when (error.code) {
        SignalErrors.CLIENT_NOT_CONNECTED -> "还没连上信令服务器"
        SignalErrors.CLIENT_TIMEOUT -> "服务端没有回应（超时）"
        SignalErrors.CLIENT_BAD_ENDPOINT -> "信令地址格式不对，形如 ws://host:port"
        SignalErrors.ROOM_CODE_TAKEN -> "这个房号已经被占用了，换一个"
        SignalErrors.ROOM_NOT_FOUND -> "房间不存在（可能已经解散）"
        SignalErrors.ROOM_FULL -> "房间人数已满"
        SignalErrors.BAD_PASSWORD -> "密码不对"
        SignalErrors.RATE_LIMITED, SignalErrors.CREATE_RATE_LIMITED -> "操作太频繁，等几秒再试"
        else -> "${error.code}：${error.message ?: "未知错误"}"
    }

    else -> error.message ?: error::class.java.simpleName
}

private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        Log.info("[cinema] 已复制：$text")
    }
}
