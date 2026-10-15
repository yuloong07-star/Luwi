package io.github.yuloong07star.luwi.channel

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import io.github.yuloong07star.luwi.host.AppSkills
import io.github.yuloong07star.luwi.lock.LwLock
import io.github.yuloong07star.luwi.tool.LwCamera
import io.github.yuloong07star.luwi.tool.LwAutomation
import io.github.yuloong07star.luwi.tool.LwCalendar
import io.github.yuloong07star.luwi.tool.LwEvents
import io.github.yuloong07star.luwi.tool.LwFiles
import io.github.yuloong07star.luwi.tool.LwGallery
import io.github.yuloong07star.luwi.tool.LwKeepAwake
import io.github.yuloong07star.luwi.tool.LwLinux
import io.github.yuloong07star.luwi.tool.LwMedia
import io.github.yuloong07star.luwi.tool.LwModel
import io.github.yuloong07star.luwi.tool.LwNotifications
import io.github.yuloong07star.luwi.tool.LwNotify
import io.github.yuloong07star.luwi.tool.LwOverlay
import io.github.yuloong07star.luwi.tool.LwPhoto
import io.github.yuloong07star.luwi.tool.LwPlugin
import io.github.yuloong07star.luwi.tool.LwPower
import io.github.yuloong07star.luwi.tool.LwQuick
import io.github.yuloong07star.luwi.tool.LwSync
import io.github.yuloong07star.luwi.tool.LwSpeech
import io.github.yuloong07star.luwi.tool.LwSpeak
import io.github.yuloong07star.luwi.tool.LwSystem
import io.github.yuloong07star.luwi.tool.LwSystemCommand
import io.github.yuloong07star.luwi.tool.LwWakeWord
import io.github.yuloong07star.luwi.tool.text
import io.github.yuloong07star.luwi.util.PermissionCatalog
import io.github.yuloong07star.luwi.workspace.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** What the host needs to reach the channel, handed to it through the environment */
data class ChannelEndpoint(val address: String, val token: String)

/**
 * The socket the dsh tools reach this process through
 *
 * The tools run in the Node host, a separate process with the same uid, so a loopback socket is
 * the shortest path between them: no service to bind, no binder to wrap, and the port is only
 * reachable from this device. It binds port 0, which makes the kernel pick an ephemeral port, and
 * reports that endpoint to the host along with a token - an app on the same device could open
 * this port, and the token is what it cannot guess
 *
 * The protocol is one request per connection: a line of JSON in, a line of JSON out, close. That
 * is all a tool call needs and it leaves no framing state to get wrong
 *
 * The privileged channel lives on this side rather than in the host because binders are an app
 * process capability: Node can hold a socket, it cannot hold an IBinder
 */
object PrivilegedBridge {

    private const val TAG = "LwBridge"

    /** One caller at a time is all this ever has, so the backlog only has to absorb a race */
    private const val BACKLOG = 4

    /** The address the host is told to connect to, spelled the way it will use it */
    private const val LOOPBACK = "127.0.0.1"

    /** A caller that connects and then goes silent must not hold the only accept loop */
    private const val REQUEST_TIMEOUT_MS = 120_000

    /** The socket's own read timeout, a moment past the request budget so the caller hears why */
    private const val SO_TIMEOUT_MS = REQUEST_TIMEOUT_MS + 5_000

