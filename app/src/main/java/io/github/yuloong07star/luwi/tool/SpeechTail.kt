package io.github.yuloong07star.luwi.tool

/**
 * 「她还在出声吗」这条判据的算术部分 —— **纯函数, 不碰安卓**, 判据在 `SpeechTailTest`
 *
 * 2026-10-16 主人报的那条: "正在说有时会跟随手机其它应用播放而显示"。原来那条判据 (`LwSpeak.talking`)
 * 在"引擎报完之后"那一段里整体返回 `AudioManager.isMusicActive` —— 那是**全系统 STREAM_MUSIC 的活跃
 * 度**, 不看是谁在放: 于是任何一次朗读之后那段预算窗 (350 ms/字 + 4 s, 封顶 25 s) 里, 别的应用一放
 * 音乐/视频, 球上就写「正在说」, 而窗口长度与本句真实播了多久无关
 *
 * 现在拆成三条各自说得准的答案:
 *
 * | 引擎 | 「还在放」由什么说了算 |
 * | :-- | :-- |
 * | 自带 (sherpa-onnx, `LwTts`) | 它自己的 `MediaPlayer` 就在本进程, 播放标记就是精确答案 |
 * | 在线 (Edge / API, `LwVoiceClip`) | 同上 (两条共用一个 `MediaPlayer`) |
 * | 系统 (`TextToSpeech`) | 播放器在引擎进程里, 看不到 —— 于是**给它一份专属属性** ([SystemSpeech]), 只认"带这份属性的活跃播放", 而 [budgetMs] 只当上限 |
 *
 * **`isMusicActive` 从这条链上整个删掉了**: 宁可「正在说」多亮一会儿 (有上限), 也不能被别人的音乐带起来
 */
internal object SpeechTail {

    /** 一个汉字在正常语速下两百多毫秒, 语速 2.0 还要减半: 按 350 ms 估, 再加 4 s 余量 */
    const val TAIL_MS_PER_CHAR = 350L
    const val TAIL_MARGIN_MS = 4_000L
    const val TAIL_MAX_MS = 25_000L

    /**
     * 这一句念完之后"可能还在响"的预算
     *
     * **它只是上限**: 系统那条引擎的 `onDone` 常常在"合成完了"就报 (合成比实时快), 所以引擎报完之后
     * 还要留一段; 而这一段的真实长度由 [holding] 里那两条精确判据说, 到点就放行
     */
    fun budgetMs(text: String): Long =
        (text.length * TAIL_MS_PER_CHAR + TAIL_MARGIN_MS).coerceAtMost(TAIL_MAX_MS)

    /** 预算还没过期吗 (系统那条的"可能还在响"窗) */
    fun inTail(now: Long, tailUntil: Long): Boolean = tailUntil > now

    /**
     * 这一拍到底算不算"在说"
     *
     * @param now 现在
     * @param enginePlaying 自带 / 在线那两条的播放标记 (进程内, 精确)
     * @param utterancePending 系统那条还挂着一条没收到 `onDone` / `onError` 的 utterance
     * @param tailUntil 系统那条的预算到什么时候 (见 [budgetMs])
     * @param ownPlayback 活跃播放里有没有**我们给引擎那一份属性**的那一条 (见 `SystemSpeech`)
     */
    fun holding(
        now: Long,
        enginePlaying: Boolean,
        utterancePending: Boolean,
        tailUntil: Long,
        ownPlayback: Boolean,
    ): Boolean = enginePlaying || utterancePending || (inTail(now, tailUntil) && ownPlayback)
}
