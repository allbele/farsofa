// SPDX-License-Identifier: AGPL-3.0-only

#include "smooth_video.h"

#include <GLES2/gl2ext.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>

#define LOG_TAG "FarsofaSmooth"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// The picture is compared with the one before on a coarse grid; that is enough to tell a new
// picture from a repeated one, and small enough to read back without holding up the frame.
const int GRID_WIDTH = 64;
const int GRID_HEIGHT = 36;

// Differences are mean brightness steps out of 255 over the grid.
// A repeated picture is not identical to the first: the console spends spare bandwidth refining it.
const float REPEAT_ABSOLUTE = 0.05f;
const float REPEAT_RELATIVE = 0.35f;
// Compression noise in a still scene must not be taken for motion, and the decision must not flicker.
const float MOTION_ON = 3.0f;
const float MOTION_OFF = 1.5f;
// A camera move changes the picture by 7 to 17 or so; a cut changes it far more, all at once.
const float CUT_ABSOLUTE = 25.0f;
const float CUT_RELATIVE = 4.0f;

const double PERIOD_MIN_NS = 16666667.0;
const double PERIOD_MAX_NS = 33333333.0;

const char *VERTEX_SHADER = R"(#version 300 es
uniform mat4 uTransform;
out vec2 vUv;
void main()
{
	vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
	vUv = (uTransform * vec4(corner, 0.0, 1.0)).xy;
	gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
)";

// The swapchain stores sRGB, so the video's values are turned into light here and the graphics chip
// turns them back when it writes; what is stored is then exactly what the decoder produced.
const char *VIDEO_SHADER = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uVideo;
uniform float uLinearise;
in vec2 vUv;
layout(location = 0) out vec4 oColor;
layout(location = 1) out vec4 oHistory;
vec3 toLight(vec3 c)
{
	return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}
void main()
{
	vec3 c = texture(uVideo, vUv).rgb;
	if(uLinearise > 0.5)
		c = toLight(c);
	oColor = vec4(c, 1.0);
	oHistory = oColor;
}
)";

const char *GRID_SHADER = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uVideo;
in vec2 vUv;
out vec4 oColor;
void main()
{
	float luma = dot(texture(uVideo, vUv).rgb, vec3(0.299, 0.587, 0.114));
	oColor = vec4(luma, luma, luma, 1.0);
}
)";

int64_t now_ns()
{
	timespec time = {};
	clock_gettime(CLOCK_MONOTONIC, &time);
	return static_cast<int64_t>(time.tv_sec) * 1000000000LL + time.tv_nsec;
}

GLuint compile(GLenum type, const char *source)
{
	GLuint shader = glCreateShader(type);
	glShaderSource(shader, 1, &source, nullptr);
	glCompileShader(shader);
	GLint compiled = 0;
	glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
	if(!compiled)
	{
		char message[512] = {};
		glGetShaderInfoLog(shader, sizeof(message), nullptr, message);
		LOGE("Shader did not compile: %s", message);
		glDeleteShader(shader);
		return 0;
	}
	return shader;
}

GLuint link(const char *fragment_source)
{
	GLuint vertex = compile(GL_VERTEX_SHADER, VERTEX_SHADER);
	GLuint fragment = compile(GL_FRAGMENT_SHADER, fragment_source);
	if(!vertex || !fragment)
	{
		glDeleteShader(vertex);
		glDeleteShader(fragment);
		return 0;
	}
	GLuint program = glCreateProgram();
	glAttachShader(program, vertex);
	glAttachShader(program, fragment);
	glLinkProgram(program);
	glDeleteShader(vertex);
	glDeleteShader(fragment);
	GLint linked = 0;
	glGetProgramiv(program, GL_LINK_STATUS, &linked);
	if(!linked)
	{
		LOGE("Shader program did not link");
		glDeleteProgram(program);
		return 0;
	}
	return program;
}

bool has_gl_extension(const char *name)
{
	GLint count = 0;
	glGetIntegerv(GL_NUM_EXTENSIONS, &count);
	for(GLint i = 0; i < count; i++)
	{
		const char *extension = reinterpret_cast<const char *>(glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i)));
		if(extension && strcmp(extension, name) == 0)
			return true;
	}
	return false;
}

