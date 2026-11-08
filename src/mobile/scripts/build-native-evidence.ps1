param([ValidateSet('arm64-v8a','x86_64')][string]$Abi='x86_64', [int]$Jobs=6)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
$nativeRoot=Join-Path $env:LOCALAPPDATA 'NeuroScribe'
$nativeSource=Join-Path $nativeRoot 'native-evidence-v1/sherpa-onnx-1.13.8'
if (!(Test-Path -LiteralPath $nativeSource)) {
    python (Join-Path $PSScriptRoot 'prepare-native-evidence.py') (Join-Path $nativeRoot 'native-evidence-v1')
    if ($LASTEXITCODE -ne 0) { throw 'Source preparation failed' }
}
$cmake=Join-Path $env:ANDROID_HOME 'cmake/3.22.1/bin/cmake.exe'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../native/evidence-jni.cc') -Destination (Join-Path $nativeSource 'sherpa-onnx/jni/evidence-jni.cc') -Force
$build=Join-Path $nativeRoot "native-evidence-build/$Abi"
$arguments=@('-S',$nativeSource,'-B',$build,'-G','Ninja',
    "-DCMAKE_MAKE_PROGRAM=$env:ANDROID_HOME/cmake/3.22.1/bin/ninja.exe",
    "-DCMAKE_TOOLCHAIN_FILE=$env:ANDROID_HOME/ndk/28.2.13676358/build/cmake/android.toolchain.cmake",
    "-DANDROID_ABI=$Abi",'-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_static',
    '-DCMAKE_BUILD_TYPE=Release','-DBUILD_SHARED_LIBS=ON','-DSHERPA_ONNX_ENABLE_JNI=ON',
    '-DSHERPA_ONNX_ENABLE_TTS=OFF','-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF',
    '-DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF','-DSHERPA_ONNX_ENABLE_BINARY=OFF',
    '-DSHERPA_ONNX_ENABLE_C_API=OFF','-DSHERPA_ONNX_BUILD_C_API_EXAMPLES=OFF')
$sharedSources=Join-Path $nativeRoot 'native-evidence-build/x86_64/_deps'
if ($Abi -ne 'x86_64' -and (Test-Path -LiteralPath $sharedSources)) {
    foreach($dependency in @('eigen','hclust_cpp','json','kaldi_decoder','kaldi_native_fbank','kaldifst','kissfft','onnxruntime','openfst','simple-sentencepiece')) {
        $arguments += "-DFETCHCONTENT_SOURCE_DIR_$($dependency.ToUpper())=$sharedSources/$dependency-src"
    }
}
& $cmake @arguments
if ($LASTEXITCODE -ne 0) { throw 'Native configuration failed' }
& $cmake --build $build --target sherpa-onnx-jni -j $Jobs
if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
