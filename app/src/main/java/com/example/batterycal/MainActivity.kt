package com.example.batterycal

import android.app.Activity
import android.content.*
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var testTxt: TextView
    private lateinit var capIn: EditText
    private var startLevel = -1
    private var startCounter = 0L
    private val prefs by lazy { getSharedPreferences("p", 0) }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = update(i)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        info = TextView(this).apply { textSize = 16f }
        capIn = EditText(this).apply {
            hint = "Design capacity in mAh (e.g. 5000)"
            inputType = InputType.TYPE_CLASS_NUMBER
            val saved = prefs.getFloat("design", 0f)
            val d = if (saved > 0) saved.toDouble() else defaultDesign()
            if (d > 0) setText(d.toInt().toString())
        }
        val save = Button(this).apply {
            text = "Save design capacity"
            setOnClickListener {
                prefs.edit().putFloat("design", capIn.text.toString().toFloatOrNull() ?: 0f).apply()
                registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { update(it) }
            }
        }
        val test = Button(this).apply {
            text = "Start calibration / capacity test"
            setOnClickListener {
                val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@setOnClickListener
                startLevel = pct(i)
                startCounter = counter()
                update(i)
            }
        }
        testTxt = TextView(this).apply { textSize = 14f; setPadding(0, pad, 0, 0) }
        val guide = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            text = "How to calibrate (no root):\n" +
                "1. Use the phone until it shuts off, or drops below ~5%.\n" +
                "2. Charge to 100% without turning it on / using it, then keep it plugged in 1 more hour.\n" +
                "3. Unplug, restart. Then tap 'Start test' below at a low %, charge by 15%+ and read the measured capacity.\n\n" +
                "Note: Android can't reset the battery chip without root; this just gives the gauge a full learning cycle and measures real capacity."
        }
        listOf(info, capIn, save, test, testTxt, guide).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); registerReceiver(rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    override fun onPause() { super.onPause(); unregisterReceiver(rx) }

    private fun pct(i: Intent): Int {
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return l * 100 / s
    }

    private fun counter(): Long =
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) // µAh

    private fun defaultDesign(): Double = try {
        val c = Class.forName("com.android.internal.os.PowerProfile")
        c.getMethod("getBatteryCapacity").invoke(c.getConstructor(Context::class.java).newInstance(this)) as Double
    } catch (e: Throwable) { 0.0 }

    private fun update(i: Intent) {
        val p = pct(i)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            else -> "Unknown"
        }
        val cycles = if (Build.VERSION.SDK_INT >= 34) i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) else -1
        val cnt = counter()
        val design = capIn.text.toString().toDoubleOrNull() ?: 0.0
        val est = if (cnt > 0 && p >= 15) cnt / 1000.0 / (p / 100.0) else 0.0

        val sb = StringBuilder()
        sb.append("Level: $p%  ${if (plugged) "(charging)" else ""}\n")
        sb.append("Status: $health\nTemp: $temp °C\nVoltage: $volt mV\n")
        sb.append("Cycle count: ${if (cycles >= 0) cycles else "not reported"}\n")
        if (est > 0) {
            sb.append("Estimated full capacity: ${est.toInt()} mAh\n")
            if (design > 0) sb.append("Health: ${"%.0f".format(est / design * 100).toInt().coerceAtMost(100)}% of design\n")
        } else sb.append("Estimated capacity: unavailable (needs ≥15% and a supported device)\n")
        info.text = sb.toString()

        if (startLevel >= 0) {
            val dl = p - startLevel
            testTxt.text = if (cnt <= 0) "Charge counter not supported on this device."
            else if (dl >= 15) {
                val cap = (cnt - startCounter) / 1000.0 / (dl / 100.0)
                "Measured capacity: ${cap.toInt()} mAh (over +$dl%)" +
                    if (design > 0) "\nHealth: ${(cap / design * 100).toInt().coerceAtMost(100)}%" else ""
            } else "Test running: charged +$dl% so far. Keep charging until +15% or more."
        }
    }
}
