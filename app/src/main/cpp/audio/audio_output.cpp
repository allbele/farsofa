// SPDX-License-Identifier: AGPL-3.0-only
//
// Plays the console's audio through Oboe. Same C interface as chiaki-ng's Android audio output
// (audio-output.h), which this replaces for two reasons seen on a Quest 3: that one opens an
// exclusive stream and never reopens it once the system takes the device away, and when its buffer
// fills it keeps the old audio and throws away the new, so the sound falls behind the picture.

#include "audio-output.h"

#include <chiaki/log.h>

#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

namespace {

class AudioOutput : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback
{
public:
	explicit AudioOutput(ChiakiLog *log) : log(log) {}

	~AudioOutput() override
	{
		closing = true;
		std::shared_ptr<oboe::AudioStream> old;
		{
			std::lock_guard<std::mutex> lock(stream_mutex);
			old = std::move(stream);
		}
		if(old)
		{
			old->stop();
			old->close();
		}
	}

	void configure(uint32_t new_channels, uint32_t new_rate)
	{
		channels = new_channels;
		rate = new_rate;
		{
			// Room for about 120 ms; normal playback keeps far less than that waiting.
			std::lock_guard<std::mutex> lock(buffer_mutex);
			buffer.assign(static_cast<size_t>(rate) * channels * 120 / 1000, 0);
			read_pos = 0;
			filled = 0;
		}
		open();
	}

	void push(const int16_t *samples, size_t count)
	{
		std::lock_guard<std::mutex> lock(buffer_mutex);
		if(buffer.empty())
			return;
		if(count > buffer.size())
		{
			samples += count - buffer.size();
			count = buffer.size();
		}
		// When playback falls behind, the oldest audio goes: late sound is worse than a skipped moment.
		size_t free_space = buffer.size() - filled;
		if(count > free_space)
		{
			size_t drop = count - free_space;
			read_pos = (read_pos + drop) % buffer.size();
			filled -= drop;
			dropped_samples += drop;
		}
		size_t write_pos = (read_pos + filled) % buffer.size();
		size_t first = std::min(count, buffer.size() - write_pos);
		memcpy(buffer.data() + write_pos, samples, first * sizeof(int16_t));
		memcpy(buffer.data(), samples + first, (count - first) * sizeof(int16_t));
		filled += count;
		report();
	}

	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audio_stream, void *data, int32_t frames) override
	{
		auto *out = static_cast<int16_t *>(data);
		size_t wanted = static_cast<size_t>(frames) * static_cast<size_t>(audio_stream->getChannelCount());
		std::lock_guard<std::mutex> lock(buffer_mutex);
		size_t count = std::min(wanted, filled);
		if(count > 0)
		{
			size_t first = std::min(count, buffer.size() - read_pos);
			memcpy(out, buffer.data() + read_pos, first * sizeof(int16_t));
			memcpy(out + first, buffer.data(), (count - first) * sizeof(int16_t));
			read_pos = (read_pos + count) % buffer.size();
			filled -= count;
		}
		if(count < wanted)
		{
			memset(out + count, 0, (wanted - count) * sizeof(int16_t));
			if(count > 0)
				underruns++;
		}
		return oboe::DataCallbackResult::Continue;
	}

	// The system closed the stream, for instance when the headset is taken off or the output changes.
	void onErrorAfterClose(oboe::AudioStream *, oboe::Result error) override
	{
		CHIAKI_LOGW(log, "Audio output lost its stream (%s), reopening", oboe::convertToText(error));
		if(!closing)
			open();
	}

private:
	void open()
	{
		oboe::AudioStreamBuilder builder;
		builder.setDirection(oboe::Direction::Output)
			->setPerformanceMode(oboe::PerformanceMode::LowLatency)
			->setSharingMode(oboe::SharingMode::Shared)
			->setUsage(oboe::Usage::Game)
			->setFormat(oboe::AudioFormat::I16)
			->setChannelCount(static_cast<int32_t>(channels))
			->setSampleRate(static_cast<int32_t>(rate))
			->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
			->setDataCallback(this)
			->setErrorCallback(this);

		std::shared_ptr<oboe::AudioStream> opened;
		oboe::Result result = builder.openStream(opened);
		if(result != oboe::Result::OK)
		{
			CHIAKI_LOGE(log, "Audio output failed to open: %s", oboe::convertToText(result));
			return;
		}
		result = opened->requestStart();
		if(result != oboe::Result::OK)
			CHIAKI_LOGE(log, "Audio output failed to start: %s", oboe::convertToText(result));
		else
			CHIAKI_LOGI(log, "Audio output started: %d Hz, %d channels, burst %d frames, buffer %d frames, %s",
					opened->getSampleRate(), opened->getChannelCount(), opened->getFramesPerBurst(),
					opened->getBufferSizeInFrames(), oboe::convertToText(opened->getAudioApi()));

		std::lock_guard<std::mutex> lock(stream_mutex);
		stream = std::move(opened);
	}

	// Called with buffer_mutex held. One line every few seconds, and only when something went wrong.
	void report()
	{
		auto now = std::chrono::steady_clock::now();
		if(now - last_report < std::chrono::seconds(5))
			return;
		last_report = now;
		int under = underruns.exchange(0);
		if(dropped_samples > 0 || under > 0)
			CHIAKI_LOGW(log, "Audio output in the last 5 s: dropped %.0f ms, ran dry %d times",
					1000.0 * static_cast<double>(dropped_samples) / (static_cast<double>(rate) * channels), under);
		dropped_samples = 0;
	}

	ChiakiLog *log;
	uint32_t channels = 2;
	uint32_t rate = 48000;
	std::atomic<bool> closing{false};

	std::mutex stream_mutex;
	std::shared_ptr<oboe::AudioStream> stream;

	std::mutex buffer_mutex;
	std::vector<int16_t> buffer;
	size_t read_pos = 0;
	size_t filled = 0;
	size_t dropped_samples = 0;
	std::atomic<int> underruns{0};
	std::chrono::steady_clock::time_point last_report = std::chrono::steady_clock::now();
};

} // namespace

extern "C" void *android_chiaki_audio_output_new(ChiakiLog *log)
{
	return new AudioOutput(log);
}

extern "C" void android_chiaki_audio_output_free(void *audio_output)
{
	delete static_cast<AudioOutput *>(audio_output);
}

extern "C" void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output)
{
	static_cast<AudioOutput *>(audio_output)->configure(channels, rate);
}

extern "C" void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output)
{
	static_cast<AudioOutput *>(audio_output)->push(buf, samples_count);
}
