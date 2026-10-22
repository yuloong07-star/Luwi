package io.github.yuloong07star.luwi.sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import io.github.yuloong07star.luwi.channel.CameraOwner
import kotlin.math.abs

/**
 * 扫码用的那台相机 (Camera2 + ZXing)
 *
 * **不用 CameraX**: 这里要的东西一共三件 —— 后摄、一个 YUV 预览流、每 150 ms 解一帧, 而 CameraX 会
 * 为了这三件拖进一整套 use case 与生命周期, 反而更难说清"什么时候开、什么时候关", Camera2 的
 * `openCamera` / `createCaptureSession` 各自只有一处回调, 出错也只剩一个 `onError(error)` 要翻
 * 成一句人话
 *
 * 三条纪律:
 *   · **每一张 Image 都要 close**: `acquireLatestImage` 拿到的缓冲不还回去, 第三帧之后
 *     `acquireLatestImage` 就永远回 null (那一档看起来像"相机卡住了")
 *   · **解码在相机那条线程上做**: 1280x720 的一帧拷成连续数组再交给 ZXing 是几毫秒的事, 放到主线程
 *     就是每 150 ms 卡一下取景
 *   · **回调回主线程**: [start] 给的两个回调都由这里 post 到主线程再叫, 于是界面那一侧不用自己转
 *
 * 相机被谁占着这件事应用里已经有一份账 ([CameraOwner]): 视频模式开着的时候那张表里有人, 那就直接
 * 回一句"先退出视频模式" —— 比等相机服务回 `CAMERA_IN_USE` 更快, 也说得更清
 */
