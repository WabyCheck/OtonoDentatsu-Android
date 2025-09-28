package com.wabycheck.ond

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UDPReceiver(
    private val listener: OnPacketReceivedListener
) {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private var receiverThread: Thread? = null

    interface OnPacketReceivedListener {
        fun onPacketReceived(data: ByteArray, size: Int)
    }

    fun startListening(port: Int) {
        if (isRunning) return

        isRunning = true
        receiverThread = Thread {
            try {
                val address = InetAddress.getByName("0.0.0.0")  // Принимать на любом интерфейсе
                socket = DatagramSocket(port, address)

                Log.d("UDPReceiver", "Слушаю на 0.0.0.0:$port")

                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isRunning) {
                    try {
                        socket?.receive(packet)
                        val receivedData = packet.data.copyOfRange(0, packet.length)
                        listener.onPacketReceived(receivedData, packet.length)
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e("UDPReceiver", "Ошибка приёма: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("UDPReceiver", "Ошибка инициализации сокета: ${e.message}")
            }
        }
        receiverThread?.start()
    }

    fun stopListening() {
        isRunning = false
        socket?.close()
        receiverThread?.interrupt()
        receiverThread = null
    }
}