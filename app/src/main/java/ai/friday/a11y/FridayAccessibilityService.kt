package ai.friday.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FridayAccessibilityService : AccessibilityService() {

    private var server: A11yServer? = null
    private var pollTimer: Timer? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Gateway relay config — polls gateway HTTP endpoint for commands
    private var gatewayUrl = ""
    private var gatewayToken = ""
    
    // Current app tracking
    @Volatile var currentPackage: String = ""
    @Volatile var currentActivity: String = ""
    
    // UI change detection
    @Volatile var uiChangeLatch: CountDownLatch? = null
    
    companion object {
        private const val TAG = "FridayA11y"
        private const val POLL_INTERVAL_MS = 2000L
        var instance: FridayAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        // Start local HTTP server
        server = A11yServer(this, 7333)
        server?.start()
        Log.i(TAG, "A11y service started, HTTP server on :7333")
        
        // Load gateway config from shared prefs
        val prefs = applicationContext.getSharedPreferences("friday_a11y", MODE_PRIVATE)
        gatewayUrl = prefs.getString("gateway_url", "") ?: ""
        gatewayToken = prefs.getString("gateway_token", "") ?: ""
        
        if (gatewayUrl.isNotEmpty()) {
            startPolling()
        }
        
        // Wire notification push to relay
        FridayNotificationListener.onNotification = { notification ->
            pushNotificationToRelay(notification)
        }
    }

    private fun startPolling() {
        pollTimer?.cancel()
        pollTimer = Timer()
        pollTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                pollGateway()
            }
        }, 0, POLL_INTERVAL_MS)
        Log.i(TAG, "Started polling gateway: $gatewayUrl")
    }
    
    private fun pollGateway() {
        try {
            val url = URL("$gatewayUrl/poll")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $gatewayToken")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            
            if (conn.responseCode == 200) {
                val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(body)
                if (json.has("command")) {
                    val result = executeCommand(json)
                    postResult(json.optString("id", ""), result)
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Poll failed: ${e.message}")
        }
    }
    
    private fun postResult(commandId: String, result: JSONObject) {
        try {
            val url = URL("$gatewayUrl/result")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $gatewayToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            
            val payload = JSONObject()
            payload.put("id", commandId)
            payload.put("result", result)
            
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            conn.responseCode // trigger
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Post result failed: ${e.message}")
        }
    }
    
    private fun executeCommand(json: JSONObject): JSONObject {
        return when (json.getString("command")) {
            "screen" -> getScreenTree()
            "click" -> {
                val params = json.getJSONObject("params")
                val success = if (params.has("text")) {
                    clickByText(params.getString("text"))
                } else if (params.has("x") && params.has("y")) {
                    clickAt(params.getDouble("x").toFloat(), params.getDouble("y").toFloat())
                } else false
                JSONObject().put("ok", success)
            }
            "type" -> {
                val params = json.getJSONObject("params")
                JSONObject().put("ok", typeText(params.getString("text")))
            }
            "setText" -> {
                val params = json.getJSONObject("params")
                JSONObject().put("ok", setText(params.getString("text")))
            }
            "longPress" -> {
                val params = json.getJSONObject("params")
                JSONObject().put("ok", longPressAt(
                    params.getDouble("x").toFloat(),
                    params.getDouble("y").toFloat(),
                    params.optLong("durationMs", 1000)
                ))
            }
            "scroll" -> {
                val params = json.getJSONObject("params")
                val direction = params.optString("direction", "down")
                JSONObject().put("ok", scroll(direction))
            }
            "back" -> JSONObject().put("ok", pressBack())
            "home" -> JSONObject().put("ok", pressHome())
            "recents" -> JSONObject().put("ok", pressRecents())
            "notifications" -> JSONObject().put("ok", pressNotifications())
            "swipe" -> {
                val params = json.getJSONObject("params")
                JSONObject().put("ok", swipe(
                    params.getDouble("startX").toFloat(),
                    params.getDouble("startY").toFloat(),
                    params.getDouble("endX").toFloat(),
                    params.getDouble("endY").toFloat(),
                    params.optLong("durationMs", 300)
                ))
            }
            "currentApp" -> {
                JSONObject()
                    .put("package", currentPackage)
                    .put("activity", currentActivity)
            }
            "waitForChange" -> {
                val timeoutMs = json.optLong("timeoutMs", 5000)
                val latch = CountDownLatch(1)
                uiChangeLatch = latch
                val changed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                uiChangeLatch = null
                JSONObject().put("changed", changed)
            }
            "getNotifications" -> {
                val params = json.optJSONObject("params")
                val limit = params?.optInt("limit", 20) ?: 20
                val pkg = params?.optString("package")
                val listener = FridayNotificationListener.instance
                if (listener != null) {
                    JSONObject().put("notifications", listener.getRecentNotifications(limit, pkg))
                } else {
                    JSONObject().put("error", "notification_listener_not_connected")
                }
            }
            "getActiveNotifications" -> {
                val listener = FridayNotificationListener.instance
                if (listener != null) {
                    JSONObject().put("notifications", listener.getActiveNotifs())
                } else {
                    JSONObject().put("error", "notification_listener_not_connected")
                }
            }
            "findText" -> {
                val params = json.getJSONObject("params")
                JSONObject().put("results", findTextNodes(params.getString("text")))
            }
            "ping" -> JSONObject().put("status", "ok").put("service", "friday-a11y").put("version", 2)
            else -> JSONObject().put("error", "unknown_command")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Track current app/activity
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { currentPackage = it }
            event.className?.toString()?.let { currentActivity = it }
        }
        // Signal UI change waiters
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            uiChangeLatch?.countDown()
        }
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        pollTimer?.cancel()
        server?.stop()
        instance = null
        super.onDestroy()
    }

    fun getScreenTree(): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().put("error", "no_window")
        val result = JSONObject()
        result.put("timestamp", System.currentTimeMillis())
        result.put("tree", nodeToJson(root, 0))
        return result
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, depth: Int): JSONObject {
        val obj = JSONObject()
        obj.put("cls", node.className?.toString()?.substringAfterLast('.') ?: "")
        if (node.text?.isNotEmpty() == true) obj.put("text", node.text.toString())
        if (node.contentDescription?.isNotEmpty() == true) obj.put("desc", node.contentDescription.toString())
        if (node.viewIdResourceName?.isNotEmpty() == true) obj.put("id", node.viewIdResourceName)
        if (node.isClickable) obj.put("click", true)
        if (node.isEditable) obj.put("edit", true)
        if (node.isScrollable) obj.put("scroll", true)
        if (node.isChecked) obj.put("checked", true)

        val rect = Rect()
        node.getBoundsInScreen(rect)
        obj.put("b", "${rect.left},${rect.top},${rect.right},${rect.bottom}")

        if (depth < 15) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    children.put(nodeToJson(child, depth + 1))
                }
            }
            if (children.length() > 0) obj.put("c", children)
        }

        return obj
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
            }
        }
        return false
    }

    fun clickAt(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPressAt(x: Float, y: Float, durationMs: Long = 1000): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // Try focused input first
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        // Fall back: find first editable node
        val editable = findFirstEditable(root)
        if (editable != null) {
            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditable(child)
            if (result != null) return result
        }
        return null
    }

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollable(root) ?: return false
        return when (direction) {
            "down" -> scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "up" -> scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "right" -> scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "left" -> scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            else -> false
        }
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstScrollable(child)
            if (result != null) return result
        }
        return null
    }

    fun findTextNodes(text: String): JSONArray {
        val arr = JSONArray()
        val root = rootInActiveWindow ?: return arr
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            arr.put(JSONObject().apply {
                put("text", node.text?.toString() ?: "")
                put("desc", node.contentDescription?.toString() ?: "")
                put("cls", node.className?.toString()?.substringAfterLast('.') ?: "")
                put("clickable", node.isClickable)
                put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
                put("centerX", (rect.left + rect.right) / 2)
                put("centerY", (rect.top + rect.bottom) / 2)
            })
        }
        return arr
    }

    fun pressNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    private fun pushNotificationToRelay(notification: JSONObject) {
        if (gatewayUrl.isEmpty()) return
        Thread {
            try {
                val url = URL("$gatewayUrl/notification")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $gatewayToken")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                OutputStreamWriter(conn.outputStream).use { it.write(notification.toString()) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Push notification to relay failed: ${e.message}")
            }
        }.start()
    }
}

