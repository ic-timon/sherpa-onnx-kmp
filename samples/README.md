# Samples

## JVM CLI

```bash
./scripts/build-android-native.sh # macOS: builds the JVM-compatible JNI library
SHERPA_ONNX_KMP_NATIVE_PATH="$PWD/build/native/lib" \
  ./gradlew :samples:jvm-cli:run --args="/path/to/model /path/to/test.wav"
```

## Android App

1. Copy a compatible offline ASR model and test WAV to
   `samples/android-app/src/main/assets/models/nemo-ctc`.
2. Build the Android native libraries and AAR.
3. Install the sample:

```bash
./scripts/build-android-native.sh
./gradlew :samples:android-app:installDebug
```

The app expects `model.int8.onnx`, `tokens.txt`, and `test_wavs/0.wav`.

## Apple Swift Samples

`./scripts/test-apple-samples.sh 0.4.0` builds and runs the macOS sample, runs
the iOS sample in Simulator, and links the iOS device target without a connected
physical device. See [apple/README.md](apple/README.md).
