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

#include "motion_flow.h"

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

		/**
		 * How frames are made up. 1: guessed ahead, only in the parts of the picture that move.
		 * 2: guessed ahead over the whole picture. 3: each real picture is held back until the next
		 * one arrives and the ones shown in between are built from both, so nothing is a guess; it
		 * looks smoother, above all in motion that keeps changing, and costs one picture of delay.
		 */
		void SetLevel(int level) { level_ = level; }
		/** Pictures shown in the last full second: real ones, and ones made up. */
		int RealPerSecond() const { return real_per_second_; }
		int PredictedPerSecond() const { return predicted_per_second_; }

		XrSwapchain Swapchain() const { return swapchain_; }
		/** The Surface the decoder writes to (a local reference owned by this object as a global one). */
		jobject Surface() const { return surface_; }

	private:
		typedef void (*ExtrapolateFn)(GLuint older, GLuint newer, GLuint output, GLfloat scale);

		bool CreateSwapchain(XrSession session);
		bool CreateSurface(JNIEnv *env);
		bool CreatePrograms();
		void CreateHistory();
		bool CreateMask();
		void UpdateMask();
		void Blend(GLuint target);
		bool PredictionTakesEffect(float scale);
		bool BetweenWorks();
		void RenderBetween(bool real, int64_t now);
		void Copy(GLuint source, GLuint target);
		float BlockDifference(GLuint a, GLuint b);
		void MakeGuides();
		void SelfTest(const char *when);
		int frames_drawn_ = 0;
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

		// Pictures built between two real ones (level 3).
		GLuint copy_framebuffer_ = 0;
		bool between_checked_ = false;
		bool between_works_ = false;
		int between_tries_ = 0;
		bool between_shows_newest_ = false;
		// The chip only carries a picture forwards from a pair. To carry the older real picture
		// towards the newer one, it is given a made-up picture of what came before the older one
		// (and the mirror of that for the newer): the motion it then finds is the real one.
		GLuint guides_[2] = {0, 0};

		// The motion worked out here, for level 3: see motion_flow.h.
		MotionFlow flow_;
		bool flow_ready_ = false;
		bool flow_current_ = false;
		int between_probe_ = 0;
		int between_step_shown_ = -1;
		// When the newest real picture is taken to have arrived: its real arrival with the jitter of
		// the network and the decoder smoothed out, so that pictures are shown evenly spaced.
		int64_t steady_time_ns_ = 0;
		int64_t steady_before_ns_ = 0;
		int ahead_ = 0;
		int between_refreshes_ = 0;
		int between_nudges_ = 0;
		int troubled_ = 0;
		void DumpPicture(GLuint texture, int set, const char *name);
		int dumps_ = 0;
		int64_t dump_time_ns_ = 0;
		int between_holds_[6] = {0};
		double estimate_ms_ = 0.0;
		int estimates_ = 0;
		bool guides_current_ = false;

		// Where the picture is moving. A prediction covers the whole picture, and in the parts that
		// stand still (the map, the crosshair, text, fine static detail) it can only do harm, so the
		// predicted picture is used where something moved between the last two real ones and the
		// real picture everywhere else.
		GLuint predicted_texture_ = 0;
		GLuint mask_[2] = {0, 0};
		GLuint mask_framebuffer_ = 0;
		GLuint diff_program_ = 0;
		GLuint dilate_program_ = 0;
		GLuint blend_program_ = 0;
		int mask_width_ = 0;
		int mask_height_ = 0;
		bool mask_ready_ = false;
		int level_ = 1;
		int64_t second_start_ns_ = 0;
		int real_this_second_ = 0;
		int predicted_this_second_ = 0;
		int real_per_second_ = 0;
		int predicted_per_second_ = 0;
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