bool clear_exception(JNIEnv *env)
{
	if(!env->ExceptionCheck())
		return false;
	env->ExceptionDescribe();
	env->ExceptionClear();
	return true;
}

} // namespace

bool SmoothVideo::Create(JNIEnv *env, XrSession session, int width, int height)
{
	width_ = width;
	height_ = height;
	if(!CreateSwapchain(session) || !CreatePrograms() || !CreateSurface(env))
	{
		Destroy(env);
		return false;
	}

	glGenFramebuffers(1, &framebuffer_);
	glGenVertexArrays(1, &vertex_array_);

	glGenTextures(1, &grid_texture_);
	glBindTexture(GL_TEXTURE_2D, grid_texture_);
	glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, GRID_WIDTH, GRID_HEIGHT);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	glGenFramebuffers(1, &grid_framebuffer_);
	glBindFramebuffer(GL_FRAMEBUFFER, grid_framebuffer_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, grid_texture_, 0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	grid_now_.assign(GRID_WIDTH * GRID_HEIGHT * 4, 0);
	grid_before_.assign(GRID_WIDTH * GRID_HEIGHT * 4, 0);

	if(has_gl_extension("GL_QCOM_frame_extrapolation"))
		extrapolate_ = reinterpret_cast<ExtrapolateFn>(eglGetProcAddress("glExtrapolateTex2DQCOM"));
	if(extrapolate_)
		CreateHistory();
	LOGI("Smooth video ready: %dx%d, format 0x%x, frame prediction %s", width_, height_, format_,
			extrapolate_ ? "available" : "not available on this device");

	// A layer may only show a swapchain that has had a picture released into it.
	GLuint first = Acquire();
	if(first)
	{
		glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, first, 0);
		glViewport(0, 0, width_, height_);
		glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		glClear(GL_COLOR_BUFFER_BIT);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		Release();
	}
	report_time_ns_ = now_ns();
	return true;
}

bool SmoothVideo::CreateSwapchain(XrSession session)
{
	uint32_t count = 0;
	if(XR_FAILED(xrEnumerateSwapchainFormats(session, 0, &count, nullptr)) || count == 0)
		return false;
	std::vector<int64_t> formats(count);
	if(XR_FAILED(xrEnumerateSwapchainFormats(session, count, &count, formats.data())))
		return false;
	for(int64_t wanted : {static_cast<int64_t>(GL_SRGB8_ALPHA8), static_cast<int64_t>(GL_RGBA8)})
	{
		if(std::find(formats.begin(), formats.end(), wanted) != formats.end())
		{
			format_ = static_cast<GLenum>(wanted);
			break;
		}
	}
	if(!format_)
		return false;

	XrSwapchainCreateInfo info = {XR_TYPE_SWAPCHAIN_CREATE_INFO};
	info.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
	info.format = format_;
	info.sampleCount = 1;
	info.width = static_cast<uint32_t>(width_);
	info.height = static_cast<uint32_t>(height_);
	info.faceCount = 1;
	info.arraySize = 1;
	info.mipCount = 1;
	if(XR_FAILED(xrCreateSwapchain(session, &info, &swapchain_)))
	{
		swapchain_ = XR_NULL_HANDLE;
		return false;
	}

	if(XR_FAILED(xrEnumerateSwapchainImages(swapchain_, 0, &count, nullptr)) || count == 0)
		return false;
	std::vector<XrSwapchainImageOpenGLESKHR> images(count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
	if(XR_FAILED(xrEnumerateSwapchainImages(swapchain_, count, &count,
			reinterpret_cast<XrSwapchainImageBaseHeader *>(images.data()))))
		return false;
	for(const auto &image : images)
		images_.push_back(image.image);
	return true;
}

// The decoder needs an android.view.Surface; one is made around a texture this context can sample.
bool SmoothVideo::CreateSurface(JNIEnv *env)
{
	glGenTextures(1, &external_texture_);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_texture_);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

	jclass texture_class = env->FindClass("android/graphics/SurfaceTexture");
	jclass surface_class = env->FindClass("android/view/Surface");
	if(!texture_class || !surface_class)
	{
		clear_exception(env);
		return false;
	}
	jmethodID texture_new = env->GetMethodID(texture_class, "<init>", "(I)V");
	jmethodID set_size = env->GetMethodID(texture_class, "setDefaultBufferSize", "(II)V");
	update_tex_image_ = env->GetMethodID(texture_class, "updateTexImage", "()V");
	get_timestamp_ = env->GetMethodID(texture_class, "getTimestamp", "()J");
	get_transform_ = env->GetMethodID(texture_class, "getTransformMatrix", "([F)V");
	release_ = env->GetMethodID(texture_class, "release", "()V");
	jmethodID surface_new = env->GetMethodID(surface_class, "<init>", "(Landroid/graphics/SurfaceTexture;)V");

	jobject texture = env->NewObject(texture_class, texture_new, static_cast<jint>(external_texture_));
	if(clear_exception(env) || !texture)
		return false;
	env->CallVoidMethod(texture, set_size, static_cast<jint>(width_), static_cast<jint>(height_));
	jobject surface = env->NewObject(surface_class, surface_new, texture);
	if(clear_exception(env) || !surface)
		return false;
	surface_texture_ = env->NewGlobalRef(texture);
	surface_ = env->NewGlobalRef(surface);
	transform_array_ = static_cast<jfloatArray>(env->NewGlobalRef(env->NewFloatArray(16)));
	env->DeleteLocalRef(texture);
	env->DeleteLocalRef(surface);
	return surface_texture_ && surface_ && transform_array_;
}

