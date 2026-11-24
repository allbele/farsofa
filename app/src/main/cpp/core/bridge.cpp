// SPDX-License-Identifier: AGPL-3.0-only
//
// The only code in the app that talks to libchiaki. Everything crosses the JNI boundary as
// primitives, strings and byte arrays so the Kotlin side never depends on library structs.

#include <jni.h>

#include <arpa/inet.h>
#include <netinet/in.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>

#include <chiaki/common.h>
#include <chiaki/controller.h>
#include <chiaki/discoveryservice.h>
#include <chiaki/log.h>
#include <chiaki/regist.h>
#include <chiaki/remote/holepunch.h>
#include <chiaki/session.h>

extern "C" {
#include "audio-decoder.h"
#include "audio-output.h"
#include "log.h"
#include "video-decoder.h"

// Symbols the chiaki-ng Android helpers expect from their host library.
JavaVM *global_vm = nullptr;
JNIEnv *attach_thread_jni();
jobject jnistr_from_ascii(JNIEnv *env, const char *str);
}

#define NATIVE(name) Java_app_farsofa_vr_core_Native_##name

namespace {

ChiakiLog g_log;

// JNIEnv for the current thread; detaches on scope exit only if this scope attached it.
struct ScopedEnv
{
	JNIEnv *env = nullptr;
	bool attached = false;

	ScopedEnv()
	{
		if(global_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK)
			return;
		if(global_vm->AttachCurrentThread(&env, nullptr) == JNI_OK)
			attached = true;
		else
			env = nullptr;
	}

	~ScopedEnv()
	{
		if(env && env->ExceptionCheck())
		{
			env->ExceptionDescribe();
			env->ExceptionClear();
		}
		if(attached)
			global_vm->DetachCurrentThread();
	}
};

// Console strings (nicknames, app titles) are UTF-8 but not guaranteed to be valid modified UTF-8,
// so they are decoded on the Java side instead of going through NewStringUTF.
jstring utf8_string(JNIEnv *env, const char *str)
{
	if(!str)
		return nullptr;
	jsize len = static_cast<jsize>(strlen(str));
	jbyteArray bytes = env->NewByteArray(len);
	env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(str));
	jclass string_class = env->FindClass("java/lang/String");
	jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
	jstring charset = env->NewStringUTF("UTF-8");
	jstring result = static_cast<jstring>(env->NewObject(string_class, ctor, bytes, charset));
	env->DeleteLocalRef(charset);
	env->DeleteLocalRef(bytes);
	env->DeleteLocalRef(string_class);
	return result;
}

std::string to_std_string(JNIEnv *env, jstring str)
{
	if(!str)
		return {};
	const char *chars = env->GetStringUTFChars(str, nullptr);
	std::string result(chars ? chars : "");
	if(chars)
		env->ReleaseStringUTFChars(str, chars);
	return result;
}

jbyteArray to_byte_array(JNIEnv *env, const void *data, size_t size)
{
	jbyteArray array = env->NewByteArray(static_cast<jsize>(size));
	env->SetByteArrayRegion(array, 0, static_cast<jsize>(size), static_cast<const jbyte *>(data));
	return array;
}

// Copies a Java byte array into a fixed-size buffer. Fails if the lengths differ.
bool copy_exact(JNIEnv *env, jbyteArray array, void *out, size_t size)
{
	if(!array || static_cast<size_t>(env->GetArrayLength(array)) != size)
		return false;
	env->GetByteArrayRegion(array, 0, static_cast<jsize>(size), static_cast<jbyte *>(out));
	return true;
}

// ---------------------------------------------------------------------------------------------
// Discovery

const int DISCOVERY_FIELDS = 7;

struct Discovery
{
	ChiakiDiscoveryService service;
	jobject listener;
	jmethodID on_hosts;
};

void discovery_cb(ChiakiDiscoveryHost *hosts, size_t hosts_count, void *user)
{
	auto *discovery = static_cast<Discovery *>(user);
	ScopedEnv scoped;
	JNIEnv *env = scoped.env;
	if(!env)
		return;

	jclass string_class = env->FindClass("java/lang/String");
	jobjectArray array = env->NewObjectArray(static_cast<jsize>(hosts_count * DISCOVERY_FIELDS), string_class, nullptr);
	for(size_t i = 0; i < hosts_count; i++)
	{
		ChiakiDiscoveryHost *host = &hosts[i];
		const char *fields[DISCOVERY_FIELDS] = {
			chiaki_discovery_host_state_string(host->state),
			host->host_addr,
			host->host_name,
			host->host_type,
			host->host_id,
			chiaki_discovery_host_is_ps5(host) ? "1" : "0",
			host->running_app_name,
		};
		for(int f = 0; f < DISCOVERY_FIELDS; f++)
		{
			jstring value = utf8_string(env, fields[f]);
			env->SetObjectArrayElement(array, static_cast<jsize>(i * DISCOVERY_FIELDS + f), value);
			if(value)
				env->DeleteLocalRef(value);
		}
	}
	env->CallVoidMethod(discovery->listener, discovery->on_hosts, array);
	env->DeleteLocalRef(array);
	env->DeleteLocalRef(string_class);
}

