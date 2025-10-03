#include <jni.h>
#include <opus.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include <vector>
#include <memory>

#define LOG_TAG "OpusJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Класс для управления декодером с улучшенной обработкой ошибок
class OpusDecoderWrapper {
private:
    OpusDecoder *decoder;
    int sampleRate;
    int channels;
    int maxFrameSize;

public:
    OpusDecoderWrapper(int sampleRate, int channels)
            : decoder(nullptr), sampleRate(sampleRate), channels(channels) {

        // Вычислить максимальный размер фрейма (для 120ms при максимальной частоте)
        maxFrameSize = 48000 * 120 / 1000;

        int error = OPUS_OK;
        decoder = opus_decoder_create(sampleRate, channels, &error);

        if (error != OPUS_OK) {
            LOGE("Ошибка создания декодера: %s", opus_strerror(error));
            decoder = nullptr;
        } else {
            LOGI("Opus декодер создан: %dHz, %d каналов", sampleRate, channels);
        }
    }

    ~OpusDecoderWrapper() {
        if (decoder) {
            opus_decoder_destroy(decoder);
            decoder = nullptr;
            LOGI("Opus декодер уничтожен");
        }
    }

    bool isValid() const {
        return decoder != nullptr;
    }

    int decode(const unsigned char* encodedData, int encodedSize, short* pcmOut, int frameSize) {
        if (!decoder) {
            LOGE("Декодер не инициализирован");
            return OPUS_INVALID_STATE;
        }

        if (!encodedData && encodedSize != 0) {
            LOGE("Неверные входные данные");
            return OPUS_BAD_ARG;
        }

        // Декодирование (encodedData может быть NULL для PLC - Packet Loss Concealment)
        int result = opus_decode(decoder, encodedData, encodedSize, pcmOut, frameSize, 0);

        if (result < 0) {
            LOGE("Ошибка декодирования: %s", opus_strerror(result));
        } else {
            LOGD("Декодировано %d сэмплов из %d байт", result, encodedSize);
        }

        return result;
    }

    // Сброс состояния декодера
    int reset() {
        if (!decoder) return OPUS_INVALID_STATE;
        return opus_decoder_ctl(decoder, OPUS_RESET_STATE);
    }

    // Получение задержки декодера
    int getDelay() {
        if (!decoder) return 0;
        opus_int32 delay;
        int ret = opus_decoder_ctl(decoder, OPUS_GET_LOOKAHEAD(&delay));
        return (ret == OPUS_OK) ? delay : 0;
    }

    int getSampleRate() const { return sampleRate; }
    int getChannels() const { return channels; }
    int getMaxFrameSize() const { return maxFrameSize; }
};

// Глобальные переменные для совместимости со старым кодом
static std::unique_ptr<OpusDecoderWrapper> globalDecoder = nullptr;

