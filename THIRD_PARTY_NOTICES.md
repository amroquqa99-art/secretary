# Local inference dependencies in 0.7.0

llama.cpp / ggml, ggml-org contributors, MIT License.
Source: https://github.com/ggml-org/llama.cpp
Pinned revision: `50569eb87df530daff11afda229ceb9ab8e6cae8`.
Source archive SHA-256: `10af4d51ee5c29566e1f2899752d12ea41004554ceff7a91ca931b0ace48bc93`.
The MIT copyright notice and license are bundled as `app/src/main/assets/licenses/LLAMA-CPP-MIT.txt`.
Only the CPU runtime is built. No llama server, HTTP client, GPU backend or model weights are bundled.

Qwen models, Qwen team, Apache License 2.0 according to their official model cards.
Public pinned artifacts and the evaluated model choice are recorded in [LOCAL_MODELS.md](LOCAL_MODELS.md).
The weights are downloaded only at the user's request or imported as an owned copy. They are excluded from the APK, Git and personal-data backups.
The Apache license is bundled as `app/src/main/assets/licenses/APACHE-2.0.txt`.

LiteRT-LM was used in 0.6.0 and is no longer an Android dependency in 0.7.0. Existing owned `.litertlm` weights remain available for explicit removal; the new engine does not load them.
Other Android/Compose, Kotlin and Gson dependencies retain their own licenses.
