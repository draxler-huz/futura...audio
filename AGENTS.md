# AGENTS.md — Futura Audio

Repository knowledge for future agent sessions. Keep this factual and short.

## Build environment (this sandbox)

The container ships **no JDK and no Android SDK**, so a fresh session cannot
compile until they are installed. `gradle/wrapper/gradle-wrapper.jar` is a
**placeholder, not a real jar** — `./gradlew` cannot run. Use a standalone
Gradle 8.9 distribution instead.

One-time setup (needs network; `sudo` is available without a password):

```bash
# 1. JDK 17 (Temurin tarball; apt has no openjdk-17 in this image)
curl -sL -o /tmp/jdk17.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
sudo mkdir -p /opt/jdk17 && sudo tar -xzf /tmp/jdk17.tar.gz -C /opt/jdk17 --strip-components=1

# 2. Gradle 8.9 (matches gradle/wrapper/gradle-wrapper.properties)
curl -sL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
python3 -c "import zipfile;zipfile.ZipFile('/tmp/gradle.zip').extractall('/tmp/gradle_extract')"
chmod +x /tmp/gradle_extract/gradle-8.9/bin/gradle

# 3. Android SDK: commandline-tools, then platform/build-tools/ndk/cmake
curl -sL -o /tmp/cmdline.zip \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
python3 -c "import zipfile;zipfile.ZipFile('/tmp/cmdline.zip').extractall('/tmp/cltmp')"
sudo mkdir -p /opt/android-sdk/cmdline-tools && sudo chown -R "$USER" /opt/android-sdk
mv /tmp/cltmp/cmdline-tools /opt/android-sdk/cmdline-tools/latest
sudo chmod -R +x /opt/android-sdk/cmdline-tools/latest/bin

export JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$JAVA_HOME/bin:/tmp/gradle_extract/gradle-8.9/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
  "ndk;26.3.11579264" "cmake;3.22.1"   # NDK/CMake versions are pinned in engine/
```

`local.properties` is git-ignored; set `sdk.dir=/opt/android-sdk` in it.

## Build and test

```bash
export JAVA_HOME=/opt/jdk17 ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$JAVA_HOME/bin:/tmp/gradle_extract/gradle-8.9/bin:$PATH

gradle :app:compileDebugKotlin --no-daemon   # fast Kotlin + Hilt(KSP) check
gradle :app:testDebugUnitTest --no-daemon    # JVM unit tests
gradle :app:assembleRelease --no-daemon      # what CI runs; builds native engine + R8
```

`assembleRelease` succeeds here unsigned. **`assembleDebug` fails in this
sandbox**: vendored Oboe turns on `-Werror` for the DEBUG config
(`engine/src/main/cpp/third_party/oboe/CMakeLists.txt:106`) and its
OpenSLES sources trip `-Wsign-compare`. That is upstream Oboe, not app code.
CI builds Release, so it is unaffected.

## Conventions

- Line endings: some files are committed with **CRLF** (e.g.
  `NowPlayingViewModel.kt`, `res/values/strings.xml`). Keep them CRLF or the
  diff explodes into a whole-file rewrite. Check with
  `git show HEAD:<file> | grep -c $'\r'`.
- No local Android device/emulator, so UI changes are verified by compiling
  and by unit-testing the non-Android logic (state, mapping, filtering).
