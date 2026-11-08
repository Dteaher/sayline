// NeuroScribe research: constrained local-track average linkage. Apache-2.0.
#pragma once
#include <algorithm>
#include <cstdint>
#include <utility>
#include <vector>
#include <numeric>

namespace sherpa_onnx {
inline thread_local std::vector<std::pair<int32_t,int32_t>> neuro_research_forbidden;

template<class Labels,class Pairs,class Samples>
void NeuroSetLocalConstraints(const Labels &labels,const Pairs &pairs,const Samples &samples,
    int32_t n,int32_t rate,int32_t step,int32_t frame_shift) {
  neuro_research_forbidden.clear();
  auto clean = [&](int32_t i) {
    int64_t size=0;
    for(const auto &p:samples[i]) size+=std::max(0,std::min(n,p.second)-std::max(0,p.first));
    return size;
  };
  for(int32_t i=0;i<static_cast<int32_t>(pairs.size());++i) {
    const auto chunk=pairs[i].first;const auto local=pairs[i].second;
    for(int32_t j=i+1;j<static_cast<int32_t>(pairs.size()) && pairs[j].first==chunk;++j) {
      if(local==pairs[j].second)continue;
      const auto a=clean(i),b=clean(j);
      bool distinct=a>=rate*1.5 && b>=rate*1.5;
      if(!distinct && a>=rate*.5 && b>=rate*.5) {
        int64_t shared=0;
        for(int32_t f=0;f<labels[chunk].rows() && chunk*step+f*frame_shift<n;++f)
          if(labels[chunk](f,local) && labels[chunk](f,pairs[j].second))shared+=frame_shift;
        distinct=shared>=rate*.2;
      }
      if(distinct)neuro_research_forbidden.emplace_back(i,j);
    }
  }
}

inline void NeuroPenalizeLocalConflicts(std::vector<double> *distance,int32_t n) {
  // For the app's <=4096 rows this cannot dilute below any legal cosine cut.
  // Finite values keep upstream fastcluster's full-tree construction defined.
  for(const auto &p:neuro_research_forbidden) {
    const auto i=p.first,j=p.second;
    const auto index=static_cast<size_t>(i)*n-static_cast<size_t>(i)*(i+1)/2+j-i-1;
    if(i>=0 && j>i && j<n && index<distance->size())(*distance)[index]=1e12;
  }
}

// Neighbouring windows see much of the SAME physical clean waveform. Pool only
// strong matches, and never join contradictory local tracks of one window.
template<class Matrix,class Pairs,class Samples>
Matrix NeuroPoolSameSpeech(Matrix *embeddings,const Pairs &pairs,const Samples &samples,
    int32_t n,int32_t rate,std::vector<int32_t> *membership) {
  const int32_t rows=embeddings->rows();
  embeddings->rowwise().normalize();
  std::vector<int32_t> parent(rows);std::iota(parent.begin(),parent.end(),0);
  std::vector<std::vector<int32_t>> members(rows);
  for(int32_t i=0;i<rows;++i)members[i].push_back(i);
  auto root=[&](int32_t i) { while(parent[i]!=i) { parent[i]=parent[parent[i]];i=parent[i]; } return i; };
  auto length=[&](int32_t i) { int64_t total=0;for(auto p:samples[i])total+=std::max(0,std::min(n,p.second)-std::max(0,p.first));return total; };
  struct Edge { double score;int32_t a,b; };
  std::vector<Edge> edges;
  for(int32_t i=0;i<rows;++i)for(int32_t j=i+1;j<rows && pairs[j].first-pairs[i].first<=3;++j) {
    if(pairs[i].first==pairs[j].first)continue;
    int64_t shared=0;
    for(auto a:samples[i])for(auto b:samples[j])shared+=std::max(0,std::min({n,a.second,b.second})-std::max({0,a.first,b.first}));
    const auto maximum=std::max(length(i),length(j));
    if(maximum<=0 || shared<rate || shared/static_cast<double>(maximum)<.6)continue;
    const double similarity=embeddings->row(i).dot(embeddings->row(j));
    if(similarity>=.65)edges.push_back({shared/static_cast<double>(maximum),i,j});
  }
  std::sort(edges.begin(),edges.end(),[](const Edge&a,const Edge&b) {
    if(a.score!=b.score)return a.score>b.score;
    return a.a!=b.a?a.a<b.a:a.b<b.b;
  });
  for(auto e:edges) {
    auto a=root(e.a),b=root(e.b);if(a==b)continue;
    bool incompatible=false;
    for(auto i:members[a])for(auto j:members[b])if(pairs[i].first==pairs[j].first && pairs[i].second!=pairs[j].second)incompatible=true;
    if(incompatible)continue;
    if(members[a].size()<members[b].size())std::swap(a,b);
    parent[b]=a;members[a].insert(members[a].end(),members[b].begin(),members[b].end());members[b].clear();
  }
  std::vector<int32_t> component(rows,-1);membership->resize(rows);int32_t count=0;
  for(int32_t i=0;i<rows;++i) { auto r=root(i);if(component[r]<0)component[r]=count++;(*membership)[i]=component[r]; }
  Matrix pooled=Matrix::Zero(count,embeddings->cols());std::vector<int32_t> sizes(count,0);
  for(int32_t i=0;i<rows;++i) { pooled.row((*membership)[i])+=embeddings->row(i);++sizes[(*membership)[i]]; }
  for(int32_t i=0;i<count;++i)pooled.row(i)/=sizes[i];
  std::vector<std::pair<int32_t,int32_t>> remapped;
  for(auto p:neuro_research_forbidden) {
    auto a=(*membership)[p.first],b=(*membership)[p.second];if(a>b)std::swap(a,b);
    if(a!=b)remapped.emplace_back(a,b);
  }
  std::sort(remapped.begin(),remapped.end());remapped.erase(std::unique(remapped.begin(),remapped.end()),remapped.end());
  neuro_research_forbidden=std::move(remapped);
  return pooled;
}
}  // namespace sherpa_onnx
