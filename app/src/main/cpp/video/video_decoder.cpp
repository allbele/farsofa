// SPDX-License-Identifier: AGPL-3.0-only

// Decodes the console's video with the headset's hardware decoder, straight onto a Surface.
// Same C interface as chiaki-ng's Android decoder (video-decoder.h), so the bridge does not change;
// the differences are that the decoder is asked to work without buffering frames, and that a
// surface can come and go at any moment without leaving a thread or a lock behind.

extern "C" {
#include <chiaki/common.h>
#include "video-decoder.h"
}

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window_jni.h>

#include <sys/resource.h>
#include <unistd.h>
#include <climits>
#include <cstring>

namespace {

const int64_t INPUT_TIMEOUT_US = 10 * 1000;
// Short enough for a stop request to be noticed quickly; a decoded frame returns at once anyway.
const int64_t OUTPUT_TIMEOUT_US = 20 * 1000;

void *output_thread(void *user)
{
	auto *decoder = static_cast<AndroidChiakiVideoDecoder *>(user);
	// Showing a frame late costs more than anything else this process does.
	setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), -10);

	while(true)
	{
		chiaki_mutex_lock(&decoder->codec_mutex);
		bool shutdown = decoder->shutdown_output;
		chiaki_mutex_unlock(&decoder->codec_mutex);
		if(shutdown)
			break;

		AMediaCodecBufferInfo info;
		ssize_t index = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, OUTPUT_TIMEOUT_US);
		if(index >= 0)
			AMediaCodec_releaseOutputBuffer(decoder->codec, static_cast<size_t>(index), info.size != 0);
		else if(index != AMEDIACODEC_INFO_TRY_AGAIN_LATER && index != AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED
			&& index != AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
			usleep(5000); // a decoder in trouble answers at once; do not spin on it
	}
	return nullptr;
}

AMediaFormat *make_format(const char *mime, int32_t width, int32_t height, bool low_latency)
{
	AMediaFormat *format = AMediaFormat_new();
	AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
	if(low_latency)
	{
		// Written as text because the named constants only exist from Android 11 on.
		AMediaFormat_setInt32(format, "low-latency", 1);
		AMediaFormat_setInt32(format, "priority", 0); // realtime
		AMediaFormat_setInt32(format, "operating-rate", 32767); // "as fast as you can"; larger values are refused
		AMediaFormat_setInt32(format, "vendor.qti-ext-dec-low-latency.enable", 1);
	}
	return format;
}

// Creates, configures and starts a decoder on the window. Returns null when nothing worked.
AMediaCodec *start_codec(AndroidChiakiVideoDecoder *decoder, ANativeWindow *window)
{
	const char *mime = chiaki_codec_is_h265(decoder->target_codec) ? "video/hevc" : "video/avc";
	// A decoder that refuses the low-latency hints is still better than no picture.
	for(int attempt = 0; attempt < 2; attempt++)
	{
		bool low_latency = attempt == 0;
		AMediaCodec *codec = AMediaCodec_createDecoderByType(mime);
		if(!codec)
		{
			CHIAKI_LOGE(decoder->log, "Video decoder: no decoder for %s", mime);
			return nullptr;
		}
		AMediaFormat *format = make_format(mime, decoder->target_width, decoder->target_height, low_latency);
		media_status_t status = AMediaCodec_configure(codec, format, window, nullptr, 0);
		AMediaFormat_delete(format);
		if(status == AMEDIA_OK)
			status = AMediaCodec_start(codec);
		if(status == AMEDIA_OK)
		{
			CHIAKI_LOGI(decoder->log, "Video decoder started: %s, low latency %s", mime, low_latency ? "on" : "off");
			return codec;
		}
		CHIAKI_LOGE(decoder->log, "Video decoder failed to start (low latency %s): %d", low_latency ? "on" : "off", static_cast<int>(status));
		AMediaCodec_delete(codec);
	}
	return nullptr;
}

