# syntax=docker/dockerfile:1.7
# Linux CPU environment for LifeOS/Secretary backend development and tests.
# Android APK builds and host-specific Apple integrations use separate toolchains.
FROM python:3.11.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    LIFEOS_TEST_INSTANCE=1

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates build-essential git pkg-config \
      libgomp1 libgl1 libglib2.0-0 libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY requirements.txt ./

# CPU PyTorch avoids pulling CUDA wheels into a portable backend test image.
# Keep the host's GPU/runtime drivers outside this image.
RUN --mount=type=cache,id=secretary-pip-cache,target=/root/.cache/pip \
    python -m pip install --upgrade "pip==25.1.1" \
    && python -m pip install --index-url https://download.pytorch.org/whl/cpu "torch==2.5.1" \
    && python -m pip install -r requirements.txt

COPY . .
RUN python -m pip freeze > /opt/secretary-installed-dependencies.txt

# Isolated synthetic tests. The real server must use its host-aware launcher.
CMD ["python", "-m", "pytest", "-q", "-m", "unit and not slow"]
