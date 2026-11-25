// SPDX-License-Identifier: AGPL-3.0-only

#include "motion_flow.h"

#include <android/log.h>

#include <initializer_list>
#include <string>
#include <vector>

#define LOG_TAG "FarsofaSmooth"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

const char *const VERTEX = R"(#version 300 es
out vec2 vUv;
void main()
{
	vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
	vUv = corner;
	gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
)";

// The full picture to brightness at a quarter of the size: four samples, each the mean of four pixels.
const char *const SHRINK_FIRST = R"(#version 300 es
precision highp float;
uniform sampler2D uSource;
uniform vec2 uPixel;
in vec2 vUv;
out vec4 color;
void main()
{
	float sum = 0.0;
	for(int j = 0; j < 2; j++)
		for(int i = 0; i < 2; i++)
		{
			vec3 c = texture(uSource, vUv + (vec2(float(i), float(j)) - 0.5) * 2.0 * uPixel).rgb;
			sum += dot(sqrt(c), vec3(0.299, 0.587, 0.114));
		}
	color = vec4(sum / 4.0, 0.0, 0.0, 1.0);
}
)";

const char *const SHRINK = R"(#version 300 es
precision highp float;
uniform sampler2D uSource;
in vec2 vUv;
out vec4 color;
void main()
{
	color = vec4(texture(uSource, vUv).r, 0.0, 0.0, 1.0);
}
)";

// For each spot of the newer picture: where it was in the older one. Starts from the answer found
// on the smaller picture and tries the places around it, keeping the one where a small patch
// matches best. The motion is kept as a fraction of the picture, so every size speaks the same unit.
const char *const MATCH_COMMON = R"(#version 300 es
precision highp float;
uniform sampler2D uOlder;
uniform sampler2D uNewer;
uniform vec2 uTexel;
uniform float uTaps;
in vec2 vUv;
out vec4 color;
float newer[9];
void readNewer()
{
	for(int j = 0; j < 3; j++)
		for(int i = 0; i < 3; i++)
			newer[j * 3 + i] = texture(uNewer, vUv + vec2(float(i - 1), float(j - 1)) * uTexel * uTaps).r;
}
float costOf(vec2 motion)
{
	float cost = 0.0;
	for(int j = 0; j < 3; j++)
		for(int i = 0; i < 3; i++)
			cost += abs(newer[j * 3 + i] - texture(uOlder, vUv + vec2(float(i - 1), float(j - 1)) * uTexel * uTaps - motion).r);
	return cost;
}
)";

const char *const MATCH_BODY = R"(
uniform sampler2D uPrevious;
uniform float uStep;
uniform int uRadius;
uniform float uHasPrevious;
uniform float uBias;
void main()
{
	readNewer();
	vec2 start = uHasPrevious > 0.5 ? texture(uPrevious, vUv).rg : vec2(0.0);
	float best = 1.0e9;
	vec2 found = start;
	for(int dy = -uRadius; dy <= uRadius; dy++)
		for(int dx = -uRadius; dx <= uRadius; dx++)
		{
			vec2 shift = vec2(float(dx), float(dy));
			vec2 candidate = start + shift * uStep * uTexel;
			// Between near equals, the one that changes the least: a patch of sky or road fits
			// almost anywhere, and left to itself picks a different anywhere each time.
			float cost = costOf(candidate) + uBias * length(shift);
			if(cost < best)
			{
				best = cost;
				found = candidate;
			}
		}
	// How much there is here to tell motion by: nothing, on a flat patch.
	float contrast = 0.0;
	for(int i = 0; i < 9; i++)
		contrast += abs(newer[i] - newer[4]);
	color = vec4(found, best, smoothstep(0.004, 0.02, contrast / 8.0));
}
)";

