// Synthetic native algorithm regression, NOT an audio accuracy measurement.
#include <Eigen/Core>
#include "research-local-constraints.h"
#include <cstdio>

int main() {
  using Matrix=Eigen::Matrix<float,Eigen::Dynamic,Eigen::Dynamic,Eigen::RowMajor>;
  using Mask=Eigen::Matrix<int32_t,Eigen::Dynamic,Eigen::Dynamic,Eigen::RowMajor>;
  std::vector<std::pair<int32_t,int32_t>> pairs{{0,0},{1,0},{1,1},{2,0}};
  std::vector<std::vector<std::pair<int32_t,int32_t>>> samples{
    {{10000,50000}},{{16000,56000}},{{60000,90000}},{{32000,72000}}};
  std::vector<Mask> labels(3,Mask::Zero(1000,2));
  for(size_t i=0;i<pairs.size();++i)for(auto span:samples[i]) {
    int32_t offset=pairs[i].first*16000;
    for(int32_t f=0;f<1000;++f)if(offset+f*160>=span.first && offset+f*160<span.second)
      labels[pairs[i].first](f,pairs[i].second)=1;
  }
  Matrix embeddings(4,2);embeddings<<1,0,1,0,0,1,1,0;
  sherpa_onnx::NeuroSetLocalConstraints(labels,pairs,samples,120000,16000,16000,160);
  std::vector<int32_t> membership;
  auto pooled=sherpa_onnx::NeuroPoolSameSpeech(&embeddings,pairs,samples,120000,16000,&membership);
  if(pooled.rows()!=2 || membership[0]!=membership[1] || membership[0]!=membership[3] || membership[0]==membership[2])return 1;
  std::vector<double> distance{.25};sherpa_onnx::NeuroPenalizeLocalConflicts(&distance,2);
  if(distance[0]<1e10)return 2;
  std::puts("PASS: shared-waveform pooling, distinct local-track preservation, condensed-distance constraint");
  return 0;
}
