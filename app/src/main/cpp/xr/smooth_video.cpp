// SPDX-License-Identifier: AGPL-3.0-only

#include <sys/system_properties.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <climits>
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

// How much each spot changed between the last two real pictures: the largest change in brightness
// among four points around it, so a thin moving edge is not missed between samples.
const char *DIFF_SHADER = R"(#version 300 es
precision highp float;
uniform sampler2D uNew;
uniform sampler2D uOld;
uniform vec2 uPixel;
in vec2 vUv;
out vec4 oColor;
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main()
{
	float change = 0.0;
	for(int i = 0; i < 4; i++)
	{
		vec2 at = vUv + vec2(float(i & 1) * 4.0 - 2.0, float(i >> 1) * 4.0 - 2.0) * uPixel;
		change = max(change, abs(luma(texture(uNew, at).rgb) - luma(texture(uOld, at).rgb)));
	}
	oColor = vec4(change, 0.0, 0.0, 1.0);
}
)";

// Spreads the moving areas outwards, so the leading edge of something that moves is inside them.
const char *DILATE_SHADER = R"(#version 300 es
precision highp float;
uniform sampler2D uMask;
uniform vec2 uPixel;
in vec2 vUv;
out vec4 oColor;
void main()
{
	float change = 0.0;
	for(int y = -2; y <= 2; y++)
		for(int x = -2; x <= 2; x++)
			change = max(change, texture(uMask, vUv + vec2(float(x), float(y)) * 2.0 * uPixel).r);
	oColor = vec4(change, 0.0, 0.0, 1.0);
}
)";

const char *BLEND_SHADER = R"(#version 300 es
precision highp float;
uniform sampler2D uReal;
uniform sampler2D uPredicted;
uniform sampler2D uMask;
in vec2 vUv;
out vec4 oColor;
void main()
{
	float moving = smoothstep(0.03, 0.08, texture(uMask, vUv).r);
	oColor = vec4(mix(texture(uReal, vUv).rgb, texture(uPredicted, vUv).rgb, moving), 1.0);
}
)";

const float IDENTITY[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

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

namespace { bool st_enabled(); }

namespace { bool st_enabled(); }

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
	{
		CreateHistory();
		mask_ready_ = CreateMask();
		if(!mask_ready_)
			LOGE("No motion mask on this device; the whole picture is predicted");
	}
	LOGI("Smooth video ready: %dx%d, format 0x%x, frame prediction %s", width_, height_, format_,
			extrapolate_ ? "available" : "not available on this device");
	if(extrapolate_)
		flow_ready_ = flow_.Create(width_, height_);
	if(st_enabled())
		SelfTest("at start");

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

bool SmoothVideo::CreateMask()
{
	diff_program_ = link(DIFF_SHADER);
	dilate_program_ = link(DILATE_SHADER);
	blend_program_ = link(BLEND_SHADER);
	if(!diff_program_ || !dilate_program_ || !blend_program_)
		return false;
	mask_width_ = std::max(1, width_ / 8);
	mask_height_ = std::max(1, height_ / 8);

	// These draw textures as they are, with no decoder transform in the way.
	for(GLuint program : {diff_program_, dilate_program_, blend_program_})
	{
		glUseProgram(program);
		glUniformMatrix4fv(glGetUniformLocation(program, "uTransform"), 1, GL_FALSE, IDENTITY);
	}
	glUseProgram(diff_program_);
	glUniform1i(glGetUniformLocation(diff_program_, "uNew"), 0);
	glUniform1i(glGetUniformLocation(diff_program_, "uOld"), 1);
	glUniform2f(glGetUniformLocation(diff_program_, "uPixel"), 1.0f / static_cast<float>(width_), 1.0f / static_cast<float>(height_));
	glUseProgram(dilate_program_);
	glUniform1i(glGetUniformLocation(dilate_program_, "uMask"), 0);
	glUniform2f(glGetUniformLocation(dilate_program_, "uPixel"), 1.0f / static_cast<float>(mask_width_), 1.0f / static_cast<float>(mask_height_));
	glUseProgram(blend_program_);
	glUniform1i(glGetUniformLocation(blend_program_, "uReal"), 0);
	glUniform1i(glGetUniformLocation(blend_program_, "uPredicted"), 1);
	glUniform1i(glGetUniformLocation(blend_program_, "uMask"), 2);
	glUseProgram(0);

	glGenTextures(2, mask_);
	for(GLuint texture : mask_)
	{
		glBindTexture(GL_TEXTURE_2D, texture);
		glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, mask_width_, mask_height_);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}
	glGenTextures(1, &predicted_texture_);
	glBindTexture(GL_TEXTURE_2D, predicted_texture_);
	glTexStorage2D(GL_TEXTURE_2D, 1, format_, width_, height_);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	glBindTexture(GL_TEXTURE_2D, 0);
	glGenFramebuffers(1, &mask_framebuffer_);
	return true;
}