bool SmoothVideo::CreatePrograms()
{
	video_program_ = link(VIDEO_SHADER);
	grid_program_ = link(GRID_SHADER);
	if(!video_program_ || !grid_program_)
		return false;
	video_transform_ = glGetUniformLocation(video_program_, "uTransform");
	video_linearise_ = glGetUniformLocation(video_program_, "uLinearise");
	grid_transform_ = glGetUniformLocation(grid_program_, "uTransform");
	return true;
}

// The two pictures a prediction is made from. Same size and format as the swapchain, down to the
// way the storage is declared: the prediction quietly does nothing when they differ.
void SmoothVideo::CreateHistory()
{
	glGenTextures(2, history_);
	for(GLuint texture : history_)
	{
		glBindTexture(GL_TEXTURE_2D, texture);
		glTexStorage2D(GL_TEXTURE_2D, 1, format_, width_, height_);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}
	glBindTexture(GL_TEXTURE_2D, 0);
}

void SmoothVideo::Destroy(JNIEnv *env)
{
	if(surface_)
		env->DeleteGlobalRef(surface_);
	if(surface_texture_)
	{
		env->CallVoidMethod(surface_texture_, release_);
		clear_exception(env);
		env->DeleteGlobalRef(surface_texture_);
	}
	if(transform_array_)
		env->DeleteGlobalRef(transform_array_);
	surface_ = nullptr;
	surface_texture_ = nullptr;
	transform_array_ = nullptr;

	glDeleteTextures(2, history_);
	glDeleteTextures(1, &grid_texture_);
	glDeleteTextures(1, &external_texture_);
	glDeleteFramebuffers(1, &framebuffer_);
	glDeleteFramebuffers(1, &grid_framebuffer_);
	glDeleteVertexArrays(1, &vertex_array_);
	glDeleteProgram(video_program_);
	glDeleteProgram(grid_program_);
	history_[0] = history_[1] = 0;
	grid_texture_ = external_texture_ = framebuffer_ = grid_framebuffer_ = vertex_array_ = 0;
	video_program_ = grid_program_ = 0;
	extrapolate_ = nullptr;

	if(swapchain_ != XR_NULL_HANDLE)
		xrDestroySwapchain(swapchain_);
	swapchain_ = XR_NULL_HANDLE;
	images_.clear();
}