// Each spot may take the motion found three spots away on any side, if it fits clearly better:
// a right answer spreads into the wrong ones next to it.
const char *const SPREAD_BODY = R"(
uniform sampler2D uFlow;
uniform float uBias;
void main()
{
	readNewer();
	vec4 own = texture(uFlow, vUv);
	float best = costOf(own.rg);
	vec2 found = own.rg;
	for(int j = -1; j <= 1; j++)
		for(int i = -1; i <= 1; i++)
		{
			if(i == 0 && j == 0)
				continue;
			vec2 candidate = texture(uFlow, vUv + vec2(float(i), float(j)) * 3.0 * uTexel).rg;
			float cost = costOf(candidate) + uBias;
			if(cost < best)
			{
				best = cost;
				found = candidate;
			}
		}
	color = vec4(found, best, own.a);
}
)";

// Spots with nothing to tell motion by take it from the ones around that have.
const char *const FILL = R"(#version 300 es
precision highp float;
uniform sampler2D uFlow;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 color;
void main()
{
	vec4 own = texture(uFlow, vUv);
	vec2 sum = vec2(0.0);
	float weight = 0.0;
	for(int j = -1; j <= 1; j++)
		for(int i = -1; i <= 1; i++)
		{
			vec4 beside = texture(uFlow, vUv + vec2(float(i), float(j)) * uTexel);
			sum += beside.rg * beside.a;
			weight += beside.a;
		}
	weight /= 9.0;
	sum /= 9.0;
	float keep = weight > 0.001 ? own.a : 1.0;
	color = vec4(mix(sum / max(weight, 0.0001), own.rg, keep), own.b, max(own.a, min(weight * 0.9, 1.0)));
}
)";

// Tidies the motion found: each spot takes the middle value of the nine around it. Unlike an average
// this throws away wrong answers without inventing, at the edge between two things that move
// differently, a motion that belongs to neither.
const char *const MEDIAN = R"(#version 300 es
precision highp float;
uniform sampler2D uFlow;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 color;
#define s2(a, b) t = a; a = min(t, b); b = max(t, b);
#define mn3(a, b, c) s2(a, b); s2(a, c);
#define mx3(a, b, c) s2(b, c); s2(a, c);
#define mnmx3(a, b, c) mx3(a, b, c); s2(a, b);
#define mnmx4(a, b, c, d) s2(a, b); s2(c, d); s2(a, c); s2(b, d);
#define mnmx5(a, b, c, d, e) s2(a, b); s2(c, d); mn3(a, c, e); mx3(b, d, e);
#define mnmx6(a, b, c, d, e, f) s2(a, d); s2(b, e); s2(c, f); mn3(a, b, c); mx3(d, e, f);
void main()
{
	vec2 v[9];
	for(int j = 0; j < 3; j++)
		for(int i = 0; i < 3; i++)
			v[j * 3 + i] = texture(uFlow, vUv + vec2(float(i - 1), float(j - 1)) * uTexel).rg;
	vec2 t;
	mnmx6(v[0], v[1], v[2], v[3], v[4], v[5]);
	mnmx5(v[1], v[2], v[3], v[4], v[6]);
	mnmx4(v[2], v[3], v[4], v[7]);
	mnmx3(v[3], v[4], v[8]);
	vec4 own = texture(uFlow, vUv);
	color = vec4(v[4], own.b, own.a);
}
)";

// How well the motion fits, and how well no motion at all would, shrunk to a handful of numbers
// that can be read back.
const char *const COST_BODY = R"(
uniform sampler2D uFlow;
void main()
{
	readNewer();
	color = vec4(clamp(texture(uFlow, vUv).b * 0.5, 0.0, 1.0), clamp(costOf(vec2(0.0)) * 0.5, 0.0, 1.0),
			clamp(0.5 + newer[4] - texture(uOlder, vUv).r, 0.0, 1.0), 1.0);
}
)";