// Once per real picture, from the two remembered ones.
void SmoothVideo::UpdateMask()
{
	if(!mask_ready_ || history_count_ < 2)
		return;
	int newest = history_newest_;
	glBindFramebuffer(GL_FRAMEBUFFER, mask_framebuffer_);
	glViewport(0, 0, mask_width_, mask_height_);
	glDisable(GL_BLEND);
	glBindVertexArray(vertex_array_);

	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mask_[0], 0);
	glUseProgram(diff_program_);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, history_[newest]);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, history_[1 - newest]);
	glDrawArrays(GL_TRIANGLES, 0, 3);

	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mask_[1], 0);
	glUseProgram(dilate_program_);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, mask_[0]);
	glDrawArrays(GL_TRIANGLES, 0, 3);

	glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

// The real picture where nothing moved, the predicted one where something did.
void SmoothVideo::Blend(GLuint target)
{
	glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
	const GLenum one = GL_COLOR_ATTACHMENT0;
	glDrawBuffers(1, &one);
	glViewport(0, 0, width_, height_);
	glDisable(GL_BLEND);
	glUseProgram(blend_program_);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, history_[history_newest_]);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, predicted_texture_);
	glActiveTexture(GL_TEXTURE2);
	glBindTexture(GL_TEXTURE_2D, mask_[1]);
	glBindVertexArray(vertex_array_);
	glDrawArrays(GL_TRIANGLES, 0, 3);
	glActiveTexture(GL_TEXTURE0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

// The graphics chip reports no error when it declines to predict; it just leaves the output alone.
// So the output is painted a colour no game picture is made of, a prediction is asked for, and one
// spot is read back: if the paint is still there, nothing happened.
bool SmoothVideo::PredictionTakesEffect(float scale)
{
	int newest = history_newest_;
	glBindFramebuffer(GL_FRAMEBUFFER, mask_framebuffer_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, predicted_texture_, 0);
	glViewport(0, 0, width_, height_);
	glClearColor(1.0f, 0.0f, 1.0f, 1.0f);
	glClear(GL_COLOR_BUFFER_BIT);
	while(glGetError() != GL_NO_ERROR) { }
	extrapolate_(history_[1 - newest], history_[newest], predicted_texture_, scale);
	GLenum error = glGetError();
	uint8_t pixel[4] = {0, 0, 0, 0};
	glReadPixels(width_ / 2, height_ / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	bool untouched = pixel[0] > 250 && pixel[1] < 5 && pixel[2] > 250;
	if(error != GL_NO_ERROR || untouched)
	{
		LOGE("Frame prediction does nothing here (error 0x%x, output %s); showing real frames only", error,
				untouched ? "untouched" : "changed");
		return false;
	}
	LOGI("Frame prediction is working, with the motion mask");
	return true;
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
	glDeleteTextures(2, mask_);
	glDeleteTextures(1, &predicted_texture_);
	glDeleteFramebuffers(1, &mask_framebuffer_);
	glDeleteProgram(diff_program_);
	glDeleteProgram(dilate_program_);
	glDeleteProgram(blend_program_);
	mask_[0] = mask_[1] = predicted_texture_ = mask_framebuffer_ = 0;
	diff_program_ = dilate_program_ = blend_program_ = 0;
	mask_ready_ = false;
	glDeleteTextures(1, &grid_texture_);
	glDeleteTextures(1, &external_texture_);
	glDeleteFramebuffers(1, &framebuffer_);
	if(guides_[0])
		glDeleteTextures(2, guides_);
	guides_[0] = guides_[1] = 0;
	if(flow_ready_)
		flow_.Destroy();
	flow_ready_ = false;
	if(copy_framebuffer_)
		glDeleteFramebuffers(1, &copy_framebuffer_);
	copy_framebuffer_ = 0;
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
			moving_ = true; // calm scenes get frames too: switching in and out of it showed as uneven motion

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

	bool between = predicting && level_ >= 3 && flow_ready_;
	if(between)
	{
		RenderBetween(real, now);
	}
	else if(real)
	{
		between_shows_newest_ = false;
		GLuint target = Acquire();
		if(target)
		{
			DrawVideo(target, predicting);
			Release();
			if(predicting)
				UpdateMask();
			drawn_once_ = true;
			shown_predicted_ = false;
			reals_++;
			real_this_second_++;
		}
	}
	else if(drawn_once_)
	{
		double period = std::min(std::max(period_ns_, PERIOD_MIN_NS), PERIOD_MAX_NS);
		float scale = static_cast<float>(static_cast<double>(now - real_time_ns_) / period);
		bool predict = predicting && history_count_ >= 2 && moving_ && pair_adjacent_ && scale > 0.05f && scale <= 1.0f;
		if(predict && mask_ready_ && level_ < 2)
		{
			if(!extrapolate_checked_)
			{
				extrapolate_checked_ = true;
				if(!PredictionTakesEffect(scale))
					extrapolate_ = nullptr;
			}
			if(extrapolate_)
			{
				predicted_this_second_++;
				extrapolate_(history_[1 - history_newest_], history_[history_newest_], predicted_texture_, scale);
				GLuint target = Acquire();
				if(target)
				{
					Blend(target);
					Release();
					shown_predicted_ = true;
					predicted_++;
				}
			}
		}
		else if(predict)
		{
			GLuint target = Acquire();
			if(target)
			{
				int newest = history_newest_;
				if(!extrapolate_checked_)
					while(glGetError() != GL_NO_ERROR) { }
				// The last real picture goes in first: a prediction that quietly does nothing then leaves
				// that on screen, and not whatever this image held a few frames ago.
				DrawVideo(target, false);
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
						LOGI("Frame prediction is working, on the whole picture");
					}
				}
				Release();
				if(extrapolate_)
				{
					shown_predicted_ = true;
					predicted_++;
					predicted_this_second_++;
				}
			}
		}
		else if(shown_predicted_ || fresh)
		{
			// Either the guess has run out and the real picture has not arrived, or a new picture was
			// taken for a repeat and there is no guess to show instead: put the decoder's picture up.
			GLuint target = Acquire();
			if(target)
			{
				DrawVideo(target, false);
				Release();
				shown_predicted_ = false;
			}
		}
	}

	if(now - second_start_ns_ >= 1000000000LL)
	{
		real_per_second_ = real_this_second_;
		predicted_per_second_ = predicted_this_second_;
		real_this_second_ = predicted_this_second_ = 0;
		second_start_ns_ = now;
	}
	if(now - report_time_ns_ >= 10000000000LL)
		Report();
}

