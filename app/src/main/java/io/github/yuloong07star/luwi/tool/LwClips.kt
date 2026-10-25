package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.yuloong07star.luwi.voice.VoiceState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 预录填音: `assets/clips/` 下那几个 `.ogg` 走 SoundPool, **不经过任何 TTS 引擎**
 *
 * 为什么要有这一条 (`speak op=clip`, 2.7.9 批次 9 T13): 主人那个填音插件原来只能调 `speak`, 而小米
 * 那台机器的系统 TTS 偶发卡 15 s —— 填音迟到甚至不响, 那一句话就白醒了。预录的几段音频走
 * SoundPool 是几十毫秒出声, 不联网、不初始化引擎, 也不受任何一家 ROM 的 TTS 影响
 *
 * 三条纪律 (施工单 §8.2):
 *
 * 1. **必须置 [VoiceState.speaking]** (半双工那道闸): 不置的话 fill 音会被自己的麦克风录回
 *    inbox —— 就是 2026-10-10 那个"她自己的回答被录回去"的形态
 * 2. **不进 [LwSpeak.talking]**: 填音一两秒, 而 `talking` 是"说的时候把麦真关掉"的判据 ——
 *    每响一声关一次麦, 再开一次, 就是那个插件 README 里写的"麦克风反复开关"
 * 3. **出声就返回**: SoundPool 是异步播的, 这一层不等待, 于是通道往返是几十毫秒而不是"音频长度"
 *
 * 素材不随策略: 哪一声、什么时候响、响几次由调用方 (插件) 定, 这里只认文件名。加一段就是往
 * `assets/clips/` 里放一个 `.ogg`, 名字就是 `clip` 参数
 */
internal object LwClips {

    private const val TAG = "LwClips"

    /** 跟随指纹存哪儿 (与 SpeakSettings / VoiceSettings 同一个偏好文件, 全 app 一个) */
    private const val STORE = "luwi"

    /** 上一次合成用的是哪套 音色 + 语速 */
    private const val FOLLOW_KEY = "clips-follow-key"

    /** 素材目录: `assets/` 里那份是随包的底, `filesDir/clips` 里那份是"跟着设置重新合成"的那份 */
    private const val DIR = "clips"

    /** 随包那份的后缀 (施工单里录的是 ogg) */
    private const val EXT = ".ogg"

    /** 跟随合成出来的那份是 Edge 直接回的 mp3 */
    private const val GEN_EXT = ".mp3"

    /** 认得的后缀, 三样 SoundPool 都吃 */
    private val KNOWN_EXT = listOf(".ogg", ".mp3", ".wav")

    /** 同时最多几条: 填音是一声接一声, 4 条足够, 再多就互相盖 */
    private const val MAX_STREAMS = 4

    /** 预载等多久: 四段几百 KB 的 opus, 真机上几十毫秒; 等不到也照常返回, 下一趟自己会好 */
    private const val LOAD_BUDGET_MS = 1_500L

    /** 播放结束那条 scheduled 的记号 (取消旧的那一条用) */
    private val END_TOKEN = Any()

    /** 播完到"闸放开"之间留的余量: 喇叭尾巴比文件长度长一点点 */
    private const val TAIL_MS = 120L

    private val lock = Any()
    private var unwritten: SoundPool? = null

    /** 名字 → SoundPool 的 sampleId (只增不减, 素材变了要重装应用) */
    private val ids = mutableMapOf<String, Int>()

    /** 名字 → 时长 (ms), 播完之后靠它把半双工那道闸放开 */
    private val durations = mutableMapOf<String, Long>()

    /** 正在播的那几条 streamId */
    private val streams = mutableSetOf<Int>()

    /** 每一次出声一个号: 旧的"到点放开闸"不许把新的一声那笔账清掉 */
    private val generation = AtomicInteger(0)

    private val handler = Handler(Looper.getMainLooper())