GLuint SmoothVideo::Acquire()
{
	uint32_t index = 0;
	if(XR_FAILED(xrAcquireSwapchainImage(swapchain_, nullptr, &index)))
		return 0;
	XrSwapchainImageWaitInfo wait = {XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
	wait.timeout = XR_INFINITE_DURATION;
	if(XR_FAILED(xrWaitSwapchainImage(swapchain_, &wait)) || index >= images_.size())
	{
		xrReleaseSwapchainImage(swapchain_, nullptr);
		return 0;
	}
	return images_[index];
}

void SmoothVideo::Release()
{
	xrReleaseSwapchainImage(swapchain_, nullptr);
}

// Draws the decoder's picture into [target], and into the older of the two remembered pictures too
// when [write_history]: one extra write, not a second pass over the screen.
void SmoothVideo::DrawVideo(GLuint target, bool write_history)
{
	glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
	int slot = history_newest_ == 0 ? 1 : 0;
	bool with_history = write_history && history_[slot] != 0;
	if(with_history)
	{
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, history_[slot], 0);
		if(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
		{
			// The remembered pictures do not match the swapchain after all. The video matters more.
			LOGE("History does not match the swapchain; frame prediction is off");
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
			extrapolate_ = nullptr;
			with_history = false;
		}
	}
	const GLenum both[2] = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
	glDrawBuffers(with_history ? 2 : 1, both);

	glViewport(0, 0, width_, height_);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glUseProgram(video_program_);
	glUniformMatrix4fv(video_transform_, 1, GL_FALSE, transform_);
	glUniform1f(video_linearise_, format_ == GL_SRGB8_ALPHA8 ? 1.0f : 0.0f);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_texture_);
	glBindVertexArray(vertex_array_);
	glDrawArrays(GL_TRIANGLES, 0, 3);

	if(with_history)
	{
		// Left attached, it would make the framebuffer incomplete the next time it is not wanted.
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, 0, 0);
		history_newest_ = slot;
		history_count_ = std::min(history_count_ + 1, 2);
	}
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

// How much the decoder's picture differs from the one before, in brightness steps out of 255.
float SmoothVideo::MeasureChange()
{
	int64_t started = now_ns();
	glBindFramebuffer(GL_FRAMEBUFFER, grid_framebuffer_);
	glViewport(0, 0, GRID_WIDTH, GRID_HEIGHT);
	glDisable(GL_BLEND);
	glUseProgram(grid_program_);
	glUniformMatrix4fv(grid_transform_, 1, GL_FALSE, transform_);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, external_texture_);
	glBindVertexArray(vertex_array_);
	glDrawArrays(GL_TRIANGLES, 0, 3);
	glReadPixels(0, 0, GRID_WIDTH, GRID_HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, grid_now_.data());
	glBindFramebuffer(GL_FRAMEBUFFER, 0);

	float change = 255.0f; // the first picture is new by definition
	if(grid_known_)
	{
		int64_t sum = 0;
		for(int i = 0; i < GRID_WIDTH * GRID_HEIGHT; i++)
			sum += std::abs(static_cast<int>(grid_now_[i * 4]) - static_cast<int>(grid_before_[i * 4]));
		change = static_cast<float>(sum) / static_cast<float>(GRID_WIDTH * GRID_HEIGHT);
	}
	grid_now_.swap(grid_before_);
	grid_known_ = true;
	measure_ms_ += static_cast<double>(now_ns() - started) / 1000000.0;
	return change;
}

// A repeat is either as good as identical, or changes far less than pictures have lately. A fixed
// line between the two does not work: slow real motion falls below any line that catches repeats.
bool SmoothVideo::IsRepeat(float change)
{
	float recent = std::max(recent_change_[0], std::max(recent_change_[1], recent_change_[2]));
	bool repeat = change < REPEAT_ABSOLUTE || (recent > 0.0f && change < REPEAT_RELATIVE * recent);
	recent_change_[recent_index_] = change;
	recent_index_ = (recent_index_ + 1) % 3;
	// A paused game repeats one picture for as long as it likes. That is not a rhythm to follow.
	repeats_in_a_row_ = repeat ? repeats_in_a_row_ + 1 : 0;
	if(repeats_in_a_row_ > 30)
		repeat = false;
	return repeat;
}

