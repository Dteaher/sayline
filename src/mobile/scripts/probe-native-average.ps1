param([switch]$LocalConstraints,[switch]$PoolSameSpeech,[switch]$CompleteLinkage)
$ErrorActionPreference='Stop'
if($PoolSameSpeech) { $LocalConstraints=$true }
. (Join-Path $PSScriptRoot 'env.ps1')
$nativeRoot=Join-Path $env:LOCALAPPDATA 'NeuroScribe'
$source=Join-Path $nativeRoot 'native-evidence-v1/sherpa-onnx-1.13.8/sherpa-onnx/csrc/fast-clustering.cc'
$original=Get-Content -LiteralPath $source -Raw
$pyannote=Join-Path $nativeRoot 'native-evidence-v1/sherpa-onnx-1.13.8/sherpa-onnx/csrc/offline-speaker-diarization-pyannote-impl.h'
$originalPyannote=Get-Content -LiteralPath $pyannote -Raw
if (($original.Split('HCLUST_METHOD_COMPLETE').Length-1) -ne 1) { throw 'Unexpected clustering source' }
$cmake=Join-Path $env:ANDROID_HOME 'cmake/3.22.1/bin/cmake.exe'
$build=Join-Path $nativeRoot 'native-evidence-build/x86_64'
try {
    $modified=if($CompleteLinkage) { $original } else { $original.Replace('HCLUST_METHOD_COMPLETE','HCLUST_METHOD_AVERAGE') }
    if($LocalConstraints) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../native/research-local-constraints.h') -Destination (Join-Path (Split-Path $source) 'neuro-research-local-constraints.h') -Force
        $include='#include "sherpa-onnx/csrc/neuro-research-local-constraints.h"' + "`n"
        $modified=$include+$modified
        $modified=$modified.Replace('    std::vector<int32_t> merge(2 * (num_rows - 1));',
            '    auto neuro_plain_distance = silhouettes ? distance : std::vector<double>{};' + "`n" +
            '    NeuroPenalizeLocalConflicts(&distance,num_rows);' + "`n" +
            '    std::vector<int32_t> merge(2 * (num_rows - 1));')
        $modified=$modified.Replace('ComputeSilhouettes(distance, labels, num_rows, silhouettes);',
            'ComputeSilhouettes(neuro_plain_distance.empty() ? distance : neuro_plain_distance, labels, num_rows, silhouettes);')
        $before='    std::vector<float> silhouettes;'
        if(($originalPyannote.Split($before).Length-1) -ne 1) { throw 'Unexpected pyannote source' }
        $setup=@'
    const auto &neuro_meta = segmentation_model_.GetModelMetaData();
    NeuroSetLocalConstraints(labels,chunk_speaker_samples_list_pair.first,
        chunk_speaker_samples_list_pair.second,n,neuro_meta.sample_rate,
        neuro_meta.window_shift,neuro_meta.receptive_field_shift);
    std::vector<float> silhouettes;
'@
        $modifiedPyannote=$include+$originalPyannote.Replace($before,$setup)
        if($PoolSameSpeech) {
            $oldCluster=@'
    std::vector<int32_t> cluster_labels = clustering_->Cluster(
        &embeddings(0, 0), embeddings.rows(), embeddings.cols(),
        config_.clustering.compute_confidence ? &silhouettes : nullptr);
'@
            $newCluster=@'
    std::vector<int32_t> neuro_membership;
    Matrix2D neuro_pooled = NeuroPoolSameSpeech(&embeddings,
        chunk_speaker_samples_list_pair.first,chunk_speaker_samples_list_pair.second,
        n,neuro_meta.sample_rate,&neuro_membership);
    std::vector<float> neuro_component_silhouettes;
    std::vector<int32_t> neuro_component_labels;
    if(neuro_pooled.rows()>0)neuro_component_labels=clustering_->Cluster(
        &neuro_pooled(0,0),neuro_pooled.rows(),neuro_pooled.cols(),
        config_.clustering.compute_confidence ? &neuro_component_silhouettes : nullptr);
    std::vector<int32_t> cluster_labels(embeddings.rows());
    if(config_.clustering.compute_confidence)silhouettes.assign(embeddings.rows(),-2.f);
    for(int32_t i=0;i<embeddings.rows();++i) {
      cluster_labels[i]=neuro_component_labels[neuro_membership[i]];
      if(config_.clustering.compute_confidence)silhouettes[i]=neuro_component_silhouettes[neuro_membership[i]];
    }
'@
            $modifiedPyannote=$modifiedPyannote.Replace("`r`n","`n")
            $oldCluster=$oldCluster.Replace("`r`n","`n")
            if(!$modifiedPyannote.Contains($oldCluster)) { throw 'Unexpected native clustering call' }
            $modifiedPyannote=$modifiedPyannote.Replace($oldCluster,$newCluster)
        }
        [IO.File]::WriteAllText($pyannote,$modifiedPyannote)
    }
    [IO.File]::WriteAllText($source,$modified)
    & $cmake --build $build --target sherpa-onnx-jni -j 6
    if ($LASTEXITCODE -ne 0) { throw 'Average-linkage research build failed' }
    python (Join-Path $PSScriptRoot 'package-native-evidence.py') --research-x86-only --output (Join-Path $PSScriptRoot '../.work/native-average-experiment.aar')
    if ($LASTEXITCODE -ne 0) { throw 'Research packaging failed' }
} finally {
    [IO.File]::WriteAllText($source,$original)
    [IO.File]::WriteAllText($pyannote,$originalPyannote)
    & $cmake --build $build --target sherpa-onnx-jni -j 6
    if ($LASTEXITCODE -ne 0) { throw 'Failed to restore the original complete-linkage build' }
}