void SmoothVideo::Copy(GLuint source, GLuint target)
{
	if(!copy_framebuffer_)
		glGenFramebuffers(1, &copy_framebuffer_);
	glBindFramebuffer(GL_READ_FRAMEBUFFER, copy_framebuffer_);
	glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, source, 0);
	glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer_);
	glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
	const GLenum one = GL_COLOR_ATTACHMENT0;
	glDrawBuffers(1, &one);
	glBlitFramebuffer(0, 0, width_, height_, 0, 0, width_, height_, GL_COLOR_BUFFER_BIT, GL_NEAREST);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

// How far apart two pictures are over a block in the middle, in steps out of 255.
float SmoothVideo::BlockDifference(GLuint a, GLuint b)
{
	const int size = 96;
	static uint8_t block[2][size * size * 4];
	const GLuint textures[2] = {a, b};
	if(!copy_framebuffer_)
		glGenFramebuffers(1, &copy_framebuffer_);
	glBindFramebuffer(GL_FRAMEBUFFER, copy_framebuffer_);
	for(int i = 0; i < 2; i++)
	{
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[i], 0);
		glReadPixels((width_ - size) / 2, (height_ - size) / 2, size, size, GL_RGBA, GL_UNSIGNED_BYTE, block[i]);
	}
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	int64_t sum = 0;
	for(int i = 0; i < size * size * 4; i++)
		sum += std::abs(static_cast<int>(block[0][i]) - static_cast<int>(block[1][i]));
	return static_cast<float>(sum) / static_cast<float>(size * size * 4);
}

// Test builds, with "adb shell setprop debug.farsofa.selftest 1": what the chip's frame prediction
// really does, measured on pictures made here whose motion is known to the pixel. Everything goes
// to the log; nothing of it is shown.
namespace {
const int ST_PAD = 256;
const int ST_VPAD = 64;
const int ST_BLOCK_W = 256;
const int ST_BLOCK_H = 32;

uint32_t st_hash(uint32_t x, uint32_t y, uint32_t seed)
{
	uint32_t h = x * 374761393u + y * 668265263u + seed * 2246822519u;
	h = (h ^ (h >> 13)) * 1274126177u;
	return h ^ (h >> 16);
}

float st_noise(float x, float y, uint32_t seed)
{
	int xi = static_cast<int>(x), yi = static_cast<int>(y);
	float fx = x - xi, fy = y - yi;
	float a = (st_hash(xi, yi, seed) & 255) / 255.0f, b = (st_hash(xi + 1, yi, seed) & 255) / 255.0f;
	float c = (st_hash(xi, yi + 1, seed) & 255) / 255.0f, d = (st_hash(xi + 1, yi + 1, seed) & 255) / 255.0f;
	return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
}

bool st_enabled()
{
	char value[PROP_VALUE_MAX] = {0};
	return __system_property_get("debug.farsofa.selftest", value) > 0 && value[0] == '1';
}
}

