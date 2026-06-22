# Toolchain image for building the Tacky debug APK with no host Android install.
# JDK 17 + a pinned Android SDK (platform 34 / build-tools 34.0.0) + Gradle 8.7.
# Mirrors zippy's pinned-download toolchain images. The build itself runs at
# `docker run` time (build-apk.sh supplies `gradle assembleDebug`); Gradle's own
# dependency cache (AGP etc.) is mounted from the host, not baked here.
FROM debian:bookworm

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk

# Command-line tools (sdkmanager). Build 11076708 (cmdline-tools 12.0); the zip
# unpacks to cmdline-tools/, but sdkmanager expects cmdline-tools/latest/.
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
    && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdtools.zip \
    && echo "2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258  /tmp/cmdtools.zip" | sha256sum -c \
    && unzip -q /tmp/cmdtools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools \
    && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
    && rm -f /tmp/cmdtools.zip

ENV PATH=${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}

# Accept licenses, then install the pinned SDK packages this project compiles
# against (compileSdk 34, build-tools 34.0.0). Pre-installing everything means the
# build never tries to write to the root-owned SDK at run time.
RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;android-34" \
        "build-tools;34.0.0"

# Gradle 8.7 (matches gradle/wrapper/gradle-wrapper.properties; run directly so no
# wrapper jar is needed in the repo).
RUN curl -fsSL https://services.gradle.org/distributions/gradle-8.7-bin.zip -o /tmp/gradle.zip \
    && echo "544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d  /tmp/gradle.zip" | sha256sum -c \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm -f /tmp/gradle.zip
ENV PATH=/opt/gradle-8.7/bin:${PATH}
