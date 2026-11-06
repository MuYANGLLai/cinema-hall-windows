package com.cinemahall.core.rtc

import com.cinemahall.core.model.UserId
import kotlinx.coroutines.flow.StateFlow

/**
 * 房间级音频会话（契约 §8.3）—— 一份采集、一份路由、一份意图。
 *
 * Phase 1 只放接口；实现 `WebRtcRoomAudioSession` 在 `core:rtc-android`，
 * 多人 P3 落地。
 */
interface RoomAudioSession : CallAudio {

    fun attach(link: PeerLink)

    fun detach(link: PeerLink)

    val perPeer: StateFlow<Map<UserId, CallAudioState>>

    val gate: StateFlow<AudioGateState>
}

/** 关麦闸门：等所有链路都停发之后，才允许释放共享音轨。 */
sealed interface AudioGateState {
    data object Idle : AudioGateState
    data object Stopping : AudioGateState
    data object Stopped : AudioGateState

    /** 超时仍有这些对端没停发；UI 提供「重建全部链路」兜底。 */
    data class Stuck(val pending: Set<UserId>) : AudioGateState
}
