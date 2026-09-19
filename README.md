# Minos Tactical HUD 🎯🌐

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Version](https://img.shields.io/badge/Version-v1.6.2-FF6F00?style=for-the-badge&logo=android&logoColor=white)](app/build.gradle)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![ONNX Runtime](https://img.shields.io/badge/AI-ONNX%20Runtime%201.18.0-00599C?style=for-the-badge&logo=onnx&logoColor=white)](https://onnxruntime.ai)
[![CameraX](https://img.shields.io/badge/Camera-CameraX%201.4.0-00C853?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/training/camerax)
[![Build](https://img.shields.io/badge/Build-Passing-brightgreen?style=for-the-badge&logo=github)](https://github.com)

**Minos Tactical HUD** is an advanced, high-performance Android Heads-Up Display (HUD) application engineered for real-time edge AI object tracking, automatic license plate recognition (ALPR), face framing, cybernetic operational telemetry, and geospatial intelligence. Built using modern Android architecture—including **Jetpack Compose**, **CameraX**, **ONNX Runtime Mobile**, **Google ML Kit**, and **OSMDroid**—it delivers an immersive cyber-tactical interface optimized for real-time field operations with zero cloud dependencies.

---

## 📸 Overview & Operational Views

Minos Tactical HUD features five distinct operational screens accessible via the tactical navigation bar:

1. **Tactical HUD View** (`HUD`) – Real-time augmented video feed with Canvas-drawn tracking brackets, velocity vectors, threat indicators, target locking, exposure & digital zoom controls.
2. **Geospatial Tactical Map** (`MAP`) – Inverted dark cyber-style OpenStreetMap pipeline with GPS position tracking, compass orientation, and interactive target blip nodes.
3. **Event Dossier Log** (`LOGS`) – Local event repository storing target thumbnails, high-res captures, metadata, category filtering, search, and ZIP archive exporting.
4. **CyberSec Operations Deck** (`CYBERSEC`) – Cyber threat terminal stream, network breach indicators, radar sweep visuals, and synthesized `SoundPool` audio feedback.
5. **Panopticore Diagnostics** (`PANOPTICORE`) – Multi-sensor telemetry matrix displaying camera stream metrics, frame buffer queue health, ONNX model pipeline latency, and classification breakdowns.

---

## 🌟 Key Features

### 👁️ Real-Time Edge AI Engine (ONNX Runtime & Google ML Kit)
- **On-Device Offline Inference**: Powered by `onnxruntime-android` (v1.18.0) for fast, private, low-latency execution without internet connectivity.
- **YOLO Deep Learning Neural Networks**:
  - `yolov8n.onnx` (12.2 MB): Primary COCO 80-class real-time object detector (640x640 tensor input).
  - `yolo26n.onnx` (12.2 MB): Secondary lightweight detection variant.
  - `license_plate_yolov5s.onnx` (27.2 MB): Dedicated ALPR localization network for vehicle license plates.
- **Google ML Kit Face Detection**: Integrated on-device face detector (`com.google.mlkit:face-detection:16.1.7`) providing high-precision face framing and head-bounding boxes.
- **Bounding Box Interpolation & Tracking**: Applies exponential smoothing (`alpha` box smoothing) to eliminate bounding box flicker across consecutive video frames.

### 🎯 Tactical Heads-Up Display (HUD) & Camera Control
- **Hardware-Accelerated Overlay (`HUDOverlayView`)**: Custom Android Canvas view rendering dynamic target brackets, target lock reticles, threat level color codes, velocity trajectory vectors, and distance indicators.
- **Target Locking & Auto-Zoom**: Lock onto specific tracked targets with a single tap, triggering automated digital zoom and tracking stabilization.
- **Live Telemetry Bar**: Real-time FPS readout, ONNX inference execution time (`ms`), active target count, and current tracking profile badge.
- **Camera Gestures & Manual Control**: Pinch-to-zoom (1.0x to 8.0x digital zoom), tap-to-focus with `FocusMeteringAction`, exposure compensation slider, and flashlight (torch) toggle with low-light auto-suggestion.
- **Clean View Toggle**: Instantly hide HUD overlay elements for unobstructed camera stream observation.
- **Immersive Fullscreen Sticky Mode**: Keeps screen active (`FLAG_KEEP_SCREEN_ON`) and hides system navigation bars during tactical operation.

### ⚡ Tracking Profiles & Quality/Speed Presets

#### Personal Tracking Profiles
| Profile | Target Classes | Plate Scanning | Smoothing | Torch Auto-Suggest | Primary Use Case |
|---|---|---|---|---|---|
| **Vehicle Mode** | Cars, Trucks, Motorcycles, Bicycles, Plates | High Frequency (250ms) | `0.80` | No | Traffic & highway surveillance, ALPR |
| **People Mode** | Person only | Disabled | `0.80` | No | Crowd analysis, personal perimeter security |
| **Indoor Mode** | Persons, Pets, Household/Office Items | Standard (3000ms) | `0.80` | **Yes** | Indoor tactical navigation, low-light entry |
| **Outdoor Mode** | Humans, Vehicles, Wildlife (18+ classes) | Balanced (400ms) | `0.80` | No | Perimeter monitoring, general field operations |
| **Moving Mode** | All COCO Classes | Fast (300ms) | `0.85` | No | High-speed motion tracking & fast response |

#### Quality vs. Speed Presets
- **Performance**: 640x640 detection tensor, 600ms license plate scans, minimal crop overhead, maximum frame rate.
- **Balanced**: 640x640 detection tensor, 400ms license plate scans, sharpest-frame selection, balanced frame rate.
- **Quality**: 640x640 main detector, frequent 200ms plate scans, strict sharpness thresholds, high-resolution still capture triggers (`ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY`).

### 📸 Best-Frame Selector & High-Res Dossiers
- **Sharpness & Stability Evaluation (`BestFrameSelector`)**: Uses Laplacian variance calculation to grade image blur, crop dimensions, aspect ratio, and frame stability across consecutive video frames.
- **High-Resolution Still Extraction**: Triggers CameraX `ImageCapture` in high-quality mode to extract crystal-clear target dossiers without interrupting the live 60fps video analysis stream.

### 🛡️ CyberSec Operations Terminal & Sound Engine
- **Cyber Threat Deck (`CyberSecTerminalScreen` & `CyberTerminalLayout`)**: Interactive cybernetic operations terminal displaying real-time simulated breach alerts, system activity logs, and target vector radar sweeps.
- **Synthesized Audio Engine (`CyberSecSoundManager`)**: Uses the Android `SoundPool` API to produce tactile UI button clicks, target acquisition lock tones, and multi-pitch warning siren cascades.

### 🗺️ Live Geographic Tactical Map
- **Dark Tactical OpenStreetMap (`TacticalMapScreen`)**: Custom dark cyber-themed map rendered via OSMDroid with inverted `ColorMatrixColorFilter` tile shaders.
- **GPS Location Tracking**: Integrated `MyLocationNewOverlay` with auto-center location lock and heading indication.
- **Target Location Pins**: Automatically projects recorded detection events onto the geographic map as interactive target blips.

### 📂 Persistent Event Log & Dossier Manager
- **Local Event Storage (`EventRepository`)**: Stores complete target event records including category, confidence score, sharpness rating, frame count, tracked duration, screen coordinates, GPS position, thumbnail, and optional high-res still image.
- **Filtering & Search**: Instant filtering by event category (*People*, *Vehicles*, *Animals*, *Plates*) or text query.
- **Dossier Viewing & ZIP Export**: Fullscreen modal image viewer with single-tap deletion and full ZIP archive export capabilities for external reporting.

---

## 🛠️ Architecture & Tech Stack

```
                     ┌────────────────────────────────────────┐
                     │          CameraX Video Feed            │
                     │       (1280x720 @ 60fps Stream)        │
                     └───────────────────┬────────────────────┘
                                         │
                   ┌─────────────────────┴─────────────────────┐
                   │                                           │
         ┌─────────▼──────────┐                     ┌──────────▼─────────┐
         │  ImageAnalysis     │                     │  ImageCapture      │
         │  (ONNX Analyzer)   │                     │  (High-Res Still)  │
         └─────────┬──────────┘                     └──────────┬─────────┘
                   │                                           │
      ┌────────────┼───────────────────────┐                   │
      │            │                       │                   │
┌─────▼──────┐ ┌───▼────────────┐ ┌────────▼────────┐          │
│ YOLOv8n /  │ │ License Plate  │ │ ML Kit Face     │          │
│ YOLO26n    │ │ YOLOv5s ALPR   │ │ Detection       │          │
└─────┬──────┘ └───┬────────────┘ └────────┬────────┘          │
      │            │                       │                   │
      └────────────┼───────────────────────┘                   │
                   │                                           │
         ┌─────────▼──────────┐                                │
         │ BestFrameSelector  ├────────────────────────────────┘
         │ (Laplacian Blur)   │  (Trigger High-Res Still Crop)
         └─────────┬──────────┘
                   │
         ┌─────────▼──────────┐
         │ Canvas Overlay &   │
         │ Jetpack Compose UI │
         └────────────────────┘
```

| Layer | Technology / Dependency | Version | Purpose |
|---|---|---|---|
| **Language** | Kotlin | `2.2.10` | Core application source code |
| **Target SDK / Min SDK** | Android SDK | Target `34` / Min `24` | Android 14 (API 34) with backward compatibility to Android 7.0 |
| **UI Framework** | Jetpack Compose + Material 3 | Compose BOM `2024.06.00` | Declarative UI, reactive state management, cyber themes |
| **Camera Pipeline** | CameraX | `1.4.0` | `ImageAnalysis` video streaming & `ImageCapture` high-res still capture |
| **Inference Engine** | ONNX Runtime Mobile | `1.18.0` | On-device, hardware-accelerated neural network execution |
| **Face Detection** | Google ML Kit | `16.1.7` | On-device face detection & head framing |
| **Geospatial Maps** | OSMDroid | `6.1.18` | Offline/online OpenStreetMap with dark tactical color filtering |
| **Image Loading** | Coil Compose | `2.6.0` | Asynchronous image loading & memory caching |
| **Audio Synthesizer** | Android `SoundPool` API | Native | Low-latency tactical sound effects & alarm sirens |
| **Architecture / ABI** | `arm64-v8a` NDK | NDK Optimized | 64-bit ARM optimization for neural network math |

---

## 📁 Project Directory Structure

```
MinosTacticalHUD/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── assets/                      # Neural network ONNX model files
│   │   │   │   ├── yolov8n.onnx             # Primary YOLOv8 COCO detector (12.2 MB)
│   │   │   │   ├── yolo26n.onnx             # Alternative YOLO detector (12.2 MB)
│   │   │   │   └── license_plate_yolov5s.onnx # Dedicated ALPR license plate model (27.2 MB)
│   │   │   ├── java/com/minos/hud/
│   │   │   │   ├── MainActivity.kt           # Activity lifecycle & CameraX pipeline binding
│   │   │   │   ├── MainViewModel.kt          # Global UI state, tracking settings & parameters
│   │   │   │   ├── OnnxImageAnalyzer.kt      # ONNX Runtime inference, NMS & box smoothing
│   │   │   │   ├── YoloAnalyzer.kt           # YOLO tensor pre/post-processing logic
│   │   │   │   ├── LicensePlateDetector.kt   # Dedicated ALPR model wrapper
│   │   │   │   ├── TacticalFaceAnalyzer.kt   # ML Kit Face Detection pipeline wrapper
│   │   │   │   ├── BestFrameSelector.kt      # Laplacian variance blur & frame quality analyzer
│   │   │   │   ├── HudScreen.kt              # Main Compose HUD layout & camera interaction
│   │   │   │   ├── TacticalHudScreen.kt      # HUD overlay menus, drawer controls & sliders
│   │   │   │   ├── HUDOverlayView.kt         # Hardware-accelerated Canvas target bracket renderer
│   │   │   │   ├── TacticalMapScreen.kt      # OSMDroid dark tactical map with GPS & target pins
│   │   │   │   ├── EventLogScreen.kt         # Event dossier list, search & modal viewer
│   │   │   │   ├── EventRepository.kt        # Local storage event persistence & ZIP exporter
│   │   │   │   ├── CyberSecTerminalScreen.kt # Cyber threat monitoring screen
│   │   │   │   ├── CyberTerminalLayout.kt    # CyberSec layout structure & terminal stream
│   │   │   │   ├── CyberSecViewModel.kt      # CyberSec state & threat log stream generator
│   │   │   │   ├── CyberSecSoundManager.kt   # SoundPool audio feedback & siren engine
│   │   │   │   ├── PanopticoreScreen.kt      # Sensor matrix diagnostic dashboard
│   │   │   │   ├── PanopticoreViewModel.kt   # Diagnostic metrics state container
│   │   │   │   ├── PanopticoreTheme.kt       # Diagnostic dashboard theme definitions
│   │   │   │   ├── CyberSecPalette.kt        # CyberSec color palette tokens
│   │   │   │   ├── DataModels.kt             # Tracking profiles, presets, event models & classes
│   │   │   │   ├── VideoBuffer.kt            # Circular video buffer for historical frame caching
│   │   │   │   └── Widgets.kt                # Shared Compose tactical UI components
│   │   │   └── AndroidManifest.xml           # System permissions & activity config
│   │   └── test/                             # Unit tests (`FeaturesUnitTest.kt`)
│   └── build.gradle                          # App build configuration & dependencies
├── gradle/
│   └── libs.versions.toml                    # Dependency version catalog
├── build.gradle                              # Root build configuration
└── settings.gradle                           # Subproject inclusions
```

---

## 🚀 Getting Started

### System Requirements
- **Android Studio**: Ladybug (2024.2.1+) or newer.
- **JDK**: Java 17 Development Kit (JDK 17).
- **Physical Device**: Android device running Android 7.0 (API level 24) or higher, powered by an `arm64-v8a` 64-bit ARM processor and rear camera.
  > *Note: Neural network inference and CameraX image analysis perform best on modern physical hardware.*

### System Permissions
The application requests the following runtime permissions:
- `android.permission.CAMERA` – Required for real-time video analysis and image capture.
- `android.permission.ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION` – Required for real-time tactical map GPS positioning.
- `android.permission.WAKE_LOCK` – Maintains active HUD operation during field scanning.

### Build & Deploy Instructions

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/your-username/MinosTacticalHUD.git
   cd MinosTacticalHUD
   ```

2. **Open in Android Studio**:
   Launch Android Studio and open the `MinosTacticalHUD` root folder. Allow Gradle to sync dependencies.

3. **Build Debug APK via Command Line**:
   ```bash
   ./gradlew assembleDebug
   ```

4. **Run Unit Tests**:
   ```bash
   ./gradlew testDebugUnitTest
   ```

5. **Install on Connected Device**:
   Ensure USB Debugging is enabled on your physical device, then run:
   ```bash
   ./gradlew installDebug
   ```

---

## ⚙️ Customization & Model Swapping

### Replacing or Upgrading ONNX Neural Networks
To replace the default object detection model (`yolov8n.onnx`):

1. Export your custom model to ONNX format with input tensor shape `1x3x640x640` (RGB normalized `[0, 1]`).
2. Copy the `.onnx` file into `app/src/main/assets/`.
3. Update the model reference in `MainActivity.kt`:
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

> [!IMPORTANT]
> **Operational Disclaimer**: Minos Tactical HUD is designed for computer vision research, tactical UI demonstration, and edge AI performance benchmarking. Users must ensure compliance with all applicable local, national, and international laws regarding surveillance, automated license plate recognition (ALPR), and privacy rights.
