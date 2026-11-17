package dev.enov.ytm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class OutputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = MusicRepository(this)
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setPadding(24, 24, 24, 24) }
        column.addView(TextView(this).apply { text = "Audio output"; textSize = 24f })
        column.addView(TextView(this).apply { text = "Choose a connected output. Android may apply its own routing rules." })
        val group = RadioGroup(this)
        val selected = repository.number("outputDevice", -1f).toInt()
        val default = RadioButton(this).apply { id = android.view.View.generateViewId(); text = "System default"; isChecked = selected == -1; setOnClickListener { repository.setNumber("outputDevice", -1f) } }
        group.addView(default)
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
            group.addView(RadioButton(this).apply { id = android.view.View.generateViewId(); text = device.productName.toString(); isChecked = selected == device.id; setOnClickListener { repository.setNumber("outputDevice", device.id.toFloat()) } })
        }
        column.addView(group)
        column.addView(Button(this).apply { text = "Connect a Bluetooth device"; setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } })
        column.addView(Button(this).apply { text = "Done"; setOnClickListener { finish() } })
        setContentView(column)
    }
}
