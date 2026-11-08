// NeuroScribe extension to sherpa-onnx 1.13.8. Apache-2.0.
#include "sherpa-onnx/csrc/offline-speaker-diarization.h"
#include "sherpa-onnx/jni/common.h"
#include <exception>
#include <vector>

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_k2fsa_sherpa_onnx_NeuroEvidence_process(
    JNIEnv *env, jobject, jobject diarizer, jfloatArray samples) {
  const auto cls = env->GetObjectClass(diarizer);
  const auto field = env->GetFieldID(cls, "ptr", "J");
  if (!field || env->ExceptionCheck()) return nullptr;
  auto *sd = reinterpret_cast<sherpa_onnx::OfflineSpeakerDiarization *>(
      env->GetLongField(diarizer, field));
  const auto n = env->GetArrayLength(samples);
  if (!sd || n <= 0 || n > 1200 * 16000) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid diarization evidence input");
    return nullptr;
  }
  // Match the original JNI's array access; do not allocate another whole audio
  // vector (a20-minute16k recording alone is76.8MB).
  struct Samples {
    JNIEnv *env; jfloatArray array; jfloat *data;
    ~Samples() { if (data) env->ReleaseFloatArrayElements(array,data,JNI_ABORT); }
  } audio{env,samples,env->GetFloatArrayElements(samples,nullptr)};
  if (!audio.data || env->ExceptionCheck()) return nullptr;
  struct Capture {
    bool old = sherpa_onnx::neuro_capture_evidence;
    Capture() { sherpa_onnx::neuro_capture_evidence = true; }
    ~Capture() { sherpa_onnx::neuro_capture_evidence = old; }
  } capture;
  try {
    auto result = sd->Process(audio.data, n);
    auto segments = result.SortByStartTime();
    const auto &records = result.neuro_evidence;
    const auto dim = records.empty() ? 0 : records.front().embedding.size();
    std::vector<float> packed{1, static_cast<float>(sd->SampleRate()),
        static_cast<float>(dim), static_cast<float>(records.size()), static_cast<float>(segments.size())};
    for (const auto &s : segments) {
      packed.insert(packed.end(), {s.Start(), s.End(), static_cast<float>(s.Speaker()), s.Confidence()});
    }
    for (const auto &r : records) {
      packed.insert(packed.end(), {static_cast<float>(r.chunk), static_cast<float>(r.local),
          static_cast<float>(r.cluster), static_cast<float>(r.clean.size()), static_cast<float>(r.active.size())});
      packed.insert(packed.end(), r.embedding.begin(), r.embedding.end());
      for (const auto &p : r.clean) packed.insert(packed.end(), {p.first / 16000.f, p.second / 16000.f});
      for (const auto &p : r.active) packed.insert(packed.end(), {p.first / 16000.f, p.second / 16000.f});
    }
    auto output = env->NewFloatArray(packed.size());
    if (output) env->SetFloatArrayRegion(output, 0, packed.size(), packed.data());
    return output;
  } catch (const std::exception &e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
    return nullptr;
  }
}
