# Minos Tactical HUD 🎯🌐

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![ONNX Runtime](https://img.shields.io/badge/AI-ONNX%20Runtime%201.18.0-00599C?style=for-the-badge&logo=onnx&logoColor=white)](https://onnxruntime.ai)
[![CameraX](https://img.shields.io/badge/Camera-CameraX%201.4.0-00C853?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/training/camerax)

**Minos Tactical HUD** is an advanced, high-performance Android Heads-Up Display (HUD) application designed for real-time edge AI object tracking, license plate identification, threat telemetry, and geospatial intelligence. Built with modern Android practices—including **Jetpack Compose**, **CameraX**, **ONNX Runtime Mobile**, **Google ML Kit**, and **OSMDroid**—it delivers a futuristic cyber-tactical interface optimized for real-time field operations.

---

## 🌟 Key Features

### 👁️ Real-Time Edge AI Tracking (ONNX & ML Kit)
- **On-Device Inference**: Powered by ONNX Runtime (`onnxruntime-android`) for fast, private, offline execution without cloud dependencies.
- **YOLO Deep Learning Models**:
  - `yolov8n.onnx`: General COCO object detection (People, Vehicles, Animals, Industrial/Household items).
  - `yolo26n.onnx`: Secondary lightweight model variant.
  - `license_plate_yolov5s.onnx`: Dedicated license plate localization network.
- **Google ML Kit Integration**: On-device Face Detection for high-precision facial and head framing.

### 🎯 Tactical Heads-Up Display (HUD) Overlay
- **Dynamic Target Tracking Brackets**: Custom hardware-accelerated Canvas rendering for dynamic bounding boxes, target crosshairs, threat indicators, and predictive motion vectors.
- **Target Locking**: Lock onto specific tracked targets with automated digital zoom adjustments.
- **Live Telemetry Readouts**: Real-time FPS, model inference latency (`ms`), target counts, and camera source scaling.
- **Immersive Fullscreen Sticky Mode**: Prevents screen sleep (`FLAG_KEEP_SCREEN_ON`) and hides system bars during operation.

### ⚡ Tracking Profiles & Performance Presets
- **Personal Tracking Profiles**:
  - `Vehicle Mode`: Prioritizes cars, trucks, motorcycles, and enables high-frequency license plate scanning.
  - `People Mode`: Person-only detection with tight face and body framing.
  - `Indoor Mode`: Targeted subset for indoor object recognition with torch guidance.
  - `Outdoor Mode`: Multi-class surveillance across humans, vehicles, and wildlife.
  - `Moving Mode`: High-response tracking with aggressive predictive trajectory algorithms.
- **Quality vs. Speed Tuning**:
  - `Performance`: 640x640 detection, 600ms plate scanning, maximum frame rate.
  - `Balanced`: Balanced frame rate, sharpness checks, and crop creation.
  - `Quality`: Rapid 200ms license plate scans, strict sharpness thresholds, high-res capture triggers.

### 📸 Best-Frame Selector & High-Res Dossiers
- **Automatic Crop & Sharpness Filtering**: `BestFrameSelector` evaluates motion blur, crop size, and frame stability across consecutive video frames.
- **High-Resolution Still Capture**: Triggers CameraX high-quality JPEG captures (`ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY`) to extract crystal-clear target dossiers without interrupting live video analysis.

### 🛡️ CyberSec Operations Terminal & Sound Engine
- **Threat Operations Deck**: Real-time breach alerts, suspect tracking, and network mitigation interface.
- **Real-Time Synthesized Audio**: `CyberSecSoundManager` drives tactile sound effects, click confirmation, and alarm siren cascades using the Android `SoundPool` API.

### 🗺️ Live Geographic Tactical Map
- **Dark Tactical OpenStreetMap**: Inverted cyber-style map pipeline using custom `ColorMatrixColorFilter` transformations on OSMDroid tiles.
- **GPS Positioning**: Integrated location services (`MyLocationNewOverlay`) with manual orientation and location lock.
- **Geographic Target Index**: Maps recorded detection events into geographic blip nodes on the map.

### 📂 Event Log & Dossier Management
- **Persistent Event Repository**: Stores target dossiers complete with metadata (confidence, sharpness score, frame count, coordinates, timestamp).
- **Tactical Dossier Modal**: Fullscreen high-resolution image viewer with deletion, search filtering, and ZIP export capabilities.

---

## 🛠️ Architecture & Tech Stack

| Layer | Technology / Library |
|---|---|
| **Language** | [Kotlin 2.2.10](https://kotlinlang.org) (Targeting JVM 17) |
| **UI Framework** | [Jetpack Compose](https://developer.android.com/jetpack/compose) + Material 3 |
| **Camera & Video** | [CameraX 1.4.0](https://developer.android.com/training/camerax) (ImageAnalysis + ImageCapture) |
| **Edge AI / ML** | [ONNX Runtime Mobile 1.18.0](https://onnxruntime.ai) + [Google ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection) |
| **Geospatial / Maps** | [OSMDroid 6.1.18](https://github.com/osmdroid/osmdroid) |
| **Image Loading** | [Coil Compose 2.6.0](https://coil-kt.github.io/coil/) |
| **Audio Synthesizer** | Android `SoundPool` API |
| **Build Tooling** | Gradle 8.x + Android Gradle Plugin 8.4+ |
| **Target SDK / Min SDK** | Target SDK **34** (Android 14) / Min SDK **24** (Android 7.0) |
| **Architecture NDK** | `arm64-v8a` optimized |

---

## 📁 Project Structure

```
MinosTacticalHUD/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── assets/                      # ONNX neural network models
│   │   │   │   ├── yolov8n.onnx
│   │   │   │   ├── yolo26n.onnx
│   │   │   │   └── license_plate_yolov5s.onnx
│   │   │   ├── java/com/minos/hud/
│   │   │   │   ├── MainActivity.kt           # App lifecycle & CameraX pipeline setup
│   │   │   │   ├── MainViewModel.kt          # UI state, tracking settings & parameters
│   │   │   │   ├── OnnxImageAnalyzer.kt      # ONNX Runtime image pre/post-processing & inference
│   │   │   │   ├── LicensePlateDetector.kt   # License plate detector wrapper
│   │   │   │   ├── BestFrameSelector.kt      # Sharpness evaluation & high-res crop selection
│   │   │   │   ├── TacticalHudScreen.kt      # Main Compose HUD interface
│   │   │   │   ├── HUDOverlayView.kt         # Custom Canvas HUD target drawing & crosshairs
│   │   │   │   ├── CyberSecTerminalScreen.kt # CyberSec threat monitoring & audio triggers
│   │   │   │   ├── CyberSecSoundManager.kt   # SoundPool audio feedback engine
│   │   │   │   ├── PanopticoreScreen.kt      # Panopticore sensor matrix layout
│   │   │   │   ├── TacticalMapScreen.kt      # Inverted OSMDroid geographic map
│   │   │   │   ├── EventLogScreen.kt         # Event dossier list & full viewer modal
│   │   │   │   ├── EventRepository.kt        # Local storage & event persistence
│   │   │   │   └── DataModels.kt             # Tracking profiles, presets & data classes
│   │   │   └── AndroidManifest.xml
│   └── build.gradle
├── gradle/libs.versions.toml                # Dependency version catalog
├── build.gradle
└── settings.gradle
```

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio**: Ladybug (2024.2) or newer.
- **JDK**: Java 17 Development Kit.
- **Test Device**: Physical Android device running Android 7.0 (API 24) or higher with an `arm64-v8a` CPU and rear camera. *(Note: CameraX and ONNX Runtime run best on physical hardware).*

### Permissions
The app requests the following system permissions:
- `android.permission.CAMERA` *(Required for real-time video stream analysis)*
- `android.permission.ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION` *(Required for tactical map positioning)*
- `android.permission.WAKE_LOCK` *(Maintains HUD operation during active field scanning)*

### Building & Running

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-username/MinosTacticalHUD.git
   cd MinosTacticalHUD
   ```

2. **Open in Android Studio**:
   Open the root project folder in Android Studio and let Gradle sync complete.

3. **Build the Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Deploy to Device**:
   Connect your Android device via USB debugging and run:
   ```bash
   ./gradlew installDebug
   ```

---

## ⚙️ Configuration & Customization

### Adding / Replacing ONNX Models
To swap out or update the object detection model:
1. Export your custom model to ONNX format (input shape `1x3x640x640`).
2. Place the `.onnx` file in `app/src/main/assets/`.
3. Update the `modelName` parameter in `MainActivity.kt`:
   ```kotlin
   imageAnalyzer = OnnxImageAnalyzer(
       context = this,
       modelName = "your_custom_model.onnx",
       ...
   )
   ```

---

## 📜 License & Disclaimer

Distributed under the **MIT License**. See `LICENSE` for details.

> [!NOTE]
> **Disclaimer**: Minos Tactical HUD is designed for research, tactical UI demonstration, and computer vision experimentation. Always comply with local privacy laws and regulations regarding video recording and automatic license plate recognition (ALPR).
