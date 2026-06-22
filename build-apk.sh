#!/usr/bin/env bash
# build-apk.sh - build the Tacky APK in a pinned Android-SDK container, no host
# Android install. Mirrors zippy's in_docker.sh.
#
#   ./build-apk.sh                 # gradle :app:assembleDebug -> app-debug.apk
#   ./build-apk.sh :app:assembleRelease
#   ./build-apk.sh tasks           # any gradle args pass through
#
# The native daemon is NOT built here; cross-build it in tacky_t (`make android`)
# and copy the .so pair into app/src/main/jniLibs/arm64-v8a/ (see README). It rides
# into the build as a normal source set - no mount or property needed.
set -euo pipefail

project=$(cd "$(dirname "$0")" && pwd)
image=tacky-android-sdk
dockerfile=$project/docker/android-sdk.Dockerfile

if [ ! -f "$project/app/src/main/jniLibs/arm64-v8a/libtackyd_json.so" ]; then
    echo "build-apk.sh: daemon binary missing at app/src/main/jniLibs/arm64-v8a/" >&2
    echo "  run 'make android' in tacky_t, then copy its dist/jniLibs/arm64-v8a/*.so here." >&2
    exit 2
fi

# Gradle's dependency cache (AGP, etc.) persists here across runs; gitignored.
gradle_home=$project/_build-docker/gradle
mkdir -p "$gradle_home"

# Build the toolchain image only when it's missing (or REBUILD=1). The layers are
# cached, but even a cache hit costs a few seconds, so skip it once the image
# exists. After editing the Dockerfile, force a rebuild: REBUILD=1 ./build-apk.sh
if [ -n "${REBUILD:-}" ] || ! docker image inspect "$image" >/dev/null 2>&1; then
    DOCKER_BUILDKIT=1 docker build -t "$image" -f "$dockerfile" "$project/docker" >&2
fi

# Default goal: a debug APK. Any args passed through replace it.
if [ "$#" -eq 0 ]; then
    set -- :app:assembleDebug
fi

tty_flags=()
if [ -t 0 ] && [ -t 1 ]; then
    tty_flags=(-t -i)
fi

docker run --rm "${tty_flags[@]}" \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -e GRADLE_USER_HOME=/src/_build-docker/gradle \
    -v "$project:/src" \
    -w /src \
    "$image" \
    gradle --no-daemon "$@"

apk=$project/app/build/outputs/apk/debug/app-debug.apk
if [ -f "$apk" ]; then
    echo
    echo "APK: $apk"
    ls -la "$apk" >&2
fi
