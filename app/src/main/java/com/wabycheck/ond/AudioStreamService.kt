package com.wabycheck.ond

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AudioStreamService : Service(), UDPReceiver.OnPacketReceivedListener {

    private val binder = AudioStreamBinder()
    private var udpReceiver: UDPReceiver? = null
    private var audioTrack: AudioTrack? = null
    private var isRunning = false

    companion object {
        const val CHANNEL_ID = "AudioStreamChannelV2"
        const val NOTIFICATION_ID = 1

        // Действия для уведомлений
        const val ACTION_START = "START_AUDIO_STREAM"
        const val ACTION_STOP = "STOP_AUDIO_STREAM"

        // Широковещательные оповещения о состоянии
        const val ACTION_STATE = "AUDIO_STREAM_STATE"
        const val EXTRA_IS_RUNNING = "is_running"

        // Дополнительные параметры
        const val EXTRA_PORT = "port"
        const val EXTRA_SERVER_IP = "server_ip"

        // Загрузка native библиотеки
        init {
            System.loadLibrary("opus-lib")
        }
    }

    // Native методы
    private external fun initOpusDecoder(sampleRate: Int, channels: Int)
    private external fun decodeOpus(encodedData: ByteArray, frameSize: Int): ByteArray?
    private external fun destroyOpusDecoder()

    inner class AudioStreamBinder : Binder() {
        fun getService(): AudioStreamService = this@AudioStreamService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initOpusDecoder(48000, 2)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 5000)
                val serverIp = intent.getStringExtra(EXTRA_SERVER_IP)
                startAudioStream(port)
                if (!serverIp.isNullOrBlank()) {
                    // отправим HELLO на ПК с того же порта
                    udpReceiver?.sendHello(serverIp, port)
                }
            }
            ACTION_STOP -> {
                stopAudioStream()
                stopSelf()
            }
        }
        return START_STICKY // Сервис будет перезапущен если система его убьет
    }

    private fun startAudioStream(port: Int) {
        if (isRunning) return

        // Запуск foreground service с уведомлением
        val notification = createNotification("Слушаем на порту $port", true)
        startForeground(NOTIFICATION_ID, notification)

        // Запуск UDP приемника
        udpReceiver = UDPReceiver(this)
        udpReceiver?.startListening(port)

        isRunning = true
        sendState(true)
    }

    private fun stopAudioStream() {
        if (!isRunning) return

        // Остановка UDP приемника
        udpReceiver?.stopListening()
        udpReceiver = null

        // Остановка AudioTrack
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        isRunning = false
        stopForeground(true)
        sendState(false)
    }

    override fun onPacketReceived(data: ByteArray, size: Int) {
        // Декодирование Opus
        val pcmData = decodeOpus(data, 960)
        if (pcmData != null) {
            playAudio(pcmData)
        }
    }

    private fun playAudio(pcmData: ByteArray) {
        // Параметры аудио
        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // Размер буфера
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = if (pcmData.size > minBufferSize) pcmData.size else minBufferSize

        // Создать AudioTrack если не создан
        if (audioTrack == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()

            val audioFormatBuilder = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build()

            audioTrack = AudioTrack(
                audioAttributes,
                audioFormatBuilder,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack?.play()
        }

        // Воспроизвести данные
        audioTrack?.write(pcmData, 0, pcmData.size)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Stream Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Канал для фонового воспроизведения аудио"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String, isActive: Boolean): Notification {
        // Intent для открытия приложения
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // Intent для остановки сервиса
        val stopIntent = Intent(this, AudioStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Stream")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isActive)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Остановить",
                stopPendingIntent
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun isStreamRunning(): Boolean = isRunning

    override fun onDestroy() {
        super.onDestroy()
        stopAudioStream()
        destroyOpusDecoder()
        sendState(false)
    }

    private fun sendState(running: Boolean) {
        val intent = Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RUNNING, running)
        }
        sendBroadcast(intent)
    }
}