extern "C" {

// === Базовые функции (для совместимости) ===

JNIEXPORT jstring JNICALL
Java_com_wabycheck_ond_MainActivity_stringFromJNI(JNIEnv *env, jobject thiz) {
    std::string hello = "Opus Library v" + std::string(opus_get_version_string());
    return env->NewStringUTF(hello.c_str());
}

JNIEXPORT void JNICALL
Java_com_wabycheck_ond_MainActivity_initOpusDecoder(JNIEnv *env, jobject thiz, jint sample_rate, jint channels) {
    // Уничтожить предыдущий декодер если есть
    globalDecoder.reset();

    // Создать новый
    globalDecoder = std::make_unique<OpusDecoderWrapper>(sample_rate, channels);

    if (!globalDecoder->isValid()) {
        LOGE("Не удалось создать декодер");
        globalDecoder.reset();
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_wabycheck_ond_MainActivity_decodeOpus(JNIEnv *env, jobject thiz, jbyteArray encoded_data, jint frame_size) {
    if (!globalDecoder || !globalDecoder->isValid()) {
        LOGE("Декодер не инициализирован");
        return nullptr;
    }

    // Получить входные данные
    jsize encodedLength = env->GetArrayLength(encoded_data);
    if (encodedLength <= 0) {
        LOGE("Пустые входные данные");
        return nullptr;
    }

    jbyte* encodedBuffer = env->GetByteArrayElements(encoded_data, nullptr);
    if (!encodedBuffer) {
        LOGE("Не удалось получить входной буфер");
        return nullptr;
    }

    // Выходной буфер для PCM данных
    int maxSamples = (frame_size > 0) ? frame_size : globalDecoder->getMaxFrameSize();
    std::vector<short> pcmBuffer(maxSamples * globalDecoder->getChannels());

    // Декодировать
    int decodedSamples = globalDecoder->decode(
            reinterpret_cast<const unsigned char*>(encodedBuffer),
            encodedLength,
            pcmBuffer.data(),
            maxSamples
    );

    // Освободить входной буфер
    env->ReleaseByteArrayElements(encoded_data, encodedBuffer, JNI_ABORT);

    if (decodedSamples < 0) {
        LOGE("Ошибка декодирования: %d", decodedSamples);
        return nullptr;
    }

    // Создать выходной массив Java
    jsize outputSize = decodedSamples * globalDecoder->getChannels() * sizeof(short);
    jbyteArray output = env->NewByteArray(outputSize);

    if (output) {
        env->SetByteArrayRegion(output, 0, outputSize,
                                reinterpret_cast<jbyte*>(pcmBuffer.data()));
    }

    return output;
}

JNIEXPORT void JNICALL
Java_com_wabycheck_ond_MainActivity_destroyOpusDecoder(JNIEnv *env, jobject thiz) {
    globalDecoder.reset();
}

// === Дубликаты для AudioStreamService (используют тот же globalDecoder) ===

JNIEXPORT void JNICALL
Java_com_wabycheck_ond_AudioStreamService_initOpusDecoder(JNIEnv *env, jobject thiz, jint sample_rate, jint channels) {
    // Уничтожить предыдущий декодер если есть
    globalDecoder.reset();

    // Создать новый
    globalDecoder = std::make_unique<OpusDecoderWrapper>(sample_rate, channels);

    if (!globalDecoder->isValid()) {
        LOGE("Не удалось создать декодер");
        globalDecoder.reset();
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_wabycheck_ond_AudioStreamService_decodeOpus(JNIEnv *env, jobject thiz, jbyteArray encoded_data, jint frame_size) {
    if (!globalDecoder || !globalDecoder->isValid()) {
        LOGE("Декодер не инициализирован");
        return nullptr;
    }

    // Получить входные данные
    jsize encodedLength = env->GetArrayLength(encoded_data);
    if (encodedLength <= 0) {
        LOGE("Пустые входные данные");
        return nullptr;
    }

    jbyte* encodedBuffer = env->GetByteArrayElements(encoded_data, nullptr);
    if (!encodedBuffer) {
        LOGE("Не удалось получить входной буфер");
        return nullptr;
    }

    // Определить реальное число сэмплов в пакете (120/240/480/960 и т.д.)
    int packetSamples = opus_packet_get_nb_samples(
            reinterpret_cast<const unsigned char*>(encodedBuffer),
            encodedLength,
            globalDecoder->getSampleRate()
    );
    if (packetSamples <= 0) {
        // Fallback на максимально допустимый фрейм
        packetSamples = (frame_size > 0) ? frame_size : globalDecoder->getMaxFrameSize();
    }

    // Выходной буфер для PCM данных
    std::vector<short> pcmBuffer(packetSamples * globalDecoder->getChannels());

    // Декодировать с авто-подстройкой размера
    int decodedSamples = globalDecoder->decode(
            reinterpret_cast<const unsigned char*>(encodedBuffer),
            encodedLength,
            pcmBuffer.data(),
            packetSamples
    );

    // Освободить входной буфер
    env->ReleaseByteArrayElements(encoded_data, encodedBuffer, JNI_ABORT);

    if (decodedSamples < 0) {
        LOGE("Ошибка декодирования: %d", decodedSamples);
        return nullptr;
    }

    // Создать выходной массив Java
    jsize outputSize = decodedSamples * globalDecoder->getChannels() * sizeof(short);
    jbyteArray output = env->NewByteArray(outputSize);

    if (output) {
        env->SetByteArrayRegion(output, 0, outputSize,
                                reinterpret_cast<jbyte*>(pcmBuffer.data()));
    }

    return output;
}

JNIEXPORT jbyteArray JNICALL
Java_com_wabycheck_ond_AudioStreamService_decodePlc(JNIEnv *env, jobject thiz, jint frame_size) {
    if (!globalDecoder || !globalDecoder->isValid()) {
        LOGE("Декодер не инициализирован (PLC)");
        return nullptr;
    }

    int maxSamples = (frame_size > 0) ? frame_size : globalDecoder->getMaxFrameSize();
    std::vector<short> pcmBuffer(maxSamples * globalDecoder->getChannels());

    int decodedSamples = globalDecoder->decode(
            nullptr,
            0,
            pcmBuffer.data(),
            maxSamples
    );

    if (decodedSamples <= 0) {
        return nullptr;
    }

    jsize outputSize = decodedSamples * globalDecoder->getChannels() * sizeof(short);
    jbyteArray output = env->NewByteArray(outputSize);
    if (output) {
        env->SetByteArrayRegion(output, 0, outputSize,
                                reinterpret_cast<jbyte*>(pcmBuffer.data()));
    }
    return output;
}

JNIEXPORT void JNICALL
Java_com_wabycheck_ond_AudioStreamService_destroyOpusDecoder(JNIEnv *env, jobject thiz) {
    globalDecoder.reset();
}

// === Улучшенные функции для потокового воспроизведения ===

JNIEXPORT jlong JNICALL
Java_com_wabycheck_ond_MainActivity_createOpusDecoder(JNIEnv *env, jobject thiz, jint sample_rate, jint channels) {
    auto decoder = std::make_unique<OpusDecoderWrapper>(sample_rate, channels);

    if (!decoder->isValid()) {
        return 0;
    }

    // Возвращаем указатель как long
    return reinterpret_cast<jlong>(decoder.release());
}

JNIEXPORT void JNICALL
Java_com_wabycheck_ond_MainActivity_destroyOpusDecoderPtr(JNIEnv *env, jobject thiz, jlong decoder_ptr) {
    if (decoder_ptr != 0) {
        OpusDecoderWrapper* decoder = reinterpret_cast<OpusDecoderWrapper*>(decoder_ptr);
        delete decoder;
    }
}

JNIEXPORT jshortArray JNICALL
Java_com_wabycheck_ond_MainActivity_decodeOpusPacket(JNIEnv *env, jobject thiz, jlong decoder_ptr,
                                                     jbyteArray encoded_data) {
    if (decoder_ptr == 0) {
        LOGE("Неверный указатель декодера");
        return nullptr;
    }

    OpusDecoderWrapper* decoder = reinterpret_cast<OpusDecoderWrapper*>(decoder_ptr);

    // Получить размер входных данных
    jsize encodedLength = env->GetArrayLength(encoded_data);

    // Для случая потери пакета (PLC)
    if (encodedLength == 0) {
        // Генерировать примерно 20ms аудио
        int frameSize = decoder->getSampleRate() / 50; // 20ms
        std::vector<short> pcmBuffer(frameSize * decoder->getChannels());

        int decodedSamples = decoder->decode(nullptr, 0, pcmBuffer.data(), frameSize);

        if (decodedSamples > 0) {
            jshortArray result = env->NewShortArray(decodedSamples * decoder->getChannels());
            env->SetShortArrayRegion(result, 0, decodedSamples * decoder->getChannels(),
                                     pcmBuffer.data());
            return result;
        }
        return nullptr;
    }

    // Нормальное декодирование
    jbyte* encodedBuffer = env->GetByteArrayElements(encoded_data, nullptr);
    if (!encodedBuffer) {
        return nullptr;
    }

    // Определить количество сэмплов в пакете
    int samples = opus_packet_get_nb_samples(
            reinterpret_cast<const unsigned char*>(encodedBuffer),
            encodedLength,
            decoder->getSampleRate()
    );

    if (samples <= 0) {
        env->ReleaseByteArrayElements(encoded_data, encodedBuffer, JNI_ABORT);
        LOGE("Не удалось определить размер пакета");
        return nullptr;
    }

    // Выделить буфер для декодированных данных
    std::vector<short> pcmBuffer(samples * decoder->getChannels());

    int decodedSamples = decoder->decode(
            reinterpret_cast<const unsigned char*>(encodedBuffer),
            encodedLength,
            pcmBuffer.data(),
            samples
    );

    env->ReleaseByteArrayElements(encoded_data, encodedBuffer, JNI_ABORT);

    if (decodedSamples <= 0) {
        return nullptr;
    }

    // Создать результирующий массив
    jshortArray result = env->NewShortArray(decodedSamples * decoder->getChannels());
    env->SetShortArrayRegion(result, 0, decodedSamples * decoder->getChannels(),
                             pcmBuffer.data());

    return result;
}

// Получение информации о пакете
JNIEXPORT jint JNICALL
Java_com_wabycheck_ond_MainActivity_getOpusPacketSamples(JNIEnv *env, jobject thiz,
                                                         jbyteArray packet_data, jint sample_rate) {
    jsize length = env->GetArrayLength(packet_data);
    if (length <= 0) return -1;

    jbyte* buffer = env->GetByteArrayElements(packet_data, nullptr);
    if (!buffer) return -1;

    int samples = opus_packet_get_nb_samples(
            reinterpret_cast<const unsigned char*>(buffer),
            length,
            sample_rate
    );

    env->ReleaseByteArrayElements(packet_data, buffer, JNI_ABORT);
    return samples;
}

JNIEXPORT jint JNICALL
Java_com_wabycheck_ond_MainActivity_getOpusPacketChannels(JNIEnv *env, jobject thiz, jbyteArray packet_data) {
    jsize length = env->GetArrayLength(packet_data);
    if (length <= 0) return -1;

    jbyte* buffer = env->GetByteArrayElements(packet_data, nullptr);
    if (!buffer) return -1;

    int channels = opus_packet_get_nb_channels(reinterpret_cast<const unsigned char*>(buffer));

    env->ReleaseByteArrayElements(packet_data, buffer, JNI_ABORT);
    return channels;
}

JNIEXPORT jstring JNICALL
Java_com_wabycheck_ond_MainActivity_getOpusVersion(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF(opus_get_version_string());
}

// Сброс состояния декодера (полезно при потере соединения)
JNIEXPORT jint JNICALL
Java_com_wabycheck_ond_MainActivity_resetOpusDecoder(JNIEnv *env, jobject thiz, jlong decoder_ptr) {
    if (decoder_ptr == 0) return OPUS_INVALID_STATE;

    OpusDecoderWrapper* decoder = reinterpret_cast<OpusDecoderWrapper*>(decoder_ptr);
    return decoder->reset();
}

} // extern "C"