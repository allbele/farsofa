// SPDX-License-Identifier: AGPL-3.0-only
//
// Immersive screen: an OpenXR session whose only content is a video surface placed in front of the
// user. The decoder writes straight into a compositor-owned Android surface swapchain, so no frame
// is copied or drawn by the app. Optional passthrough shows the room behind the screen.
//
// Two more surface swapchains carry the control bar and the resize handles. The Kotlin side draws
// them and decides where everything sits; this file only places layers and reports controller input.

#include <jni.h>

#include <EGL/egl.h>
#include <android/log.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "FarsofaXr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define XR_NATIVE(name) Java_app_farsofa_vr_xr_XrNative_##name

namespace {

// Button bits match app.farsofa.vr.input.PadState.
enum PadButton : int
{
	PAD_CROSS = 1 << 0,
	PAD_CIRCLE = 1 << 1,
	PAD_SQUARE = 1 << 2,
	PAD_TRIANGLE = 1 << 3,
	PAD_L1 = 1 << 8,
	PAD_R1 = 1 << 9,
	PAD_L3 = 1 << 10,
	PAD_R3 = 1 << 11,
	// Not a console button: the Touch menu key, which the app keeps for its own control bar.
	TOUCH_MENU = 1 << 16,
};

// Everything the UI thread decides about the scene. Lengths in metres, angles in radians.
struct Scene
{
	float distance = 2.4f;
	float width = 3.2f;
	bool curved = false;
	bool passthrough = true;
	float screen_y = 0.0f; // height of the screen's centre
	float yaw = 0.0f; // the whole scene turned to the left around the viewer
	float pitch = 0.0f; // and then tipped upwards
	bool bar_visible = false;
	float bar_distance = 1.5f;
	float bar_elevation = -0.4f;
	float bar_width = 1.4f;
	float bar_height = 0.35f;
	bool overlay_visible = false;
	float overlay_margin = 0.04f; // extra size of the overlay on each side, as a share of the screen width
	bool pointer = false; // report controller rays instead of leaving them idle
	bool follow = false; // the scene keeps its place around the viewer as they walk
	bool expand_range = true; // stretch limited-range video to the display's full range
};

struct Panel
{
	int width = 0;
	int height = 0;
	XrSwapchain swapchain = XR_NULL_HANDLE;
	jobject surface = nullptr; // global ref
};

struct TouchState
{
	int buttons = 0;
	int l2 = 0;
	int r2 = 0;
	int left_x = 0;
	int left_y = 0;
	int right_x = 0;
	int right_y = 0;

	bool operator==(const TouchState &o) const
	{
		return buttons == o.buttons && l2 == o.l2 && r2 == o.r2 && left_x == o.left_x
			&& left_y == o.left_y && right_x == o.right_x && right_y == o.right_y;
	}
};

struct XrScreen
{
	JavaVM *vm = nullptr;
	jobject activity = nullptr; // global ref
	jobject listener = nullptr; // global ref
	jmethodID on_surfaces_ready = nullptr;
	jmethodID on_touch_state = nullptr;
	jmethodID on_pointer = nullptr;
	jmethodID on_gaze = nullptr;
	jmethodID on_error = nullptr;
	jmethodID on_exit = nullptr;

	int video_width = 1920;
	int video_height = 1080;

	// Changed from the UI thread while the frame loop runs.
	std::mutex scene_mutex;
	Scene scene;
	std::atomic<bool> stop_requested{false};

	std::thread thread;

	// --- owned by the frame thread ---
	JNIEnv *env = nullptr;
	EGLDisplay egl_display = EGL_NO_DISPLAY;
	EGLConfig egl_config = nullptr;
	EGLContext egl_context = EGL_NO_CONTEXT;
	EGLSurface egl_surface = EGL_NO_SURFACE;

	XrInstance instance = XR_NULL_HANDLE;
	XrSystemId system_id = XR_NULL_SYSTEM_ID;
	XrSession session = XR_NULL_HANDLE;
	XrSpace local_space = XR_NULL_HANDLE;
	XrSwapchain swapchain = XR_NULL_HANDLE;
	jobject surface = nullptr; // global ref to the swapchain's android.view.Surface
	Panel bar;
	Panel overlay;
	jfloatArray pointer_values = nullptr; // global ref, reused every frame
	bool pointer_was_reported = false;
	XrResult last_end_frame_error = XR_SUCCESS;

	// Diagnostics, logged every few seconds.
	std::chrono::steady_clock::time_point stats_since = std::chrono::steady_clock::now();
	int stats_frames = 0;
	int stats_not_rendered = 0;
	int64_t stats_period_ns = 0;

	bool has_cylinder = false;
	bool has_refresh_rate = false;
	bool has_color_space = false;
	bool has_passthrough = false;
	bool has_image_layout = false;
	bool has_boundary_visibility = false;
	bool has_color_scale_bias = false;
	PFN_xrRequestBoundaryVisibilityMETA request_boundary_visibility = nullptr;
	int boundary_suppressed = -1; // last request: -1 none yet, 0 shown, 1 suppressed
	XrSpace view_space = XR_NULL_HANDLE;
	XrVector3f origin = {0.0f, 0.0f, 0.0f}; // centre of the scene; the viewer's position when following
	bool origin_set = false;
	XrVector3f frame_origin = {0.0f, 0.0f, 0.0f}; // the centre used for the frame being built (frame thread only)
	XrVector3f head_position = {0.0f, 0.0f, 0.0f}; // last known, guarded by scene_mutex like origin
	bool head_valid = false;
	XrPath touch_profile = XR_NULL_PATH;
	XrPath hand_paths[2] = {XR_NULL_PATH, XR_NULL_PATH}; // right, left
	bool bare_hand[2] = {false, false};
	XrPassthroughFB passthrough = XR_NULL_HANDLE;
	XrPassthroughLayerFB passthrough_layer = XR_NULL_HANDLE;

	bool session_running = false;
	bool session_focused = false;
	bool exit_requested = false;

	XrActionSet action_set = XR_NULL_HANDLE;
	XrAction a = XR_NULL_HANDLE, b = XR_NULL_HANDLE, x = XR_NULL_HANDLE, y = XR_NULL_HANDLE;
	XrAction menu = XR_NULL_HANDLE, left_click = XR_NULL_HANDLE, right_click = XR_NULL_HANDLE;
	XrAction left_trigger = XR_NULL_HANDLE, right_trigger = XR_NULL_HANDLE;
	XrAction left_grip = XR_NULL_HANDLE, right_grip = XR_NULL_HANDLE;
	XrAction left_stick = XR_NULL_HANDLE, right_stick = XR_NULL_HANDLE;
	XrAction left_aim = XR_NULL_HANDLE, right_aim = XR_NULL_HANDLE;
	XrAction left_pinch = XR_NULL_HANDLE, right_pinch = XR_NULL_HANDLE;
	bool has_hand_interaction = false;
	XrSpace left_aim_space = XR_NULL_HANDLE, right_aim_space = XR_NULL_HANDLE;
	TouchState last_touch;