class A11yServer(private val service: FridayAccessibilityService, port: Int) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
                method == Method.GET && uri == "/screen" -> {
                    jsonOk(service.getScreenTree())
                }
                method == Method.POST && uri == "/click" -> {
                    val json = parseBody(session)
                    val success = if (json.has("text")) {
                        service.clickByText(json.getString("text"))
                    } else if (json.has("x") && json.has("y")) {
                        service.clickAt(json.getDouble("x").toFloat(), json.getDouble("y").toFloat())
                    } else false
                    jsonOk(JSONObject().put("ok", success))
                }
                method == Method.POST && uri == "/type" -> {
                    val json = parseBody(session)
                    jsonOk(JSONObject().put("ok", service.typeText(json.getString("text"))))
                }
                method == Method.POST && uri == "/setText" -> {
                    val json = parseBody(session)
                    jsonOk(JSONObject().put("ok", service.setText(json.getString("text"))))
                }
                method == Method.POST && uri == "/longPress" -> {
                    val json = parseBody(session)
                    jsonOk(JSONObject().put("ok", service.longPressAt(
                        json.getDouble("x").toFloat(),
                        json.getDouble("y").toFloat(),
                        json.optLong("durationMs", 1000)
                    )))
                }
                method == Method.POST && uri == "/scroll" -> {
                    val json = parseBody(session)
                    jsonOk(JSONObject().put("ok", service.scroll(json.optString("direction", "down"))))
                }
                method == Method.POST && uri == "/back" -> jsonOk(JSONObject().put("ok", service.pressBack()))
                method == Method.POST && uri == "/home" -> jsonOk(JSONObject().put("ok", service.pressHome()))
                method == Method.POST && uri == "/recents" -> jsonOk(JSONObject().put("ok", service.pressRecents()))
                method == Method.POST && uri == "/notifications" -> jsonOk(JSONObject().put("ok", service.pressNotifications()))
                method == Method.POST && uri == "/swipe" -> {
                    val json = parseBody(session)
                    jsonOk(JSONObject().put("ok", service.swipe(
                        json.getDouble("startX").toFloat(),
                        json.getDouble("startY").toFloat(),
                        json.getDouble("endX").toFloat(),
                        json.getDouble("endY").toFloat(),
                        json.optLong("durationMs", 300)
                    )))
                }
                method == Method.GET && uri == "/currentApp" -> {
                    jsonOk(JSONObject()
                        .put("package", service.currentPackage)
                        .put("activity", service.currentActivity))
                }
                method == Method.POST && uri == "/findText" -> {
                    val json = parseBody(session)
                    jsonOk(JSONObject().put("results", service.findTextNodes(json.getString("text"))))
                }
                method == Method.POST && uri == "/waitForChange" -> {
                    // This blocks — use with care
                    val json = parseBody(session)
                    val timeoutMs = json.optLong("timeoutMs", 5000)
                    val latch = java.util.concurrent.CountDownLatch(1)
                    service.uiChangeLatch = latch
                    val changed = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    service.uiChangeLatch = null
                    jsonOk(JSONObject().put("changed", changed))
                }
                method == Method.GET && uri == "/getNotifications" -> {
                    val limit = session.parms["limit"]?.toIntOrNull() ?: 20
                    val pkg = session.parms["package"]
                    val listener = FridayNotificationListener.instance
                    if (listener != null) {
                        jsonOk(JSONObject().put("notifications", listener.getRecentNotifications(limit, pkg)))
                    } else {
                        jsonOk(JSONObject().put("error", "notification_listener_not_connected"))
                    }
                }
                method == Method.GET && uri == "/getActiveNotifications" -> {
                    val listener = FridayNotificationListener.instance
                    if (listener != null) {
                        jsonOk(JSONObject().put("notifications", listener.getActiveNotifs()))
                    } else {
                        jsonOk(JSONObject().put("error", "notification_listener_not_connected"))
                    }
                }
                method == Method.GET && uri == "/ping" -> {
                    jsonOk(JSONObject().put("status", "ok").put("service", "friday-a11y").put("version", 2))
                }
                else -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                        JSONObject().put("error", "not_found").toString())
                }
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("error", e.message).toString())
        }
    }

    private fun jsonOk(json: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())

    private fun parseBody(session: IHTTPSession): JSONObject {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return JSONObject(map["postData"] ?: "{}")
    }
}
