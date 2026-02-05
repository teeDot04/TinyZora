# TinyZora - Run & Test Instructions

The code is ready. To run the app and "talk" to the AI, you must perform one manual step to load the model file onto your device.

## 1. Prerequisites
- **Android Device**: Developer Mode enabled, USB Debugging on.
- **Model File**: You need a `.bin` or `.tflite` model compatible with MediaPipe (e.g., Gemma 2B, Phi-2).
  - *Note*: `InferenceModel.kt` is configured to look for `/data/local/tmp/llm/model.bin`.

## 2. Load the Model (ADB Method)
Since this is a developer build, we use `adb` to push the model to a temporary directory readable by the shell (and accessible by the app in this specific config).

```bash
# 1. Create the directory on the device
adb shell mkdir -p /data/local/tmp/llm/

# 2. Push your model file (rename it to model.bin for simplicity)
adb push path/to/your/downloaded_model.bin /data/local/tmp/llm/model.bin
```

## 3. Build & Run
1. Open the project in **Android Studio**.
2. Sync Gradle (it will download the `tasks-genai` and `material3` dependencies).
3. Click **Run** (green arrow) to deploy to your device.
4. Grant **Camera Permission** when prompted (for the Flashlight action).

## 4. Usage
- **Chat**: Type "Hello" to test the LLM.
- **Actions**: Type "Open flashlight" to test the `MobileActionExecutor`.
- **Logic**: Type "Buy BTC" to see it classify as `TRADING`.