    /** 现在能放哪几段: `filesDir/clips` 里那份优先, assets 里那份兜底 (按名字排好) */
    fun names(context: Context): List<String> {
        val generated = runCatching {
            generated(context).listFiles()?.mapNotNull { clipNameOf(it.name) }.orEmpty()
        }.getOrDefault(emptyList())
        val shipped = runCatching {
            context.assets.list(DIR)?.mapNotNull { clipNameOf(it) }.orEmpty()
        }.getOrDefault(emptyList())
        return (generated + shipped).filter { it.isNotBlank() }.distinct().sorted()
    }

    /** 一个文件名算不算素材, 算的话回不带后缀的名字 */
    private fun clipNameOf(at: String): String? {
        val name = at.substringAfterLast('/')
        if (KNOWN_EXT.none { name.endsWith(it, ignoreCase = true) }) return null
        return name.substringBeforeLast('.').takeIf { it.isNotBlank() }
    }

    /** 跟随合成出来的那几段放哪 */
    private fun generated(context: Context): File = File(context.filesDir, DIR)

    /**
     * 把四段先读进来 (几十毫秒, 一次)
     *
     * 插件那侧开工前会调一次 `op=status` 把引擎拉起来, 这里顺手把 SoundPool 也填上 —— 于是第一声
     * 填音的往返也是几十毫秒, 而不是"第一次加载素材"的那几百毫秒
     */
    fun warm(context: Context) {
        runCatching { pool(context) }
            .onFailure { Log.w(TAG, "the clips would not preload: ${it.message ?: it}") }
    }

    /**
     * **跟着设置走**: 垫音是预录的, 而主人可以换音色、改语速 —— 那四段随包的 ogg 一换就不是同一个
     * 人的声音了 (2026-10-24 主人: "这音色是和当前音色一致的吗" → "跟随")
     *
     * 做法: 只在 Edge 那条引擎上做, 按当前 音色 + 语速 合成四段到 `filesDir/clips/`, [names] 与
     * [loadOne] 会优先认那份, assets 那四份退成兜底. 合成一次之后**播放仍然是从内存里放**
     * (SoundPool 预载), 响应速度一点没变 —— 变的只是"准备"这一步挪到了改设置之后
     *
     * 什么时候做: 每次 [play] 与 `op=status` 都问一句, 靠指纹 [fingerprint] 判断要不要重来;
     * 指纹存偏好 (与其它设置同一个 `luwi` 文件). 失败**静默退回**上一次那份 (或随包那份), 只记一行
     */
    fun follow(context: Context) {
        if (!SpeakSettings.usesEdge()) return
        val key = fingerprint(SpeakSettings.edgeVoice, SpeakSettings.rate)
        if (key == storedKey(context)) return
        if (!synthesizing.compareAndSet(false, true)) return
        Thread({
            try {
                synthesizeAll(context, SpeakSettings.edgeVoice, SpeakSettings.rate, key)
            } catch (error: Throwable) {
                Log.w(TAG, "the clips would not follow the settings: ${error.message ?: error}")
            } finally {
                synthesizing.set(false)
            }
        }, "lw-clips-follow").apply { isDaemon = true }.start()
    }

    /**
     * 跟随的那个指纹: 音色或语速一变就不一样
     *
     * 算法在 [ClipsKey] (只吃参数的对象), 于是 `ClipsFollowTest` 不必把这一份 object 初始化起来
     */
    internal fun fingerprint(voice: String, rate: Float): String = ClipsKey.of(voice, rate)

    /** 四段各自的句子: 与随包那四份同一个说法 (改它等于换台词) */
    private val TEXTS = linkedMapOf(
        "ok" to "好",
        "thinking" to "让我想想",
        "huh" to "嗯？",
        "wait" to "等一下",
    )

    /** 合成本身在后台线程跑, 同一刻只跑一趟 */
    private val synthesizing = AtomicBoolean(false)

