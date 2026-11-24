// SPDX-License-Identifier: AGPL-3.0-only

#ifndef FARSOFA_SMOOTH_VIDEO_H
#define FARSOFA_SMOOTH_VIDEO_H

#include <jni.h>

#include <GLES3/gl3.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <EGL/egl.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <cstdint>
#include <vector>

/**
 * The video drawn by the app instead of handed straight to the compositor, so that frames can be
 * made up for the screen refreshes that have no new one.
 *
 * The decoder fills a texture; each refresh, this draws that texture into a swapchain of its own.
 * A game at 30 frames a second reaches the headset as 60, every picture sent twice, and the screen
 * refreshes 120 times: three refreshes out of four have nothing new. For those, the headset's
 * graphics chip is asked to carry the last two real pictures forward in time
 * (GL_QCOM_frame_extrapolation). Nothing is held back to do it, so the controls feel the same; the
 * price is that a wrong guess shows as a wobble.
 *
 * Everything here belongs to the thread that owns the session's GL context.
 */
class SmoothVideo
{
	public:
		/** Returns false, with nothing left behind, when this device cannot do it. */
		bool Create(JNIEnv *env, XrSession session, int width, int height);
		void Destroy(JNIEnv *env);

		/** Draws whatever this refresh should show. Call once per frame, before the layers are submitted. */
		void Render(JNIEnv *env);

		XrSwapchain Swapchain() const { return swapchain_; }
		/** The Surface the decoder writes to (a local reference owned by this object as a global one). */
		jobject Surface() const { return surface_; }

	private:
		typedef void (*ExtrapolateFn)(GLuint older, GLuint newer, GLuint output, GLfloat scale);

		bool CreateSwapchain(XrSession session);
		bool CreateSurface(JNIEnv *env);
		bool CreatePrograms();
		void CreateHistory();
		GLuint Acquire();
		void Release();
		void DrawVideo(GLuint target, bool write_history);
		float MeasureChange();
		bool IsRepeat(float change);
		void Report();

		int width_ = 0;
		int height_ = 0;
		XrSwapchain swapchain_ = XR_NULL_HANDLE;
		std::vector<GLuint> images_;
		GLenum format_ = 0;

		jobject surface_texture_ = nullptr; // global refs
		jobject surface_ = nullptr;
		jmethodID update_tex_image_ = nullptr;
		jmethodID get_timestamp_ = nullptr;
		jmethodID get_transform_ = nullptr;
		jmethodID release_ = nullptr;
		jfloatArray transform_array_ = nullptr; // global ref
		float transform_[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		int64_t last_timestamp_ = -1;

		GLuint external_texture_ = 0;
		GLuint framebuffer_ = 0;
		GLuint vertex_array_ = 0;
		GLuint video_program_ = 0;
		GLint video_transform_ = -1;
		GLint video_linearise_ = -1;
		GLuint grid_program_ = 0;
		GLint grid_transform_ = -1;
		GLuint grid_texture_ = 0;
		GLuint grid_framebuffer_ = 0;
		std::vector<uint8_t> grid_now_;
		std::vector<uint8_t> grid_before_;
		bool grid_known_ = false;

		// Frame prediction.
		ExtrapolateFn extrapolate_ = nullptr;
		bool extrapolate_checked_ = false;
		GLuint history_[2] = {0, 0};
		int history_newest_ = -1;
		int history_count_ = 0;
		bool shown_predicted_ = false;
		bool drawn_once_ = false;

		float recent_change_[3] = {0, 0, 0};
		int recent_index_ = 0;
		int repeats_in_a_row_ = 0;
		float motion_level_ = 0.0f;
		bool moving_ = false;
		bool pair_adjacent_ = false;
		int64_t real_time_ns_ = 0;
		double period_ns_ = 16666667.0;

		// Counted between two lines in the log.
		int64_t report_time_ns_ = 0;
		int reals_ = 0;
		int repeats_ = 0;
		int predicted_ = 0;
		int cuts_ = 0;
		double measure_ms_ = 0.0;
};

#endif
