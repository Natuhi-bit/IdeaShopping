package com.koga.qloverplayer.playback

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.exoplayer.ExoPlayer
import com.koga.qloverplayer.data.Track
import kotlin.math.pow

/**
 * 音量ノーマライゼーション。
 *
 * 考え方（2段構え）:
 * 1. トラックごとのゲイン: 目標LUFS - 実測LUFS (+ 手動gainDb)。
 *    実測値がなければ補正なし（手動ゲインだけを適用）。
 * 2. ゲインの掛け方は符号で分ける。これが大事なところで、以前は
 *    どちらの方向も同じ処理にしていて、小さい曲が持ち上がらなかった。
 *    - ゲインがマイナス（大きい曲を絞る）: ExoPlayer.volume で減衰する。
 *      volume は 0.0〜1.0 しか取れないので、プラス方向の補正には使えない。
 *    - ゲインがプラス（小さい曲を持ち上げる）: LoudnessEnhancer で増幅する。
 *      LoudnessEnhancer は「音量を上げるための」エフェクトで、target gain は
 *      最大でどれだけ増幅するかを mB (100mB = 1dB) で指定する。0mB が増幅なし。
 *      増幅には setEnabled(true) が別途必要で、作っただけではバイパスされた
 *      ままになる（AudioEffect の仕様）。セッションが変わってエフェクトを
 *      作り直したときは、前回のゲインを掛け直す。
 *
 * ライブ配信（ラジオ）は duration が無限で LUFS を事前に測れないため、
 * ライブ時は手動ゲインと LoudnessEnhancer だけが効く。完全なリアルタイム
 * LUFS測定はこの規模では AudioRecord による別途解析が必要になる。
 */
class LoudnessNormalizer(private val player: ExoPlayer) {
    private var enhancer: LoudnessEnhancer? = null
    /** 最後に apply() で計算したゲイン。セッション切替時の掛け直し用 */
    private var lastGainDb: Float = 0f
    private var lastEnabled: Boolean = false

    /** ExoPlayer の audioSessionId が決まったら呼ぶ */
    fun attach(audioSessionId: Int) {
        runCatching { enhancer?.release() }
        enhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        // エフェクトを作り直したので、前回のゲインを掛け直す
        applyEnhancer(lastGainDb, lastEnabled)
    }

    fun apply(track: Track?, enabled: Boolean, targetLufs: Float) {
        if (!enabled || track == null) {
            player.volume = 1f
            lastGainDb = 0f
            lastEnabled = false
            applyEnhancer(0f, false)
            return
        }
        val measured = track.loudnessLufs ?: targetLufs // 未測定なら補正なし
        val gainDb = (targetLufs - measured + track.gainDb).coerceIn(-12f, 12f)
        lastGainDb = gainDb
        lastEnabled = true
        if (gainDb <= 0f) {
            // 大きい曲は絞る: dB -> 線形 10^(dB/20) で音量を下げる
            player.volume = 10f.pow(gainDb / 20f).coerceIn(0f, 1f)
        } else {
            // 小さい曲は持ち上げる: 音量は最大のまま、増幅はエフェクトに任せる
            player.volume = 1f
        }
        applyEnhancer(gainDb, true)
    }

    /**
     * プラスゲインのときだけ LoudnessEnhancer を有効にして増幅する。
     * マイナスゲインは player.volume 側で済んでいるので、ここでは増幅なし
     * （target gain は増幅量の指定で、0mB が増幅なし）にして無効化する。
     * ゲインは ±12dB に丸めてあるので mB にしても最大 1200mB になる。
     */
    private fun applyEnhancer(gainDb: Float, enabled: Boolean) {
        val e = enhancer ?: return
        runCatching {
            if (enabled && gainDb > 0f) {
                e.setTargetGain((gainDb * 100).toInt().coerceIn(0, 1200))
                e.setEnabled(true)
            } else {
                e.setTargetGain(0)
                e.setEnabled(false)
            }
        }
    }

    fun release() {
        runCatching { enhancer?.release() }
        enhancer = null
    }
}
