# Real Russian test speech

FLEURS, Conneau et al. (2022), Google. Russian ru_ru validation rows0 and2, IDs1614 and1634. License CC-BY-4.0: https://creativecommons.org/licenses/by/4.0/ . Dataset card https://huggingface.co/datasets/google/fleurs . Exact source, transcriptions and hashes: provenance.json.

Original clips retain their source float32 WAV. returning.wav converts to mono PCM16 at the same16kHz rate and concatenates male–1s silence–female–1s silence–same male. This tests returning-speaker identity with human read speech; it is not evidence for spontaneous conversation, overlaps or diverse speakers. Test audio never goes into production assets.