// ---------------------------------------------------------------------------------------------
// Registration

struct Regist
{
	ChiakiRegist regist;
	jobject listener;
	jmethodID on_result;
};

void regist_cb(ChiakiRegistEvent *event, void *user)
{
	auto *regist = static_cast<Regist *>(user);
	ScopedEnv scoped;
	JNIEnv *env = scoped.env;
	if(!env)
		return;

	if(event->type != CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS || !event->registered_host)
	{
		jint status = event->type == CHIAKI_REGIST_EVENT_TYPE_FINISHED_CANCELED ? 2 : 1;
		env->CallVoidMethod(regist->listener, regist->on_result, status, nullptr, nullptr, nullptr, nullptr);
		return;
	}

	ChiakiRegisteredHost *host = event->registered_host;
	char nickname[sizeof(host->server_nickname) + 1] = {};
	memcpy(nickname, host->server_nickname, sizeof(host->server_nickname));
	jstring nickname_java = utf8_string(env, nickname);
	jbyteArray mac = to_byte_array(env, host->server_mac, sizeof(host->server_mac));
	jbyteArray regist_key = to_byte_array(env, host->rp_regist_key, sizeof(host->rp_regist_key));
	jbyteArray rp_key = to_byte_array(env, host->rp_key, sizeof(host->rp_key));
	env->CallVoidMethod(regist->listener, regist->on_result, 0, nickname_java, mac, regist_key, rp_key);
}

// ---------------------------------------------------------------------------------------------
// Session

struct Session
{
	ChiakiSession session;
	ChiakiLog log;
	AndroidChiakiVideoDecoder video;
	AndroidChiakiAudioDecoder audio;
	void *audio_output;
	jobject listener;
	jmethodID on_connected;
	jmethodID on_login_pin_request;
	jmethodID on_quit;
	jmethodID on_rumble;

	// Counted on the stream thread, read by the UI for diagnostics.
	std::atomic<int64_t> video_frames{0};
	std::atomic<int64_t> video_frames_lost{0};
	std::atomic<int64_t> video_frames_recovered{0};
	std::atomic<int64_t> video_bytes{0};

	// Haptics state, touched only by the stream thread.
	int haptic_left = 0;
	int haptic_right = 0;
	int haptic_frames_since_sent = 0;
	bool trigger_effects_logged = false;
};

bool count_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user)
{
	auto *session = static_cast<Session *>(user);
	session->video_frames++;
	session->video_bytes += static_cast<int64_t>(buf_size);
	if(frames_lost > 0)
		session->video_frames_lost += frames_lost;
	if(frame_recovered)
		session->video_frames_recovered++;
	return android_chiaki_video_decoder_video_sample(buf, buf_size, frames_lost, frame_recovered, &session->video);
}

// Haptics arrive as a low-rate stereo waveform (16-bit samples, left and right actuator). A gamepad
// reached through Android only has two rumble motors, so each short frame is reduced to how strong
// it is and sent as rumble: not the fine texture of the real thing, but the hits and rumbles are there.
void haptics_frame_cb(uint8_t *buf, size_t buf_size, void *user)
{
	auto *session = static_cast<Session *>(user);
	size_t frames = buf_size / 4;
	if(frames == 0)
		return;
	int64_t sum_left = 0, sum_right = 0;
	for(size_t i = 0; i < frames; i++)
	{
		int16_t left, right;
		memcpy(&left, buf + i * 4, 2);
		memcpy(&right, buf + i * 4 + 2, 2);
		sum_left += left < 0 ? -left : left;
		sum_right += right < 0 ? -right : right;
	}
	auto strength = [frames](int64_t sum) {
		double level = static_cast<double>(sum) / static_cast<double>(frames) / 32768.0 * 4.0;
		int value = static_cast<int>(level * 255.0);
		return value > 255 ? 255 : (value < 6 ? 0 : value);
	};
	int left = strength(sum_left);
	int right = strength(sum_right);

	// Send changes only, and no more often than a Bluetooth controller can follow.
	session->haptic_frames_since_sent++;
	bool stopped = left == 0 && right == 0 && (session->haptic_left != 0 || session->haptic_right != 0);
	int change = std::abs(left - session->haptic_left) + std::abs(right - session->haptic_right);
	if(!stopped && (change < 24 || session->haptic_frames_since_sent < 4))
		return;
	session->haptic_left = left;
	session->haptic_right = right;
	session->haptic_frames_since_sent = 0;

	ScopedEnv scoped;
	if(!scoped.env)
		return;
	scoped.env->CallVoidMethod(session->listener, session->on_rumble, static_cast<jint>(left), static_cast<jint>(right));
	if(scoped.env->ExceptionCheck())
		scoped.env->ExceptionClear();
}

