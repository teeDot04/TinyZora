# TinyZora 🧠
> **An Offline-First, Privacy-Centric AI Assistant for Android.**

TinyZora is a cutting-edge Android application designed to run Large Language Models (LLMs) like **Gemma 2 2b** entirely on-device using **Google's LiteRT (formerly TensorFlow Lite Runtime)**. It demonstrates how to build a persistent, memory-aware AI assistant that works without an internet connection.

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple) ![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue) ![LiteRT](https://img.shields.io/badge/AI-LiteRT%20Native-green) ![Offline](https://img.shields.io/badge/Offline-100%25-orange)

## ✨ Key Features

- **🚀 100% Offline Inference**: No cloud APIs, no subscription fees, no data leaks. Powered by `com.google.ai.edge.litertlm`.
- **💾 Long-Term Memory**: Unlike standard chat bots, TinyZora **remembers**. It uses a local `Room` database to store facts, preferences, and reminders that persist across app restarts.
- **🗣️ Natural Conversation**: Optimized `PromptBuilder` allows for up to **8,000 characters** of context, enabling deep discussions and long study sessions.
- **🌡️ Thermal & Crash Protection**: Smart context management prevents device overheating and OOM crashes by strictly managing token windows and truncating massive history logs.
- **📷 Multimodal Ready**: Built with support for visual inputs (switching seamlessly between Text-Only and Multimodal modes).

---

## 🏗️ Architecture

TinyZora follows a modern **MVVM (Model-View-ViewModel)** architecture:

### 1. The Brain (`InferenceManager.kt`)
The core of the app. It wraps the native LiteRT C++ engine in a Kotlin Coroutine-friendly singleton.
- Manages the `LLM Engine` lifecycle.
- Handles the **KV Cache** (Conversation history) to ensure fast responses.
- Implements a **Mutex** system to prevent race conditions during streaming.

### 2. The Thought Process (`TinyViewModel.kt`)
Disconnects the UI from the heavy AI lifting.
- **Context Injection**: Intelligently injects "System Instructions" only once per session to prevent context degradation (the "One Word" bug).
- **Date Patching**: Automatically patches AI-generated dates (e.g., "17:00") with the current year/month to ensure reminders are saved correctly.

### 3. The Memory (`MemoryDatabase.kt`)
A standardized SQL layer that stores:
- **Reminders**: Tasks with timestamps.
- **Facts**: User details (e.g., "My name is Tee").
- **Preferences**: UI settings and AI personality tweaks.

---

## 🛠️ Tech Stack

*   **Language**: Kotlin
*   **UI Toolkit**: Jetpack Compose (Material3)
*   **AI Engine**: Google AI Edge LiteRT (`com.google.ai.edge.litertlm`)
*   **Database**: Android Room (SQLite)
*   **Concurrency**: Kotlin Coroutines & Flow

---

## 🚀 Getting Started

### Prerequisites
*   Android Device with **8GB+ RAM** (Recommended).
*   A compatible `.bin` model file (e.g., `gemma-2b-it-cpu.bin` or `gpu.bin`).

### Installation
1.  **Clone the Repo**:
    ```bash
    git clone https://github.com/teeDot04/TinyZora.git
    cd TinyZora
    ```
2.  **Build**:
    Open in Android Studio or run:
    ```bash
    ./gradlew installDebug
    ```
3.  **Load a Model**:
    - Push your model to the device:
      ```bash
      adb push gemma-2b-it-gpu.bin /data/local/tmp/
      ```
    - Or use the **"Import Model"** button in the app settings.

---

## 🤝 Contributing

This project is a Proof of Concept for **Edge AI**. Pull Requests are welcome, especially for:
- Optimizing inference latency.
- Adding support for Audio Input (Native).
- Enhancing the RAG (Retrieval Augmented Generation) capabilities.

---

**Built with ❤️ by TeeDot.**
