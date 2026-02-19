# ============================================================
# Dockerfile — Marine Watch Wear OS APK builder
#
# This image contains:
#   - OpenJDK 17
#   - Android SDK command-line tools
#   - Android SDK platform 34 + build-tools 34.0.0
#   - Gradle (downloaded on first build via the wrapper)
#
# Build the image:
#   docker build -t marine-watch-builder .
#
# Build the debug APK:
#   docker run --rm -v "$(pwd)":/workspace marine-watch-builder
#
# Build the release APK (unsigned — see README for signing):
#   docker run --rm -v "$(pwd)":/workspace marine-watch-builder \
#       ./gradlew assembleRelease
#
# The APK will appear in: app/build/outputs/apk/debug/app-debug.apk
# ============================================================

FROM eclipse-temurin:17-jdk-jammy

# ---------- Environment ----------
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools"
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false"

# ---------- System deps ----------
RUN apt-get update && apt-get install -y --no-install-recommends \
        wget \
        unzip \
        git \
        curl \
    && rm -rf /var/lib/apt/lists/*

# ---------- Android SDK command-line tools ----------
ARG CMDLINE_TOOLS_VERSION=11076708
RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
         -O /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-raw \
    && mv /tmp/cmdline-tools-raw/cmdline-tools "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-raw

# ---------- Accept licences & install SDK components ----------
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true
RUN sdkmanager \
        "platform-tools" \
        "platforms;android-34" \
        "build-tools;34.0.0" \
    && yes | sdkmanager --licenses > /dev/null 2>&1 || true

# ---------- Working directory ----------
WORKDIR /workspace

# ---------- Pre-download Gradle wrapper (cache layer) ----------
# Copy only the wrapper files first so this layer is cached unless
# the Gradle version changes.
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
COPY gradlew .
RUN chmod +x gradlew && ./gradlew --version || true

# ---------- Default command: build debug APK ----------
CMD ["./gradlew", "assembleDebug", "--stacktrace"]