void session_event_cb(ChiakiEvent *event, void *user)
{
	auto *session = static_cast<Session *>(user);
	ScopedEnv scoped;
	JNIEnv *env = scoped.env;
	if(!env)
		return;

	switch(event->type)
	{
		case CHIAKI_EVENT_CONNECTED:
			env->CallVoidMethod(session->listener, session->on_connected);
			break;
		case CHIAKI_EVENT_LOGIN_PIN_REQUEST:
			env->CallVoidMethod(session->listener, session->on_login_pin_request,
					static_cast<jboolean>(event->login_pin_request.pin_incorrect));
			break;
		case CHIAKI_EVENT_QUIT:
		{
			jstring name = utf8_string(env, chiaki_quit_reason_string(event->quit.reason));
			jstring detail = utf8_string(env, event->quit.reason_str);
			env->CallVoidMethod(session->listener, session->on_quit,
					static_cast<jint>(event->quit.reason),
					static_cast<jboolean>(chiaki_quit_reason_is_error(event->quit.reason)),
					name, detail);
			break;
		}
		case CHIAKI_EVENT_RUMBLE:
			env->CallVoidMethod(session->listener, session->on_rumble,
					static_cast<jint>(event->rumble.left), static_cast<jint>(event->rumble.right));
			break;
		case CHIAKI_EVENT_TRIGGER_EFFECTS:
			// Adaptive triggers need raw access to the controller, which Android does not give over Bluetooth.
			if(!session->trigger_effects_logged)
			{
				session->trigger_effects_logged = true;
				CHIAKI_LOGI(&session->log, "Console sent adaptive trigger effects (not applied)");
			}
			break;
		default:
			break;
	}
}

} // namespace

extern "C" {

JNIEnv *attach_thread_jni()
{
	JNIEnv *env = nullptr;
	if(global_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK)
		return env;
	if(global_vm->AttachCurrentThread(&env, nullptr) == JNI_OK)
		return env;
	return nullptr;
}

jobject jnistr_from_ascii(JNIEnv *env, const char *str)
{
	return utf8_string(env, str);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *)
{
	global_vm = vm;
	android_chiaki_file_log_init(&g_log, CHIAKI_LOG_ALL & ~CHIAKI_LOG_VERBOSE, nullptr);
	ChiakiErrorCode err = chiaki_lib_init();
	CHIAKI_LOGI(&g_log, "libchiaki init: %s", chiaki_error_string(err));
	return JNI_VERSION_1_6;
}

// Random device id that PSN ties to this client; sent with the login URL.
JNIEXPORT jstring JNICALL NATIVE(generateDuid)(JNIEnv *env, jclass)
{
	char duid[CHIAKI_DUID_STR_SIZE] = {};
	size_t size = sizeof(duid);
	if(chiaki_holepunch_generate_client_device_uid(duid, &size) != CHIAKI_ERR_SUCCESS)
		return nullptr;
	return env->NewStringUTF(duid);
}

// --- Discovery ---

JNIEXPORT jlong JNICALL NATIVE(discoveryStart)(JNIEnv *env, jclass, jstring subnet_broadcast, jobject listener)
{
	auto *discovery = new Discovery();

	sockaddr_in send_addr = {};
	send_addr.sin_family = AF_INET;
	send_addr.sin_addr.s_addr = htonl(INADDR_BROADCAST);

	// Some routers drop 255.255.255.255, so the subnet broadcast address is pinged as well.
	sockaddr_storage extra = {};
	size_t extra_count = 0;
	std::string subnet = to_std_string(env, subnet_broadcast);
	if(!subnet.empty())
	{
		auto *extra_in = reinterpret_cast<sockaddr_in *>(&extra);
		extra_in->sin_family = AF_INET;
		if(inet_pton(AF_INET, subnet.c_str(), &extra_in->sin_addr) == 1)
			extra_count = 1;
	}

	ChiakiDiscoveryServiceOptions options = {};
	options.hosts_max = 16;
	options.host_drop_pings = 3;
	options.ping_ms = 500;
	options.ping_initial_ms = 50;
	options.send_addr = reinterpret_cast<sockaddr_storage *>(&send_addr);
	options.send_addr_size = sizeof(send_addr);
	options.broadcast_addrs = extra_count ? &extra : nullptr;
	options.broadcast_num = extra_count;
	options.cb = discovery_cb;
	options.cb_user = discovery;

	discovery->listener = env->NewGlobalRef(listener);
	jclass listener_class = env->GetObjectClass(listener);
	discovery->on_hosts = env->GetMethodID(listener_class, "onHosts", "([Ljava/lang/String;)V");

	ChiakiErrorCode err = chiaki_discovery_service_init(&discovery->service, &options, &g_log);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(&g_log, "Discovery failed to start: %s", chiaki_error_string(err));
		env->DeleteGlobalRef(discovery->listener);
		delete discovery;
		return 0;
	}
	return reinterpret_cast<jlong>(discovery);
}

