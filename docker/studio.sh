#!/usr/bin/env bash
# Runs inside the `studio` compose service (see ./dstudio). Studio, its settings and caches live
# under /workspace/.android-studio, the git-ignored local folder.
set -euo pipefail

root=/workspace/.android-studio
mkdir -p "$root/home"

# Pinned stable release, checked against the SHA-256 on developer.android.com/studio.
version=2026.2.2.9
file=android-studio-rabbit2-patch1-linux.tar.gz
url="https://edgedl.me.gvt1.com/android/studio/ide-zips/$version/$file"
sha256=11ecbf0e6dc0f04a1353f797e45bf239a3ff1a82221539b1b8cdd7eab0d8d8ea

case "${1:-run}" in
    install)
        # Studio's setup wizard rejects an SDK with no platform, and the project syncs against API 36.
        sdk=/opt/android-sdk
        [ -d "$sdk/platforms/android-36" ] || "$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" \
            "platforms;android-36" "build-tools;36.0.0"
        if [ -x "$root/android-studio/bin/studio.sh" ]; then
            echo "Android Studio already installed in .android-studio/android-studio, kept"
            exit 0
        fi
        mkdir -p "$root/download"
        [ -f "$root/download/$file" ] || curl -fL --progress-bar -o "$root/download/$file.part" "$url"
        [ -f "$root/download/$file.part" ] && mv "$root/download/$file.part" "$root/download/$file"
        echo "$sha256  $root/download/$file" | sha256sum -c -
        tar -xzf "$root/download/$file" -C "$root"
        rm "$root/download/$file"
        echo "Installed. Start with: ./dstudio"
        ;;
    run)
        [ -x "$root/android-studio/bin/studio.sh" ] || { echo "Not installed yet: run ./dstudio install" >&2; exit 1; }
        exec "$root/android-studio/bin/studio.sh" /workspace
        ;;
    *)
        echo "usage: ./dstudio install | ./dstudio   (opens the project)" >&2
        exit 2
        ;;
esac