// The picture itself, moved along the motion. In between two frames, each is carried towards the
// moment wanted and the two are mixed.
//
// The motion is known for each spot of the newer frame. At the moment in between, what was at a
// spot has only come part of the way, so the motion to use there is that of the spot it is heading
// for; found by stepping towards it a couple of times. Without this everything that moves drags a
// ghost of itself along its edges, as wide as half its motion.
//
// Then each of the two frames is asked whether what it shows at its end of the motion is the same
// thing: where something came out from behind something else, one of them shows the thing in front,
// and only the other is used. What did not change between the two frames at all (the crosshair,
// the map, text) is left exactly where it is.
const char *const WARP = R"(#version 300 es
precision highp float;
uniform sampler2D uOlder;
uniform sampler2D uNewer;
uniform sampler2D uFlow;
uniform float uAmount;
uniform float uBetween;
uniform vec2 uSize;
in vec2 vUv;
out vec4 color;
float inside(vec2 p)
{
	return step(0.0, p.x) * step(p.x, 1.0) * step(0.0, p.y) * step(p.y, 1.0);
}
void main()
{
	vec3 stillOlder = texture(uOlder, vUv).rgb;
	vec3 stillNewer = texture(uNewer, vUv).rgb;
	// Compared as the eye sees them, not as light: otherwise dark scenes never count as different.
	float change = distance(sqrt(stillOlder), sqrt(stillNewer));
	if(change < 0.01)
	{
		color = vec4(stillNewer, 1.0);
		return;
	}
	vec3 result;
	if(uBetween > 0.5)
	{
		float ahead = 1.0 - uAmount;
		vec2 flow = texture(uFlow, vUv).rg;
		flow = texture(uFlow, vUv + ahead * flow).rg;
		flow = texture(uFlow, vUv + ahead * flow).rg;
		vec2 inNewer = vUv + ahead * flow;
		vec2 inOlder = vUv - uAmount * flow;
		vec3 a = texture(uOlder, inOlder).rgb;
		vec3 b = texture(uNewer, inNewer).rgb;
		float offNewer = length((texture(uFlow, inNewer).rg - flow) * uSize);
		vec2 back = texture(uFlow, inOlder + flow).rg;
		back = texture(uFlow, inOlder + back).rg;
		float offOlder = length((back - flow) * uSize);
		float trustOlder = (1.0 - smoothstep(4.0, 14.0, offOlder)) * inside(inOlder);
		float trustNewer = (1.0 - smoothstep(4.0, 14.0, offNewer)) * inside(inNewer);
		// Something small that stays put on the screen (the crosshair, a marker) is too small for
		// the motion to know about, and what moves behind it would fetch it from where it sits in
		// each frame: one crosshair becomes three. So a sample is not believed when the spot it comes
		// from did not change between the frames, the other frame says something else, and it does
		// not look like what is here either.
		vec3 a2 = sqrt(a);
		vec3 b2 = sqrt(b);
		vec3 here = sqrt(0.5 * (stillOlder + stillNewer));
		float disagree = smoothstep(0.03, 0.10, distance(a2, b2));
		float fixedOlder = 1.0 - smoothstep(0.01, 0.04, distance(a2, sqrt(texture(uNewer, inOlder).rgb)));
		float fixedNewer = 1.0 - smoothstep(0.01, 0.04, distance(b2, sqrt(texture(uOlder, inNewer).rgb)));
		trustOlder *= 1.0 - fixedOlder * disagree * smoothstep(0.03, 0.10, distance(a2, here));
		trustNewer *= 1.0 - fixedNewer * disagree * smoothstep(0.03, 0.10, distance(b2, here));
		float weightOlder = (1.0 - uAmount) * trustOlder + 0.001;
		float weightNewer = uAmount * trustNewer + 0.001;
		result = (weightOlder * a + weightNewer * b) / (weightOlder + weightNewer);
	}
	else
	{
		// Ahead of the newer frame: what is seen here left from a spot further back, and it is that
		// spot's motion that says how far back.
		vec2 flow = texture(uFlow, vUv).rg;
		flow = texture(uFlow, vUv - uAmount * flow).rg;
		flow = texture(uFlow, vUv - uAmount * flow).rg;
		vec2 from = vUv - uAmount * flow;
		result = mix(stillNewer, texture(uNewer, from).rgb, inside(from));
	}
	float still = 1.0 - smoothstep(0.01, 0.04, change);
	color = vec4(mix(result, stillNewer, still), 1.0);
}
)";

GLuint compile(GLenum type, const char *source)
{
	GLuint shader = glCreateShader(type);
	glShaderSource(shader, 1, &source, nullptr);
	glCompileShader(shader);
	GLint ok = GL_FALSE;
	glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
	if(!ok)
	{
		char log[512] = {0};
		glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
		LOGE("Motion shader does not compile: %s", log);
		glDeleteShader(shader);
		return 0;
	}
	return shader;
}