JNIEXPORT void JNICALL NATIVE(discoveryStop)(JNIEnv *env, jclass, jlong ptr)
{
	auto *discovery = reinterpret_cast<Discovery *>(ptr);
	if(!discovery)
		return;
	chiaki_discovery_service_fini(&discovery->service);
	env->DeleteGlobalRef(discovery->listener);
	delete discovery;
}

JNIEXPORT jboolean JNICALL NATIVE(wakeup)(JNIEnv *env, jclass, jlong discovery_ptr, jstring host, jlong credential, jboolean ps5)
{
	auto *discovery = reinterpret_cast<Discovery *>(discovery_ptr);
	std::string host_str = to_std_string(env, host);
	ChiakiErrorCode err = chiaki_discovery_wakeup(&g_log,
			discovery ? &discovery->service.discovery : nullptr,
			host_str.c_str(), static_cast<uint64_t>(credential), ps5);
	return err == CHIAKI_ERR_SUCCESS;
}

// --- Registration ---

JNIEXPORT jlong JNICALL NATIVE(registStart)(JNIEnv *env, jclass, jstring host, jboolean broadcast, jbyteArray account_id, jint pin, jobject listener)
{
	std::string host_str = to_std_string(env, host);

	ChiakiRegistInfo info = {};
	info.target = CHIAKI_TARGET_PS5_1;
	info.host = host_str.c_str(); // copied by chiaki_regist_start
	info.broadcast = broadcast;
	info.pin = static_cast<uint32_t>(pin);
	if(!copy_exact(env, account_id, info.psn_account_id, sizeof(info.psn_account_id)))
	{
		CHIAKI_LOGE(&g_log, "Regist: account id must be %d bytes", CHIAKI_PSN_ACCOUNT_ID_SIZE);
		return 0;
	}

	auto *regist = new Regist();
	regist->listener = env->NewGlobalRef(listener);
	jclass listener_class = env->GetObjectClass(listener);
	regist->on_result = env->GetMethodID(listener_class, "onRegistResult", "(ILjava/lang/String;[B[B[B)V");

	ChiakiErrorCode err = chiaki_regist_start(&regist->regist, &g_log, &info, regist_cb, regist);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(&g_log, "Regist failed to start: %s", chiaki_error_string(err));
		env->DeleteGlobalRef(regist->listener);
		delete regist;
		return 0;
	}
	return reinterpret_cast<jlong>(regist);
}

// Stops (if still running), joins the worker and frees. Must not be called from onRegistResult.
JNIEXPORT void JNICALL NATIVE(registFree)(JNIEnv *env, jclass, jlong ptr)
{
	auto *regist = reinterpret_cast<Regist *>(ptr);
	if(!regist)
		return;
	chiaki_regist_stop(&regist->regist);
	chiaki_regist_fini(&regist->regist);
	env->DeleteGlobalRef(regist->listener);
	delete regist;
}

// --- Session ---

