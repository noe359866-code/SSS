// Puente JNI: expone la API C del núcleo a Kotlin.
// Cada función devuelve un String JSON y libera la memoria nativa en el acto.
#include <jni.h>

#include <string>

#include "cineflux/capi.h"

namespace {

jstring to_java(JNIEnv* env, const char* json) {
  if (!json) return env->NewStringUTF("");
  jstring result = env->NewStringUTF(json);
  cfx_free(const_cast<char*>(json));
  return result;
}

std::string from_java(JNIEnv* env, jstring value) {
  if (!value) return "";
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) return "";
  std::string out(chars);
  env->ReleaseStringUTFChars(value, chars);
  return out;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_app_cineflux_core_NativeCore_version(JNIEnv* env, jobject /*thiz*/) {
  return env->NewStringUTF(cfx_version());
}

JNIEXPORT jstring JNICALL
Java_app_cineflux_core_NativeCore_defaultProfiles(JNIEnv* env, jobject /*thiz*/) {
  return to_java(env, cfx_default_profiles());
}

JNIEXPORT jstring JNICALL
Java_app_cineflux_core_NativeCore_parseManifest(JNIEnv* env, jobject /*thiz*/,
                                                jstring manifest, jstring base_url) {
  return to_java(env, cfx_parse_manifest(from_java(env, manifest).c_str(),
                                         from_java(env, base_url).c_str()));
}

JNIEXPORT jstring JNICALL
Java_app_cineflux_core_NativeCore_planRequests(JNIEnv* env, jobject /*thiz*/, jstring request) {
  return to_java(env, cfx_plan_requests(from_java(env, request).c_str()));
}

JNIEXPORT jstring JNICALL
Java_app_cineflux_core_NativeCore_rankStreams(JNIEnv* env, jobject /*thiz*/, jstring request) {
  return to_java(env, cfx_rank_streams(from_java(env, request).c_str()));
}

JNIEXPORT jstring JNICALL
Java_app_cineflux_core_NativeCore_planCatalog(JNIEnv* env, jobject /*thiz*/, jstring request) {
  return to_java(env, cfx_plan_catalog(from_java(env, request).c_str()));
}

JNIEXPORT jstring JNICALL
Java_app_cineflux_core_NativeCore_skipSegments(JNIEnv* env, jobject /*thiz*/, jstring request) {
  return to_java(env, cfx_skip_segments(from_java(env, request).c_str()));
}

JNIEXPORT jstring JNICALL
Java_app_cineflux_core_NativeCore_parseJsRepo(JNIEnv* env, jobject /*thiz*/,
                                              jstring repo_json, jstring base_url) {
  return to_java(env, cfx_parse_js_repo(from_java(env, repo_json).c_str(),
                                        from_java(env, base_url).c_str()));
}

JNIEXPORT jstring JNICALL
Java_app_cineflux_core_NativeCore_normalizeJsStreams(JNIEnv* env, jobject /*thiz*/,
                                                     jstring raw_json, jstring plugin_name) {
  return to_java(env, cfx_normalize_js_streams(from_java(env, raw_json).c_str(),
                                               from_java(env, plugin_name).c_str()));
}

}  // extern "C"