GLuint link(const char *fragment_source, const char *second_part = nullptr)
{
	std::string whole = fragment_source;
	if(second_part)
		whole += second_part;
	GLuint vertex = compile(GL_VERTEX_SHADER, VERTEX);
	GLuint fragment = compile(GL_FRAGMENT_SHADER, whole.c_str());
	GLuint program = 0;
	if(vertex && fragment)
	{
		program = glCreateProgram();
		glAttachShader(program, vertex);
		glAttachShader(program, fragment);
		glLinkProgram(program);
		GLint ok = GL_FALSE;
		glGetProgramiv(program, GL_LINK_STATUS, &ok);
		if(!ok)
		{
			LOGE("Motion shader does not link");
			glDeleteProgram(program);
			program = 0;
		}
	}
	if(vertex)
		glDeleteShader(vertex);
	if(fragment)
		glDeleteShader(fragment);
	return program;
}

GLuint make_texture(GLenum format, int width, int height)
{
	GLuint texture = 0;
	glGenTextures(1, &texture);
	glBindTexture(GL_TEXTURE_2D, texture);
	glTexStorage2D(GL_TEXTURE_2D, 1, format, width, height);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	glBindTexture(GL_TEXTURE_2D, 0);
	return texture;
}

void bind(int unit, GLuint texture)
{
	glActiveTexture(GL_TEXTURE0 + unit);
	glBindTexture(GL_TEXTURE_2D, texture);
}

} // namespace

bool MotionFlow::Create(int width, int height)
{
	width_ = width;
	height_ = height;
	shrink_first_ = link(SHRINK_FIRST);
	shrink_ = link(SHRINK);
	match_ = link(MATCH_COMMON, MATCH_BODY);
	spread_ = link(MATCH_COMMON, SPREAD_BODY);
	fill_ = link(FILL);
	median_ = link(MEDIAN);
	cost_ = link(MATCH_COMMON, COST_BODY);
	warp_ = link(WARP);
	if(!shrink_first_ || !shrink_ || !match_ || !spread_ || !fill_ || !median_ || !cost_ || !warp_)
	{
		Destroy();
		return false;
	}
	glGenFramebuffers(1, &framebuffer_);
	glGenVertexArrays(1, &vertex_array_);
	for(int level = 0; level < LEVELS; level++)
	{
		int divisor = 4 << level;
		level_width_[level] = (width + divisor - 1) / divisor;
		level_height_[level] = (height + divisor - 1) / divisor;
		for(int i = 0; i < 2; i++)
		{
			luma_[i][level] = make_texture(GL_R8, level_width_[level], level_height_[level]);
			flow_[i][level] = make_texture(GL_RGBA16F, level_width_[level], level_height_[level]);
		}
	}
	trouble_texture_ = make_texture(GL_RGBA8, level_width_[LEVELS - 1], level_height_[LEVELS - 1]);

	// Motion is kept in half floats; drawing into those is not promised everywhere.
	glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, flow_[0][0], 0);
	bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	if(!complete)
	{
		LOGE("Motion cannot be kept on this device (no drawing into half floats)");
		Destroy();
		return false;
	}
	LOGI("Motion flow ready: %dx%d down to %dx%d", level_width_[0], level_height_[0], level_width_[LEVELS - 1], level_height_[LEVELS - 1]);
	return true;
}

void MotionFlow::Destroy()
{
	for(int level = 0; level < LEVELS; level++)
		for(int i = 0; i < 2; i++)
		{
			glDeleteTextures(1, &luma_[i][level]);
			glDeleteTextures(1, &flow_[i][level]);
			luma_[i][level] = flow_[i][level] = 0;
		}
	glDeleteTextures(1, &trouble_texture_);
	glDeleteFramebuffers(1, &framebuffer_);
	glDeleteVertexArrays(1, &vertex_array_);
	for(GLuint program : {shrink_first_, shrink_, match_, spread_, fill_, median_, cost_, warp_})
		if(program)
			glDeleteProgram(program);
	trouble_texture_ = final_ = framebuffer_ = vertex_array_ = 0;
	shrink_first_ = shrink_ = match_ = spread_ = fill_ = median_ = cost_ = warp_ = 0;
}

