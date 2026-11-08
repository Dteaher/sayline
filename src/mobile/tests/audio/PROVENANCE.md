# Test audio provenance

The files in this folder are synthetic Russian speech generated locally by `scripts/make-test-audio.ps1` using the installed Windows Microsoft Irina and Pavel speech voices, plus programmatically generated silence and seeded Gaussian noise. They contain newly authored, non-personal test phrases. They are test fixtures, never application results or shipped demonstration transcripts.

`utterances.json` stores the exact synthesis text and voice names. `scripts/benchmark.py` joins the voices in known A–B–A order and reports model outputs without overriding cluster IDs. Its results characterize synthetic host CPU smoke tests, not human conversation accuracy or Android performance. Record consented human conversations before defense to validate actual Russian speakers, noise, overlap and returning-speaker stability.
