package org.kirundi.voicestudio.audio.device

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Une entrée audio détectée par Android, avec ce qu'elle annonce (rien n'est deviné). */
data class InputDevice(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<String>
)

object AudioDeviceScanner {

    fun scan(context: Context): List<InputDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { d ->
            InputDevice(
                id = d.id,
                name = d.productName?.toString() ?: "inconnu",
                typeLabel = typeLabel(d.type),
                sampleRates = d.sampleRates.toList(),
                channelCounts = d.channelCounts.toList(),
                encodings = d.encodings.map { encodingLabel(it) }
            )
        }
    }
}

fun typeLabel(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "micro intégré"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "périphérique USB"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "casque USB"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "casque filaire"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (SCO)"
    AudioDeviceInfo.TYPE_TELEPHONY -> "téléphonie"
    else -> "type Android $type"
}

/** Codes Android : 2 = 16 bits, 3 = 8 bits, 4 = float, 21 = 24 bits packed, 22 = 32 bits. */
fun encodingLabel(code: Int): String = when (code) {
    2 -> "PCM 16 bits"
    3 -> "PCM 8 bits"
    4 -> "PCM float 32 bits"
    21 -> "PCM 24 bits (packed)"
    22 -> "PCM 32 bits"
    else -> "code $code"
}