void SmoothVideo::SelfTest(const char *when)
{
	if(!extrapolate_)
		return;
	const int bw = width_ + 2 * ST_PAD, bh = height_ + 2 * ST_VPAD;
	std::vector<uint8_t> base(static_cast<size_t>(bw) * bh * 4);
	for(int y = 0; y < bh; y++)
		for(int x = 0; x < bw; x++)
		{
			float v = 0.5f * st_noise(x / 96.0f, y / 96.0f, 1) + 0.3f * st_noise(x / 24.0f, y / 24.0f, 2) + 0.2f * st_noise(x / 6.0f, y / 6.0f, 3);
			uint8_t *px = &base[(static_cast<size_t>(y) * bw + x) * 4];
			px[0] = static_cast<uint8_t>(v * 255);
			px[1] = static_cast<uint8_t>((0.6f * v + 0.4f * st_noise(x / 48.0f, y / 48.0f, 4)) * 255);
			px[2] = static_cast<uint8_t>((0.6f * v + 0.4f * st_noise(x / 40.0f, y / 40.0f, 5)) * 255);
			px[3] = 255;
		}

	GLuint tex[5];
	glGenTextures(5, tex);
	for(GLuint t : tex)
	{
		glBindTexture(GL_TEXTURE_2D, t);
		glTexStorage2D(GL_TEXTURE_2D, 1, format_, width_, height_);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}
	GLuint A = tex[0], B = tex[1], OUT = tex[2], G = tex[3], G2 = tex[4];

	// The picture, moved right by [mx] and down by [my] pixels.
	auto upload = [&](GLuint t, int mx, int my) {
		glBindTexture(GL_TEXTURE_2D, t);
		glPixelStorei(GL_UNPACK_ROW_LENGTH, bw);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE,
				&base[(static_cast<size_t>(ST_VPAD - my) * bw + (ST_PAD - mx)) * 4]);
		glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
	};
	// How far right (or down, when [vertical]) the picture in [t] sits, and how well that fits (0 is exact).
	auto measure = [&](GLuint t, bool vertical, float *residual, int cx = -1, int cy = -1) {
		static uint8_t block[ST_BLOCK_W * ST_BLOCK_H * 4];
		int x0 = (cx < 0 ? width_ / 2 : cx) - ST_BLOCK_W / 2, y0 = (cy < 0 ? height_ / 2 : cy) - ST_BLOCK_H / 2;
		if(!copy_framebuffer_)
			glGenFramebuffers(1, &copy_framebuffer_);
		glBindFramebuffer(GL_FRAMEBUFFER, copy_framebuffer_);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0);
		glReadPixels(x0, y0, ST_BLOCK_W, ST_BLOCK_H, GL_RGBA, GL_UNSIGNED_BYTE, block);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		int best = 0;
		int64_t best_sum = INT64_MAX;
		int range = vertical ? ST_VPAD - 4 : ST_PAD - 8;
		for(int m = -range; m <= range; m++)
		{
			int64_t sum = 0;
			for(int y = 0; y < ST_BLOCK_H; y += 2)
				for(int x = 0; x < ST_BLOCK_W; x += 2)
				{
					int sx = x0 + x + ST_PAD - (vertical ? 0 : m), sy = y0 + y + ST_VPAD - (vertical ? m : 0);
					sum += std::abs(static_cast<int>(block[(y * ST_BLOCK_W + x) * 4]) - static_cast<int>(base[(static_cast<size_t>(sy) * bw + sx) * 4]));
				}
			if(sum < best_sum)
			{
				best_sum = sum;
				best = m;
			}
		}
		*residual = static_cast<float>(best_sum) / (ST_BLOCK_W * ST_BLOCK_H / 4);
		return best;
	};
	auto run = [&](GLuint older, GLuint newer, GLuint out, float scale, bool finish_before, bool finish_after) {
		if(finish_before)
			glFinish();
		extrapolate_(older, newer, out, scale);
		if(finish_after)
			glFinish();
	};

	float r = 0;
	LOGI("SelfTest (%s) begins: %dx%d, format 0x%x", when, width_, height_, format_);
	while(glGetError() != GL_NO_ERROR) { }

	// 0. The instrument itself.
	upload(A, 0, 0);
	upload(B, 8, 0);
	int ma = measure(A, false, &r);
	float ra = r;
	int mb = measure(B, false, &r);
	LOGI("SelfTest instrument: A reads %d (fit %.2f), B reads %d (fit %.2f); expected 0 and 8", ma, ra, mb, r);

	// 1. Scales, with the output holding the newer picture beforehand (so "nothing done" reads 8).
	for(float scale : {0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f})
	{
		Copy(B, OUT);
		run(A, B, OUT, scale, false, false);
		int m = measure(OUT, false, &r);
		LOGI("SelfTest scale %.2f, motion 8: expected %.1f, got %d (fit %.2f)", scale, 8 * (1 + scale), m, r);
	}

	// 2. How large a motion it follows, sideways and up and down.
	for(int d : {1, 2, 4, 8, 12, 16, 24, 32, 48, 64, 96})
	{
		upload(B, d, 0);
		Copy(B, OUT);
		run(A, B, OUT, 0.5f, false, false);
		int m = measure(OUT, false, &r);
		LOGI("SelfTest motion %d sideways, scale 0.5: expected %.1f, got %d (fit %.2f)", d, d * 1.5f, m, r);
	}
	for(int d : {2, 8, 16, 32})
	{
		upload(B, 0, d);
		Copy(B, OUT);
		run(A, B, OUT, 0.5f, false, false);
		int m = measure(OUT, true, &r);
		LOGI("SelfTest motion %d downwards, scale 0.5: expected %.1f, got %d (fit %.2f)", d, d * 1.5f, m, r);
	}
	for(int d : {-8, -32})
	{
		upload(B, d, 0);
		Copy(B, OUT);
		run(A, B, OUT, 0.5f, false, false);
		int m = measure(OUT, false, &r);
		LOGI("SelfTest motion %d sideways, scale 0.5: expected %.1f, got %d (fit %.2f)", d, d * 1.5f, m, r);
	}

	// 3. Does it do the work every time? Twenty calls each way; a call "worked" if the picture moved on.
	upload(B, 8, 0);
	struct Way { const char *name; bool before, after, fresh, prefill, sleep; };
	const Way ways[] = {
		{"plain", false, false, false, true, false},
		{"finish before", true, false, false, true, false},
		{"finish after", false, true, false, true, false},
		{"finish both", true, true, false, true, false},
		{"new pictures each call", false, false, true, true, false},
		{"new pictures each call, finish before", true, false, true, true, false},
		{"output not filled first", false, false, false, false, false},
		{"8 ms pause between calls", false, false, false, true, true},
	};
	for(const Way &way : ways)
	{
		int worked = 0, untouched = 0, other = 0;
		int64_t started = now_ns();
		char detail[128] = "";
		for(int i = 0; i < 20; i++)
		{
			if(way.fresh)
			{
				upload(A, i, 0);
				upload(B, i + 8, 0);
			}
			if(way.prefill)
				Copy(B, OUT);
			if(way.sleep)
				usleep(8000);
			run(A, B, OUT, 0.5f, way.before, way.after);
			int m = measure(OUT, false, &r) - (way.fresh ? i : 0);
			if(m >= 11 && m <= 13 && r < 6)
				worked++;
			else if(m == 8 && r < 1)
				untouched++;
			else
				other++;
			if(i < 6)
				snprintf(detail + strlen(detail), sizeof(detail) - strlen(detail), " %d(%.0f)", m, r);
		}
		LOGI("SelfTest reliability, %s: %d worked, %d untouched, %d other, %.1f ms a round; first results%s", way.name, worked, untouched, other,
				static_cast<double>(now_ns() - started) / 20e6, detail);
		if(way.fresh)
		{
			upload(A, 0, 0);
			upload(B, 8, 0);
		}
	}

	// 4. What one call costs when waited for.
	{
		glFinish();
		int64_t started = now_ns();
		for(int i = 0; i < 10; i++)
			run(A, B, OUT, 0.5f, false, true);
		LOGI("SelfTest cost: %.2f ms a call", static_cast<double>(now_ns() - started) / 10e6);
	}

	// 5. Backwards, three ways.
	Copy(B, OUT);
	run(A, B, OUT, -0.5f, false, true);
	int m = measure(OUT, false, &r);
	LOGI("SelfTest negative scale -0.5: expected 4 if it goes back, 8 if ignored; got %d (fit %.2f)", m, r);
	Copy(A, OUT);
	run(B, A, OUT, 0.5f, false, true);
	m = measure(OUT, false, &r);
	LOGI("SelfTest swapped order (newer, older), scale 0.5: expected -4, 0 if ignored; got %d (fit %.2f)", m, r);
	Copy(A, G);
	run(B, A, G, 1.0f, false, true);
	m = measure(G, false, &r);
	LOGI("SelfTest guide (one step before the older): expected -8; got %d (fit %.2f)", m, r);
	for(float along : {0.25f, 0.5f, 0.75f})
	{
		Copy(A, OUT);
		run(G, A, OUT, along, false, true);
		m = measure(OUT, false, &r);
		LOGI("SelfTest in-between from a made-up guide, %.2f of the way: expected %.1f; got %d (fit %.2f)", along, 8 * along, m, r);
	}
	upload(G2, -8, 0);
	for(float along : {0.25f, 0.5f, 0.75f})
	{
		Copy(A, OUT);
		run(G2, A, OUT, along, false, true);
		m = measure(OUT, false, &r);
		LOGI("SelfTest in-between from an exact guide, %.2f of the way: expected %.1f; got %d (fit %.2f)", along, 8 * along, m, r);
	}

	// 6. Into an image of the swapchain, as the game picture is.
	for(int i = 0; i < 3; i++)
	{
		GLuint target = Acquire();
		if(!target)
			break;
		Copy(B, target);
		run(A, B, target, 0.5f, false, false);
		m = measure(target, false, &r);
		Release();
		LOGI("SelfTest into the swapchain, try %d: expected 12; got %d (fit %.2f)", i, m, r);
	}

	// 7. A picture of which only the left half moves: does the still half stay put?
	{
		std::vector<uint8_t> half(static_cast<size_t>(width_ / 2) * height_ * 4);
		glBindFramebuffer(GL_FRAMEBUFFER, copy_framebuffer_);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, A, 0);
		glReadPixels(width_ / 2, 0, width_ / 2, height_, GL_RGBA, GL_UNSIGNED_BYTE, half.data());
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, B);
		glTexSubImage2D(GL_TEXTURE_2D, 0, width_ / 2, 0, width_ / 2, height_, GL_RGBA, GL_UNSIGNED_BYTE, half.data());
		glBindTexture(GL_TEXTURE_2D, 0);
		Copy(B, OUT);
		run(A, B, OUT, 0.5f, false, true);
		float r2 = 0;
		int left = measure(OUT, false, &r, width_ / 4, height_ / 2);
		int right = measure(OUT, false, &r2, 3 * width_ / 4, height_ / 2);
		LOGI("SelfTest half moving: moving half expected 12, got %d (fit %.2f); still half expected 0, got %d (fit %.2f)", left, r, right, r2);
	}

	// 8. The motion worked out here.
	if(flow_ready_)
	{
		upload(A, 0, 0);
		struct Case { int mx, my; };
		const Case cases[] = {{1, 0}, {3, 0}, {8, 0}, {20, 0}, {48, 0}, {96, 0}, {160, 0}, {220, 0}, {-64, 0}, {0, 6}, {0, 24}, {0, 48}, {40, 20}};
		for(const Case &c : cases)
		{
			upload(B, c.mx, c.my);
			glFinish();
			int64_t started = now_ns();
			flow_.Estimate(A, B);
			glFinish();
			double estimate = static_cast<double>(now_ns() - started) / 1e6;
			for(float along : {0.25f, 0.5f, 0.75f})
			{
				started = now_ns();
				flow_.Draw(OUT, A, B, along, true);
				glFinish();
				double draw = static_cast<double>(now_ns() - started) / 1e6;
				bool vertical = c.mx == 0;
				int got = measure(OUT, vertical, &r);
				LOGI("SelfTest own motion (%d, %d), %.2f of the way: expected %.1f %s, got %d (fit %.2f); found in %.2f ms, drawn in %.2f ms",
						c.mx, c.my, along, (vertical ? c.my : c.mx) * along, vertical ? "down" : "sideways", got, r, estimate, draw);
			}
			flow_.Draw(OUT, A, B, 0.5f, false);
			int ahead = measure(OUT, c.mx == 0, &r);
			LOGI("SelfTest own motion (%d, %d), half a step ahead: expected %.1f, got %d (fit %.2f)", c.mx, c.my, (c.mx == 0 ? c.my : c.mx) * 1.5f, ahead, r);
		}
		// Only the left half moves.
		upload(B, 24, 0);
		{
			std::vector<uint8_t> half(static_cast<size_t>(width_ / 2) * height_ * 4);
			glBindFramebuffer(GL_FRAMEBUFFER, copy_framebuffer_);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, A, 0);
			glReadPixels(width_ / 2, 0, width_ / 2, height_, GL_RGBA, GL_UNSIGNED_BYTE, half.data());
			glBindFramebuffer(GL_FRAMEBUFFER, 0);
			glBindTexture(GL_TEXTURE_2D, B);
			glTexSubImage2D(GL_TEXTURE_2D, 0, width_ / 2, 0, width_ / 2, height_, GL_RGBA, GL_UNSIGNED_BYTE, half.data());
			glBindTexture(GL_TEXTURE_2D, 0);
			flow_.Estimate(A, B);
			flow_.Draw(OUT, A, B, 0.5f, true);
			float r2 = 0;
			int left = measure(OUT, false, &r, width_ / 4, height_ / 2);
			int right = measure(OUT, false, &r2, 3 * width_ / 4, height_ / 2);
			LOGI("SelfTest own motion, half moving 24: moving half expected 12, got %d (fit %.2f); still half expected 0, got %d (fit %.2f)", left, r, right, r2);
		}
	}
	else
	{
		LOGE("SelfTest: own motion is not available on this device");
	}

	GLenum error = glGetError();
	LOGI("SelfTest (%s) ends, GL error 0x%x", when, error);
	glDeleteTextures(5, tex);
}

