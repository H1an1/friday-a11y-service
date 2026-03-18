package ai.friday.a11y

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class FridayNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "FridayNotify"
        private const val MAX_HISTORY = 50
        var instance: FridayNotificationListener? = null
        
        // Recent notifications ring buffer
        val recentNotifications = mutableListOf<JSONObject>()
        
        // Callbacks for real-time push
        var onNotification: ((JSONObject) -> Unit)? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val notification = sbn.notification
            val extras = notification.extras
            
            val json = JSONObject().apply {
                put("package", sbn.packageName)
                put("timestamp", sbn.postTime)
                put("title", extras.getCharSequence("android.title")?.toString() ?: "")
                put("text", extras.getCharSequence("android.text")?.toString() ?: "")
                put("subText", extras.getCharSequence("android.subText")?.toString() ?: "")
                put("bigText", extras.getCharSequence("android.bigText")?.toString() ?: "")
                put("key", sbn.key)
                put("id", sbn.id)
                put("ongoing", sbn.isOngoing)
                put("category", notification.category ?: "")
            }
            
            synchronized(recentNotifications) {
                recentNotifications.add(0, json)
                while (recentNotifications.size > MAX_HISTORY) {
                    recentNotifications.removeAt(recentNotifications.size - 1)
                }
            }
            
            Log.i(TAG, "Notification: ${json.optString("package")} — ${json.optString("title")}: ${json.optString("text")}")
            
            // Push to callback (relay will pick this up)
            onNotification?.invoke(json)
            
        } catch (e: Exception) {
            Log.w(TAG, "Error processing notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Could track dismissals if needed
    }

    fun getRecentNotifications(limit: Int = 20, packageFilter: String? = null): JSONArray {
        val arr = JSONArray()
        synchronized(recentNotifications) {
            var count = 0
            for (n in recentNotifications) {
                if (packageFilter != null && n.optString("package") != packageFilter) continue
                arr.put(n)
                count++
                if (count >= limit) break
            }
        }
        return arr
    }

    fun getActiveNotifs(): JSONArray {
        val arr = JSONArray()
        try {
            val sbns = getActiveNotifications()
            if (sbns != null) {
                for (sbn in sbns) {
                    val extras = sbn.notification.extras
                    arr.put(JSONObject().apply {
                        put("package", sbn.packageName)
                        put("title", extras.getCharSequence("android.title")?.toString() ?: "")
                        put("text", extras.getCharSequence("android.text")?.toString() ?: "")
                        put("key", sbn.key)
                        put("ongoing", sbn.isOngoing)
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting active notifications: ${e.message}")
        }
        return arr
    }
}
