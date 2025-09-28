package com.wabycheck.ond

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), UDPReceiver.OnPacketReceivedListener {

    private lateinit var editTextIP: EditText
    private lateinit var editTextPort: EditText
    private lateinit var buttonStart: Button
    private lateinit var buttonStop: Button

    private var udpReceiver: UDPReceiver? = null
    private var audioTrack: AudioTrack? = null

    // Загрузка native-библиотеки
    companion object {
        init {
            System.loadLibrary("opus-lib")
        }
    }

    // Вызов из C++
    private external fun stringFromJNI(): String
    private external fun initOpusDecoder(sampleRate: Int, channels: Int)
    private external fun decodeOpus(encodedData: ByteArray, frameSize: Int): ByteArray?
    private external fun destroyOpusDecoder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация Views
        editTextIP = findViewById(R.id.editTextIP)
        editTextPort = findViewById(R.id.editTextPort)
        buttonStart = findViewById(R.id.buttonStart)
        buttonStop = findViewById(R.id.buttonStop)

        // Установка обработчиков
        buttonStart.setOnClickListener {
            val ip = editTextIP.text.toString()
            val portText = editTextPort.text.toString()

            if (ip.isEmpty() || portText.isEmpty()) {
                Toast.makeText(this, "Заполните IP и порт", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val port = try {
                portText.toInt()
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "Порт должен быть числом", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Запуск UDP-приёмника
            udpReceiver = UDPReceiver(this)
            udpReceiver?.startListening(port)

            // Обновление UI
            buttonStart.isEnabled = false
            buttonStop.isEnabled = true

            Toast.makeText(this, "Слушаем на порту $port", Toast.LENGTH_SHORT).show()
        }

        buttonStop.setOnClickListener {
            // Остановка приёмника
            udpReceiver?.stopListening()
            udpReceiver = null

            // Обновление UI
            buttonStart.isEnabled = true
            buttonStop.isEnabled = false

            Toast.makeText(this, "Остановлено", Toast.LENGTH_SHORT).show()
        }

        // Установка значений по умолчанию
        editTextIP.setText("192.168.0.100")
        editTextPort.setText("5000")

        // Проверка JNI
        try {
            val jniString = stringFromJNI()
            println("JNI: $jniString")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }

        // Инициализация Opus (48000, 2 канала)
        initOpusDecoder(48000, 2)
    }

    override fun onPacketReceived(data: ByteArray, size: Int) {
        // Декодирование без отладочных сообщений
        val pcmData = decodeOpus(data, 960) // frame_size = 960
        if (pcmData != null) {
            // Воспроизвести pcmData через AudioTrack
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

    override fun onDestroy() {
        super.onDestroy()
        destroyOpusDecoder() // Уничтожить декодер
        audioTrack?.release() // Освободить AudioTrack
        audioTrack = null
    }
}