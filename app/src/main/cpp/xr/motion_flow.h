// SPDX-License-Identifier: AGPL-3.0-only

#ifndef FARSOFA_MOTION_FLOW_H
#define FARSOFA_MOTION_FLOW_H

#include <GLES3/gl3.h>

/**
 * Finds how the picture moved between two frames and draws frames in between, or ahead of, them.
 *
 * The headset's own frame prediction follows motion of up to a few dozen pixels and gives up beyond
 * that, which is most of what a camera turn in a game at 30 frames a second is. This does the work
 * in shaders: both frames are shrunk to a ladder of small brightness pictures, the motion is found
 * on the smallest one first and refined on each larger one, and the full picture is then moved
 * along the motion found.
 *
 * Everything here belongs to the thread that owns the GL context.
 */
class MotionFlow
{
	public:
		/** Returns false, with nothing left behind, when this device cannot do it. */
		bool Create(int width, int height);
		void Destroy();

		/** Works out the motion from [older] to [newer]. Once per pair. */
		void Estimate(GLuint older, GLuint newer);

		/**
		 * Draws into [target]. With [between], the picture [amount] of the way from the older frame
		 * to the newer (0..1). Without, the newer frame carried [amount] of a step further on.
		 */
		void Draw(GLuint target, GLuint older, GLuint newer, float amount, bool between);

		/**
		 * How badly the two frames still disagree under the motion found, once the graphics chip has
		 * had time to finish Estimate (ask a refresh or more later). Around 0.15 for ordinary play;
		 * well above it when no motion explains the change at all: a fade, a menu sliding over.
		 */
		float Trouble();
		/** The same measure with no motion applied at all: how much the pair differs to begin with. */
		float Unmoved() const { return unmoved_; }
		/**
		 * True when the motion found does not explain how the two frames differ: they fit badly, and
		 * hardly better than if nothing had moved. Fine twigs or grass in motion fit badly too, but
		 * far better than unmoved, and are not this.
		 */
		bool Unexplained()
		{
			float trouble = Trouble();
			// The whole screen growing lighter or darker is a fade or a menu coming up over the game;
			// a camera turning leaves the brightness of the whole about where it was.
			return (trouble > 0.26f && trouble > 0.7f * unmoved_) || brighter_ > 0.02f || brighter_ < -0.02f;
		}
		float Brighter() const { return brighter_; }

	private:
		static const int LEVELS = 5; // 1/4, 1/8, 1/16, 1/32 and 1/64 of the picture
		void Pass(GLuint program, GLuint target, int width, int height);
		GLuint Run(GLuint program, int level, GLuint source);
		void UseMatch(int level, float taps);
		GLuint Match(int level, GLuint previous, GLuint target, int radius, float step);

		int width_ = 0;
		int height_ = 0;
		int level_width_[LEVELS] = {0};
		int level_height_[LEVELS] = {0};
		GLuint luma_[2][LEVELS] = {{0}};
		// Motion in red and green (as a fraction of the picture), how well it fits in blue, and in
		// alpha how much there is at that spot to tell motion by. Two per size, drawn into in turn.
		GLuint flow_[2][LEVELS] = {{0}};
		GLuint final_ = 0;
		GLuint trouble_texture_ = 0;
		float trouble_ = 0.0f;
		float unmoved_ = 0.0f;
		float brighter_ = 0.0f;
		bool trouble_read_ = true;
		GLuint framebuffer_ = 0;
		GLuint vertex_array_ = 0;
		GLuint shrink_first_ = 0;
		GLuint shrink_ = 0;
		GLuint match_ = 0;
		GLuint spread_ = 0;
		GLuint fill_ = 0;
		GLuint median_ = 0;
		GLuint cost_ = 0;
		GLuint warp_ = 0;
};

#endif
