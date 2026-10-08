# Reproducible container development — LifeOS / Secretary

**Status:** Initial backend development/test Docker image committed. Docker build,
full test suite and Android integration are not yet verified by this change.
This image is not a production server or Android APK.

## Goal

Give every contributor the same Linux Python/CPU backend toolchain without
manually installing the full requirements on every host. Avoid repeated wheel
downloads using Docker layers and the named BuildKit pip cache.

## Commands

Use Docker Engine/Compose v2 with BuildKit enabled:

```sh
docker compose build verify
docker compose run --rm verify
# Replace with a focused test when changing a specific subsystem:
docker compose run --rm verify python -m pytest -q tests/path/to/test_file.py
# Inspect the exact packages installed when the image was built:
docker compose run --rm verify cat /opt/secretary-installed-dependencies.txt
```

The image uses Python 3.11.11, CPU PyTorch 2.5.1, the repository's
`requirements.txt`, Linux native build libraries, and pytest.
The `LIFEOS_TEST_INSTANCE=1` default prevents sourcing host `.env`
secrets. **No personal Obsidian vault, Google token, user data or model is
copied into the image by design.** The `.dockerignore` excludes known
sensitive paths. Mount only explicitly sanitized fixtures for tests.

Transport an already-built image to a host with no package-registry access:

```sh
docker image save secretary-dev:local -o secretary-dev-image.tar
# On a compatible Linux Docker engine:
docker image load -i secretary-dev-image.tar
docker compose run --rm --no-deps verify
```

The saved image contains installed dependencies. A new build still requires
Internet access unless a builder already has the base image and wheel cache.
The image is CPU/Linux-focused, not a macOS integration test environment;
Apple Contacts/iMessage and other host-only services require separate tests.

## Known non-determinism and next steps

The existing `requirements.txt` specifies many minimum versions, not a
fully pinned transitive dependency lock. The container freezes *one built
image*, but a fresh build on a different date may resolve different versions.
Do not call the image build fully reproducible until a Python 3.11/Linux
platform lockfile is generated, committed, installed with integrity checks and
verified on a clean host. Use the recorded pip freeze for build audit, not
as a substitute for a reviewed lockfile.

This repository contains an Android development branch under
`mobile/android`. It requires JDK 17, the matching Android SDK/NDK/CMake,
a Gradle 9.6.0 bootstrap with checksum verification, and separate Android
tests. **This backend image cannot produce an APK** and is not a substitute
for device acceptance testing. An independent Android builder image should
be added against the actual Android branch and tested before claiming support.

The production API uses `scripts/server.sh` and host identity guards.
Do not replace its launcher with a generic public `uvicorn` service without
checking that runtime, authentication, network, and data paths are safe.

## Required engineering rule

- Maintain Docker definitions, dependency/version manifests, `.dockerignore`
  and these commands in Git along with source changes.
- Prefer focused test commands, then existing repository acceptance gates.
  Report actual passing checks; never infer success from the Dockerfile alone.
- Keep caches and downloaded dependencies in image layers/BuildKit caches,
  not in commits. Build or save a portable image before expecting offline use.
- Never commit API keys, private personal data, large local models or APK
  signing credentials inside a container or repository.
- Pin OS/SDK/image digests and Python dependency hashes for release builds.
  Require validation before widening Docker privileges or mounting host data.