	PFN_xrCreateSwapchainAndroidSurfaceKHR create_surface_swapchain = nullptr;
	PFN_xrGetOpenGLESGraphicsRequirementsKHR get_gles_requirements = nullptr;
	PFN_xrEnumerateDisplayRefreshRatesFB enumerate_refresh_rates = nullptr;
	PFN_xrRequestDisplayRefreshRateFB request_refresh_rate = nullptr;
	PFN_xrGetDisplayRefreshRateFB get_refresh_rate = nullptr;
	PFN_xrSetColorSpaceFB set_color_space = nullptr;
	PFN_xrCreatePassthroughFB create_passthrough = nullptr;
	PFN_xrDestroyPassthroughFB destroy_passthrough = nullptr;
	PFN_xrCreatePassthroughLayerFB create_passthrough_layer = nullptr;
	PFN_xrDestroyPassthroughLayerFB destroy_passthrough_layer = nullptr;
};

bool ok(XrResult result, const char *what)
{
	if(XR_SUCCEEDED(result))
		return true;
	LOGE("%s failed: %d", what, static_cast<int>(result));
	return false;
}

template<typename Fn>
bool load_fn(XrInstance instance, const char *name, Fn *out)
{
	return XR_SUCCEEDED(xrGetInstanceProcAddr(instance, name, reinterpret_cast<PFN_xrVoidFunction *>(out))) && *out;
}

void clear_exception(JNIEnv *env)
{
	if(env->ExceptionCheck())
	{
		env->ExceptionDescribe();
		env->ExceptionClear();
	}
}

void report_error(XrScreen *xr, const char *message)
{
	LOGE("%s", message);
	jstring text = xr->env->NewStringUTF(message);
	xr->env->CallVoidMethod(xr->listener, xr->on_error, text);
	clear_exception(xr->env);
	xr->env->DeleteLocalRef(text);
}

// OpenXR on Android needs an EGL context for the session even though this app never draws.
bool init_egl(XrScreen *xr)
{
	xr->egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if(xr->egl_display == EGL_NO_DISPLAY || !eglInitialize(xr->egl_display, nullptr, nullptr))
		return false;

	const EGLint config_attribs[] = {
		EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
		EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
		EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
		EGL_NONE,
	};
	EGLint config_count = 0;
	if(!eglChooseConfig(xr->egl_display, config_attribs, &xr->egl_config, 1, &config_count) || config_count < 1)
		return false;

	const EGLint context_attribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
	xr->egl_context = eglCreateContext(xr->egl_display, xr->egl_config, EGL_NO_CONTEXT, context_attribs);
	if(xr->egl_context == EGL_NO_CONTEXT)
		return false;

	const EGLint surface_attribs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
	xr->egl_surface = eglCreatePbufferSurface(xr->egl_display, xr->egl_config, surface_attribs);
	if(xr->egl_surface == EGL_NO_SURFACE)
		return false;

	return eglMakeCurrent(xr->egl_display, xr->egl_surface, xr->egl_surface, xr->egl_context);
}

void fini_egl(XrScreen *xr)
{
	if(xr->egl_display == EGL_NO_DISPLAY)
		return;
	eglMakeCurrent(xr->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
	if(xr->egl_surface != EGL_NO_SURFACE)
		eglDestroySurface(xr->egl_display, xr->egl_surface);
	if(xr->egl_context != EGL_NO_CONTEXT)
		eglDestroyContext(xr->egl_display, xr->egl_context);
	eglTerminate(xr->egl_display);
	xr->egl_display = EGL_NO_DISPLAY;
}

bool has_extension(const std::vector<XrExtensionProperties> &available, const char *name)
{
	for(const auto &ext : available)
	{
		if(strcmp(ext.extensionName, name) == 0)
			return true;
	}
	return false;
}

bool create_instance(XrScreen *xr)
{
	PFN_xrInitializeLoaderKHR init_loader = nullptr;
	if(!load_fn(XR_NULL_HANDLE, "xrInitializeLoaderKHR", &init_loader))
		return false;
	XrLoaderInitInfoAndroidKHR loader_info = {XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
	loader_info.applicationVM = xr->vm;
	loader_info.applicationContext = xr->activity;
	if(!ok(init_loader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR *>(&loader_info)), "xrInitializeLoaderKHR"))
		return false;

	uint32_t count = 0;
	if(!ok(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr), "enumerate extensions"))
		return false;
	std::vector<XrExtensionProperties> available(count, {XR_TYPE_EXTENSION_PROPERTIES});
	if(!ok(xrEnumerateInstanceExtensionProperties(nullptr, count, &count, available.data()), "enumerate extensions"))
		return false;

	const char *required[] = {
		XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
		XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
		XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME,
	};
	std::vector<const char *> enabled;
	for(const char *name : required)
	{
		if(!has_extension(available, name))
		{
			LOGE("Missing required OpenXR extension %s", name);
			return false;
		}
		enabled.push_back(name);
	}

	auto enable_optional = [&](const char *name) {
		bool present = has_extension(available, name);
		if(present)
			enabled.push_back(name);
		return present;
	};
	xr->has_cylinder = enable_optional(XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME);
	xr->has_refresh_rate = enable_optional(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
	xr->has_color_space = enable_optional(XR_FB_COLOR_SPACE_EXTENSION_NAME);
	xr->has_passthrough = enable_optional(XR_FB_PASSTHROUGH_EXTENSION_NAME);
	xr->has_image_layout = enable_optional(XR_FB_COMPOSITION_LAYER_IMAGE_LAYOUT_EXTENSION_NAME);
	xr->has_hand_interaction = enable_optional(XR_EXT_HAND_INTERACTION_EXTENSION_NAME);
	xr->has_boundary_visibility = enable_optional(XR_META_BOUNDARY_VISIBILITY_EXTENSION_NAME);
	xr->has_color_scale_bias = enable_optional(XR_KHR_COMPOSITION_LAYER_COLOR_SCALE_BIAS_EXTENSION_NAME);
	if(!xr->has_image_layout)
		LOGE("No image layout extension; the picture will be upside down");

	XrInstanceCreateInfoAndroidKHR android_info = {XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
	android_info.applicationVM = xr->vm;
	android_info.applicationActivity = xr->activity;

	XrInstanceCreateInfo create_info = {XR_TYPE_INSTANCE_CREATE_INFO};
	create_info.next = &android_info;
	strncpy(create_info.applicationInfo.applicationName, "Farsofa", XR_MAX_APPLICATION_NAME_SIZE - 1);
	create_info.applicationInfo.applicationVersion = 1;
	create_info.applicationInfo.apiVersion = XR_API_VERSION_1_0;
	create_info.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
	create_info.enabledExtensionNames = enabled.data();
	if(!ok(xrCreateInstance(&create_info, &xr->instance), "xrCreateInstance"))
		return false;

	if(!load_fn(xr->instance, "xrCreateSwapchainAndroidSurfaceKHR", &xr->create_surface_swapchain)
		|| !load_fn(xr->instance, "xrGetOpenGLESGraphicsRequirementsKHR", &xr->get_gles_requirements))
		return false;

	if(xr->has_refresh_rate)
	{
		xr->has_refresh_rate = load_fn(xr->instance, "xrEnumerateDisplayRefreshRatesFB", &xr->enumerate_refresh_rates)
			&& load_fn(xr->instance, "xrRequestDisplayRefreshRateFB", &xr->request_refresh_rate);
		load_fn(xr->instance, "xrGetDisplayRefreshRateFB", &xr->get_refresh_rate);
	}
	if(xr->has_color_space)
		xr->has_color_space = load_fn(xr->instance, "xrSetColorSpaceFB", &xr->set_color_space);
	if(xr->has_boundary_visibility)
		xr->has_boundary_visibility = load_fn(xr->instance, "xrRequestBoundaryVisibilityMETA", &xr->request_boundary_visibility);
	if(xr->has_passthrough)
	{
		xr->has_passthrough = load_fn(xr->instance, "xrCreatePassthroughFB", &xr->create_passthrough)
			&& load_fn(xr->instance, "xrDestroyPassthroughFB", &xr->destroy_passthrough)
			&& load_fn(xr->instance, "xrCreatePassthroughLayerFB", &xr->create_passthrough_layer)
			&& load_fn(xr->instance, "xrDestroyPassthroughLayerFB", &xr->destroy_passthrough_layer);
	}

	XrInstanceProperties properties = {XR_TYPE_INSTANCE_PROPERTIES};
	if(XR_SUCCEEDED(xrGetInstanceProperties(xr->instance, &properties)))
		LOGI("Runtime %s %u.%u.%u", properties.runtimeName, XR_VERSION_MAJOR(properties.runtimeVersion),
				XR_VERSION_MINOR(properties.runtimeVersion), XR_VERSION_PATCH(properties.runtimeVersion));
	LOGI("Extensions: cylinder %d, refresh rate %d, color space %d, passthrough %d, image layout %d, hands %d, boundary %d, colour range %d",
			xr->has_cylinder, xr->has_refresh_rate, xr->has_color_space, xr->has_passthrough, xr->has_image_layout,
			xr->has_hand_interaction, xr->has_boundary_visibility, xr->has_color_scale_bias);

	XrSystemGetInfo system_info = {XR_TYPE_SYSTEM_GET_INFO};
	system_info.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
	return ok(xrGetSystem(xr->instance, &system_info, &xr->system_id), "xrGetSystem");
}

XrAction create_action(XrScreen *xr, XrActionType type, const char *name)
{
	XrActionCreateInfo info = {XR_TYPE_ACTION_CREATE_INFO};
	info.actionType = type;
	strncpy(info.actionName, name, XR_MAX_ACTION_NAME_SIZE - 1);
	strncpy(info.localizedActionName, name, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
	XrAction action = XR_NULL_HANDLE;
	ok(xrCreateAction(xr->action_set, &info, &action), name);
	return action;
}

// Touch controllers stand in for a gamepad when none is connected.
bool create_actions(XrScreen *xr)
{
	XrActionSetCreateInfo set_info = {XR_TYPE_ACTION_SET_CREATE_INFO};
	strncpy(set_info.actionSetName, "gamepad", XR_MAX_ACTION_SET_NAME_SIZE - 1);
	strncpy(set_info.localizedActionSetName, "Gamepad", XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE - 1);
	if(!ok(xrCreateActionSet(xr->instance, &set_info, &xr->action_set), "xrCreateActionSet"))
		return false;

	xr->a = create_action(xr, XR_ACTION_TYPE_BOOLEAN_INPUT, "a");
	xr->b = create_action(xr, XR_ACTION_TYPE_BOOLEAN_INPUT, "b");
	xr->x = create_action(xr, XR_ACTION_TYPE_BOOLEAN_INPUT, "x");
	xr->y = create_action(xr, XR_ACTION_TYPE_BOOLEAN_INPUT, "y");
	xr->menu = create_action(xr, XR_ACTION_TYPE_BOOLEAN_INPUT, "menu");
	xr->left_click = create_action(xr, XR_ACTION_TYPE_BOOLEAN_INPUT, "left_stick_click");
	xr->right_click = create_action(xr, XR_ACTION_TYPE_BOOLEAN_INPUT, "right_stick_click");
	xr->left_trigger = create_action(xr, XR_ACTION_TYPE_FLOAT_INPUT, "left_trigger");
	xr->right_trigger = create_action(xr, XR_ACTION_TYPE_FLOAT_INPUT, "right_trigger");
	xr->left_grip = create_action(xr, XR_ACTION_TYPE_FLOAT_INPUT, "left_grip");
	xr->right_grip = create_action(xr, XR_ACTION_TYPE_FLOAT_INPUT, "right_grip");
	xr->left_stick = create_action(xr, XR_ACTION_TYPE_VECTOR2F_INPUT, "left_stick");
	xr->right_stick = create_action(xr, XR_ACTION_TYPE_VECTOR2F_INPUT, "right_stick");
	xr->left_aim = create_action(xr, XR_ACTION_TYPE_POSE_INPUT, "left_aim");
	xr->right_aim = create_action(xr, XR_ACTION_TYPE_POSE_INPUT, "right_aim");
	xr->left_pinch = create_action(xr, XR_ACTION_TYPE_FLOAT_INPUT, "left_pinch");
	xr->right_pinch = create_action(xr, XR_ACTION_TYPE_FLOAT_INPUT, "right_pinch");

	struct Binding
	{
		XrAction action;
		const char *path;
	};
	const Binding bindings[] = {
		{xr->a, "/user/hand/right/input/a/click"},
		{xr->b, "/user/hand/right/input/b/click"},
		{xr->x, "/user/hand/left/input/x/click"},
		{xr->y, "/user/hand/left/input/y/click"},
		{xr->menu, "/user/hand/left/input/menu/click"},
		{xr->left_click, "/user/hand/left/input/thumbstick/click"},
		{xr->right_click, "/user/hand/right/input/thumbstick/click"},
		{xr->left_trigger, "/user/hand/left/input/trigger/value"},
		{xr->right_trigger, "/user/hand/right/input/trigger/value"},
		{xr->left_grip, "/user/hand/left/input/squeeze/value"},
		{xr->right_grip, "/user/hand/right/input/squeeze/value"},
		{xr->left_stick, "/user/hand/left/input/thumbstick"},
		{xr->right_stick, "/user/hand/right/input/thumbstick"},
		{xr->left_aim, "/user/hand/left/input/aim/pose"},
		{xr->right_aim, "/user/hand/right/input/aim/pose"},
	};
	std::vector<XrActionSuggestedBinding> suggested;
	for(const Binding &binding : bindings)
	{
		XrPath path = XR_NULL_PATH;
		if(binding.action == XR_NULL_HANDLE || !ok(xrStringToPath(xr->instance, binding.path, &path), binding.path))
			return false;
		suggested.push_back({binding.action, path});
	}

	XrPath profile = XR_NULL_PATH;
	if(!ok(xrStringToPath(xr->instance, "/interaction_profiles/oculus/touch_controller", &profile), "profile path"))
		return false;
	XrInteractionProfileSuggestedBinding suggestion = {XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
	suggestion.interactionProfile = profile;
	suggestion.countSuggestedBindings = static_cast<uint32_t>(suggested.size());
	suggestion.suggestedBindings = suggested.data();
	if(!ok(xrSuggestInteractionProfileBindings(xr->instance, &suggestion), "suggest bindings"))
		return false;
	xr->touch_profile = profile;
	xrStringToPath(xr->instance, "/user/hand/right", &xr->hand_paths[0]);
	xrStringToPath(xr->instance, "/user/hand/left", &xr->hand_paths[1]);

	// Bare hands can point and pinch to move the screen; they never act as a gamepad.
	if(xr->has_hand_interaction)
	{
		const Binding hand_bindings[] = {
			{xr->left_aim, "/user/hand/left/input/aim/pose"},
			{xr->right_aim, "/user/hand/right/input/aim/pose"},
			{xr->left_pinch, "/user/hand/left/input/pinch_ext/value"},
			{xr->right_pinch, "/user/hand/right/input/pinch_ext/value"},
		};
		std::vector<XrActionSuggestedBinding> hand_suggested;
		XrPath hand_profile = XR_NULL_PATH;
		bool paths_ok = XR_SUCCEEDED(xrStringToPath(xr->instance, "/interaction_profiles/ext/hand_interaction_ext", &hand_profile));
		for(const Binding &binding : hand_bindings)
		{
			XrPath path = XR_NULL_PATH;
			paths_ok = paths_ok && binding.action != XR_NULL_HANDLE && XR_SUCCEEDED(xrStringToPath(xr->instance, binding.path, &path));
			hand_suggested.push_back({binding.action, path});
		}
		XrInteractionProfileSuggestedBinding hand_suggestion = {XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
		hand_suggestion.interactionProfile = hand_profile;
		hand_suggestion.countSuggestedBindings = static_cast<uint32_t>(hand_suggested.size());
		hand_suggestion.suggestedBindings = hand_suggested.data();
		if(!paths_ok || !ok(xrSuggestInteractionProfileBindings(xr->instance, &hand_suggestion), "suggest hand bindings"))
			LOGE("Hand pointing is unavailable");
	}

	XrSessionActionSetsAttachInfo attach = {XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
	attach.countActionSets = 1;
	attach.actionSets = &xr->action_set;
	if(!ok(xrAttachSessionActionSets(xr->session, &attach), "attach action sets"))
		return false;

	XrActionSpaceCreateInfo space_info = {XR_TYPE_ACTION_SPACE_CREATE_INFO};
	space_info.poseInActionSpace.orientation.w = 1.0f;
	space_info.action = xr->left_aim;
	ok(xrCreateActionSpace(xr->session, &space_info, &xr->left_aim_space), "left aim space");
	space_info.action = xr->right_aim;
	ok(xrCreateActionSpace(xr->session, &space_info, &xr->right_aim_space), "right aim space");
	return true;
}

// A surface the app draws on with Android's own canvas and the compositor shows as a layer.
bool create_panel(XrScreen *xr, Panel *panel, int width, int height)
{
	XrSwapchainCreateInfo info = {XR_TYPE_SWAPCHAIN_CREATE_INFO};
	info.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
	info.width = static_cast<uint32_t>(width);
	info.height = static_cast<uint32_t>(height);
	jobject surface = nullptr;
	if(!ok(xr->create_surface_swapchain(xr->session, &info, &panel->swapchain, &surface), "panel swapchain") || !surface)
		return false;
	panel->surface = xr->env->NewGlobalRef(surface);
	panel->width = width;
	panel->height = height;
	return true;
}

void destroy_panel(XrScreen *xr, Panel *panel)
{
	if(panel->surface)
		xr->env->DeleteGlobalRef(panel->surface);
	if(panel->swapchain != XR_NULL_HANDLE)
		xrDestroySwapchain(panel->swapchain);
	*panel = Panel();
}

bool create_session(XrScreen *xr)
{
	XrGraphicsRequirementsOpenGLESKHR requirements = {XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
	if(!ok(xr->get_gles_requirements(xr->instance, xr->system_id, &requirements), "GLES requirements"))
		return false;

	XrGraphicsBindingOpenGLESAndroidKHR binding = {XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
	binding.display = xr->egl_display;
	binding.config = xr->egl_config;
	binding.context = xr->egl_context;

	XrSessionCreateInfo session_info = {XR_TYPE_SESSION_CREATE_INFO};
	session_info.next = &binding;
	session_info.systemId = xr->system_id;
	if(!ok(xrCreateSession(xr->instance, &session_info, &xr->session), "xrCreateSession"))
		return false;

	// LOCAL space is re-centred by the system recenter gesture, which keeps the screen in front of the user.
	XrReferenceSpaceCreateInfo space_info = {XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
	space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
	space_info.poseInReferenceSpace.orientation.w = 1.0f;
	if(!ok(xrCreateReferenceSpace(xr->session, &space_info, &xr->local_space), "xrCreateReferenceSpace"))
		return false;
	space_info.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
	ok(xrCreateReferenceSpace(xr->session, &space_info, &xr->view_space), "view space");

	// Android surface swapchains take their format from the producer, so those fields stay zero.
	XrSwapchainCreateInfo swapchain_info = {XR_TYPE_SWAPCHAIN_CREATE_INFO};
	swapchain_info.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
	swapchain_info.width = static_cast<uint32_t>(xr->video_width);
	swapchain_info.height = static_cast<uint32_t>(xr->video_height);
	jobject surface = nullptr;
	if(!ok(xr->create_surface_swapchain(xr->session, &swapchain_info, &xr->swapchain, &surface), "surface swapchain")
		|| !surface)
		return false;
	xr->surface = xr->env->NewGlobalRef(surface);

	if(!create_panel(xr, &xr->bar, xr->bar.width, xr->bar.height)
		|| !create_panel(xr, &xr->overlay, xr->overlay.width, xr->overlay.height))
		return false;
	xr->pointer_values = static_cast<jfloatArray>(xr->env->NewGlobalRef(xr->env->NewFloatArray(18)));

	if(xr->has_color_space)
	{
		// Console video is Rec.709; without this the compositor assumes its wider default gamut.
		ok(xr->set_color_space(xr->session, XR_COLOR_SPACE_REC709_FB), "xrSetColorSpaceFB");
	}

	if(xr->has_refresh_rate)
	{
		// 120 Hz shows each 60 fps video frame for exactly two refreshes; 90 Hz is the next best.
		uint32_t rate_count = 0;
		if(XR_SUCCEEDED(xr->enumerate_refresh_rates(xr->session, 0, &rate_count, nullptr)) && rate_count > 0)
		{
			std::vector<float> rates(rate_count);
			if(XR_SUCCEEDED(xr->enumerate_refresh_rates(xr->session, rate_count, &rate_count, rates.data())))
			{
				float chosen = 0.0f;
				for(float preferred : {120.0f, 90.0f})
				{
					for(float rate : rates)
					{
						if(std::fabs(rate - preferred) < 0.5f)
							chosen = rate;
					}
					if(chosen > 0.0f)
						break;
				}
				if(chosen > 0.0f && ok(xr->request_refresh_rate(xr->session, chosen), "request refresh rate"))
					LOGI("Requested %.0f Hz", chosen);
			}
		}
	}

	if(xr->has_passthrough)
	{
		XrPassthroughCreateInfoFB passthrough_info = {XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
		passthrough_info.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
		XrPassthroughLayerCreateInfoFB layer_info = {XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
		layer_info.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
		layer_info.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
		if(XR_SUCCEEDED(xr->create_passthrough(xr->session, &passthrough_info, &xr->passthrough)))
		{
			layer_info.passthrough = xr->passthrough;
			if(XR_FAILED(xr->create_passthrough_layer(xr->session, &layer_info, &xr->passthrough_layer)))
				xr->passthrough_layer = XR_NULL_HANDLE;
		}
		if(xr->passthrough_layer == XR_NULL_HANDLE)
		{
			LOGI("Passthrough unavailable; using a dark background");
			xr->has_passthrough = false;
		}
	}

	if(!create_actions(xr))
		LOGE("Touch controller input is unavailable");

	return true;
}

void destroy_session(XrScreen *xr)
{
	if(xr->surface)
	{
		xr->env->DeleteGlobalRef(xr->surface);
		xr->surface = nullptr;
	}
	destroy_panel(xr, &xr->bar);
	destroy_panel(xr, &xr->overlay);
	if(xr->pointer_values)
	{
		xr->env->DeleteGlobalRef(xr->pointer_values);
		xr->pointer_values = nullptr;
	}
	if(xr->view_space != XR_NULL_HANDLE)
		xrDestroySpace(xr->view_space);
	xr->view_space = XR_NULL_HANDLE;
	if(xr->left_aim_space != XR_NULL_HANDLE)
		xrDestroySpace(xr->left_aim_space);
	if(xr->right_aim_space != XR_NULL_HANDLE)
		xrDestroySpace(xr->right_aim_space);
	xr->left_aim_space = XR_NULL_HANDLE;
	xr->right_aim_space = XR_NULL_HANDLE;
	if(xr->swapchain != XR_NULL_HANDLE)
		xrDestroySwapchain(xr->swapchain);
	if(xr->passthrough_layer != XR_NULL_HANDLE)
		xr->destroy_passthrough_layer(xr->passthrough_layer);
	if(xr->passthrough != XR_NULL_HANDLE)
		xr->destroy_passthrough(xr->passthrough);
	if(xr->local_space != XR_NULL_HANDLE)
		xrDestroySpace(xr->local_space);
	if(xr->session != XR_NULL_HANDLE)
		xrDestroySession(xr->session);
	if(xr->instance != XR_NULL_HANDLE)
		xrDestroyInstance(xr->instance);
	xr->swapchain = XR_NULL_HANDLE;
	xr->passthrough_layer = XR_NULL_HANDLE;
	xr->passthrough = XR_NULL_HANDLE;
	xr->local_space = XR_NULL_HANDLE;
	xr->session = XR_NULL_HANDLE;
	xr->instance = XR_NULL_HANDLE;
}

// Returns false once the session is over and the loop should end.
bool poll_events(XrScreen *xr)
{
	XrEventDataBuffer event = {XR_TYPE_EVENT_DATA_BUFFER};
	while(xrPollEvent(xr->instance, &event) == XR_SUCCESS)
	{
		switch(event.type)
		{
			case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED:
			{
				auto *changed = reinterpret_cast<XrEventDataSessionStateChanged *>(&event);
				xr->session_focused = changed->state == XR_SESSION_STATE_FOCUSED;
				switch(changed->state)
				{
					case XR_SESSION_STATE_READY:
					{
						XrSessionBeginInfo begin = {XR_TYPE_SESSION_BEGIN_INFO};
						begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
						if(ok(xrBeginSession(xr->session, &begin), "xrBeginSession"))
							xr->session_running = true;
						break;
					}
					case XR_SESSION_STATE_STOPPING:
						xrEndSession(xr->session);
						xr->session_running = false;
						break;
					case XR_SESSION_STATE_EXITING:
					case XR_SESSION_STATE_LOSS_PENDING:
						return false;
					default:
						break;
				}
				break;
			}
			case XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED:
				// A hand counts as bare whenever it is tracked without a Touch controller in it.
				for(int i = 0; i < 2; i++)
				{
					XrInteractionProfileState state = {XR_TYPE_INTERACTION_PROFILE_STATE};
					bool known = xr->hand_paths[i] != XR_NULL_PATH
						&& XR_SUCCEEDED(xrGetCurrentInteractionProfile(xr->session, xr->hand_paths[i], &state));
					xr->bare_hand[i] = known && state.interactionProfile != XR_NULL_PATH
						&& state.interactionProfile != xr->touch_profile;
				}
				LOGI("Input: right %s, left %s", xr->bare_hand[0] ? "bare hand" : "controller or none",
						xr->bare_hand[1] ? "bare hand" : "controller or none");
				break;
			case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING:
				return false;
			default:
				break;
		}
		event = {XR_TYPE_EVENT_DATA_BUFFER};
	}
	return true;
}

bool read_bool(XrScreen *xr, XrAction action)
{
	XrActionStateGetInfo info = {XR_TYPE_ACTION_STATE_GET_INFO};
	info.action = action;
	XrActionStateBoolean state = {XR_TYPE_ACTION_STATE_BOOLEAN};
	return action != XR_NULL_HANDLE && XR_SUCCEEDED(xrGetActionStateBoolean(xr->session, &info, &state))
		&& state.isActive && state.currentState;
}

float read_float(XrScreen *xr, XrAction action)
{
	XrActionStateGetInfo info = {XR_TYPE_ACTION_STATE_GET_INFO};
	info.action = action;
	XrActionStateFloat state = {XR_TYPE_ACTION_STATE_FLOAT};
	if(action == XR_NULL_HANDLE || XR_FAILED(xrGetActionStateFloat(xr->session, &info, &state)) || !state.isActive)
		return 0.0f;
	return state.currentState;
}

XrVector2f read_stick(XrScreen *xr, XrAction action)
{
	XrActionStateGetInfo info = {XR_TYPE_ACTION_STATE_GET_INFO};
	info.action = action;
	XrActionStateVector2f state = {XR_TYPE_ACTION_STATE_VECTOR2F};
	if(action == XR_NULL_HANDLE || XR_FAILED(xrGetActionStateVector2f(xr->session, &info, &state)) || !state.isActive)
		return {0.0f, 0.0f};
	return state.currentState;
}

int stick_axis(float value)
{
	if(std::fabs(value) < 0.08f)
		return 0;
	value = value < -1.0f ? -1.0f : (value > 1.0f ? 1.0f : value);
	return static_cast<int>(std::lround(value * 32767.0f));
}

int trigger_axis(float value)
{
	value = value < 0.0f ? 0.0f : (value > 1.0f ? 1.0f : value);
	return static_cast<int>(std::lround(value * 255.0f));
}

// Fills nine floats for one hand: tracked (1 with a controller, 2 bare), ray origin relative to the
// centre of the scene, ray direction, trigger or pinch, thumbstick y.
void read_hand(XrScreen *xr, XrSpace space, XrAction trigger, XrAction pinch, XrAction stick, XrTime time, float *out)
{
	for(int i = 0; i < 9; i++)
		out[i] = 0.0f;
	XrSpaceLocation location = {XR_TYPE_SPACE_LOCATION};
	const XrSpaceLocationFlags needed = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
	if(space == XR_NULL_HANDLE || XR_FAILED(xrLocateSpace(space, xr->local_space, time, &location))
		|| (location.locationFlags & needed) != needed)
		return;

	// The ray leaves the controller along -Z of its aim pose.
	const XrQuaternionf &q = location.pose.orientation;
	out[0] = 1.0f;
	out[1] = location.pose.position.x - xr->frame_origin.x;
	out[2] = location.pose.position.y - xr->frame_origin.y;
	out[3] = location.pose.position.z - xr->frame_origin.z;
	out[4] = -2.0f * (q.x * q.z + q.w * q.y);
	out[5] = -2.0f * (q.y * q.z - q.w * q.x);
	out[6] = -(1.0f - 2.0f * (q.x * q.x + q.y * q.y));
	out[7] = std::max(read_float(xr, trigger), read_float(xr, pinch));
	out[8] = read_stick(xr, stick).y;
}

// Reads the Touch controllers once per frame: always as a gamepad, and as pointers while the UI asks.
void update_input(XrScreen *xr, XrTime time, bool pointer_wanted)
{
	TouchState touch;
	bool synced = false;
	if(xr->session_focused && xr->action_set != XR_NULL_HANDLE)
	{
		XrActiveActionSet active = {xr->action_set, XR_NULL_PATH};
		XrActionsSyncInfo sync = {XR_TYPE_ACTIONS_SYNC_INFO};
		sync.countActiveActionSets = 1;
		sync.activeActionSets = &active;
		synced = xrSyncActions(xr->session, &sync) == XR_SUCCESS;
	}
	if(synced)
	{
		if(read_bool(xr, xr->a)) touch.buttons |= PAD_CROSS;
		if(read_bool(xr, xr->b)) touch.buttons |= PAD_CIRCLE;
		if(read_bool(xr, xr->x)) touch.buttons |= PAD_SQUARE;
		if(read_bool(xr, xr->y)) touch.buttons |= PAD_TRIANGLE;
		if(read_bool(xr, xr->menu)) touch.buttons |= TOUCH_MENU;
		if(read_bool(xr, xr->left_click)) touch.buttons |= PAD_L3;
		if(read_bool(xr, xr->right_click)) touch.buttons |= PAD_R3;
		if(read_float(xr, xr->left_grip) > 0.5f) touch.buttons |= PAD_L1;
		if(read_float(xr, xr->right_grip) > 0.5f) touch.buttons |= PAD_R1;
		touch.l2 = trigger_axis(read_float(xr, xr->left_trigger));
		touch.r2 = trigger_axis(read_float(xr, xr->right_trigger));
		XrVector2f left = read_stick(xr, xr->left_stick);
		XrVector2f right = read_stick(xr, xr->right_stick);
		// OpenXR sticks point up for positive y; the console expects down.
		touch.left_x = stick_axis(left.x);
		touch.left_y = stick_axis(-left.y);
		touch.right_x = stick_axis(right.x);
		touch.right_y = stick_axis(-right.y);
	}

	if(!(touch == xr->last_touch))
	{
		xr->last_touch = touch;
		xr->env->CallVoidMethod(xr->listener, xr->on_touch_state, touch.buttons, touch.l2, touch.r2,
				touch.left_x, touch.left_y, touch.right_x, touch.right_y);
		clear_exception(xr->env);
	}

	// Reported every frame while a hand is tracked, plus once more afterwards so the UI sees both gone.
	float values[18] = {};
	if(pointer_wanted && synced)
	{
		read_hand(xr, xr->right_aim_space, xr->right_trigger, xr->right_pinch, xr->right_stick, time, values);
		read_hand(xr, xr->left_aim_space, xr->left_trigger, xr->left_pinch, xr->left_stick, time, values + 9);
	}
	for(int i = 0; i < 2; i++)
	{
		if(values[i * 9] > 0.5f && xr->bare_hand[i])
			values[i * 9] = 2.0f;
	}
	bool report = values[0] > 0.5f || values[9] > 0.5f;
	if(!report && !xr->pointer_was_reported)
		return;
	xr->pointer_was_reported = report;
	xr->env->SetFloatArrayRegion(xr->pointer_values, 0, 18, values);
	xr->env->CallVoidMethod(xr->listener, xr->on_pointer, xr->pointer_values);
	clear_exception(xr->env);
}

// Orientation of something turned yaw to the left around the vertical axis after being tipped up by tilt.
XrQuaternionf facing(float yaw, float tilt)
{
	float sy = std::sin(yaw / 2.0f), cy = std::cos(yaw / 2.0f);
	float sx = std::sin(tilt / 2.0f), cx = std::cos(tilt / 2.0f);
	return {cy * sx, sy * cx, -sy * sx, cy * cx};
}

// A point of the unturned scene after the scene is tipped up by pitch, turned by yaw and moved to origin.
XrVector3f turned(const XrVector3f &origin, float yaw, float pitch, float x, float y, float z)
{
	float pc = std::cos(pitch), ps = std::sin(pitch);
	float ty = y * pc - z * ps;
	float tz = y * ps + z * pc;
	float c = std::cos(yaw), s = std::sin(yaw);
	return {origin.x + x * c + tz * s, origin.y + ty, origin.z - x * s + tz * c};
}

// When following, the scene's centre drifts after the viewer's head: quickly enough to keep up with
// walking, slowly enough that small head movements do not make the screen swim.
void update_origin(XrScreen *xr, const XrPosef &head_pose, bool follow)
{
	if(!follow && xr->origin_set)
		return;
	const XrVector3f &p = head_pose.position;
	if(!xr->origin_set)
	{
		// The scene starts centred on the viewer's eyes and the UI is told where they are looking, so
		// the screen opens in front of their face even when they are lying back.
		xr->origin = p;
		xr->origin_set = true;
		const XrQuaternionf &q = head_pose.orientation;
		float fx = -2.0f * (q.x * q.z + q.w * q.y);
		float fy = -2.0f * (q.y * q.z - q.w * q.x);
		float fz = -(1.0f - 2.0f * (q.x * q.x + q.y * q.y));
		float heading = std::atan2(-fx, -fz);
		float elevation = std::atan2(fy, std::sqrt(fx * fx + fz * fz));
		LOGI("Scene centred at head position %.2f %.2f %.2f, looking heading %.2f elevation %.2f", p.x, p.y, p.z,
				heading, elevation);
		xr->env->CallVoidMethod(xr->listener, xr->on_gaze, heading, elevation);
		clear_exception(xr->env);
		return;
	}
	float dx = p.x - xr->origin.x, dy = p.y - xr->origin.y, dz = p.z - xr->origin.z;
	float away = std::sqrt(dx * dx + dy * dy + dz * dz);
	if(away < 0.12f)
		return;
	float step = std::min(1.0f, 0.04f * (away / 0.12f));
	xr->origin.x += dx * step;
	xr->origin.y += dy * step;
	xr->origin.z += dz * step;
}

void submit_frame(XrScreen *xr)
{
	XrFrameState frame_state = {XR_TYPE_FRAME_STATE};
	if(XR_FAILED(xrWaitFrame(xr->session, nullptr, &frame_state)))
		return;
	if(XR_FAILED(xrBeginFrame(xr->session, nullptr)))
		return;

	XrSpaceLocation head = {XR_TYPE_SPACE_LOCATION};
	const XrSpaceLocationFlags head_needed = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
	bool head_known = xr->view_space != XR_NULL_HANDLE
		&& XR_SUCCEEDED(xrLocateSpace(xr->view_space, xr->local_space, frame_state.predictedDisplayTime, &head))
		&& (head.locationFlags & head_needed) == head_needed;

	// The scene and its centre are taken together, so a re-centre from the UI thread never lands
	// between the two.
	Scene scene;
	XrVector3f origin;
	{
		std::lock_guard<std::mutex> lock(xr->scene_mutex);
		scene = xr->scene;
		if(head_known)
		{
			update_origin(xr, head.pose, scene.follow);
			xr->head_position = head.pose.position;
			xr->head_valid = true;
		}
		origin = xr->origin;
	}
	xr->frame_origin = origin;

	// With the room in view there is nothing to bump into unseen, so the play-area boundary stays out
	// of the way; in the dark it comes back. The runtime only honours this while passthrough is shown.
	if(xr->has_boundary_visibility)
	{
		int suppress = (xr->has_passthrough && scene.passthrough) ? 1 : 0;
		if(suppress != xr->boundary_suppressed)
		{
			XrResult boundary = xr->request_boundary_visibility(xr->session,
					suppress ? XR_BOUNDARY_VISIBILITY_SUPPRESSED_META : XR_BOUNDARY_VISIBILITY_NOT_SUPPRESSED_META);
			LOGI("Boundary %s: result %d", suppress ? "suppressed" : "shown", static_cast<int>(boundary));
			// Suppression is refused until the passthrough layer has been on screen, so keep asking.
			if(XR_SUCCEEDED(boundary) && boundary != XR_BOUNDARY_VISIBILITY_SUPPRESSION_NOT_ALLOWED_META)
				xr->boundary_suppressed = suppress;
		}
	}
	update_input(xr, frame_state.predictedDisplayTime, scene.pointer);

	float aspect = static_cast<float>(xr->video_width) / static_cast<float>(xr->video_height);
	bool curved = scene.curved && xr->has_cylinder;
	XrQuaternionf screen_facing = facing(scene.yaw, scene.pitch);
	const XrCompositionLayerFlags blended = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;

	XrCompositionLayerPassthroughFB passthrough_layer = {XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
	passthrough_layer.flags = blended;
	passthrough_layer.layerHandle = xr->passthrough_layer;

	auto sub_image = [](XrSwapchain swapchain, int width, int height) {
		XrSwapchainSubImage image = {};
		image.swapchain = swapchain;
		image.imageRect.extent = {width, height};
		return image;
	};

	// The runtime reads swapchain images bottom-up, as OpenGL does, while Android surfaces are filled
	// top-down, so every layer fed by a surface asks to be flipped. (A source rectangle with negative
	// height is rejected by the Quest runtime with XR_ERROR_SWAPCHAIN_RECT_INVALID.)
	XrCompositionLayerImageLayoutFB flipped = {XR_TYPE_COMPOSITION_LAYER_IMAGE_LAYOUT_FB};
	flipped.flags = XR_COMPOSITION_LAYER_IMAGE_LAYOUT_VERTICAL_FLIP_BIT_FB;
	const void *layout = xr->has_image_layout ? &flipped : nullptr;

	// The compositor's sharpening filter (XR_FB_composition_layer_settings) is deliberately not used:
	// on a Quest 3 it only sharpens a disc in the middle of the view, and the player sees that disc
	// travel across the picture as they move their head.
	const void *video_chain = layout;

	// The console's video keeps black at 16 and white at 235 out of 255. Screenshots from a Quest 3
	// showed the compositor displaying those values as they are (blacks near 30, whites capped near
	// 230), which looks washed out. Stretching 16..235 back to 0..255 restores the contrast.
	XrCompositionLayerColorScaleBiasKHR full_range = {XR_TYPE_COMPOSITION_LAYER_COLOR_SCALE_BIAS_KHR};
	full_range.next = video_chain;
	// The compositor applies these to linear light, not to the stored values (the stored-value numbers,
	// 255/219 and -16/219, crushed everything darker than mid grey to black on a Quest 3). In linear
	// light, video black 16 is 0.0052 and video white 235 is 0.830; this maps those two to 0 and 1.
	// The mid tones end up a touch brighter than an exact conversion, which no straight line can give.
	const float video_black = 0.0052f, video_white = 0.830f;
	const float range_scale = 1.0f / (video_white - video_black);
	full_range.colorScale = {range_scale, range_scale, range_scale, 1.0f};
	full_range.colorBias = {-video_black * range_scale, -video_black * range_scale, -video_black * range_scale, 0.0f};
	if(xr->has_color_scale_bias && scene.expand_range)
		video_chain = &full_range;

	XrCompositionLayerQuad quad = {XR_TYPE_COMPOSITION_LAYER_QUAD};
	quad.next = video_chain;
	quad.space = xr->local_space;
	quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
	quad.subImage = sub_image(xr->swapchain, xr->video_width, xr->video_height);
	quad.pose.orientation = screen_facing;
	quad.pose.position = turned(origin, scene.yaw, scene.pitch, 0.0f, scene.screen_y, -scene.distance);
	quad.size = {scene.width, scene.width / aspect};

	// The cylinder is centred on the viewer, so every part of the screen sits at the same distance.
	XrCompositionLayerCylinderKHR cylinder = {XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
	cylinder.next = video_chain;
	cylinder.space = xr->local_space;
	cylinder.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
	cylinder.subImage = quad.subImage;
	cylinder.pose.orientation = screen_facing;
	cylinder.pose.position = turned(origin, scene.yaw, scene.pitch, 0.0f, scene.screen_y, 0.0f);
	cylinder.radius = scene.distance;
	cylinder.centralAngle = scene.width / scene.distance;
	cylinder.aspectRatio = aspect;

	// The handle overlay follows the screen's shape, a little larger on every side.
	float overlay_width = scene.width * (1.0f + 2.0f * scene.overlay_margin);
	float overlay_height = scene.width / aspect + 2.0f * scene.width * scene.overlay_margin;
	XrCompositionLayerQuad overlay_quad = quad;
	overlay_quad.next = layout;
	overlay_quad.layerFlags = blended;
	overlay_quad.subImage = sub_image(xr->overlay.swapchain, xr->overlay.width, xr->overlay.height);
	overlay_quad.size = {overlay_width, overlay_height};
	XrCompositionLayerCylinderKHR overlay_cylinder = cylinder;
	overlay_cylinder.next = layout;
	overlay_cylinder.layerFlags = blended;
	overlay_cylinder.subImage = overlay_quad.subImage;
	overlay_cylinder.centralAngle = overlay_width / scene.distance;
	overlay_cylinder.aspectRatio = overlay_width / overlay_height;

	// The control bar sits below the screen, closer, and leans back to face the viewer.
	float bar_cos = std::cos(scene.bar_elevation);
	float bar_sin = std::sin(scene.bar_elevation);
	XrCompositionLayerQuad bar_quad = {XR_TYPE_COMPOSITION_LAYER_QUAD};
	bar_quad.next = layout;
	bar_quad.layerFlags = blended;
	bar_quad.space = xr->local_space;
	bar_quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
	bar_quad.subImage = sub_image(xr->bar.swapchain, xr->bar.width, xr->bar.height);
	bar_quad.pose.position = turned(origin, scene.yaw, scene.pitch, 0.0f, scene.bar_distance * bar_sin, -scene.bar_distance * bar_cos);
	bar_quad.pose.orientation = facing(scene.yaw, scene.pitch + scene.bar_elevation);
	bar_quad.size = {scene.bar_width, scene.bar_height};

	std::vector<const XrCompositionLayerBaseHeader *> layers;
	auto add = [&layers](const void *layer) {
		layers.push_back(static_cast<const XrCompositionLayerBaseHeader *>(layer));
	};
	if(frame_state.shouldRender)
	{
		if(xr->has_passthrough && scene.passthrough)
			add(&passthrough_layer);
		if(curved)
			add(&cylinder);
		else
			add(&quad);
		if(scene.overlay_visible)
		{
			if(curved)
				add(&overlay_cylinder);
			else
				add(&overlay_quad);
		}
		if(scene.bar_visible)
			add(&bar_quad);
	}

	XrFrameEndInfo end = {XR_TYPE_FRAME_END_INFO};
	end.displayTime = frame_state.predictedDisplayTime;
	end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
	end.layerCount = static_cast<uint32_t>(layers.size());
	end.layers = layers.empty() ? nullptr : layers.data();
	XrResult result = xrEndFrame(xr->session, &end);

	xr->stats_frames++;
	if(!frame_state.shouldRender)
		xr->stats_not_rendered++;
	xr->stats_period_ns = frame_state.predictedDisplayPeriod;
	auto now = std::chrono::steady_clock::now();
	double elapsed = std::chrono::duration<double>(now - xr->stats_since).count();
	if(elapsed >= 5.0)
	{
		float rate = 0.0f;
		if(xr->get_refresh_rate)
			xr->get_refresh_rate(xr->session, &rate);
		LOGI("frames %.1f/s, not rendered %d, display %.0f Hz, period %.2f ms, focused %d, layers %u, scene d=%.2f w=%.2f yaw=%.2f pitch=%.2f curved=%d passthrough=%d",
				xr->stats_frames / elapsed, xr->stats_not_rendered, rate, xr->stats_period_ns / 1e6,
				xr->session_focused ? 1 : 0, end.layerCount, scene.distance, scene.width, scene.yaw, scene.pitch,
				curved ? 1 : 0, (xr->has_passthrough && scene.passthrough) ? 1 : 0);
		xr->stats_since = now;
		xr->stats_frames = 0;
		xr->stats_not_rendered = 0;
	}
	if(XR_FAILED(result) && result != xr->last_end_frame_error)
		LOGE("xrEndFrame failed: %d", static_cast<int>(result));
	xr->last_end_frame_error = result;
}

void frame_thread(XrScreen *xr)
{
	if(xr->vm->AttachCurrentThread(&xr->env, nullptr) != JNI_OK)
		return;

	bool ready = init_egl(xr) && create_instance(xr) && create_session(xr);
	if(!ready)
	{
		report_error(xr, "Could not start the immersive screen");
	}
	else
	{
		xr->env->CallVoidMethod(xr->listener, xr->on_surfaces_ready, xr->surface, xr->bar.surface,
				xr->overlay.surface, static_cast<jboolean>(xr->has_cylinder), static_cast<jboolean>(xr->has_passthrough));
		clear_exception(xr->env);

		auto exit_deadline = std::chrono::steady_clock::time_point::max();
		while(poll_events(xr))
		{
			if(xr->stop_requested.load() && !xr->exit_requested)
			{
				// Ask the runtime to wind the session down, but don't wait on it forever.
				xr->exit_requested = true;
				exit_deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
				if(!xr->session_running || XR_FAILED(xrRequestExitSession(xr->session)))
					break;
			}
			if(std::chrono::steady_clock::now() > exit_deadline)
				break;

			if(xr->session_running)
				submit_frame(xr);
			else
				std::this_thread::sleep_for(std::chrono::milliseconds(50));
		}
	}

	bool stopped_by_app = xr->stop_requested.load();
	destroy_session(xr);
	fini_egl(xr);

	if(ready && !stopped_by_app)
	{
		xr->env->CallVoidMethod(xr->listener, xr->on_exit);
		clear_exception(xr->env);
	}
	xr->vm->DetachCurrentThread();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL XR_NATIVE(start)(JNIEnv *env, jclass, jobject activity, jint video_width, jint video_height,
		jint bar_width, jint bar_height, jint overlay_width, jint overlay_height, jobject listener)
{
	auto *xr = new XrScreen();
	env->GetJavaVM(&xr->vm);
	xr->activity = env->NewGlobalRef(activity);
	xr->listener = env->NewGlobalRef(listener);
	xr->video_width = video_width;
	xr->video_height = video_height;
	xr->bar.width = bar_width;
	xr->bar.height = bar_height;
	xr->overlay.width = overlay_width;
	xr->overlay.height = overlay_height;

	jclass listener_class = env->GetObjectClass(listener);
	xr->on_surfaces_ready = env->GetMethodID(listener_class, "onSurfacesReady",
			"(Landroid/view/Surface;Landroid/view/Surface;Landroid/view/Surface;ZZ)V");
	xr->on_touch_state = env->GetMethodID(listener_class, "onTouchState", "(IIIIIII)V");
	xr->on_pointer = env->GetMethodID(listener_class, "onPointer", "([F)V");
	xr->on_gaze = env->GetMethodID(listener_class, "onGaze", "(FF)V");
	xr->on_error = env->GetMethodID(listener_class, "onXrError", "(Ljava/lang/String;)V");
	xr->on_exit = env->GetMethodID(listener_class, "onXrExit", "()V");

	xr->thread = std::thread(frame_thread, xr);
	return reinterpret_cast<jlong>(xr);
}

JNIEXPORT void JNICALL XR_NATIVE(setScene)(JNIEnv *, jclass, jlong ptr, jfloat distance, jfloat width, jboolean curved,
		jboolean passthrough, jfloat screen_y, jfloat yaw, jfloat pitch, jboolean bar_visible, jfloat bar_distance,
		jfloat bar_elevation, jfloat bar_width, jfloat bar_height, jboolean overlay_visible, jfloat overlay_margin,
		jboolean pointer, jboolean follow)
{
	auto *xr = reinterpret_cast<XrScreen *>(ptr);
	if(!xr)
		return;
	Scene scene;
	scene.distance = distance;
	scene.width = width;
	scene.curved = curved;
	scene.passthrough = passthrough;
	scene.screen_y = screen_y;
	scene.yaw = yaw;
	scene.pitch = pitch;
	scene.bar_visible = bar_visible;
	scene.bar_distance = bar_distance;
	scene.bar_elevation = bar_elevation;
	scene.bar_width = bar_width;
	scene.bar_height = bar_height;
	scene.overlay_visible = overlay_visible;
	scene.overlay_margin = overlay_margin;
	scene.pointer = pointer;
	scene.follow = follow;
	std::lock_guard<std::mutex> lock(xr->scene_mutex);
	xr->scene = scene;
}

// Moves the centre of the scene to where the viewer is now and re-aims the screen so it stays where
// it was, now facing them. Returns {yaw, pitch, distance}, or null before the head has been tracked.
// Without this, a viewer who has walked away from where the session began would be moving a screen
// that turns around a point they are no longer standing on.
JNIEXPORT jfloatArray JNICALL XR_NATIVE(recentre)(JNIEnv *env, jclass, jlong ptr)
{
	auto *xr = reinterpret_cast<XrScreen *>(ptr);
	if(!xr)
		return nullptr;
	float values[3];
	{
		std::lock_guard<std::mutex> lock(xr->scene_mutex);
		if(!xr->head_valid)
			return nullptr;
		Scene &scene = xr->scene;
		XrVector3f centre = turned(xr->origin, scene.yaw, scene.pitch, 0.0f, 0.0f, -scene.distance);
		float vx = centre.x - xr->head_position.x;
		float vy = centre.y - xr->head_position.y;
		float vz = centre.z - xr->head_position.z;
		float flat = std::sqrt(vx * vx + vz * vz);
		float distance = std::sqrt(flat * flat + vy * vy);
		if(distance < 0.3f)
			return nullptr;
		scene.yaw = std::atan2(-vx, -vz);
		scene.pitch = std::atan2(vy, flat);
		scene.distance = distance;
		xr->origin = xr->head_position;
		values[0] = scene.yaw;
		values[1] = scene.pitch;
		values[2] = scene.distance;
	}
	jfloatArray result = env->NewFloatArray(3);
	if(result)
		env->SetFloatArrayRegion(result, 0, 3, values);
	return result;
}

// Ends the session and joins the frame thread. Anything still writing to the surface must stop first.
JNIEXPORT void JNICALL XR_NATIVE(stop)(JNIEnv *env, jclass, jlong ptr)
{
	auto *xr = reinterpret_cast<XrScreen *>(ptr);
	if(!xr)
		return;
	xr->stop_requested = true;
	if(xr->thread.joinable())
		xr->thread.join();
	env->DeleteGlobalRef(xr->activity);
	env->DeleteGlobalRef(xr->listener);
	delete xr;
}

} // extern "C"
