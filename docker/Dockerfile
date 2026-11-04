FROM eclipse-temurin:17-jdk-jammy AS sdk

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

RUN apt-get update && apt-get install -y --no-install-recommends unzip curl && rm -rf /var/lib/apt/lists/*

# Android commandline-tools (version pinned for reproducibility)
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    curl -sSL -o /tmp/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null && \
    sdkmanager "platform-tools" "platforms;android-36" "platforms;android-26" "build-tools;36.0.0"

WORKDIR /workspace

# Emulator runtime: only the X11/GL libraries the emulator binary needs. The emulator itself,
# system images and AVDs are installed by docker/emulator.sh into the bind-mounted, git-ignored
# .android-emulator/ folder, so the multi-GB downloads live on local disk, not in the image.
FROM sdk AS emulator
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libegl1 libgbm1 libdrm2 libnss3 libdbus-1-3 libfontconfig1 libpulse0 libasound2 \
        libx11-6 libx11-xcb1 libxcomposite1 libxcursor1 libxdamage1 libxext6 libxfixes3 libxi6 \
        libxrandr2 libxrender1 libxtst6 libxkbcommon0 libxkbcommon-x11-0 libxkbfile1 \
        libxcb1 libxcb-cursor0 libxcb-icccm4 libxcb-image0 libxcb-keysyms1 libxcb-randr0 \
        libxcb-render-util0 libxcb-shape0 libxcb-xfixes0 libxcb-xinerama0 libxcb-xkb1 \
    && rm -rf /var/lib/apt/lists/*
