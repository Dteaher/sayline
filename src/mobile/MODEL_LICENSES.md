# Модели и лицензии

Обновление0.7: используется `sherpa-onnx-neuro-evidence.aar` — собственная сборка JNI из закреплённого sherpa-onnx1.13.8 с передачей промежуточных признаков. Исходный официальный AAR сохранён. Kotlin-классы, ONNX Runtime и все веса не заменялись. Патч/сборка/происхождение: native/README.md; хеши конечного AAR и двух JNI находятся в его META-INF/neuroscribe-native-evidence.json и отчёте поставки. Apache-2.0; нативные изменения — Sayline. Таблица ниже описывает исходные компоненты и веса.

Все inference-файлы внутри assets/models APK; точные распакованные SHA-256/размеры — app/src/main/assets/models/manifest.json. Имена модели GigaAM, segmentation и speaker изменены для приложения; веса не обучались и не модифицировались Sayline. Проверка 7 ноября 2026.

| Компонент | Источник / лицензия | Размер |
|---|---|---:|
| sherpa-onnx 1.13.8 AAR | https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8 ; Apache-2.0 | 50 129 134 байт |
| GigaAM v3 Russian punct CTC int8 | https://github.com/salute-developers/GigaAM ; MIT, Copyright 2024 GigaChat Team; экспорт sherpa-onnx от 2025-12-16 | model 224 893 661; tokens 2 007 байт |
| pyannote segmentation 3.0 ONNX | https://huggingface.co/pyannote/segmentation-3.0 ; MIT, Copyright 2023 CNRS; экспорт sherpa-onnx | 5 992 913 байт |
| WeSpeaker ResNet34 VoxCeleb | https://github.com/wenet-e2e/wespeaker/blob/master/docs/pretrained.md#model-license ; pretrained веса CC-BY-4.0, код Apache-2.0 | 26 534 365 байт |
| 3D-Speaker ERes2Net VoxCeleb, уточнение отдельных реплик | https://www.modelscope.cn/models/iic/speech_eres2net_sv_en_voxceleb_16k ; API ModelScope подтверждает Apache License 2.0, toolkit https://github.com/modelscope/3D-Speaker | 26 485 263 байт |
| GTCRN, выбранная пользователем очистка фрагмента | https://github.com/Xiaobin-Rong/gtcrn ; MIT | 535 638 байт |
| ONNX Runtime в AAR | https://github.com/microsoft/onnxruntime ; MIT | включён в native libs |
| AndroidX / Compose / Kotlin | AndroidX Apache-2.0; Kotlin Apache-2.0 | Gradle зависимости |

WeSpeaker: авторство WeSpeaker project / wenet-e2e, ResNet34 trained on VoxCeleb; ONNX конвертация опубликована sherpa-onnx. Модель переименована в speaker.onnx, без новых изменений весов. Лицензия весов https://creativecommons.org/licenses/by/4.0/ отличается от лицензии кода. Атрибуция и тексты лицензий включены в assets/licenses.

## Закреплённые загрузки

- ERes2Net: https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx ; SHA-256 `c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5`, локально вычислен; publisher digest не предоставлен. Переименован в speaker-refinement.onnx, веса не изменены. Apache/атрибуция включены в APK.
- GTCRN: https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx ; SHA-256 `e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534`, локально вычислен; publisher digest не предоставлен. Переименован в gtcrn.onnx, MIT включена в APK.

- GigaAM archive: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16.tar.bz2 ; 163 282 594 байт; SHA-256 `20e41e160efa6f2460a7ef6554cdbbb9e8fffb2b92e6bc708e9406bd8b9256ea`.
- AAR: https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar ; SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`, совпал с publisher GitHub API digest.
- Segmentation archive: https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2 ; SHA-256 `24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488`.
- Embedding: https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx ; SHA-256 `5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94`.
- Gradle 9.3.1 bin: SHA-256 `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`, проверен по официальному checksum endpoint.

Архивы и AAR проверяются scripts/bootstrap.py; APK сверяется scripts/verify-artifacts.py с исходным manifest и обязательными моделями/библиотеками каждого ABI. Издательский digest AAR отделён от локально закреплённых хешей модельных файлов.

Реальная тестовая речь: FLEURS, Conneau et al. (2022), Google, CC-BY-4.0. Карточка https://huggingface.co/datasets/google/fleurs ; исходники/транскрипции/хеши и изменения — tests/audio/human/provenance.json. Эти записи входят только в QA/тестовый APK, не в production assets. Прежний Zipformer заменён; результаты его model card не являются измерениями Sayline.
