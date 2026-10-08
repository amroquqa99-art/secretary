# Android local inference dependencies

llama.cpp / ggml, ggml-org contributors, MIT License.
Source: https://github.com/ggml-org/llama.cpp
Pinned revision: `50569eb87df530daff11afda229ceb9ab8e6cae8`.
Source archive SHA-256: `10af4d51ee5c29566e1f2899752d12ea41004554ceff7a91ca931b0ace48bc93`.
The MIT copyright notice and license are bundled as `app/src/main/assets/licenses/LLAMA-CPP-MIT.txt`.
Only the CPU runtime is built. No llama server, HTTP client, GPU backend or model weights are bundled.

Qwen models, Qwen team, Apache License 2.0 according to their official model cards.
Public pinned artifacts are recorded in `app/src/main/java/com/alsekretary/app/localmodel/ModelStore.kt` and `EmbeddingModelStore.kt`.
The weights are downloaded only at the user's request or imported as an owned copy. They are excluded from the APK, Git and personal-data backups.
The Apache license is bundled as `app/src/main/assets/licenses/APACHE-2.0.txt`.

LiteRT-LM is not an Android dependency. Existing owned `.litertlm` weights remain available for explicit removal; the engine does not load them.
Other Android/Compose, Kotlin and Gson dependencies retain their own licenses.

Vosk Android 0.3.75, Alpha Cephei contributors, Apache License 2.0.
Source: https://github.com/alphacep/vosk-api
JNA 5.18.1, JNA contributors, Apache License 2.0 (chosen from its dual license).
Source: https://github.com/java-native-access/jna
The Apache license and dependency notices are bundled in `assets/licenses`.

Bundled recognition models from https://alphacephei.com/vosk/models:
- `vosk-model-small-en-us-0.15.zip`: 41,205,931 bytes, SHA-256
  `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`, Apache 2.0.
- `vosk-model-ar-mgb2-0.4.zip`: 333,241,610 bytes, SHA-256
  `357469ae1bb4d7a3810c9cd6b86d33bc135898dfc134e6df8bc2ddd28c5fe77a`, Apache 2.0.

These archives are fetched and checked at build time, not committed to Git.
Runtime recognition does not download models or send audio over the network.
