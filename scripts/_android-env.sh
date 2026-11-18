# Sourced by the android scripts: locates the SDK, installing it if missing.
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ ! -d "$ANDROID_HOME/platforms/android-35" ]; then
  "$REPO_ROOT/scripts/setup-android.sh"
fi
[ -f "$REPO_ROOT/android/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$REPO_ROOT/android/local.properties"
cd "$REPO_ROOT/android"
