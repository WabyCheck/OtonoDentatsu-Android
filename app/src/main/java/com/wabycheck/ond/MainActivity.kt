package com.wabycheck.ond

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var editTextIP: EditText
    private lateinit var editTextPort: EditText
    private lateinit var buttonStart: Button
    private lateinit var buttonStop: Button

    private var bound = false
    private var service: AudioStreamService? = null

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Разрешение на уведомления отклонено", Toast.LENGTH_SHORT).show()
            }
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == AudioStreamService.ACTION_STATE) {
                val running = intent.getBooleanExtra(AudioStreamService.EXTRA_IS_RUNNING, false)
                applyRunningState(running)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? AudioStreamService.AudioStreamBinder
            service = b?.getService()
            bound = true
            applyRunningState(service?.isStreamRunning() == true)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация Views
        editTextIP = findViewById(R.id.editTextIP)
        editTextPort = findViewById(R.id.editTextPort)
        buttonStart = findViewById(R.id.buttonStart)
        buttonStop = findViewById(R.id.buttonStop)

        // Установка значений по умолчанию
        editTextIP.setText("192.168.0.100")
        editTextPort.setText("5000")

        buttonStart.setOnClickListener {
            ensureNotificationPermission()

            val ip = editTextIP.text.toString()
            val portText = editTextPort.text.toString()

            if (ip.isEmpty() || portText.isEmpty()) {
                Toast.makeText(this, "Заполните IP и порт", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val port = portText.toIntOrNull()
            if (port == null) {
                Toast.makeText(this, "Порт должен быть числом", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, AudioStreamService::class.java).apply {
                action = AudioStreamService.ACTION_START
                putExtra(AudioStreamService.EXTRA_PORT, port)
            }
            ContextCompat.startForegroundService(this, intent)

            buttonStart.isEnabled = false
            buttonStop.isEnabled = true
            Toast.makeText(this, "Слушаем на порту $port", Toast.LENGTH_SHORT).show()
        }

        buttonStop.setOnClickListener {
            val intent = Intent(this, AudioStreamService::class.java).apply {
                action = AudioStreamService.ACTION_STOP
            }
            startService(intent)

            buttonStart.isEnabled = true
            buttonStop.isEnabled = false
            Toast.makeText(this, "Остановлено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        // Подписка на состояние
        registerReceiver(stateReceiver, IntentFilter(AudioStreamService.ACTION_STATE))
        // Привязка к сервису для запроса текущего состояния
        bindService(Intent(this, AudioStreamService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(stateReceiver)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun applyRunningState(running: Boolean) {
        buttonStart.isEnabled = !running
        buttonStop.isEnabled = running
    }
}