internal class QrScanner(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** 相机这一路自己的线程 (打开 / 配会话 / 取帧 / 解码全在它上面) */
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var request: CaptureRequest.Builder? = null

    private var onText: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /** 现在该不该出帧 (stop 之后取到的帧一律丢掉) */
    @Volatile
    private var running = false

    /** 这一台有没有闪光灯 ([setTorch] 拿它回答) */
    @Volatile
    var torchAvailable: Boolean = false
        private set

    /** 手电现在开着没有 (配会话时补进请求里) */
    @Volatile
    private var torch = false

    /** 这一台支不支持连续对焦 (不支持就别往请求里写那个模式) */
    @Volatile
    private var continuousAf = false

    /** 上一次解码的时刻 (节流用, `elapsedRealtime`) */
    private var lastDecodeAt = 0L

    /** 上一段解出来的文字与它的时刻 (同一张码不重复报) */
    private var lastText = ""
    private var lastTextAt = 0L

    /** 只在这一条线程上用, 所以不用同步 */
    private val decoder = MultiFormatReader()

    /**
     * 开始扫码
     *
     * @param surface 预览面 (由调用方那块 `SurfaceView` 给, **这里不释放它** —— 它是别人的)
     * @param onText 解出一段文本 (已经去过重; 主线程)
     * @param onError 起不来或中途断开时的一句人话 (主线程)
     */
    @SuppressLint("MissingPermission")
    fun start(surface: Surface, onText: (String) -> Unit, onError: (String) -> Unit) {
        if (running) return
        if (!surface.isValid) {
            refuse(onError, "取景面还没准备好, 再点一次")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            refuse(onError, "没有相机权限: 在系统设置里允许相机, 或者把配对文本粘进来")
            return
        }
        CameraOwner.read(context)?.let { owner ->
            refuse(
                onError,
                "视频模式正占着相机 (会话 ${CameraOwner.short(owner.sessionId)}), 先退出视频模式再扫码",
            )
            return
        }
        val manager = context.getSystemService(CameraManager::class.java)
        if (manager == null) {
            refuse(onError, "这台设备没有相机服务")
            return
        }
        val cameraId = runCatching { backCameraId(manager) }.getOrNull()
        if (cameraId == null) {
            refuse(onError, "没找到能用的后摄")
            return
        }
        val characteristics = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (error: CameraAccessException) {
            refuse(onError, "相机参数读不出来: ${error.message ?: error.reason}")
            return
        }
        torchAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        // 连续对焦不是每台机器都有 (定焦的后摄不少), 不支持时硬写这个值在建请求那一步就抛
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet()
            ?: emptySet()
        continuousAf = afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        val size = chooseSize(characteristics)
        val background = HandlerThread("lw-qr-scanner").apply { start() }
        val cameraHandler = Handler(background.looper)
        val frames = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, MAX_IMAGES).apply {
            setOnImageAvailableListener({ source -> onFrame(source) }, cameraHandler)
        }
        this.onText = onText
        this.onError = onError
        this.thread = background
        this.handler = cameraHandler
        this.reader = frames
        this.lastDecodeAt = 0L
        this.lastText = ""
        this.lastTextAt = 0L
        running = true
        Log.i(TAG, "opening camera $cameraId with a ${size.width}x${size.height} frame reader")
        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (!running) {
                            device.close()
                            return
                        }
                        this@QrScanner.device = device
                        openSession(device, surface, frames, cameraHandler)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        fail("相机被别的应用抢走了, 再点一次「扫码配对」")
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        fail(cameraErrorText(error))
                    }
                },
                cameraHandler,
            )
        } catch (error: CameraAccessException) {
            fail(cameraErrorText(error.reason))
        } catch (error: SecurityException) {
            fail("没有相机权限: 在系统设置里允许相机, 或者把配对文本粘进来")
        }
    }

    /** 收掉相机; **可以重复调** (界面那一侧 [androidx.compose.runtime.DisposableEffect] 会说两次) */
    fun stop() {
        running = false
        torch = false
        val activeSession = session
        val activeDevice = device
        val activeReader = reader
        val activeThread = thread
        val activeHandler = handler
        session = null
        device = null
        reader = null
        request = null
        thread = null
        handler = null
        onText = null
        onError = null
        val cleanup = Runnable {
            runCatching { activeSession?.close() }
            runCatching { activeDevice?.close() }
            runCatching { activeReader?.close() }
            activeThread?.quitSafely()
        }
        if (activeHandler == null || !activeHandler.post(cleanup)) cleanup.run()
    }

    /**
     * 开 / 关手电 (闪光灯当常亮灯用)
     * @return 真的做了才回 true (没有闪光灯 / 会话还没起来都回 false)
     */
    fun setTorch(on: Boolean): Boolean {
        if (!torchAvailable) return false
        val builder = request ?: return false
        val active = session ?: return false
        val cameraHandler = handler ?: return false
        torch = on
        return runCatching {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
            )
            active.setRepeatingRequest(builder.build(), null, cameraHandler)
            true
        }.getOrDefault(false)
    }

    // --- 相机这一半边 -----------------------------------------------------------

    /** 后摄优先, 一台都没标后摄就取第一台 */
    private fun backCameraId(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        val back = ids.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }.getOrNull() == CameraCharacteristics.LENS_FACING_BACK
        }
        return back ?: ids.firstOrNull()
    }

    /** 取景尺寸: 挑离 1280x720 最近的那一档 (只认横着的, 竖着的那几档是同一个流的另一种说法) */
    private fun chooseSize(characteristics: CameraCharacteristics): Size {
        val all = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.toList()
            .orEmpty()
        if (all.isEmpty()) return Size(TARGET_WIDTH, TARGET_HEIGHT)
        val pool = all.filter { it.width >= it.height }.ifEmpty { all }
        return pool.minByOrNull { abs(it.width - TARGET_WIDTH) + abs(it.height - TARGET_HEIGHT) }
            ?: Size(TARGET_WIDTH, TARGET_HEIGHT)
    }

    /** 配会话: 预览面与取帧面两个输出, 都是这一路的连续请求 */
    private fun openSession(
        device: CameraDevice,
        preview: Surface,
        frames: ImageReader,
        cameraHandler: Handler,
    ) {
        if (!running) return
        val builder = try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                addTarget(frames.surface)
                // 定焦那一档不写 (写了在 build 那一步就抛)
                if (continuousAf) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (torch) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
        } catch (error: CameraAccessException) {
            fail("相机请求建不起来: ${error.message ?: error.reason}")
            return
        } catch (error: IllegalArgumentException) {
            fail("相机请求建不起来 (这一台不支持这样的取景参数)")
            return
        }
        request = builder
        try {
            device.createCaptureSession(
                listOf(preview, frames.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (!running) {
                            configured.close()
                            return
                        }
                        session = configured
                        try {
                            configured.setRepeatingRequest(builder.build(), null, cameraHandler)
                        } catch (error: CameraAccessException) {
                            fail("相机预览起不来: ${error.message ?: error.reason}")
                        } catch (error: IllegalStateException) {
                            fail("相机预览起不来: ${error.message ?: error}")
                        }
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        fail("这一台不支持这样的取景尺寸, 换一台设备试")
                    }
                },
                cameraHandler,
            )
        } catch (error: CameraAccessException) {
            fail("相机会话配不起来: ${error.message ?: error.reason}")
        } catch (error: IllegalArgumentException) {
            fail("相机会话配不起来 (取景面尺寸不对)")
        }
    }

    // --- 取帧与解码 -------------------------------------------------------------

    /** 一帧到了: 该解就解, 不该解就当场还回去 (节流那一条在这里) */
    private fun onFrame(source: ImageReader) {
        if (!running) return
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDecodeAt < DECODE_INTERVAL_MS) return
            lastDecodeAt = now
            val text = decode(image) ?: return
            val stamp = SystemClock.elapsedRealtime()
            if (text == lastText && stamp - lastTextAt < SAME_TEXT_MS) return
            lastText = text
            lastTextAt = stamp
            val sink = onText ?: return
            main.post { if (running) sink(text) }
        } finally {
            image.close()
        }
    }

    /** 一张 Image → 一段文字 (解不出回 null, 这一档不是错误) */
    private fun decode(image: Image): String? {
        val width = image.width
        val height = image.height
        val plane = image.planes.firstOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val data = runCatching { luminance(plane, width, height) }.getOrNull() ?: return null
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            decoder.decode(bitmap, HINTS)?.text
        } catch (error: ReaderException) {
            null
        } catch (error: Throwable) {
            // 解码器的任何一个意外都不该把相机那条线程带走 (那会静悄悄地不再出帧)
            Log.w(TAG, "the decoder threw: ${error.message}")
            null
        }
    }

    /**
     * Y 平面 → 连续的亮度数组
     *
     * **必须按 `rowStride` 拷**: 相机交来的那一块每行后面可能还有对齐用的空字节, 直接按宽乘高读会
     * 把整张图斜着切开 (二维码的定位图案一歪就解不出来), 而 `pixelStride` 在一些设备上也不是 1
     */
    private fun luminance(plane: Image.Plane, width: Int, height: Int): ByteArray {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val data = ByteArray(width * height)
        if (pixelStride == 1 && rowStride >= width) {
            for (y in 0 until height) {
                val base = y * rowStride
                if (base + width > buffer.limit()) break
                buffer.position(base)
                buffer.get(data, y * width, width)
            }
            return data
        }
        for (y in 0 until height) {
            val rowBase = y * rowStride
            for (x in 0 until width) {
                val index = rowBase + x * pixelStride
                data[y * width + x] = if (index < buffer.limit()) buffer.get(index) else 0
            }
        }
        return data
    }

    // --- 收尾 -------------------------------------------------------------------

    /** 起不来那一档: 只在主线程说一句, 什么都不留 */
    private fun refuse(onError: (String) -> Unit, message: String) {
        running = false
        main.post { onError(message) }
    }

    /** 中途那一档: 先说完再收 (与 [stop] 同一条路, 只是多一句话) */
    private fun fail(message: String) {
        val sink = onError
        running = false
        if (sink != null) main.post { sink(message) }
        stop()
    }

    /** `CameraDevice.StateCallback.onError` 那几个码 → 一句人话 */
    private fun cameraErrorText(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "相机正被别的应用占着, 关掉它再试"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "相机已经用满了 (有应用正开着相机), 关掉它再试"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "系统的相机开关关着 (设备策略禁用了相机)"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "相机报了一个硬件错误, 再点一次可能就好"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "相机服务出错了, 再点一次可能就好"
        else -> "相机起不来 (错误码 $error)"
    }

    private companion object {
        const val TAG = "LwQrScanner"

        /** 目标取景尺寸 (720p 的二维码够解, 再大只是多烧电) */
        const val TARGET_WIDTH = 1280
        const val TARGET_HEIGHT = 720

        /** 每 ~150 ms 解一帧: 扫码不需要 30 fps, 而每帧都解会把相机线程占满 */
        const val DECODE_INTERVAL_MS = 150L

        /** 同一段文字多久之内不重复报 (同框里那张码一直对着镜头) */
        const val SAME_TEXT_MS = 3_000L

        /** 取帧那一块缓冲留两张: 一张在解, 一张给相机接着写 */
        const val MAX_IMAGES = 2

        /** 只解二维码 (别的码型开着只会多花时间) */
        val HINTS: Map<DecodeHintType, Any> = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
    }
}
