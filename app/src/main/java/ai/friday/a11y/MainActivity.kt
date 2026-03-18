package ai.friday.a11y

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("friday_a11y", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = "Friday A11y Service"
            textSize = 24f
        })

        layout.addView(TextView(this).apply {
            text = "\nGateway URL (for remote control):"
            textSize = 14f
        })

        val urlInput = EditText(this).apply {
            hint = "https://your-gateway.example.com"
            setText(prefs.getString("gateway_url", ""))
            setSingleLine(true)
        }
        layout.addView(urlInput)

        layout.addView(TextView(this).apply {
            text = "Gateway Token:"
            textSize = 14f
        })

        val tokenInput = EditText(this).apply {
            hint = "your-gateway-token"
            setText(prefs.getString("gateway_token", ""))
            setSingleLine(true)
        }
        layout.addView(tokenInput)

        layout.addView(Button(this).apply {
            text = "Save & Start Polling"
            setOnClickListener {
                prefs.edit()
                    .putString("gateway_url", urlInput.text.toString().trimEnd('/'))
                    .putString("gateway_token", tokenInput.text.toString())
                    .apply()
                Toast.makeText(this@MainActivity, "Saved! Restart A11y service to apply.", Toast.LENGTH_SHORT).show()
            }
        })

        layout.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        layout.addView(Button(this).apply {
            text = "Open Notification Listener Settings"
            setOnClickListener {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        })

        layout.addView(TextView(this).apply {
            text = "\nLocal HTTP API: port 7333\nRemote: polls gateway for commands\n\nv2: +notifications +setText +longPress +scroll +waitForChange +currentApp +findText"
            textSize = 12f
        })

        setContentView(layout)
    }
}