    /**
     * One request at a time is no longer enough
     *
     * Every request used to be served on the accept loop, which was fine while they were all
     * quick: read a screen, inject a touch, take a picture. A confirmation is not quick - it waits
     * for a person - and serving that on the accept loop would freeze every other call for as long
     * as the dialog is up. So each connection gets its own thread now, and the loop only accepts
     */
    private val callers = java.util.concurrent.Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lw-bridge-request").apply { isDaemon = true }
    }

    private var server: ServerSocket? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var current: ChannelEndpoint? = null

    /** Start listening, returning what the host has to be told, or null when it could not bind */
    fun start(): ChannelEndpoint? {
        current?.let { if (running) return it }
        val listener = try {
            // Loopback and an ephemeral port: nothing outside this device can reach it, and there
            // is no port number to keep in sync between the app and the host. The address is named
            // as an IPv4 literal on purpose - the loopback interface also answers as ::1, and the
            // host is told where to connect through a single string, so it has to be unambiguous
            ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
        } catch (error: IOException) {
            Log.e(TAG, "could not open the channel socket", error)
            return null
        }
        val endpoint = ChannelEndpoint(
            address = "${listener.inetAddress.hostAddress}:${listener.localPort}",
            token = UUID.randomUUID().toString(),
        )
        server = listener
        current = endpoint
        running = true
        worker = Thread({ accept(listener) }, "lw-bridge").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "listening on ${endpoint.address}")
        return endpoint
    }

    /** Stop listening; the host's next tool call finds nothing and says so */
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        worker = null
        current = null
    }

    /** One accept loop for the process's lifetime, one request at a time on it */
    private fun accept(listener: ServerSocket) {
        while (running) {
            val client = try {
                listener.accept()
            } catch (error: IOException) {
                // Closing the listener is how stop() ends this loop, so only a real failure is news
                if (running) Log.w(TAG, "accept failed", error)
                return
            }
            try {
                callers.execute { serve(client) }
            } catch (error: Throwable) {
                Log.w(TAG, "a channel request failed", error)
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = SO_TIMEOUT_MS
            val request = client.getInputStream().bufferedReader().readLine() ?: return
            val response = respond(request)
            client.getOutputStream().write((response + "\n").toByteArray())
            client.getOutputStream().flush()
        } catch (error: Throwable) {
            Log.w(TAG, "serving a channel request failed", error)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun respond(request: String): String = runBlocking {
        val parsed = try {
            Json.parseToJsonElement(request).jsonObject
        } catch (error: Throwable) {
            return@runBlocking failure("the request is not a JSON object: ${error.message}")
        }
        // Any app on the device could open this port, so the token is the guard rather than the
        // socket's location
        val presented = parsed["token"]?.jsonPrimitive?.contentOrNull
        if (presented == null || presented != current?.token) {
            Log.w(TAG, "refusing a request that did not present the channel token")
            return@runBlocking failure("the request did not present the channel token")
        }
        val method = parsed["method"]?.jsonPrimitive?.contentOrNull
            ?: return@runBlocking failure("the request names no method")
        try {
            buildJsonObject {
                put("ok", true)
                put("result", dispatch(method, parsed))
            }.toString()
        } catch (error: Throwable) {
            Log.w(TAG, "the $method request failed", error)
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Everything a tool can ask for
     *
     * The request object travels along because a screen is made by its caller: the name it is to
     * be shown under, and the size it is to have, are both decided out there rather than here
     */
    private suspend fun dispatch(method: String, request: JsonObject) = when (method) {
        "ping" -> buildJsonObject { put("pong", true) }
        "status" -> ChannelReport.status(PrivilegedChannel.state())
        "connect" -> {
            PrivilegedChannel.ensure()
            ChannelReport.status(PrivilegedChannel.state())
        }

        // 探针: 通道自己 + 无障碍那六件事实 + 通知使用权那几件。无障碍放在这里而不是另开一个方法,
        // 是因为模型问"通道现在什么样"的时候, "无障碍到底开没开"几乎总是它真正想知道的那一半;
        // 通知使用权是 1.0.3 加的另一半, 同一个理由 (它也是一道要人去系统设置里给的授权)
        "probe" -> ChannelReport.probe(PrivilegedChannel.probe()).let { report ->
            val state = appContext { runCatching { LwPermission(it).inspect() }.getOrNull() }
            val notices = appContext {
                runCatching { LwPermission(it).notificationListeners() }.getOrNull()
            }
            if (state == null && notices == null) {
                report
            } else {
                buildJsonObject {
                    report.forEach { (name, element) -> put(name, element) }
                    state?.let { putJsonObject("accessibility") { accessibilityJson(it) } }
                    notices?.let { putJsonObject("notifications") { notificationJson(it) } }
                }
            }
        }

        // What one screen says about itself: the text on it, and where that text is in the
        // screen's own pixels. Read here in the app's own process rather than over the channel,
        // because the accessibility service belongs to this app
        "ui" -> {
            val screen = namedScreen(request)
            val tree = LwAccessibility.tree(screen.displayId)
            buildJsonObject {
                put("enabled", LwAccessibility.running)
                put("displayId", screen.displayId)
                put("label", screen.label)
                put("width", screen.width)
                put("height", screen.height)
                put("package", tree.packageName)
                put("truncated", tree.truncated)
                put("error", tree.error.orEmpty())
                put("nodes", buildJsonArray { tree.nodes.forEach { add(nodeJson(it)) } })
            }
        }

        // Press the thing a screen calls by that name, with no coordinate spent on it
        "tapText" -> {
            val screen = namedScreen(request)
            // 暂停要在这里也拦一次: 按名字走的是无障碍动作, 不经过 VirtualScreen 那条手势队列
            VirtualScreen.requireAcceptsControl(screen)
            val name = request["text"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("tapText has to name the text it means")
            val holdMs = hold(request)
            val hit = LwAccessibility.tap(screen.displayId, name, holdMs)
            var via = hit.via
            var clicked = hit.outcome == "clicked"
            if (hit.outcome == "unclicked") {
                // A view that draws itself may take a touch but not an accessibility action. The
                // tree already said where the thing is, so the finger is the fallback rather than
                // a coordinate the caller had to guess
                val target = hit.target
                if (target != null) {
                    VirtualScreen.tap(screen, target.exactCenterX(), target.exactCenterY(), holdMs)
                    clicked = true
                    via = "finger"
                }
            }
            buildJsonObject {
                put("outcome", hit.outcome)
                put("clicked", clicked)
                put("via", via)
                put("displayId", screen.displayId)
                put("holdMs", holdMs)
                put("long", holdMs >= LwServiceProtocol.LONG_PRESS_MS)
                put("error", hit.error.orEmpty())
                put("matches", buildJsonArray { hit.matches.forEach { add(nodeJson(it)) } })
            }
        }

        // 滚一屏: 走无障碍自己的滚动动作, 不注入触摸。列表比一屏长的时候, 按名字点不到还没铺出来的行,
        // 而注入的拖动在这台设备的虚拟屏上到不了应用 —— 这一条正好补上那两处
        "scroll" -> {
            val screen = namedScreen(request)
            VirtualScreen.requireAcceptsControl(screen)
            val backward = request["direction"]?.jsonPrimitive?.contentOrNull == "backward"
            val name = request["text"]?.jsonPrimitive?.contentOrNull
            val times = (request["times"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, MAX_SCROLL_TIMES)
            val hit = LwAccessibility.scroll(screen.displayId, !backward, name, times)
            val node = hit.node
            val sentence = if (hit.scrolled > 0) {
                "scrolled displayId ${screen.displayId} " + (if (backward) "backward" else "forward") +
                    " " + hit.scrolled + " screenful(s) through " +
                    (node?.className ?: "the scrollable control") +
                    ": read the screen again, the rows have moved"
            } else {
                hit.error ?: "nothing was scrolled"
            }
            buildJsonObject {
                put("outcome", hit.outcome)
                put("scrolled", hit.scrolled)
                put("direction", if (backward) "backward" else "forward")
                put("displayId", screen.displayId)
                put("error", hit.error.orEmpty())
                put("text", sentence)
                if (node != null) put("node", nodeJson(node))
            }
        }
        // A screen is made here rather than in the app's UI, so a tool says what it wants: a name
        // to be known by, and a size when this device's own is not the right shape
        "create" -> {
            val screen = VirtualScreen.create(
                name = request["name"]?.jsonPrimitive?.contentOrNull,
                width = request["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = request["height"]?.jsonPrimitive?.intOrNull ?: 0,
                dpi = request["dpi"]?.jsonPrimitive?.intOrNull ?: 0,
            )
            buildJsonObject {
                put("created", screen != null)
                put("displayId", screen?.displayId ?: -1)
                put("label", screen?.label ?: "")
                put("error", VirtualScreen.lastError ?: "")
            }
        }

        // The screens' own state and a picture of one, which is the pair a tool needs to see what
        // it is about to touch. The phone's own screen is listed beside them rather than among
        // them: it can be named by every call, but it is nobody's to give back
        "screen" -> buildJsonObject {
            val primary = VirtualScreen.mainScreen()
            put("count", VirtualScreen.screens.size)
            put("selected", VirtualScreen.selected?.displayId ?: -1)
            putJsonObject("primary") {
                put("displayId", primary.displayId)
                put("label", primary.label)
                put("width", primary.width)
                put("height", primary.height)
                put("dpi", primary.dpi)
            }
            put(
                "screens",
                buildJsonArray {
                    VirtualScreen.screens.forEach { screen ->
                        add(
                            buildJsonObject {
                                put("displayId", screen.displayId)
                                put("label", screen.label)
                                put("width", screen.width)
                                put("height", screen.height)
                                put("dpi", screen.dpi)
                                // 用户手里的刹车: 停着的屏一律拒绝动手的调用, 但看还是看得见
                                put("acceptsControl", screen.acceptsControl)
                            },
                        )
                    }
                },
            )
            // 这里**不叫 error**: `VirtualScreen.lastError` 是这一套东西上一次出错留下的那句话, 而不是
            // 这一次的结论 —— 通道刚连上那会儿它还在, 于是答案里一边是"没有屏"一边是"需要 root 或
            // Shizuku"。所以通道状态单独给一条实时的, 谁要用那句旧话, 先看这一条
            put("lastError", VirtualScreen.lastError ?: "")
            putJsonObject("channel") {
                val state = PrivilegedChannel.state()
                put("connected", state.connected)
                put("backend", state.backend ?: "")
                put("error", state.error ?: "")
            }
        }

        // Give one screen back, by id and only by id
        //
        // It deliberately does not fall back to the selected screen: which one is selected is the
        // user's to change at any moment, so a caller that named nothing could close a screen it
        // never meant to touch - the one the user had just switched to
        "release" -> {
            val displayId = request["displayId"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("release has to name the displayId it means")
            if (displayId == LwServiceProtocol.MAIN_DISPLAY) {
                // 主屏不在这张表里, 走下面的查找只会得到一句"没有这个 id", 而这里能说清为什么
                buildJsonObject {
                    put("released", false)
                    put("displayId", displayId)
                    put(
                        "error",
                        "displayId $displayId is the phone's own screen rather than a virtual one:" +
                            " there is nothing here to release, and it cannot be closed",
                    )
                }
            } else {
                val screen = VirtualScreen.screens.firstOrNull { it.displayId == displayId }
                if (screen != null) {
                    VirtualScreen.release(screen)
                } else {
                    Log.w(TAG, "asked to release display $displayId, which is not one of ours")
                }
                buildJsonObject {
                    put("released", screen != null)
                    put("displayId", displayId)
                    put("error", if (screen == null) VirtualScreen.missingScreenReason(displayId) else "")
                }
            }
        }

        // One key, named the way Android names keys rather than numbered by whoever asks
        //
        // Some buttons are the platform's rather than any app's - BACK, HOME, the volume keys - so
        // they are on no tree and at no coordinate, and `input keyevent` is refused for this app's
        // uid exactly like `am` is. That leaves this, and it is why the key is checked against the
        // platform's own list here rather than taken on trust from the caller
        "key" -> {
            val screen = namedScreen(request)
            val keyCode = namedKey(request)
            val holdMs = hold(request)
            VirtualScreen.key(screen, keyCode, holdMs)
            buildJsonObject {
                put("pressed", true)
                put("key", KeyCodes.nameOf(keyCode))
                put("code", keyCode)
                put("holdMs", holdMs)
                put("longPress", holdMs >= LwServiceProtocol.LONG_PRESS_MS)
                put("displayId", screen.displayId)
                put("label", screen.label)
            }
        }

        // What a screen is to be made to say, into the field it is showing
        //
        // The field is written through the platform's own action where the screen reports one,
        // which is what makes Chinese and emoji work and what makes an IME irrelevant; key presses
        // are what is left when it reports none. Which of the two happened is in the answer, because
        // a caller reading its own text back has to know whether it landed in a field or went
        // wherever focus happened to be
        "type" -> {
            val screen = namedScreen(request)
            VirtualScreen.requireAcceptsControl(screen)
            val text = request["text"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("this call has to say what to type")
            if (text.length > MAX_TYPED_CHARS) {
                throw IllegalArgumentException(
                    "that is ${text.length} characters, and this call takes at most $MAX_TYPED_CHARS",
                )
            }
            val replace = request["replace"]?.jsonPrimitive?.booleanOrNull == true
            // 1.0.3: 落在指定的那一点上。**先按一下再打字**, 因为"往哪个字段里写"这件事只有按下去才算
            // 数: 无障碍那条路写的是**有焦点的**那个字段, 而调用方常常知道的是坐标
            val atX = request["x"]?.jsonPrimitive?.floatOrNull
            val atY = request["y"]?.jsonPrimitive?.floatOrNull
            if (atX != null || atY != null) {
                require(atX != null && atY != null) {
                    "putting the text at a point needs both x and y: giving one of them is not a point"
                }
                VirtualScreen.tap(screen, atX, atY)
                // 按下去之后那块界面可能还没画出来 (常常是打开了一个新页面), 而字段不在树里的时候
                // `type` 会退回按键 —— 按键落进空气里, 答案却只说"打了几个键"。所以给它一小段时间
                waitForField(screen.displayId)
            }
            val field = LwAccessibility.type(screen.displayId, text, replace)
            when (field.outcome) {
                "typed" -> typingJson(screen, "field", text.length, field.text, field.password, field.field)

                // Two fields and no focus is the one case where nothing sensible can be chosen, and
                // it is the caller that knows which one it meant
                "ambiguous" -> throw IllegalStateException(
                    "displayId ${screen.displayId} (\"${screen.label}\") shows more than one text" +
                        " field and none of them has focus, so nothing was typed - tap the one you" +
                        " mean with lw_tap and call this again, or tap it and type into it: " +
                        field.matches.joinToString("; ") { node -> fieldSentence(node) },
                )

                "failed" -> throw IllegalStateException(
                    "the field on displayId ${screen.displayId} (\"${screen.label}\") did not take" +
                        " the text: ${field.error ?: "no reason given"}",
                )

                else -> {
                    // Nothing to set: the text goes in as keys, which is all such a screen can be
                    // given, and the keyboard decides whether it can produce it at all
                    if (replace) {
                        throw IllegalStateException(
                            "displayId ${screen.displayId} (\"${screen.label}\") reports no text" +
                                " field, so there is nothing to replace - type into it as keys, or" +
                                " tap the field you meant first (${field.error ?: "no field found"})",
                        )
                    }
                    val typed = VirtualScreen.text(screen, text)
                    if (typed < 0) {
                        throw IllegalStateException(
                            "displayId ${screen.displayId} (\"${screen.label}\") reports no text" +
                                " field, and this device's keyboard cannot produce \"$text\" - it" +
                                " types what a virtual keyboard has, which is letters, digits and" +
                                " its punctuation and no Chinese",
                        )
                    }
                    typingJson(screen, "keys", typed, "", false, null)
                }
            }
        }

        // Another shape for a screen that already exists
        //
        // The shape of a display is what an app lays itself out for, so this is what puts a
        // landscape-only app on a screen the size it wants; the swap flag is the same change asked
        // for as a quarter turn, which is how a caller thinks about it and saves it from having to
        // read the current size back first
        "resize" -> {
            val displayId = request["displayId"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("resize has to name the displayId it means")
            if (displayId == LwServiceProtocol.MAIN_DISPLAY) {
                // 主屏不是我们的屏: 它是用户手里那块, 尺寸跟着他的设备与旋转走
                buildJsonObject {
                    put("resized", false)
                    put("displayId", displayId)
                    put(
                        "error",
                        "displayId $displayId is the phone's own screen rather than a virtual one:" +
                            " its size belongs to the device and to how the user is holding it, so" +
                            " there is nothing here to resize",
                    )
                }
            } else {
                val screen = namedScreen(request)
                val swap = request["swap"]?.jsonPrimitive?.booleanOrNull ?: false
                val resized = VirtualScreen.resize(
                    screen = screen,
                    width = request["width"]?.jsonPrimitive?.intOrNull ?: 0,
                    height = request["height"]?.jsonPrimitive?.intOrNull ?: 0,
                    dpi = request["dpi"]?.jsonPrimitive?.intOrNull ?: 0,
                    swap = swap,
                )
                buildJsonObject {
                    put("resized", resized != null)
                    put("displayId", displayId)
                    put("label", screen.label)
                    put("swapped", swap)
                    put("width", resized?.width ?: screen.width)
                    put("height", resized?.height ?: screen.height)
                    put("dpi", resized?.dpi ?: screen.dpi)
                    put("error", if (resized == null) VirtualScreen.lastError.orEmpty() else "")
                }
            }
        }

        // Which apps can be started. The two halves come from the two sides that can answer them:
        // what a person can launch is the app's own question (the launcher intent, made visible by
        // the manifest's <queries> declaration), and which of those exist for a user is the
        // privileged side's, since a cloned app is only a package in the other user's list
        //
        // The app's own list is already its own user's, so that user needs no listing; for any other
        // user the launcher's apps are the ones this app can name, and a label is the same string in
        // either copy - the clone is the same APK - so the names found here serve both
        "apps" -> {
            val userId = (request["user"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val launchable = LwApps.launchable()
            val listing = if (userId == LwApps.ownUser) {
                null
            } else {
                val service = PrivilegedChannel.ensure()
                    ?: throw IllegalStateException(
                        PrivilegedChannel.state().error
                            ?: "the privileged channel is not available",
                    )
                service.packages(userId)
            }
            val installed = listing?.let { installedPackages(it) }
            // "this user has none of them" and "nobody could tell us" look the same in the list, so
            // an empty answer with something to say about it is reported as the failure it is
            val unreadable = listing != null && installed?.isEmpty() == true && listing.isNotBlank()
            val apps = installed?.let { names -> launchable.filter { it.packageName in names } }
                ?: launchable
            val query = request["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val matching = if (query.isEmpty()) apps else apps.filter { app ->
                app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            buildJsonObject {
                put("user", userId)
                put("launchable", apps.size)
                put("matched", matching.size)
                put("query", query)
                put("truncated", matching.size > MAX_APPS)
                putJsonArray("apps") {
                    matching.take(MAX_APPS).forEach { app ->
                        add(
                            buildJsonObject {
                                put("package", app.packageName)
                                put("label", app.label)
                                put("component", app.component)
                            },
                        )
                    }
                }
                put(
                    "error",
                    when {
                        launchable.isEmpty() -> NO_LAUNCHER_APPS
                        unreadable -> "the privileged side could not list user $userId's packages: " +
                            listing.orEmpty().lineSequence()
                                .firstOrNull { it.isNotBlank() }
                                .orEmpty()

                        else -> ""
                    },
                )
            }
        }

        // Start an app onto one screen
        //
        // Nothing here can do this from the app's side: `am` calls itself the shell's package and
        // is refused an app uid, so the one place it works is the privileged process - which is
        // also why this is a transaction rather than a shell command a caller could run
        "launch" -> {
            val screen = namedScreen(request)
            VirtualScreen.requireAcceptsControl(screen)
            val asked = request["package"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val component = request["component"]?.jsonPrimitive?.contentOrNull.orEmpty()
            // 手里往往只有一个名字 ("设置" / "哔哩哔哩"), 而 am 要的是包名: 能唯一对上就用它, 一个都
            // 对不上就按原样交下去 (那是调用方自己知道的包名), 对上不止一个就什么都不起
            val packageName = if (component.isBlank()) resolvePackage(asked) else asked
            // 双开的那份与原版同名同组件, 只有 userId 不同, 所以"起哪一个"就是这一个数字
            val userId = (request["user"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val timeoutMs = (request["timeoutMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_LAUNCH_MS)
                .coerceIn(MIN_LAUNCH_MS, MAX_LAUNCH_MS)
            val service = PrivilegedChannel.ensure()
                ?: throw IllegalStateException(
                    PrivilegedChannel.state().error ?: "the privileged channel is not available",
                )
            val (code, output) = service.launch(
                displayId = screen.displayId,
                userId = userId,
                packageName = packageName,
                component = component,
                timeoutMs = timeoutMs,
            )
            buildJsonObject {
                put("displayId", screen.displayId)
                put("label", screen.label)
                put("package", packageName)
                put("asked", if (packageName == asked) "" else asked)
                put("component", component)
                put("user", userId)
                put("code", code)
                put("started", code == 0 && output.contains(STARTED_MARK))
                // 起完自查一次窗口: 见 waitForWindow 上那段。**无障碍没开时不下这个结论** —— 那时
                // 窗口列表根本读不到, "没有窗口"与"读不到窗口"是两件事, 而前者会让模型白白去重建屏
                val window = if (LwAccessibility.running) waitForWindow(screen.displayId) else null
                put("windowOnDisplay", window)
                when (window) {
                    false -> put(
                        "windowNote",
                        "the launch was accepted but displayId ${screen.displayId} still has no" +
                            " window on it. An app that is already running keeps its window where it" +
                            " already is - $packageName is most likely up on the phone's own screen or" +
                            " on another screen of ours. Either name that screen instead, or make a" +
                            " fresh one (lw_screen_create) and launch again: a second launch on the" +
                            " same screen does not move the running window onto it.",
                    )

                    null -> put(
                        "windowNote",
                        "the launch was accepted, and this answer cannot say whether anything is on" +
                            " displayId ${screen.displayId}: that check reads the accessibility" +
                            " window list, and the accessibility service is off. Read the screen" +
                            " back with lw_ui once it is on.",
                    )

                    true -> Unit
                }
                put("output", output.trim())
            }
        }

        // A picture of one screen, named for the same reason every other gesture here names its
        // screen: only the caller knows which one it is about to look at.
        //
        // **`op=status` 是这一条事务唯一不截图的那一档** (批次 4): 宿主插件在投递一句话之前要问一次
        // "指代不明时自动附图那一条开着没有", 而那条偏好只有应用这一侧读得到 —— 它判在最前面, 所以
        // 通道没连上时也答得出来 (那时它答的正是"帮我截一张"做不到)
        "screenshot" -> if (request["op"]?.jsonPrimitive?.contentOrNull == "status") {
            buildJsonObject { put("autoShot", ScreenshotBudget.autoShot) }
        } else run {
            val screen = namedScreen(request)
            // 预算由设置页那两档给, 调用方说了就用调用方的: 图必须在模型那条路由的预算之内, 超了
            // 要在 host 那边重编码, 而设备上没有编码器
            val maxPixels = request["maxPixels"]?.jsonPrimitive?.intOrNull ?: ScreenshotBudget.pixels
            val maxBytes = request["maxBytes"]?.jsonPrimitive?.intOrNull ?: ScreenshotBudget.bytes
            val region = region(request)
            // 连拍: 相隔一小段再拍一张。**不是"每帧一张"** —— 一次截图在本机要两三百毫秒, 而 `intervalMs`
            // 说的是**墙钟上两张之间隔多久** (从这一张开始到下一张开始), 不是"拍完之后再歇多久": 后者
            // 会让真实间隔随设备忙闲漂移, 而答案里报的是量到的真实间隔, 所以这个数做不到时会被说出来
            val count = (request["count"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, MAX_SERIES)
            val intervalMs = (request["intervalMs"]?.jsonPrimitive?.intOrNull?.toLong()
                ?: DEFAULT_SERIES_GAP_MS).coerceIn(MIN_SERIES_GAP_MS, MAX_SERIES_GAP_MS)
            val wantedSheet = request["sheet"]?.jsonPrimitive?.booleanOrNull ?: false
            val taken = ArrayList<VirtualScreen.Shot>(count)
            // 每一张是在第几毫秒拍的 (相对第一张), 因为"两张隔了多久"是这件事的全部意义
            val offsets = ArrayList<Long>(count)
            val base = SystemClock.uptimeMillis()
            repeat(count) { index ->
                val started = SystemClock.uptimeMillis()
                val shot = VirtualScreen.screenshot(
                    screen = screen,
                    maxPixels = maxPixels,
                    maxBytes = maxBytes,
                    region = region,
                    suffix = if (count > 1) "-${index + 1}" else "",
                )
                if (shot != null) {
                    taken.add(shot)
                    offsets.add(started - base)
                }
                if (index < count - 1) {
                    val wait = intervalMs - (SystemClock.uptimeMillis() - started)
                    if (wait > 0) Thread.sleep(wait)
                }
            }
            val sheet = if (wantedSheet && taken.size > 1) {
                VirtualScreen.contactSheet(
                    frames = taken.map { it.fitted.file },
                    name = "screen-${screen.displayId}-sheet",
                    maxPixels = maxPixels,
                    maxBytes = maxBytes,
                )
            } else {
                null
            }
            // 要了网格而没有网格, 理由必须说出来 (与"屏上没有东西"长得不一样)
            val sheetError = when {
                !wantedSheet -> ""
                taken.size < 2 -> "a grid needs at least two pictures and only ${taken.size} came back"
                sheet == null -> VirtualScreen.lastError ?: "no reason reported"
                else -> ""
            }
            val shots = buildJsonArray { taken.forEach { add(shotJson(it, region)) } }
            val first = taken.firstOrNull()
            buildJsonObject {
                put("displayId", screen.displayId)
                put("label", screen.label)
                put("width", screen.width)
                put("height", screen.height)
                // 单张时这几个字段是老样子; 连拍时它们说的是**第一张**, 而全部几张在 `shots` 里
                put("path", first?.fitted?.file?.absolutePath ?: "")
                put("bytes", first?.fitted?.file?.length() ?: 0L)
                put("fullPath", first?.full?.absolutePath ?: "")
                put("picture", first?.let { shotJson(it, region)["picture"] } ?: buildJsonObject { put("scale", 0f) })
                put("count", taken.size)
                put("intervalMs", intervalMs)
                // 量到的跨度: 与 (count-1) * intervalMs 差得多, 就是这台设备拍不了那么快
                put("spanMs", (offsets.lastOrNull() ?: 0L) - (offsets.firstOrNull() ?: 0L))
                put("offsets", buildJsonArray { offsets.forEach { add(it) } })
                put("shots", shots)
                if (sheet != null) {
                    put("sheet", buildJsonObject {
                        shotJson(sheet.shot, null).forEach { (key, value) -> put(key, value) }
                        put("columns", sheet.columns)
                        put("rows", sheet.rows)
                    })
                }
                put("sheetError", sheetError)
                put("error", VirtualScreen.lastError ?: "")
            }
        }

        // Touch, aimed at the screen the request names rather than at whatever the user happens to
        // be looking at; the answer says what was delivered, since the tool that asked cannot see
        // the screen for itself
        "tap" -> {
            val screen = namedScreen(request)
            val x = number(request, "x")
            val y = number(request, "y")
            val holdMs = hold(request)
            VirtualScreen.tap(screen, x, y, holdMs)
            buildJsonObject {
                put("tapped", true)
                put("displayId", screen.displayId)
                put("screen", VirtualScreen.displayPower(screen.displayId))
                put("x", x)
                put("y", y)
                put("holdMs", holdMs)
                put("longPress", holdMs >= LwServiceProtocol.LONG_PRESS_MS)
            }
        }

        "swipe" -> {
            val screen = namedScreen(request)
            val fromX = number(request, "fromX")
            val fromY = number(request, "fromY")
            val toX = number(request, "toX")
            val toY = number(request, "toY")
            val durationMs = request["durationMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_SWIPE_MS
            VirtualScreen.swipe(screen, fromX, fromY, toX, toY, durationMs)
            buildJsonObject {
                put("swiped", true)
                put("displayId", screen.displayId)
                put("durationMs", durationMs)
            }
        }

        // 多点手势: 每条路径一根手指, 一起走。捏合是它的糖衣 (两条直线路径)
        "gesture" -> {
            val screen = namedScreen(request)
            VirtualScreen.requireAcceptsControl(screen)
            val paths = (request["paths"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.map { path ->
                    LwInput.Path(path.points())
                }
                .orEmpty()
            if (paths.isEmpty()) {
                throw IllegalArgumentException(
                    "gesture has to name paths: an array of objects, each with a points array of" +
                        " {x, y} pairs (one path per finger, at least two points each)",
                )
            }
            val durationMs = request["durationMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_SWIPE_MS
            // 切断由 multiGesture 自己抛出来 (与拖那条路一致), 所以这里没有返回值可看
            VirtualScreen.multiGesture(
                screen,
                paths,
                durationMs,
            )
            buildJsonObject {
                put("displayId", screen.displayId)
                put("fingers", paths.size)
                put("durationMs", durationMs)
            }
        }

        "pinch" -> {
            val screen = namedScreen(request)
            VirtualScreen.requireAcceptsControl(screen)
            val scale = number(request, "scale", 2.0)
            val durationMs = request["durationMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_SWIPE_MS
            VirtualScreen.pinch(
                screen,
                number(request, "x"),
                number(request, "y"),
                scale.toFloat(),
                durationMs,
            )
            buildJsonObject {
                put("displayId", screen.displayId)
                put("scale", scale)
            }
        }

        // 端侧 OCR: 现在只到"加载 + 跑一遍看耗时", 认字那条路还没接。它读的是 assets 里的模型,
        // 与特权进程无关, 挂在这条桥上只是因为这里是 host 唯一能说话的地方
        // 端侧 OCR: 认这块屏上写着什么。与 `screenshot` 一样属于"看", 所以不碰主屏那道触摸刹车,
        // 也不用管虚拟屏那两道闸 —— 它不改变任何东西
        "ocr" -> {
            val screen = namedScreen(request)
            val maxLines = request["maxLines"]?.jsonPrimitive?.intOrNull ?: 0
            val captured = System.nanoTime()
            val bitmap = VirtualScreen.capture(screen)
            val captureMs = (System.nanoTime() - captured) / 1_000_000
            if (bitmap == null) {
                buildJsonObject {
                    put("displayId", screen.displayId)
                    put("lines", buildJsonArray {})
                    put("error", VirtualScreen.lastError ?: "capture failed")
                }
            } else {
                try {
                    val minScore = request["minScore"]?.jsonPrimitive?.floatOrNull ?: Float.NaN
                    val outcome = if (minScore.isNaN()) {
                        if (maxLines > 0) {
                            LwOcr.recognize(bitmap, maxLines, captureMs = captureMs)
                        } else {
                            LwOcr.recognize(bitmap, captureMs = captureMs)
                        }
                    } else {
                        LwOcr.recognize(bitmap, maxLines.coerceAtLeast(1), minScore, captureMs)
                    }
                    buildJsonObject {
                        put("displayId", screen.displayId)
                        put("label", screen.label)
                        put("screen", VirtualScreen.displayPower(screen.displayId))
                        put("width", bitmap.width)
                        put("height", bitmap.height)
                        put("backend", outcome.backend)
                        put("captureMs", captureMs)
                        put("detMs", outcome.detMs)
                        put("recMs", outcome.recMs)
                        put("boxes", LwOcr.lastBoxes)
                        put("error", outcome.error ?: "")
                        put("lines", buildJsonArray {
                            for (line in outcome.lines) {
                                add(buildJsonObject {
                                    put("text", line.text)
                                    put("score", line.score.toDouble())
                                    put("box", buildJsonArray {
                                        add(line.left)
                                        add(line.top)
                                        add(line.right)
                                        add(line.bottom)
                                    })
                                    put("center", buildJsonArray {
                                        add(line.centerX)
                                        add(line.centerY)
                                    })
                                })
                            }
                        })
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        // 同一条路的诊断版: 拿合成输入跑两个模型, 报计算单元、耗时, 以及 NPU 与 CPU 的输出差
        "ocrProbe" -> LwOcr.probe(
            rounds = request["rounds"]?.jsonPrimitive?.intOrNull ?: 3,
            force = request["force"]?.jsonPrimitive?.contentOrNull,
            compare = request["compare"]?.jsonPrimitive?.booleanOrNull ?: false,
            perf = request["perf"]?.jsonPrimitive?.contentOrNull ?: "burst",
        )

        // 下面这一批是 1.0.2 加的: 通知、震动、剪贴板、分享、下载在 app 进程里自己做, 不需要特权;
        // 设备与系统信息同理 (读的多); 这几条回的都是 {"text": ...}, 由插件念给模型
        "notify" -> appContext { LwNotify.notify(it, request) }
        "vibrate" -> appContext { LwNotify.vibrate(it, request) }
        "clipboard" -> appContext { LwNotify.clipboard(it, request) }
        "share" -> appContext { LwNotify.share(it, request) }
        "openFile" -> appContext { LwNotify.openFile(it, request) }
        "download" -> appContext { LwNotify.download(it, request) }
        "device" -> appContext { LwSystem.device(it) }
        "battery" -> appContext { LwSystem.battery(it) }
        "storage" -> appContext { LwSystem.storage(it) }
        "running" -> appContext { LwSystem.running(it) }
        "volume" -> appContext { LwSystem.volume(it, request) }
        "media" -> appContext { LwSystem.media(it, request) }
        "net" -> appContext { LwSystem.net(it, request) }
        "sensor" -> appContext { LwSystem.sensor(it, request) }
        "location" -> appContext { LwSystem.location(it, request) }
        "system" -> appContext { LwSystem.system(it, request) }
        "permissions" -> appContext { text(PermissionCatalog.report(it)) }
        // 1.0.3: 让设备别睡 —— app 进程里一个 PARTIAL_WAKE_LOCK 就够, 不必过特权
        "keepAwake" -> LwKeepAwake.dispatch(request)
        // 模式: 只换那个助手的提示词文件 (手机模式 / 视频模式), 不动预设也不动工具表
        "mode" -> appContext { LwModes.dispatch(it, request) }
        // 需求 2 (3.0.2): 视频模式用哪一个模型的目录 (`op=catalog`) —— 应用读宿主写的
        // model-catalog.json, 设置页与这里的量具 (`lw.ps1 -Method model`) 看到的是同一份
        "model" -> appContext { LwModel.dispatch(it, request) }
        "camera" -> appContext { LwCamera.dispatch(it, request) }
        // 1.0.3 批次 4: 工作区里那几个文件与手机那一侧的读数、让媒体库看见、拍一张照
        "files" -> appContext { LwFiles.dispatch(it, request) }
        "mediaScan" -> appContext { LwMedia.dispatch(it, request) }
        // p 图那条链的最后一站 (2.5.0 批次 9): 把成图放进相册, 顺手打开。写的是 MediaStore,
        // 所以"所有文件访问权限"有没有给都不影响这一条
        "gallery" -> appContext { LwGallery.dispatch(it, request) }
        "takePhoto" -> appContext { LwPhoto.dispatch(it, request) }
        // 通知栏那一侧: 读的是系统绑在本进程里的监听服务, 与无障碍同一条路
        "notifications" -> appContext { LwNotifications.dispatch(it, request) }
        // 2.5.0 批次 7: 日程 (读日历 / 建一条 / 改一条 / 查空闲) 与快捷指令 (dsh 家里那几个 md)
        "calendar" -> appContext { LwCalendar.dispatch(it, request) }
                "quick" -> appContext { LwQuick.dispatch(it, request) }
                // 自动指令 (2.5.0 批次 8): 规则 / 设置 / 历史 / 现在能不能用, 都在这一条上
        "automation" -> appContext { LwAutomation.dispatch(it, request) }
        // 会话同步那份配置 (2026-10-14): 模型够不到 `$DSH_HOME` (它的 fs 工具关在工作区, lw_linux 的
        // proot 也只 bind 工作区), 而设置页第一排写着"让 agent 帮你配置" —— 这一条就是那句话的落地。
        // 语义走 SyncSetting 的纯文件那一半, 这里只有一行转发
        "sync" -> appContext { LwSync.dispatch(it, request) }

        // LW 插件 (批次 9): 装 / 启用 / 停用 / 卸 / 授权 / 审计, 以及宿主每几秒拉一次的那份快照
        // (它按 revision 决定要不要重注册工具)。与别的工具同一个形状: 应用这一侧写答案
        "plugin" -> appContext { LwPlugin.dispatch(it, request) }

        // 应用对应的技能那一档 (2.7.0): `scan` 看会装什么, `install` 才落地。桥上有这一条, 设置页
        // 那颗「一键」按钮与脚本走的是同一条路 —— 两边共用一份实现, 不会各自漂
        "skill" -> appContext { AppSkills.dispatch(it, request) }
        // 1.0.3 批次 6: 事件订阅 —— 让模型"等到一件事发生", 而不是反复读屏
        "eventsSubscribe" -> LwEvents.subscribe(request)
        "eventsWait" -> LwEvents.wait(request)
        "power" -> appContext { LwPower.dispatch(it, request) }
        // 锁屏那一条 (批次 5): 状态 / 立刻解锁一次 / 看录了什么 / 清掉 / 录一段
        "lock" -> appContext { LwLock.dispatch(it, request) }
        "speech" -> appContext { LwSpeech.dispatch(it, request) }
        "speak" -> appContext { LwSpeak.dispatch(it, request) }
        "overlay" -> appContext { LwOverlay.dispatch(it, request) }
        "wakeword" -> appContext { LwWakeWord.dispatch(it, request) }
        "syscmd" -> LwSystemCommand.dispatch(request)
        // Linux 沙盒 (装 / 跑 / 问 / 删): 命令形状写死在特权那一侧 (见 LwLinuxSandbox), 这一侧只
        // 负责把随包的 base 抄出自己的 data 目录、把 work 过一道形状, 以及通道不在线时也能答的那半个状态
        "linux" -> LwLinux.dispatch(request)

        // 等一个控件出现: 在无障碍树里轮询, 找到就立刻回话, 不找到就回到超时为止。上限压在与桥那条
        // 120 秒读超时之下, 超时说的是"这段时间里没出现", 不挂住模型
        "waitFor" -> {
            val screen = namedScreen(request)
            val name = request["text"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("waitFor has to name the text it is waiting for")
            val timeoutMs = (request["timeoutMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_WAIT_MS)
                .coerceIn(POLL_STEP_MS, MAX_WAIT_MS)
            val started = System.currentTimeMillis()
            var found: List<UiNode> = emptyList()
            var reason: String? = null
            while (true) {
                val (matches, error) = LwAccessibility.find(screen.displayId, name)
                reason = error
                if (matches.isNotEmpty()) {
                    found = matches
                    break
                }
                if (System.currentTimeMillis() - started >= timeoutMs) break
                Thread.sleep(POLL_STEP_MS)
            }
            val elapsed = System.currentTimeMillis() - started
            // 提示里那两句只有这一处用得到, 所以直接写在这里, 不另开 helper
            buildJsonObject {
                put("displayId", screen.displayId)
                put("found", found.isNotEmpty())
                put("elapsedMs", elapsed)
                put("timeoutMs", timeoutMs)
                put("count", found.size)
                put("error", reason ?: "")
                put("wanted", name)
                put("matches", buildJsonArray { found.forEach { add(nodeJson(it)) } })
                if (found.isNotEmpty()) {
                    val first = found.first()
                    put(
                        "text",
                        "displayId ${screen.displayId} now shows " + fieldSentence(first) +
                            " at ${first.bounds.toShortString()} after ${elapsed}ms",
                    )
                } else {
                    put(
                        "text",
                        "nothing on displayId ${screen.displayId} said \"$name\" within" +
                            " ${elapsed}ms" + (reason?.let { " ($it)" } ?: "") +
                            "; call lw_ui to see what the screen does say",
                    )
                }
            }
        }

        // 整棵树落文件: 与 lw_ui 不同, 布局容器也写下来。文本给前若干行, 完整的在工作区那个文件里
        "uiDump" -> {
            val screen = namedScreen(request)
            val limit = (request["limit"]?.jsonPrimitive?.intOrNull ?: MAX_DUMP_NODES)
                .coerceIn(1, MAX_DUMP_NODES)
            val dump = LwAccessibility.dump(screen.displayId, limit)
            // 落点与截图同一个地方 (工作区下的 screenshots/), 因为模型的路径工具只在那儿找得到
            val directory = appContext { Workspace.resolve(it).directory }
            val path = request["to"]?.jsonPrimitive?.contentOrNull?.let { java.io.File(it) }
                ?: java.io.File(directory, "ui-dump-${screen.displayId}.txt")
            val written = try {
                path.parentFile?.mkdirs()
                path.writeText(dump.text)
                true
            } catch (error: Throwable) {
                false
            }
            buildJsonObject {
                put("displayId", screen.displayId)
                put("nodes", dump.nodes)
                put("truncated", dump.truncated)
                put("path", if (written) path.absolutePath else "")
                put("error", dump.error ?: if (written) "" else "could not write $path")
                put("preview", dump.text.lines().take(DUMP_PREVIEW_LINES).joinToString("\n"))
                put(
                    "text",
                    if (dump.error != null) {
                        "could not read the tree of displayId ${screen.displayId}: ${dump.error}"
                    } else {
                        "wrote ${dump.nodes} nodes of displayId ${screen.displayId} to" +
                            " ${path.absolutePath}" +
                            (if (dump.truncated) " (stopped at the $limit node limit)" else "") +
                            "; the first $DUMP_PREVIEW_LINES lines are in preview"
                    },
                )
            }
        }

        else -> throw IllegalArgumentException("unknown method $method")
    }

    /** 这一批工具要一个 Context 才能做事, 拿不到就是 app 那一侧还没初始化 */
    private inline fun <T> appContext(block: (android.content.Context) -> T): T {
        val context = PrivilegedChannel.context()
            ?: throw IllegalStateException("this app has no context yet, so there is nothing to use")
        return block(context)
    }

    /**
     * The screen one request names
     *
     * A request that names no screen is refused rather than aimed at the selection, because every
     * call here changes something on a screen and the selection is the user's to change. An id
     * that points at nothing gets the reason the app remembers, so a screen the user closed from
     * the phone reads as the user's decision rather than as a bad id
     *
     * Display zero is the phone's own screen, which is the one display that is always there: it is
     * resolved out of the display manager rather than looked for in a list of ours, because it is
     * not ours
     */
    private fun namedScreen(request: JsonObject): ScreenState {
        val displayId = request["displayId"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("this call has to name the displayId it means")
        if (displayId == LwServiceProtocol.MAIN_DISPLAY) return VirtualScreen.mainScreen()
        return VirtualScreen.screens.firstOrNull { it.displayId == displayId }
            ?: throw IllegalArgumentException(VirtualScreen.missingScreenReason(displayId))
    }

    /**
     * 等这块屏上出现窗口, 给 `lw_launch` 用来自查
     *
     * `am start -W` 等到 activity 起来就返回, 而窗口进无障碍那份窗口列表要再晚一点, 所以这里是问几次
     * 而不是问一次就下结论 —— 否则一个慢一点的启动会被说成"什么都没有"
     */
    private fun waitForWindow(displayId: Int): Boolean {
        val deadline = System.currentTimeMillis() + WINDOW_SETTLE_MS
        while (true) {
            if (LwAccessibility.hasWindow(displayId)) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(WINDOW_POLL_MS)
        }
    }

    /**
     * 等这块屏上出现能打字的字段, 给"先按一下再打字"那条路用
     *
     * 按下去常常是打开一个新页面, 而那个页面的字段要等它画出来才在无障碍树里。等不到也不报错: 交给
     * `type` 自己走它的退路 (按键), 而答案里那句 `via` 会说清走的是哪一条
     */
    private fun waitForField(displayId: Int) {
        val deadline = System.currentTimeMillis() + FIELD_SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            if (LwAccessibility.hasEditable(displayId)) return
            Thread.sleep(WINDOW_POLL_MS)
        }
    }

    /**
     * 一次截图落到答案里的那几个字段
     *
     * **两份路径都由应用这一侧给** (见 [VirtualScreen.Shot]): 上面照着文件名去猜缩放那份叫什么, 已经
     * 猜错过一次 —— `.model` 那个后缀加两遍就得到一个从来没有过的文件
     */
    private fun shotJson(shot: VirtualScreen.Shot, region: Rect?): JsonObject = buildJsonObject {
        put("path", shot.fitted.file.absolutePath)
        put("bytes", shot.fitted.file.length())
        put("fullPath", shot.full.absolutePath)
        put("picture", buildJsonObject {
            put("width", shot.fitted.width)
            put("height", shot.fitted.height)
            put("scale", shot.fitted.scale)
            // 只要了屏幕的一块时, 图上的点先按 scale 放大、再加上这个偏移, 才是屏幕上的点
            put("left", region?.left ?: 0)
            put("top", region?.top ?: 0)
        })
    }

    /**
     * 截图只要屏幕的一块时, 那一块在哪
     *
     * 四个数**都给**才算数: 只给一部分是调用方弄错了, 而不是"默认整屏"—— 那种默认会让它以为自己在看
     * 一块, 实际拿到的是整屏
     */
    private fun region(request: JsonObject): Rect? {
        val x = request["x"]?.jsonPrimitive?.intOrNull
        val y = request["y"]?.jsonPrimitive?.intOrNull
        val width = request["width"]?.jsonPrimitive?.intOrNull
        val height = request["height"]?.jsonPrimitive?.intOrNull
        if (x == null && y == null && width == null && height == null) return null
        require(x != null && y != null && width != null && height != null) {
            "a region needs all four of x, y, width and height: giving some of them is not a region"
        }
        require(width > 0 && height > 0) { "a region needs a positive width and height" }
        return Rect(x, y, x + width, y + height)
    }

    /**
     * The key one request names
     *
     * A name and a number are both accepted, because a caller may have either: the name is the
     * readable one, and the one whose mistakes can be refused instead of acted on, while the number
     * is what an Android reference is full of. What is not accepted is a name Android does not have
     * or a number it does not define, and the refusal says what to use instead - keycode 5 is a
     * call and 26 is the power key, so an unchecked number is a wrong action rather than an error
     */
    private fun namedKey(request: JsonObject): Int {
        val name = request["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val code = request["code"]?.jsonPrimitive?.intOrNull
        if (name.isNotEmpty() && code != null) {
            throw IllegalArgumentException("this call names the key either by key or by code, not both")
        }
        if (name.isEmpty() && code == null) {
            throw IllegalArgumentException(
                "this call has to name the key it means, by name (\"BACK\") or by code (4)",
            )
        }
        if (code != null) {
            if (!KeyCodes.known(code)) {
                throw IllegalArgumentException("this platform has no key with code $code")
            }
            return code
        }
        return KeyCodes.resolve(name) ?: throw IllegalArgumentException(
            "Android has no key called \"$name\" - name it the way Android does, for example" +
                " ${KeyCodes.COMMON.joinToString(", ")}",
        )
    }

    /** 无障碍那几件事实, 给 `probe` 用 */
    private fun JsonObjectBuilder.accessibilityJson(state: AccessibilityState) {
        put("component", state.component)
        put("listed", state.componentListed)
        put("otherServices", state.otherServices)
        put("masterSwitch", state.masterSwitch)
        put("running", state.running)
        put("installer", state.installer)
        put("restrictedSettings", state.restrictedSettings)
        // 三态的理由一句话说不完, 所以两个键都给: 一个给布尔, 一个给"是哪一种"
        put("writesAccepted", state.writeChannelOpen)
        put("writeChannel", state.writeChannel.name.lowercase())
        // 三态: true/false 是设备答的, null 是这台设备根本没有那个类 (API 37 以下)
        put("advancedProtection", state.advancedProtection)
        put("healthy", state.healthy)
        put("reason", state.reason())
    }

    /**
     * 通知使用权那几件事实
     *
     * 三态照旧: `granted` / `banners` 是设备答的, null 是"这台设备问不到" (横幅那条要 API 34)
     */
    private fun JsonObjectBuilder.notificationJson(state: NotificationState) {
        put("component", state.component)
        put("listed", state.componentListed)
        put("otherListeners", state.otherListeners)
        put("running", state.running)
        put("granted", state.granted)
        put("banners", state.banners)
        put("healthy", state.healthy)
        put("reason", state.reason())
    }

    /** One coordinate out of a request */
    private fun number(request: JsonObject, key: String): Float =
        request[key]?.jsonPrimitive?.floatOrNull
            ?: throw IllegalArgumentException("$key has to be a number")

    /** One coordinate out of a request, with a fallback for the callers that left it out */
    private fun number(request: JsonObject, key: String, fallback: Double): Double =
        request[key]?.jsonPrimitive?.doubleOrNull ?: fallback

    /** The points of one gesture path, as `{ "x": ..., "y": ... }` objects */
    private fun JsonObject.points(): List<Pair<Float, Float>> =
        (this["points"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { point ->
                val x = point["x"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
                val y = point["y"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
                x to y
            }
            .orEmpty()

    /**
     * The package names in what `pm list packages` printed
     *
     * One `package:<name>` line per package, which is the command's own shape; anything else it
     * printed (an error, a warning) is not a package and is dropped rather than mistaken for one
     */
    private fun installedPackages(listing: String): Set<String> = listing.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(PACKAGE_MARK) }
        .map { it.removePrefix(PACKAGE_MARK).trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    /**
     * The package a name means, when that name is not a package
     *
     * A caller usually has the name a person uses rather than the package, so the same name
     * matching is done here as pressing by name does: an exact package first, then an exact label,
     * then a label or package containing it. One match is used, none is handed back unchanged (it
     * may be a package no launcher icon points at), and more than one is refused with the choices,
     * because starting the wrong app is worse than not starting one
     */
    private fun resolvePackage(asked: String): String {
        val name = asked.trim()
        if (name.isEmpty()) return asked
        val matches = try {
            LwApps.resolve(name)
        } catch (problem: Throwable) {
            Log.w(TAG, "could not look up \"$name\" among the launcher's apps", problem)
            return asked
        }
        return when (matches.size) {
            0 -> asked
            1 -> matches.first().packageName
            else -> throw IllegalArgumentException(
                "\"$name\" reaches ${matches.size} apps, so nothing was started: " +
                    matches.take(MAX_CANDIDATES).joinToString(", ") {
                        "${it.label} (${it.packageName})"
                    } + " - name the one you mean by package",
            )
        }
    }

    /**
     * How long a press is to be held, which the caller may leave out
     *
     * Clamped here as well as in the tool, because this side does not know who is asking: the
     * gesture queue is blocked for the whole of a hold, so a hold nobody meant is a call that looks
     * like it hung
     */
    private fun hold(request: JsonObject): Long =
        (request["holdMs"]?.jsonPrimitive?.longOrNull ?: 0L)
            .coerceIn(0L, LwServiceProtocol.MAX_HOLD_MS)

    /**
     * What typing did, in the shape a tool reads it
     *
     * @param via `field` when the platform wrote the text into a field, `keys` when it went in as
     *   key presses because the screen reported no field
     * @param written how many characters went in, which is the caller's own text when a field took
     *   it and what the keyboard could produce when keys did
     * @param reading what the field says afterwards, empty when there was none or it hides it
     */
    private fun typingJson(
        screen: ScreenState,
        via: String,
        written: Int,
        reading: String,
        password: Boolean,
        field: UiNode?,
    ) = buildJsonObject {
        put("displayId", screen.displayId)
        put("label", screen.label)
        put("via", via)
        put("written", written)
        put("text", reading)
        put("password", password)
        put("field", buildJsonObject {
            if (field == null) return@buildJsonObject
            put("className", field.className)
            put("text", field.text)
            put("description", field.description)
            put("viewId", field.viewId)
            put("bounds", rectJson(field.bounds))
        })
    }

    /** One field, said the way a caller can find it again on the screen */
    private fun fieldSentence(node: UiNode): String {
        val said = node.text.ifEmpty { node.description }
        val name = if (said.isEmpty()) node.className else "${node.className} \"$said\""
        return "$name at [${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}]"
    }

    /** One node of a screen, in the shape a tool reads it */
    private fun nodeJson(node: UiNode) = buildJsonObject {
        put("className", node.className)
        put("text", node.text)
        put("description", node.description)
        put("viewId", node.viewId)
        put("depth", node.depth)
        put("clickable", node.clickable)
        put("scrollable", node.scrollable)
        put("editable", node.editable)
        put("checkable", node.checkable)
        put("checked", node.checked)
        put("bounds", rectJson(node.bounds))
        node.target?.let { put("target", rectJson(it)) }
    }

    /** A rectangle as four numbers, in the order a caller reads them: left, top, right, bottom */
    private fun rectJson(rect: Rect) = buildJsonArray {
        add(rect.left)
        add(rect.top)
        add(rect.right)
        add(rect.bottom)
    }

    /** How long a swipe takes when the caller does not say, which is a deliberate drag */
    private const val DEFAULT_SWIPE_MS = 300L

    /** 等一个控件出现的两条边界: 轮询的间隔, 与一次调用最多等多久 (压在桥的 120 秒读超时之下) */
    private const val POLL_STEP_MS = 300L
    private const val DEFAULT_WAIT_MS = 15_000L
    private const val MAX_WAIT_MS = 90_000L

    /** 整棵树落文件的节点上限, 以及回给模型的预览行数 */
    private const val MAX_DUMP_NODES = 4_000
    private const val DUMP_PREVIEW_LINES = 40
    /** 一次调用最多连着滚几屏, 与无障碍那侧的钳制对齐 */
    private const val MAX_SCROLL_TIMES = 10

    /** More text than this in one call is a caller that has lost track, and one parcel to prove it */
    private const val MAX_TYPED_CHARS = 4_096

    /** What `am start -W` prints when the activity did come up, which is am's own verdict */
    private const val STARTED_MARK = "Status: ok"

    /** A cold start on a busy device is slow, but it is not this slow */
    private const val DEFAULT_LAUNCH_MS = 20_000L
    private const val MIN_LAUNCH_MS = 1_000L
    private const val MAX_LAUNCH_MS = 60_000L

    /** 起完之后窗口落进无障碍那份列表要给多久, 以及多久问一次 */
    private const val WINDOW_SETTLE_MS = 1_500L
    private const val WINDOW_POLL_MS = 150L

    /** 连拍最多几张: 12 张够看一段过程, 而再多就已经是一段视频, 模型一次也读不了那么多图 */
    private const val MAX_SERIES = 12

    /** 连拍两张之间默认隔多久, 以及它的上下限: 一次截图本身要两三百毫秒, 所以 50ms 是"贴着拍" */
    private const val DEFAULT_SERIES_GAP_MS = 120L
    private const val MIN_SERIES_GAP_MS = 50L
    private const val MAX_SERIES_GAP_MS = 5_000L

    /** 按下一点之后等那个字段出现等多久 (一个页面画出来的时间) */
    private const val FIELD_SETTLE_MS = 1_500L

    /** More apps than this in one answer is a listing nobody reads, so it is cut and said so */
    private const val MAX_APPS = 400

    /** How many of an ambiguous name's candidates are named back, matching pressing by name */
    private const val MAX_CANDIDATES = 12

    /** One line of `pm list packages` looks like this, and nothing else it prints does */
    private const val PACKAGE_MARK = "package:"

    /** Said when the launcher query came back with nothing at all, which means it could not be asked */
    private const val NO_LAUNCHER_APPS =
        "the launcher query came back empty, which means this app could not ask the package" +
            " manager rather than that the device has nothing to start"

    private fun failure(message: String): String =
        buildJsonObject {
            put("ok", false)
            put("error", message)
        }.toString()
}