// Stops the output thread and the decoder. Called without the lock held.
void stop_codec(AndroidChiakiVideoDecoder *decoder)
{
	chiaki_mutex_lock(&decoder->codec_mutex);
	if(!decoder->codec)
	{
		chiaki_mutex_unlock(&decoder->codec_mutex);
		return;
	}
	decoder->shutdown_output = true; // also keeps new samples away from the decoder
	chiaki_mutex_unlock(&decoder->codec_mutex);

	chiaki_thread_join(&decoder->output_thread, nullptr);

	chiaki_mutex_lock(&decoder->codec_mutex);
	AMediaCodec_stop(decoder->codec);
	AMediaCodec_delete(decoder->codec);
	decoder->codec = nullptr;
	if(decoder->window)
	{
		ANativeWindow_release(decoder->window);
		decoder->window = nullptr;
	}
	decoder->shutdown_output = false;
	chiaki_mutex_unlock(&decoder->codec_mutex);
}

} // namespace

extern "C" {

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, ChiakiCodec codec)
{
	decoder->log = log;
	decoder->codec = nullptr;
	decoder->window = nullptr;
	decoder->timestamp_cur = 0;
	decoder->target_width = target_width;
	decoder->target_height = target_height;
	decoder->target_codec = codec;
	decoder->shutdown_output = false;
	return chiaki_mutex_init(&decoder->codec_mutex, false);
}

void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder)
{
	stop_codec(decoder);
	chiaki_mutex_fini(&decoder->codec_mutex);
}

void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface)
{
	if(!surface)
	{
		stop_codec(decoder);
		return;
	}

	ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
	if(!window)
	{
		CHIAKI_LOGE(decoder->log, "Video decoder: the surface has no window");
		return;
	}

	chiaki_mutex_lock(&decoder->codec_mutex);
	if(decoder->codec)
	{
		// Already running: point it at the new surface and keep going.
		media_status_t status = AMediaCodec_setOutputSurface(decoder->codec, window);
		if(status == AMEDIA_OK)
		{
			if(decoder->window)
				ANativeWindow_release(decoder->window);
			decoder->window = window;
		}
		else
		{
			CHIAKI_LOGE(decoder->log, "Video decoder: could not switch surface: %d", static_cast<int>(status));
			ANativeWindow_release(window);
		}
		chiaki_mutex_unlock(&decoder->codec_mutex);
		return;
	}

	AMediaCodec *codec = start_codec(decoder, window);
	if(!codec)
	{
		ANativeWindow_release(window);
		chiaki_mutex_unlock(&decoder->codec_mutex);
		return;
	}
	decoder->codec = codec;
	decoder->window = window;
	if(chiaki_thread_create(&decoder->output_thread, output_thread, decoder) != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(decoder->log, "Video decoder: could not start the output thread");
		AMediaCodec_stop(codec);
		AMediaCodec_delete(codec);
		decoder->codec = nullptr;
		ANativeWindow_release(window);
		decoder->window = nullptr;
	}
	chiaki_mutex_unlock(&decoder->codec_mutex);
}

bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, int32_t, bool, void *user)
{
	auto *decoder = static_cast<AndroidChiakiVideoDecoder *>(user);
	bool ok = true;
	chiaki_mutex_lock(&decoder->codec_mutex);

	// Before the surface exists, and while it is being taken away, frames are simply dropped.
	while(decoder->codec && !decoder->shutdown_output && buf_size > 0)
	{
		ssize_t index = AMediaCodec_dequeueInputBuffer(decoder->codec, INPUT_TIMEOUT_US);
		if(index < 0)
		{
			ok = false; // the caller asks the console for a fresh key frame
			break;
		}
		size_t capacity = 0;
		uint8_t *input = AMediaCodec_getInputBuffer(decoder->codec, static_cast<size_t>(index), &capacity);
		size_t size = buf_size < capacity ? buf_size : capacity;
		if(input && size > 0)
			memcpy(input, buf, size);
		else
			size = 0;
		// The timestamp only has to grow: frames are shown as soon as they are decoded.
		media_status_t status = AMediaCodec_queueInputBuffer(decoder->codec, static_cast<size_t>(index), 0, size, decoder->timestamp_cur++, 0);
		if(status != AMEDIA_OK || size == 0)
		{
			ok = false;
			break;
		}
		buf += size;
		buf_size -= size;
	}

	chiaki_mutex_unlock(&decoder->codec_mutex);
	return ok;
}

} // extern "C"
