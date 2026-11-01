package com.omni.downloader.ui.design

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn

/**
 * 全局间距与圆角令牌。
 * 统一使用 4dp 栅格，避免各页面出现 13dp / 17dp 之类的散乱数值。
 */
object OmniSpacing {
    val xxs = 2
    val xs = 4
    val sm = 8
    val md = 12
    val lg = 16
    val xl = 20
    val xxl = 24
    val xxxl = 32
}

object OmniRadius {
    val chip = 999
    val small = 10
    val medium = 14
    val card = 18
    val sheet = 24
}

/**
 * 动画规格。
 *
 * 切页动画直接对齐 Mihon 的实现（material-motion-compose 的 materialFadeThrough）：
 * - 总时长 200ms
 * - 前 35%（70ms）只做淡出，使用 FastOutLinearInEasing
 * - 后 65%（130ms）做淡入，使用 LinearOutSlowInEasing，并延迟 70ms 启动
 * - Mihon 在 Tab 切换时传入 initialScale = 1f，因此实际没有任何缩放位移
 *
 * 页面序列淡出/淡入；RetainedTabHost 同时只放置一个页面，保留各页状态并暂停隐藏订阅。
 */
object OmniMotion {
    const val FAST = 160
    const val NORMAL = 220
    const val MEDIUM = 300
    const val SLOW = 420

    /** 与 Mihon HomeScreen.TabFadeDuration 一致。 */
    const val TAB_DURATION = 200

    /** materialFadeThrough 拆解后的三段时长：旧页淡出 70ms，新页延迟 70ms 后淡入 130ms。 */
    const val TAB_FADE_OUT_MS = 70
    const val TAB_FADE_IN_MS = 130
    const val TAB_FADE_IN_DELAY_MS = 70

    private const val PROGRESS_THRESHOLD = 0.35f

    private val Int.forOutgoing: Int
        get() = (this * PROGRESS_THRESHOLD).toInt()

    private val Int.forIncoming: Int
        get() = this - this.forOutgoing

    val Emphasized: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val Standard: Easing = FastOutSlowInEasing

    /**
     * Mihon materialFadeThroughIn 的原样移植。
     * initialScale 传 1f 时视觉上等于纯淡入。
     */
    fun fadeThroughIn(
        initialScale: Float = 0.92f,
        durationMillis: Int = TAB_DURATION
    ): EnterTransition {
        val incoming = durationMillis.forIncoming
        val delay = durationMillis.forOutgoing
        return fadeIn(
            animationSpec = tween(
                durationMillis = incoming,
                delayMillis = delay,
                easing = LinearOutSlowInEasing
            )
        ) + scaleIn(
            animationSpec = tween(
                durationMillis = incoming,
                delayMillis = delay,
                easing = LinearOutSlowInEasing
            ),
            initialScale = initialScale
        )
    }

    /**
     * Mihon materialFadeThroughOut 的原样移植：仅淡出，时长 70ms。
     */
    fun fadeThroughOut(durationMillis: Int = TAB_DURATION): ExitTransition = fadeOut(
        animationSpec = tween(
            durationMillis = durationMillis.forOutgoing,
            delayMillis = 0,
            easing = FastOutLinearInEasing
        )
    )
}
