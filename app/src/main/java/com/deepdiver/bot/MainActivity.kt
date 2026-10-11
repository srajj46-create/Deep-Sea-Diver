package com.deepdiver.bot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var levelBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Deep Sea Diver BOT"
            textSize = 24f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "\nStep 1: Accessibility chalu karo"
            textSize = 16f
        })
        root.addView(Button(this).apply {
            text = "Accessibility settings kholo"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(TextView(this).apply {
            text = "\nStep 2: Screen capture allow karo"
            textSize = 16f
        })
        root.addView(Button(this).apply {
            text = "Screen capture allow karo"
            setOnClickListener {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), 100)
            }
        })

        root.addView(TextView(this).apply {
            text = "\nStep 3: Abhi game me kaunsa level khelna hai (menu me LEVEL N likha hota hai)"
            textSize = 16f
        })
        levelBox = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.level(this@MainActivity).toString())
        }
        root.addView(levelBox)
        root.addView(Button(this).apply {
            text = "Level save karo"
            setOnClickListener {
                val v = levelBox.text.toString().toIntOrNull() ?: 1
                Prefs.setLevel(this@MainActivity, v.coerceAtLeast(1))
                Toast.makeText(this@MainActivity, "Level $v save", Toast.LENGTH_SHORT).show()
                refresh()
            }
        })

        root.addView(TextView(this).apply {
            text = "\nStep 4: Game kholo, neeche-right me floating START button dabao. Band karne ke liye wahi STOP."
            textSize = 16f
        })

        status = TextView(this).apply { textSize = 15f; setPadding(0, pad, 0, 0) }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, ProjectionService::class.java)
                i.putExtra(ProjectionService.EXTRA_CODE, resultCode)
                i.putExtra(ProjectionService.EXTRA_DATA, data)
                startForegroundService(i)
                Toast.makeText(this, "Screen capture chalu", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission nahi mili", Toast.LENGTH_SHORT).show()
            }
            status.postDelayed({ refresh() }, 800)
        }
    }

    private fun accessibilityOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun refresh() {
        val acc = if (accessibilityOn()) "ON" else "OFF"
        val cap = if (FrameStore.frame != null) "ON" else "OFF"
        status.text = "Accessibility: $acc\nScreen capture: $cap\nSave kiya hua level: ${Prefs.level(this)}"
    }
}