void SmoothVideo::MakeGuides()
{
	if(!guides_[0])
	{
		glGenTextures(2, guides_);
		for(GLuint texture : guides_)
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
	GLuint older = history_[1 - history_newest_];
	GLuint newer = history_[history_newest_];
	Copy(older, guides_[0]);
	extrapolate_(newer, older, guides_[0], 1.0f); // one step before the older picture
	Copy(newer, guides_[1]);
	extrapolate_(older, newer, guides_[1], 1.0f); // one step after the newer picture
	guides_current_ = true;
}

// Building a picture between two real ones asks the graphics chip to carry the newer one backwards,
// which its description does not promise. Tried once on two pictures that differ: the result has to
// be something other than either of them. Returns false while there is nothing to tell from yet.
bool SmoothVideo::BetweenWorks()
{
	GLuint older = history_[1 - history_newest_];
	GLuint newer = history_[history_newest_];
	float apart = BlockDifference(older, newer);
	if(apart < 1.5f)
	{
		// Too alike to tell anything from (a menu, a loading screen): try on a later pair. If the
		// picture is plainly moving and the two still read as equal, the reading itself is not to
		// be trusted on this device; in-between frames are then used without the proof.
		if(motion_level_ < 2.0f * MOTION_ON || apart > 0.01f || ++between_tries_ < 240)
			return false;
		between_checked_ = true;
		between_works_ = true;
		LOGE("In-between frames could not be checked (pictures read back as equal); using them unchecked");
		return true;
	}
	between_checked_ = true;

	if(!predicted_texture_)
	{
		LOGE("In-between frames: no spare picture to try on; using frames guessed ahead");
		return true;
	}
	while(glGetError() != GL_NO_ERROR) { }
	MakeGuides();
	Copy(older, predicted_texture_);
	extrapolate_(guides_[0], older, predicted_texture_, 0.5f);
	GLenum error = glGetError();
	// What each step did, for the log: the two guides against the pictures they were made from,
	// and plain steps forward of half and of a whole picture for comparison.
	float guide_back = BlockDifference(guides_[0], older);
	float guide_on = BlockDifference(guides_[1], newer);
	float guide_back_newer = BlockDifference(guides_[0], newer);
	Copy(newer, guides_[1]);
	extrapolate_(older, newer, guides_[1], 0.5f);
	float half_on = BlockDifference(guides_[1], newer);
	Copy(older, guides_[1]);
	extrapolate_(newer, older, guides_[1], 0.5f);
	float half_back = BlockDifference(guides_[1], older);
	guides_current_ = false;
	LOGI("In-between check: pair %.2f apart; guide before %.2f from older (%.2f from newer), guide after %.2f from newer; half step on %.2f, half step back %.2f",
			apart, guide_back, guide_back_newer, guide_on, half_on, half_back);
	float from_newer = BlockDifference(predicted_texture_, newer);
	float from_older = BlockDifference(predicted_texture_, older);
	// Half way there, the result is neither picture and is nearer the newer one than the older is.
	between_works_ = error == GL_NO_ERROR && from_older > 0.1f * apart && from_newer > 0.05f * apart && from_newer < 0.95f * apart;
	if(between_works_)
		LOGI("In-between frames are working (pair %.1f apart; result %.1f from the older, %.1f from the newer)", apart, from_older, from_newer);
	else
		LOGE("In-between frames do nothing here (error 0x%x; pair %.1f apart; result %.1f from the older, %.1f from the newer); using frames guessed ahead",
				error, apart, from_older, from_newer);
	return true;
}

// Level 3. The newest real picture is kept back; what is shown runs one picture behind, from the
// one before it towards it, so every picture made up lies between two that are known.
// Level 3. What is on screen runs one game picture behind what has arrived, so that every picture
// made up lies between two that are known; and it is paced by the clock, 60 a second, whatever the
// game does. A game picture that comes late does not stop it: until it arrives the motion last
// found is carried on, and when it does, the gap it left is filled with as many pictures as fit.
void SmoothVideo::RenderBetween(bool real, int64_t now)
{
	const double slot = 1000000000.0 / 60.0;
	const double half_refresh = 1000000000.0 / 240.0;
	double period = std::min(std::max(period_ns_, PERIOD_MIN_NS), PERIOD_MAX_NS);

	if(real)
	{
		int slot_index = history_newest_ == 0 ? 1 : 0;
		DrawVideo(history_[slot_index], false);
		history_newest_ = slot_index;
		history_count_ = std::min(history_count_ + 1, 2);
		drawn_once_ = true;
		between_step_shown_ = -1000;
		flow_current_ = false;
		// When this picture is taken to have arrived: on time, the small wobble of the network and
		// the decoder is smoothed out; late or early by more than that is the game itself, and kept.
		int64_t expected = steady_time_ns_ + static_cast<int64_t>(period);
		int64_t error = now - expected;
		steady_before_ns_ = steady_time_ns_;
		steady_time_ns_ = std::llabs(error) > static_cast<int64_t>(period * 0.3) ? now : expected + error / 32;
		reals_++;
		real_this_second_++;
	}
	if(!drawn_once_ || history_newest_ < 0)
		return;

	GLuint older = history_[1 - history_newest_];
	GLuint newer = history_[history_newest_];
	double gap = static_cast<double>(steady_time_ns_ - steady_before_ns_);
	// A game already at 60 has nothing to add and is not held back. Nor is anything made across a
	// cut or a pause.
	bool pair = history_count_ >= 2 && period > 25000000.0 && gap > 20000000.0 && gap < 4.5 * period;
	if(!pair)
	{
		if(between_step_shown_ == 0)
			return;
		GLuint target = Acquire();
		if(target)
		{
			Copy(newer, target);
			Release();
			between_step_shown_ = 0;
			shown_predicted_ = false;
		}
		return;
	}

	// The moment of the game to show now, counted in sixtieths of a second from the newer picture:
	// negative between the two, zero the newer one itself, positive past it. Half a refresh is
	// added so that the count never changes right on a refresh of the screen, where the slightest
	// wobble would show one picture for one refresh and the next for three.
	double wanted = static_cast<double>(now - steady_time_ns_) + half_refresh - period;
	int step = static_cast<int>(std::floor(wanted / slot));
	float along = 1.0f + static_cast<float>(static_cast<double>(step) * slot / gap);
	if(step < 0 && along < 0.04f)
	{
		step = -999; // the older picture itself
		along = 0.0f;
	}
	// Carried on for one game picture's worth at most; past that the guess is worth less than waiting.
	float ahead = static_cast<float>(static_cast<double>(step) * slot / gap);
	if(step > 0 && ahead > 1.01f)
	{
		step = 999;
	}
	if(step == between_step_shown_ || step == 999)
	{
		between_refreshes_++;
		return; // already on screen
	}
	// Test builds: for how many refreshes of the screen each picture stayed up. Even is all twos.
	between_holds_[std::min(between_refreshes_, 5)]++;
	between_refreshes_ = 1;

	GLuint target = Acquire();
	if(!target)
		return;
	between_step_shown_ = step;
	bool made = false;
	if(step == -999)
	{
		Copy(older, target);
	}
	else if(step == 0)
	{
		Copy(newer, target);
	}
	else
	{
		if(!flow_current_)
		{
			flow_.Estimate(older, newer);
			flow_current_ = true;
		}
		if(flow_.Unexplained())
		{
			// No motion explains how this pair differs (a fade, a menu sliding over): anything made
			// from it would be a smear. It goes by as the game sent it.
			Copy(step < 0 ? older : newer, target);
			troubled_++;
		}
		else if(step < 0)
		{
			flow_.Draw(target, older, newer, along, true);
			made = true;
		}
		else
		{
			flow_.Draw(target, older, newer, ahead, false);
			made = true;
			ahead_++;
		}
	}
	if(made)
	{
		predicted_++;
		predicted_this_second_++;
		// Test builds: where the picture made up sits between the two real ones.
		static const bool testing = st_enabled();
		if(testing && ++between_probe_ % 15 == 0 && between_probe_ < 15 * 600)
		{
			float apart = BlockDifference(older, newer);
			float from_older = BlockDifference(target, older);
			float from_newer = BlockDifference(target, newer);
			LOGI("Between probe: step %d (%.2f), gap %.0f ms, pair %.2f apart, result %.2f from the older, %.2f from the newer, trouble %.3f of %.3f unmoved, brighter %+.3f (%d pairs let by), %d carried on, motion found in %.2f ms; held for 1/2/3/4/5+ refreshes: %d %d %d %d %d",
					step, step < 0 ? along : ahead, gap / 1000000.0, apart, from_older, from_newer, flow_.Trouble(), flow_.Unmoved(), flow_.Brighter(), troubled_, ahead_, estimate_ms_,
					between_holds_[1], between_holds_[2], between_holds_[3], between_holds_[4], between_holds_[5]);
			troubled_ = 0;
			ahead_ = 0;
			for(int &hold : between_holds_)
				hold = 0;
		}
		// Test builds: now and then the two real pictures and the one made from them are saved, to be looked at.
		if(testing && dumps_ < 12 && now - dump_time_ns_ > 8000000000LL && BlockDifference(older, newer) > 4.0f)
		{
			dump_time_ns_ = now;
			DumpPicture(older, dumps_, "a");
			DumpPicture(newer, dumps_, "b");
			DumpPicture(target, dumps_, step < 0 ? "m" : "x");
			dumps_++;
		}
	}
	Release();
	shown_predicted_ = made;
	if(!flow_current_)
	{
		// Asked for only now, with this refresh's picture already handed over: the graphics chip
		// works on it while that picture is up, and it is needed two refreshes from here.
		int64_t started = now_ns();
		flow_.Estimate(older, newer);
		flow_current_ = true;
		static const bool timing = st_enabled();
		if(timing && ++estimates_ % 90 == 0)
		{
			glFinish();
			estimate_ms_ = static_cast<double>(now_ns() - started) / 1000000.0;
		}
	}
}

void SmoothVideo::DumpPicture(GLuint texture, int set, const char *name)
{
	static std::vector<uint8_t> pixels;
	pixels.resize(static_cast<size_t>(width_) * height_ * 4);
	if(!copy_framebuffer_)
		glGenFramebuffers(1, &copy_framebuffer_);
	glBindFramebuffer(GL_FRAMEBUFFER, copy_framebuffer_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
	glReadPixels(0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	char path[128];
	snprintf(path, sizeof(path), "/data/user/0/app.farsofa.vr/files/dump_%d_%s.raw", set, name);
	FILE *file = fopen(path, "wb");
	if(!file)
	{
		LOGE("Could not save %s", path);
		return;
	}
	fwrite(pixels.data(), 1, pixels.size(), file);
	fclose(file);
	LOGI("Saved %s (%dx%d)", path, width_, height_);
}

void SmoothVideo::Report()
{
	int measured = reals_ + repeats_;
	LOGI("10 s: level %d, %d real, %d repeated, %d predicted, %d cuts, source %.1f fps, moving %s, compare %.2f ms",
			level_, reals_, repeats_, predicted_, cuts_, 1000000000.0 / std::min(std::max(period_ns_, PERIOD_MIN_NS), PERIOD_MAX_NS),
			moving_ ? "yes" : "no", measured > 0 ? measure_ms_ / measured : 0.0);
	reals_ = repeats_ = predicted_ = cuts_ = 0;
	measure_ms_ = 0.0;
	report_time_ns_ = now_ns();
}