// Draws one full-screen pass of [program], already in use with its inputs bound, into [target].
void MotionFlow::Pass(GLuint program, GLuint target, int width, int height)
{
	(void) program;
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
	glViewport(0, 0, width, height);
	glDrawArrays(GL_TRIANGLES, 0, 3);
}

// One pass of [program] over the motion at [level]: reads [source], draws into the other texture of
// that size and returns it.
GLuint MotionFlow::Run(GLuint program, int level, GLuint source)
{
	GLuint target = source == flow_[0][level] ? flow_[1][level] : flow_[0][level];
	glUseProgram(program);
	glUniform1i(glGetUniformLocation(program, "uFlow"), 2);
	glUniform2f(glGetUniformLocation(program, "uTexel"), 1.0f / static_cast<float>(level_width_[level]), 1.0f / static_cast<float>(level_height_[level]));
	bind(2, source);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
	glViewport(0, 0, level_width_[level], level_height_[level]);
	glDrawArrays(GL_TRIANGLES, 0, 3);
	return target;
}

// Puts the two brightness pictures of [level] where the matching shaders look for them.
void MotionFlow::UseMatch(int level, float taps)
{
	for(GLuint program : {match_, spread_})
	{
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "uOlder"), 0);
		glUniform1i(glGetUniformLocation(program, "uNewer"), 1);
		glUniform1f(glGetUniformLocation(program, "uTaps"), taps);
		glUniform1f(glGetUniformLocation(program, "uBias"), 0.05f);
	}
	bind(0, luma_[0][level]);
	bind(1, luma_[1][level]);
}

GLuint MotionFlow::Match(int level, GLuint previous, GLuint target, int radius, float step)
{
	glUseProgram(match_);
	glUniform1i(glGetUniformLocation(match_, "uPrevious"), 2);
	glUniform2f(glGetUniformLocation(match_, "uTexel"), 1.0f / static_cast<float>(level_width_[level]), 1.0f / static_cast<float>(level_height_[level]));
	glUniform1f(glGetUniformLocation(match_, "uStep"), step);
	glUniform1i(glGetUniformLocation(match_, "uRadius"), radius);
	glUniform1f(glGetUniformLocation(match_, "uHasPrevious"), previous ? 1.0f : 0.0f);
	bind(2, previous ? previous : flow_[1][level]);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
	glViewport(0, 0, level_width_[level], level_height_[level]);
	glDrawArrays(GL_TRIANGLES, 0, 3);
	return target;
}

