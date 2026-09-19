# Minos Tactical HUD 🎯

An Android Heads-Up Display (HUD) application for real-time edge AI object tracking, license plate identification, threat telemetry, and geospatial mapping. Built with **Jetpack Compose**, **CameraX**, **ONNX Runtime Mobile**, **Google ML Kit**, and **OSMDroid**.

---

## 🌟 Key Features

- **Real-Time AI Tracking**: Powered by ONNX Runtime (`yolov8n.onnx`) & ML Kit Face Detection.
- **License Plate Detection**: Automated license plate scanning & crop extraction for vehicles.
- **Tactical Canvas HUD Overlay**: Hardware-accelerated bounding boxes, target crosshairs, and tracking vectors.
- **Geospatial Tactical Map**: Dark tactical OpenStreetMap with real-time GPS location tracking.
- **Event Dossier Log**: Local event logging with search, metadata, image modal viewer, and ZIP export.
- **CyberSec Deck & Telemetry**: Threat monitoring deck, sensor diagnostics, and optional audio feedback.

---

## 🛠️ Tech Stack

- **Language**: Kotlin 2.2.10 (Java 17)
- **UI**: Jetpack Compose (Material 3)
- **Camera**: CameraX 1.4.0
- **AI / ML**: ONNX Runtime Mobile 1.18.0 & Google ML Kit Face Detection
- **Maps**: OSMDroid 6.1.18

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug or newer
- Physical Android device running Android 7.0+ (API 24+)

### Build & Run
```bash
# Clone repository
git clone https://github.com/your-username/MinosTacticalHUD.git
cd MinosTacticalHUD

# Build & install debug APK
./gradlew installDebug
```

---

## 📜 License

Distributed under the **MIT License**.