JNIEXPORT jlong JNICALL NATIVE(sessionCreate)(JNIEnv *env, jclass, jstring host, jbyteArray regist_key, jbyteArray morning,
		jint resolution_preset, jint fps, jint bitrate_kbps, jint codec, jobject listener)
{
	std::string host_str = to_std_string(env, host);

	ChiakiConnectInfo info = {};
	info.ps5 = true;
	info.host = host_str.c_str(); // copied by chiaki_session_init
	if(!copy_exact(env, regist_key, info.regist_key, sizeof(info.regist_key))
		|| !copy_exact(env, morning, info.morning, sizeof(info.morning)))
	{
		CHIAKI_LOGE(&g_log, "Session: registration keys have the wrong length");
		return 0;
	}
	chiaki_connect_video_profile_preset(&info.video_profile,
			static_cast<ChiakiVideoResolutionPreset>(resolution_preset),
			static_cast<ChiakiVideoFPSPreset>(fps));
	if(bitrate_kbps > 0)
		info.video_profile.bitrate = static_cast<unsigned int>(bitrate_kbps);
	info.video_profile.codec = static_cast<ChiakiCodec>(codec);
	info.video_profile_auto_downgrade = true;
	info.enable_idr_on_fec_failure = true;
	// As a DualSense the console sends haptics, which PS5 games use instead of the old rumble commands.
	info.enable_dualsense = true;

	auto *session = new Session();
	android_chiaki_file_log_init(&session->log, CHIAKI_LOG_ALL & ~CHIAKI_LOG_VERBOSE, nullptr);

	ChiakiErrorCode err = android_chiaki_video_decoder_init(&session->video, &session->log,
			static_cast<int32_t>(info.video_profile.width), static_cast<int32_t>(info.video_profile.height),
			info.video_profile.codec);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		delete session;
		return 0;
	}

	err = android_chiaki_audio_decoder_init(&session->audio, &session->log);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		android_chiaki_video_decoder_fini(&session->video);
		delete session;
		return 0;
	}

	session->audio_output = android_chiaki_audio_output_new(&session->log);
	android_chiaki_audio_decoder_set_cb(&session->audio, android_chiaki_audio_output_settings,
			android_chiaki_audio_output_frame, session->audio_output);

	err = chiaki_session_init(&session->session, &info, &session->log);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(&g_log, "Session failed to init: %s", chiaki_error_string(err));
		android_chiaki_video_decoder_fini(&session->video);
		android_chiaki_audio_decoder_fini(&session->audio);
		android_chiaki_audio_output_free(session->audio_output);
		delete session;
		return 0;
	}

	session->listener = env->NewGlobalRef(listener);
	jclass listener_class = env->GetObjectClass(listener);
	session->on_connected = env->GetMethodID(listener_class, "onConnected", "()V");
	session->on_login_pin_request = env->GetMethodID(listener_class, "onLoginPinRequest", "(Z)V");
	session->on_quit = env->GetMethodID(listener_class, "onQuit", "(IZLjava/lang/String;Ljava/lang/String;)V");
	session->on_rumble = env->GetMethodID(listener_class, "onRumble", "(II)V");

	chiaki_session_set_event_cb(&session->session, session_event_cb, session);
	chiaki_session_set_video_sample_cb(&session->session, count_video_sample, session);

	ChiakiAudioSink audio_sink;
	android_chiaki_audio_decoder_get_sink(&session->audio, &audio_sink);
	chiaki_session_set_audio_sink(&session->session, &audio_sink);

	ChiakiAudioSink haptics_sink = {};
	haptics_sink.user = session;
	haptics_sink.frame_cb = haptics_frame_cb;
	chiaki_session_set_haptics_sink(&session->session, &haptics_sink);

	return reinterpret_cast<jlong>(session);
}

JNIEXPORT jboolean JNICALL NATIVE(sessionStart)(JNIEnv *, jclass, jlong ptr)
{
	auto *session = reinterpret_cast<Session *>(ptr);
	return session && chiaki_session_start(&session->session) == CHIAKI_ERR_SUCCESS;
}

JNIEXPORT void JNICALL NATIVE(sessionStop)(JNIEnv *, jclass, jlong ptr)
{
	auto *session = reinterpret_cast<Session *>(ptr);
	if(session)
		chiaki_session_stop(&session->session);
}

// Stops, joins and frees. Must not be called from a session listener callback.
JNIEXPORT void JNICALL NATIVE(sessionFree)(JNIEnv *env, jclass, jlong ptr)
{
	auto *session = reinterpret_cast<Session *>(ptr);
	if(!session)
		return;
	chiaki_session_stop(&session->session);
	chiaki_session_join(&session->session);
	chiaki_session_fini(&session->session);
	android_chiaki_video_decoder_fini(&session->video);
	android_chiaki_audio_decoder_fini(&session->audio);
	android_chiaki_audio_output_free(session->audio_output);
	android_chiaki_file_log_fini(&session->log);
	env->DeleteGlobalRef(session->listener);
	delete session;
}

