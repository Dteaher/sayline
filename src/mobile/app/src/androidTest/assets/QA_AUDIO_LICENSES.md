# QA audio attribution

actors-female.wav is a composed exchange from Dialogs, by Ilya Shigabeev and Ilya Latyshev (Langswap): https://huggingface.co/datasets/langswap/dialogs-ru-emotional-conversations . OpenRAIL terms in LICENSE-Dialogs.md apply. Source actors gave informed consent for release according to the dataset card. The QA fixture resamples recorded scripted utterances to mono16kHz and inserts0.5s gaps; it is not a spontaneous conversation.

Exact source order/hashes are recorded in the repository tests/audio/quality-improvement/provenance.json. actors-female.wav uses selected row indexes5,6,3,7,4 from provenance.rows (actor M,S,M,S,M), not the confirmation corpus.

human-returning.wav: derived from google/fleurs ru_ru validation, CC-BY4.0; dataset https://huggingface.co/datasets/google/fleurs . The first human voice clip is repeated, so this is not independent returning speech. Exact source hashes and derivation: repository tests/audio/human/PROVENANCE.md and provenance.json.

single.wav and returning.wav: clearly labelled locally generated Windows speech synthesis test fixtures, not evidence of human meeting accuracy; generation script scripts/make-test-audio.ps1.