void SmoothVideo::Render(JNIEnv *env)
{
	env->CallVoidMethod(surface_texture_, update_tex_image_);
	if(clear_exception(env))
		return;
	// "Nothing new" cannot be read from the update itself; the picture's own time says it.
	int64_t timestamp = env->CallLongMethod(surface_texture_, get_timestamp_);
	bool fresh = timestamp != last_timestamp_;
	if(last_timestamp_ == -1 && timestamp == 0)
		fresh = false; // the decoder has not produced anything yet
	if(fresh)
	{
		last_timestamp_ = timestamp;
		env->CallVoidMethod(surface_texture_, get_transform_, transform_array_);
		env->GetFloatArrayRegion(transform_array_, 0, 16, transform_);
		clear_exception(env);
	}

	int64_t now = now_ns();
	bool predicting = extrapolate_ != nullptr;
	bool real = fresh;

	if(fresh && predicting)
	{
		float change = MeasureChange();
		bool repeat = IsRepeat(change);
		real = !repeat;
		if(repeat)
		{
			repeats_++;
		}
		else
		{
			// A cut: nothing in the old scene says where the new one is going.
			bool cut = change > CUT_ABSOLUTE && change > CUT_RELATIVE * std::max(motion_level_, 2.0f) && history_count_ > 0;
			bool was_moving = moving_;
			motion_level_ = cut ? change : (motion_level_ + change) * 0.5f;
			if(motion_level_ >= MOTION_ON)
				moving_ = true;
			else if(motion_level_ < MOTION_OFF)
				moving_ = false;

			int64_t interval = now - real_time_ns_;
			if(interval > 4000000 && interval < 100000000)
				period_ns_ += (static_cast<double>(interval) - period_ns_) / 8.0;
			double period = std::min(std::max(period_ns_, PERIOD_MIN_NS), PERIOD_MAX_NS);
			// Two pictures far apart hold all the motion in between; carried forward in one step it jumps.
			pair_adjacent_ = static_cast<double>(interval) <= 1.7 * period;

			if(cut || (moving_ && !was_moving))
			{
				history_count_ = 0;
				pair_adjacent_ = false;
				if(cut)
					cuts_++;
			}
			real_time_ns_ = now;
		}
	}

	if(real)
	{
		GLuint target = Acquire();
		if(target)
		{
			DrawVideo(target, predicting);
			Release();
			drawn_once_ = true;
			shown_predicted_ = false;
			reals_++;
		}
	}
	else if(drawn_once_)
	{
		double period = std::min(std::max(period_ns_, PERIOD_MIN_NS), PERIOD_MAX_NS);
		float scale = static_cast<float>(static_cast<double>(now - real_time_ns_) / period);
		bool predict = predicting && history_count_ >= 2 && moving_ && pair_adjacent_ && scale > 0.05f && scale <= 1.0f;
		if(predict)
		{
			GLuint target = Acquire();
			if(target)
			{
				int newest = history_newest_;
				if(!extrapolate_checked_)
					while(glGetError() != GL_NO_ERROR) { }
				extrapolate_(history_[1 - newest], history_[newest], target, scale);
				if(!extrapolate_checked_)
				{
					// Checked once: asking every frame makes the graphics chip stop and answer.
					extrapolate_checked_ = true;
					GLenum error = glGetError();
					if(error != GL_NO_ERROR)
					{
						LOGE("Frame prediction refused (0x%x); showing real frames only", error);
						extrapolate_ = nullptr;
						DrawVideo(target, false);
					}
					else
					{
						LOGI("Frame prediction is working");
					}
				}
				Release();
				if(extrapolate_)
				{
					shown_predicted_ = true;
					predicted_++;
				}
			}
		}
		else if(shown_predicted_)
		{
			// The guess has run out and the real picture has not arrived: go back to the last real one.
			GLuint target = Acquire();
			if(target)
			{
				DrawVideo(target, false);
				Release();
				shown_predicted_ = false;
			}
		}
	}

	if(now - report_time_ns_ >= 10000000000LL)
		Report();
}

void SmoothVideo::Report()
{
	int measured = reals_ + repeats_;
	LOGI("10 s: %d real, %d repeated, %d predicted, %d cuts, source %.1f fps, moving %s, compare %.2f ms",
			reals_, repeats_, predicted_, cuts_, 1000000000.0 / std::min(std::max(period_ns_, PERIOD_MIN_NS), PERIOD_MAX_NS),
			moving_ ? "yes" : "no", measured > 0 ? measure_ms_ / measured : 0.0);
	reals_ = repeats_ = predicted_ = cuts_ = 0;
	measure_ms_ = 0.0;
	report_time_ns_ = now_ns();
}