void MotionFlow::Estimate(GLuint older, GLuint newer)
{
	glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
	const GLenum one = GL_COLOR_ATTACHMENT0;
	glDrawBuffers(1, &one);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glBindVertexArray(vertex_array_);

	const GLuint frames[2] = {older, newer};
	for(int frame = 0; frame < 2; frame++)
	{
		glUseProgram(shrink_first_);
		glUniform1i(glGetUniformLocation(shrink_first_, "uSource"), 0);
		glUniform2f(glGetUniformLocation(shrink_first_, "uPixel"), 1.0f / static_cast<float>(width_), 1.0f / static_cast<float>(height_));
		bind(0, frames[frame]);
		Pass(shrink_first_, luma_[frame][0], level_width_[0], level_height_[0]);
		glUseProgram(shrink_);
		glUniform1i(glGetUniformLocation(shrink_, "uSource"), 0);
		for(int level = 1; level < LEVELS; level++)
		{
			bind(0, luma_[frame][level - 1]);
			Pass(shrink_, luma_[frame][level], level_width_[level], level_height_[level]);
		}
	}

	// The smallest picture: four steps either way cover an eighth of the screen.
	int top = LEVELS - 1;
	UseMatch(top, 1.0f);
	GLuint flow = Match(top, 0, flow_[0][top], 4, 1.0f);
	for(int i = 0; i < 4; i++)
		flow = Run(fill_, top, flow);
	flow = Run(median_, top, flow);
	flow = Run(median_, top, flow);

	// Each larger one starts from the answer of the one before.
	for(int level = top - 1; level >= 1; level--)
	{
		UseMatch(level, 2.5f);
		flow = Match(level, flow, flow_[0][level], 2, 1.0f);
		flow = Run(spread_, level, flow);
		flow = Run(fill_, level, flow);
		flow = Run(fill_, level, flow);
		flow = Run(median_, level, flow);
		flow = Run(median_, level, flow);
	}

	// The largest has four times the spots of the one before, so only the nearest places are tried
	// there, and then half a step: two pixels of the full picture.
	UseMatch(0, 2.5f);
	flow = Match(0, flow, flow_[0][0], 1, 1.0f);
	flow = Run(median_, 0, flow);
	flow = Match(0, flow, flow == flow_[0][0] ? flow_[1][0] : flow_[0][0], 1, 0.5f);
	flow = Run(median_, 0, flow);
	final_ = flow;

	glUseProgram(cost_);
	glUniform1i(glGetUniformLocation(cost_, "uOlder"), 0);
	glUniform1i(glGetUniformLocation(cost_, "uNewer"), 1);
	glUniform1f(glGetUniformLocation(cost_, "uTaps"), 2.5f);
	glUniform2f(glGetUniformLocation(cost_, "uTexel"), 1.0f / static_cast<float>(level_width_[0]), 1.0f / static_cast<float>(level_height_[0]));
	glUniform1i(glGetUniformLocation(cost_, "uFlow"), 2);
	bind(2, final_);
	Pass(cost_, trouble_texture_, level_width_[top], level_height_[top]);
	trouble_read_ = false;

	bind(2, 0);
	bind(1, 0);
	bind(0, 0);
	glUseProgram(0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

float MotionFlow::Trouble()
{
	if(trouble_read_)
		return trouble_;
	int top = LEVELS - 1;
	std::vector<uint8_t> pixels(static_cast<size_t>(level_width_[top]) * level_height_[top] * 4);
	glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, trouble_texture_, 0);
	glReadPixels(0, 0, level_width_[top], level_height_[top], GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	long sum = 0;
	long sum_unmoved = 0;
	long sum_brighter = 0;
	for(size_t i = 0; i < pixels.size(); i += 4)
	{
		sum += pixels[i];
		sum_unmoved += pixels[i + 1];
		sum_brighter += static_cast<long>(pixels[i + 2]) - 128;
	}
	brighter_ = static_cast<float>(sum_brighter) / static_cast<float>(pixels.size() / 4) / 255.0f;
	trouble_ = static_cast<float>(sum) / static_cast<float>(pixels.size() / 4) / 255.0f * 2.0f;
	unmoved_ = static_cast<float>(sum_unmoved) / static_cast<float>(pixels.size() / 4) / 255.0f * 2.0f;
	trouble_read_ = true;
	return trouble_;
}

void MotionFlow::Draw(GLuint target, GLuint older, GLuint newer, float amount, bool between)
{
	glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
	const GLenum one = GL_COLOR_ATTACHMENT0;
	glDrawBuffers(1, &one);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glBindVertexArray(vertex_array_);
	glUseProgram(warp_);
	glUniform1i(glGetUniformLocation(warp_, "uOlder"), 0);
	glUniform1i(glGetUniformLocation(warp_, "uNewer"), 1);
	glUniform1i(glGetUniformLocation(warp_, "uFlow"), 2);
	glUniform1f(glGetUniformLocation(warp_, "uAmount"), amount);
	glUniform1f(glGetUniformLocation(warp_, "uBetween"), between ? 1.0f : 0.0f);
	glUniform2f(glGetUniformLocation(warp_, "uSize"), static_cast<float>(width_), static_cast<float>(height_));
	bind(0, older);
	bind(1, newer);
	bind(2, final_);
	Pass(warp_, target, width_, height_);
	bind(2, 0);
	bind(1, 0);
	bind(0, 0);
	glUseProgram(0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
}