JNIEXPORT void JNICALL NATIVE(sessionSetSurface)(JNIEnv *env, jclass, jlong ptr, jobject surface)
{
	auto *session = reinterpret_cast<Session *>(ptr);
	if(session)
		android_chiaki_video_decoder_set_surface(&session->video, env, surface);
}

JNIEXPORT void JNICALL NATIVE(sessionSetLoginPin)(JNIEnv *env, jclass, jlong ptr, jstring pin)
{
	auto *session = reinterpret_cast<Session *>(ptr);
	if(!session)
		return;
	std::string pin_str = to_std_string(env, pin);
	chiaki_session_set_login_pin(&session->session, reinterpret_cast<const uint8_t *>(pin_str.data()), pin_str.size());
}

// {measured bitrate in Mbit/s, packet loss 0..1, round trip in ms, frames, frames lost, frames recovered, video bytes}.
// Read without locks: these are plain numbers written by the stream threads, and a stale value is harmless.
JNIEXPORT jdoubleArray JNICALL NATIVE(sessionStats)(JNIEnv *env, jclass, jlong ptr)
{
	auto *session = reinterpret_cast<Session *>(ptr);
	jdoubleArray result = env->NewDoubleArray(7);
	if(!session || !result)
		return result;
	const jdouble values[7] = {
		session->session.stream_connection.measured_bitrate,
		session->session.stream_connection.congestion_control.packet_loss,
		static_cast<double>(session->session.rtt_us) / 1000.0,
		static_cast<double>(session->video_frames.load()),
		static_cast<double>(session->video_frames_lost.load()),
		static_cast<double>(session->video_frames_recovered.load()),
		static_cast<double>(session->video_bytes.load()),
	};
	env->SetDoubleArrayRegion(result, 0, 7, values);
	return result;
}

// touches: {id0, x0, y0, id1, x1, y1}, id < 0 means not touching. May be null.
// motion: {gyro xyz, accel xyz, orientation xyzw}. May be null.
JNIEXPORT void JNICALL NATIVE(sessionSetController)(JNIEnv *env, jclass, jlong ptr, jint buttons, jint l2, jint r2,
		jint left_x, jint left_y, jint right_x, jint right_y, jintArray touches, jfloatArray motion)
{
	auto *session = reinterpret_cast<Session *>(ptr);
	if(!session)
		return;

	ChiakiControllerState state;
	chiaki_controller_state_set_idle(&state);
	state.buttons = static_cast<uint32_t>(buttons);
	state.l2_state = static_cast<uint8_t>(l2);
	state.r2_state = static_cast<uint8_t>(r2);
	state.left_x = static_cast<int16_t>(left_x);
	state.left_y = static_cast<int16_t>(left_y);
	state.right_x = static_cast<int16_t>(right_x);
	state.right_y = static_cast<int16_t>(right_y);

	if(touches && env->GetArrayLength(touches) == CHIAKI_CONTROLLER_TOUCHES_MAX * 3)
	{
		jint values[CHIAKI_CONTROLLER_TOUCHES_MAX * 3];
		env->GetIntArrayRegion(touches, 0, CHIAKI_CONTROLLER_TOUCHES_MAX * 3, values);
		for(int i = 0; i < CHIAKI_CONTROLLER_TOUCHES_MAX; i++)
		{
			state.touches[i].id = static_cast<int8_t>(values[i * 3]);
			state.touches[i].x = static_cast<uint16_t>(values[i * 3 + 1]);
			state.touches[i].y = static_cast<uint16_t>(values[i * 3 + 2]);
		}
	}

	if(motion && env->GetArrayLength(motion) == 10)
	{
		jfloat values[10];
		env->GetFloatArrayRegion(motion, 0, 10, values);
		state.gyro_x = values[0];
		state.gyro_y = values[1];
		state.gyro_z = values[2];
		state.accel_x = values[3];
		state.accel_y = values[4];
		state.accel_z = values[5];
		state.orient_x = values[6];
		state.orient_y = values[7];
		state.orient_z = values[8];
		state.orient_w = values[9];
	}

	chiaki_session_set_controller_state(&session->session, &state);
}

} // extern "C"
