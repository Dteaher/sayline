"""Apply a small reviewed extension to the pinned official sherpa source archive."""
import argparse
import hashlib
from pathlib import Path
import shutil
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
SOURCE_SHA='b63b7613812346f2d1396a3a7f94accd47539e3dadc3ea385ba6474c70ab9897'
def main():
    p=argparse.ArgumentParser();p.add_argument('destination',type=Path);args=p.parse_args()
    archive=ROOT/'.work/sherpa-native-source-v1.13.8.zip'
    if not archive.exists():urllib.request.urlretrieve('https://codeload.github.com/k2-fsa/sherpa-onnx/zip/refs/tags/v1.13.8',archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=SOURCE_SHA:raise ValueError('Pinned source mismatch')
    source=args.destination/'sherpa-onnx-1.13.8'
    if source.exists():raise ValueError('Use a fresh destination to avoid mixing source changes')
    args.destination.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive) as z:z.extractall(args.destination)
    def edit(relative,old,new):
        path=source/relative;text=path.read_text(encoding='utf-8')
        if text.count(old)!=1:raise ValueError('Unexpected source anchor: '+relative)
        path.write_text(text.replace(old,new),encoding='utf-8')
    edit('sherpa-onnx/csrc/offline-speaker-diarization-result.h',
         'class OfflineSpeakerDiarizationResult {\n public:',
         '''inline thread_local bool neuro_capture_evidence = false;
struct NeuroSpeakerEvidence {
  int32_t chunk, local, cluster;
  std::vector<float> embedding;
  std::vector<std::pair<int32_t,int32_t>> clean, active;
};
class OfflineSpeakerDiarizationResult {
 public:
  std::vector<NeuroSpeakerEvidence> neuro_evidence;''')
    edit('sherpa-onnx/csrc/offline-speaker-diarization-result.h','#include <vector>','#include <vector>\n#include <utility>')
    edit('sherpa-onnx/csrc/offline-speaker-diarization-pyannote-impl.h',
         '    auto result = ComputeResult(final_labels, cluster_intervals);',
         '''    auto result = ComputeResult(final_labels, cluster_intervals);
    if (neuro_capture_evidence) {
      const auto &meta = segmentation_model_.GetModelMetaData();
      for (int32_t i = 0; i < embeddings.rows(); ++i) {
        NeuroSpeakerEvidence record;
        record.chunk = chunk_speaker_samples_list_pair.first[i].first;
        record.local = chunk_speaker_samples_list_pair.first[i].second;
        record.cluster = cluster_labels[i];
        record.embedding.resize(embeddings.cols());
        for (int32_t d = 0; d < embeddings.cols(); ++d) record.embedding[d] = embeddings(i,d);
        for (auto p : chunk_speaker_samples_list_pair.second[i]) {
          p.first = std::max(0, std::min(n,p.first));
          p.second = std::max(p.first,std::min(n,p.second));
          if (p.second > p.first) record.clean.push_back(p);
        }
        const auto &activity = labels[record.chunk];
        int32_t begin = -1;
        for (int32_t f = 0; f <= activity.rows(); ++f) {
          bool on = f < activity.rows() && activity(f,record.local) != 0;
          if (on && begin < 0) begin = f;
          if (!on && begin >= 0) {
            int32_t a = record.chunk * meta.window_shift + static_cast<float>(begin) / activity.rows() * meta.window_size;
            int32_t b = record.chunk * meta.window_shift + static_cast<float>(f) / activity.rows() * meta.window_size;
            a = std::max(0,std::min(n,a)); b = std::max(a,std::min(n,b));
            if (b > a) record.active.emplace_back(a,b);
            begin = -1;
          }
        }
        result.neuro_evidence.push_back(std::move(record));
      }
    }''')
    edit('sherpa-onnx/jni/CMakeLists.txt','  offline-speaker-diarization.cc\n','  offline-speaker-diarization.cc\n    evidence-jni.cc\n')
    shutil.copy2(ROOT/'native/evidence-jni.cc',source/'sherpa-onnx/jni/evidence-jni.cc')
    print(source)
if __name__=='__main__':main()
