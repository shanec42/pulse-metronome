#!/usr/bin/env bash
# Runs inside the `emulator` compose service (see ./demulator). Everything it downloads or
# creates lives under /workspace/.android-emulator, the git-ignored local folder.
set -euo pipefail

root=/workspace/.android-emulator
sdk="$root/sdk"
export ANDROID_SDK_ROOT="$sdk" ANDROID_HOME="$sdk" ANDROID_AVD_HOME="$root/avd"
mkdir -p "$sdk" "$ANDROID_AVD_HOME" "$root/home"

watch_image="system-images;android-34;android-wear;x86_64"
phone_image="system-images;android-34;google_apis_playstore;x86_64"

case "${1:-help}" in
    install)
        # The image's sdkmanager bootstraps a cmdline-tools copy inside the local SDK, so
        # avdmanager resolves system images from there rather than from /opt/android-sdk.
        # `yes` dies of SIGPIPE when sdkmanager stops reading; under pipefail that would abort the script.
        (yes || true) | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$sdk" --licenses > /dev/null
        /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$sdk" \
            "cmdline-tools;latest" "platform-tools" "emulator" "$watch_image" "$phone_image"
        echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd --force \
            -n pm-watch -k "$watch_image" -d wearos_large_round
        echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd --force \
            -n pm-phone -k "$phone_image" -d pixel_7
        echo "Installed. Start with: ./demulator watch   and   ./demulator phone"
        ;;
    watch|phone)
        window=()
        [ "${HEADLESS:-0}" = 1 ] && window=(-no-window)
        exec "$sdk/emulator/emulator" -avd "pm-$1" -gpu swiftshader_indirect -no-audio -no-boot-anim \
            ${window[@]+"${window[@]}"}
        ;;
    *)
        echo "usage: ./demulator install | watch | phone   (HEADLESS=1 for no window)" >&2
        exit 2
        ;;
esac
