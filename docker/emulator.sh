#!/usr/bin/env bash
# Runs inside the `emulator` compose service (see ./demulator). Everything it downloads or
# creates lives under /workspace/.android-emulator, the git-ignored local folder.
set -euo pipefail

root=/workspace/.android-emulator
sdk="$root/sdk"
export ANDROID_SDK_ROOT="$sdk" ANDROID_HOME="$sdk" ANDROID_AVD_HOME="$root/avd"
mkdir -p "$sdk" "$ANDROID_AVD_HOME" "$root/home"

watch_image="system-images;android-34;android-wear;x86_64"
# Wear OS 6 (API 36): matches the app's target SDK and exercises the READ_HEART_RATE path. The
# API 34 image's Health Services ignores the emulator's heart-rate control (synthetic driver only).
watch36_image="system-images;android-36;android-wear-signed;x86_64"
phone_image="system-images;android-34;google_apis_playstore;x86_64"
# Android 13 phone for pairing only: Play's "Wear OS by Google" companion won't install on 14+.
phone13_image="system-images;android-33;google_apis_playstore;x86_64"

# Creates an AVD only if it doesn't exist yet, so a re-run never wipes a signed-in emulator.
create_avd() {
    [ -d "$ANDROID_AVD_HOME/$1.avd" ] && { echo "$1 exists, kept"; return; }
    echo no | "$sdk/cmdline-tools/latest/bin/avdmanager" create avd -n "$1" -k "$2" -d "$3"
}

case "${1:-help}" in
    install)
        # The image's sdkmanager bootstraps a cmdline-tools copy inside the local SDK, so
        # avdmanager resolves system images from there rather than from /opt/android-sdk.
        # `yes` dies of SIGPIPE when sdkmanager stops reading; under pipefail that would abort the script.
        (yes || true) | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$sdk" --licenses > /dev/null
        /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$sdk" \
            "cmdline-tools;latest" "platform-tools" "emulator" "$watch_image" "$watch36_image" "$phone_image" "$phone13_image"
        create_avd pm-watch "$watch_image" wearos_large_round
        create_avd pm-watch36 "$watch36_image" wearos_large_round
        create_avd pm-phone "$phone_image" pixel_7
        create_avd pm-phone13 "$phone13_image" pixel_7
        echo "Installed. Start with: ./demulator watch | watch36 | phone | phone13"
        ;;
    watch|watch36|phone|phone13)
        window=()
        [ "${HEADLESS:-0}" = 1 ] && window=(-no-window)
        exec "$sdk/emulator/emulator" -avd "pm-$1" -gpu "${EMU_GPU:-swiftshader_indirect}" -no-audio -no-boot-anim -no-metrics \
            ${window[@]+"${window[@]}"} ${EMU_ARGS:-}
        ;;
    *)
        echo "usage: ./demulator install | watch | watch36 | phone | phone13   (HEADLESS=1 for no window)" >&2
        exit 2
        ;;
esac