    /**
     * 四段全成功才换: 半新半旧的垫音 (一句新音色、一句旧音色) 比"还没跟上"更怪
     *
     * 先写 `.tmp` 再改名, 于是读的一侧永远看不到半份文件
     */
    private fun synthesizeAll(context: Context, voice: String, rate: Float, key: String) {
        val directory = generated(context).apply { mkdirs() }
        val made = mutableMapOf<String, File>()
        TEXTS.forEach { (name, text) ->
            val bytes = runCatching { LwEdgeTts.bytesOf(text, rate, voice) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "the clip \"$name\" came back empty")
                return@forEach
            }
            val file = File(directory, "$name$GEN_EXT.tmp")
            runCatching {
                file.writeBytes(bytes)
                made[name] = file
            }
        }
        if (made.size < TEXTS.size) {
            made.values.forEach { runCatching { it.delete() } }
            Log.w(TAG, "only ${made.size} of ${TEXTS.size} clips were synthesised; keeping the old ones")
            return
        }
        made.forEach { (name, file) ->
            runCatching { file.renameTo(File(directory, "$name$GEN_EXT")) }
        }
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(FOLLOW_KEY, key).apply()
        invalidate()
        Log.i(TAG, "the clips now follow the settings: $key")
    }

    /** 换掉内存里那一份预载 (生成的文件换了才用) */
    private fun invalidate() {
        synchronized(lock) {
            val old = unwritten
            unwritten = null
            ids.clear()
            durations.clear()
            // 正在响的时候不 release: 那一声要放完, 旧 pool 交给 GC
            if (streams.isEmpty()) runCatching { old?.release() }
        }
    }

    private fun storedKey(context: Context): String = context
        .getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .getString(FOLLOW_KEY, "")
        .orEmpty()

    /** 读数: 现在这套设置该合成成什么样 ("音色|语速") */
    internal fun following(): String = fingerprint(SpeakSettings.edgeVoice, SpeakSettings.rate)

    /** 读数: 生成的那份是按哪套设置合成的 (空 = 还没合成过, 放的是随包那四段) */
    internal fun followed(context: Context): String = storedKey(context)

    /** 现在有没有一段填音在响 (读数用; 不算进 `speakingNow`) */
    fun playingNow(): Boolean = synchronized(lock) { streams.isNotEmpty() }

    /**
     * 放一段: `clip` 是文件名 (不带 `.ogg`), 没点名就回一张清单
     *
     * 返回**不等播完**: `ms` 是这一段自己的长度, 只是给调用方一个参考
     */
    fun play(context: Context, name: String, volume: Float): JsonObject {
        val available = names(context)
        if (name.isBlank()) {
            return buildJsonObject {
                put("played", false)
                put("clips", available.joinToString(", "))
                put("detail", "this call has to name a clip: ${available.joinToString(", ")}")
            }
        }
        if (available.isEmpty()) {
            unavailable("playing the clip \"$name\"", "this build carries no assets/$DIR/*.ogg at all")
        }
        if (name !in available) {
            unavailable(
                "playing the clip \"$name\"",
                "there is no assets/$DIR/$name$EXT; this build carries ${available.joinToString(", ")}",
            )
        }
        val sound = pool(context)
        val id = synchronized(lock) { ids[name] }
            ?: unavailable("playing the clip \"$name\"", "SoundPool did not accept that file")
        val gain = volume.coerceIn(0f, 1f)
        synchronized(lock) {
            // 一声盖一声: 上一条还在响就先停掉, 否则"嗯？"与"让我想想"会叠在一起
            streams.forEach { runCatching { sound.stop(it) } }
            streams.clear()
            val stream = sound.play(id, gain, gain, 1, 0, 1f)
            if (stream <= 0) {
                unavailable("playing the clip \"$name\"", "SoundPool refused to start that stream ($stream)")
            }
            streams += stream
            val run = generation.incrementAndGet()
            // 半双工那道闸: 只在播放这一段时合上, 到点就放开。**不进 talking** 是故意的, 见文件头
            VoiceState.speaking = true
            val length = durations[name] ?: 1_200L
            handler.removeCallbacksAndMessages(END_TOKEN)
            handler.postAtTime(
                {
                    synchronized(lock) {
                        if (run == generation.get()) streams.clear()
                    }
                    if (run == generation.get() && !LwSpeak.speakingNow) VoiceState.speaking = false
                },
                END_TOKEN,
                SystemClock.uptimeMillis() + length + TAIL_MS,
            )
            return buildJsonObject {
                put("played", true)
                put("clip", name)
                put("ms", length)
                put("clips", available.joinToString(", "))
                // 这一句是给调用方看的: 它知道这是一段预录, 不是引擎念的
                put("engine", "clip")
            }
        }
    }

    /**
     * 停下正在响的填音
     *
     * 与 [LwSpeak.stop] 同一条路: 手动掐断 / 新一轮开始都走它。号先加一, 旧的"到点放开闸"就作废,
     * 不会在停止之后反手把 [VoiceState.speaking] 改回来
     */
    fun stop() {
        val sound = synchronized(lock) { unwritten } ?: return
        synchronized(lock) {
            generation.incrementAndGet()
            streams.forEach { runCatching { sound.stop(it) } }
            streams.clear()
            handler.removeCallbacksAndMessages(END_TOKEN)
        }
        if (!LwSpeak.speakingNow) VoiceState.speaking = false
    }

    /**
     * 建好 SoundPool 并预载全部素材, 等它们真的进内存
     *
     * 只做一次 (哪怕失败): 素材不会在运行中变, 反复重试只会让每一趟调用都慢几百毫秒
     */
    private fun pool(context: Context): SoundPool {
        synchronized(lock) {
            unwritten?.let { return it }
            val created = SoundPool.Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // 填音是"助手在出声", 但 USAGE_ASSISTANT 在有的 ROM 上没有独立音量, 用 MEDIA
                        // 与系统里播一段语音同一个声道, 主人才听得见
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            val wanted = names(context)
            val waiting = CountDownLatch(wanted.size.coerceAtLeast(1))
            created.setOnLoadCompleteListener { _, sampleId, status ->
                if (status != 0) Log.w(TAG, "clip $sampleId would not load (status $status)")
                waiting.countDown()
            }
            wanted.forEach { name -> loadOne(context, created, name, waiting) }
            if (wanted.isEmpty()) waiting.countDown()
            val ready = waiting.await(LOAD_BUDGET_MS, TimeUnit.MILLISECONDS)
            if (!ready) Log.w(TAG, "the clips did not finish loading within ${LOAD_BUDGET_MS}ms")
            unwritten = created
            return created
        }
    }

    /** 读一段素材: 跟随合成的那份先、随包的后; 时长先量好 (SoundPool 不提供), 再交给 SoundPool 预载 */
    private fun loadOne(context: Context, sound: SoundPool, name: String, waiting: CountDownLatch) {
        val generated = generated(context).listFiles()?.firstOrNull { clipNameOf(it.name) == name }
        if (generated != null) {
            val length = runCatching { durationOf(generated) }.getOrDefault(0L)
            val id = runCatching { sound.load(generated.absolutePath, 1) }.getOrDefault(0)
            if (id <= 0) {
                Log.w(TAG, "SoundPool would not take ${generated.absolutePath}")
                waiting.countDown()
                return
            }
            synchronized(lock) {
                ids[name] = id
                if (length > 0) durations[name] = length
            }
            return
        }
        val path = "$DIR/$name$EXT"
        val file = runCatching { context.assets.openFd(path) }.getOrNull()
        if (file == null) {
            Log.w(TAG, "there is no $path in this build")
            waiting.countDown()
            return
        }
        val length = runCatching { durationOf(file) }.getOrDefault(0L)
        val id = runCatching { sound.load(file, 1) }.getOrDefault(0)
        runCatching { file.close() }
        if (id <= 0) {
            Log.w(TAG, "SoundPool would not take $path")
            waiting.countDown()
            return
        }
        synchronized(lock) {
            ids[name] = id
            if (length > 0) durations[name] = length
        }
    }

    /** 一段 ogg 有多长 (ms): 播放那一侧没有别的地方能问到它 */
    private fun durationOf(file: android.content.res.AssetFileDescriptor): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 跟随合成出来那一份的时长 (ms) */
    private fun durationOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }
}
