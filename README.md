# GazeNav – Head-Tracking Mobile Navigation System

> A hands-free Android app that lets users control their smartphone using head movements — no touch required. Built to empower people with motor disabilities to navigate their phone independently.

---

## 📌 Project Info

| | |
|---|---|
| **Developed at** | Wynd Technologies Pvt. Ltd. |
| **Role** | Software Development Intern |
| **Duration** | August 2025 – April 2026 |
| **Repository** | [github.com/arathyjose/gaze_navi](https://github.com/arathyjose/gaze_navi) |

---

## 🧠 The Problem

People with motor disabilities — such as those with paralysis, tremors, or limb differences — struggle to use touchscreen smartphones. Existing accessibility tools are often expensive, require external hardware, or are too complex to set up.

## 💡 The Solution

**GazeNav** turns the phone's front camera into a hands-free controller. By tracking the user's head position in real time, it moves a cursor across the screen — and holding the head still for 2 seconds registers as a tap. No extra hardware. No touch needed. Just your head.

---

## ✨ Features

- 🎯 **Real-time head tracking** — cursor follows head movement using the front camera
- ⏱️ **Dwell-to-tap** — hold head still at a position for 2 seconds to register a tap
- 🔧 **Adaptive calibration** — adjusts to the user's head position and environment
- 📱 **System-level navigation** — supports tap, scroll, and back gestures
- ♿ **Accessibility-first design** — built specifically for users with motor disabilities
- 📷 **No external hardware** — works with any standard Android front camera

---

## 🛠️ Tech Stack

| Category | Details |
|---|---|
| **Language** | Kotlin |
| **Platform** | Android |
| **ML / Tracking** | Google ML Kit (Face Detection & Landmark) |
| **IDE** | Android Studio |
| **Tools** | Git, GitHub, Android SDK |

---

## 🚀 Getting Started

**Prerequisites:** Android Studio installed, Android device with a front-facing camera.

```bash
# Clone the repository
git clone https://github.com/arathyjose/gaze_navi.git

# Open in Android Studio
# File → Open → select the cloned folder

# Build and run on a connected Android device
# Click ▶ Run in Android Studio
```

> **Note:** This app requires camera permission. Make sure to grant it on first launch.

---

## 📱 App Screenshots

| Cursor Control Screen | Calibration Screen |
|---|---|
| <img width="1344" height="1068" alt="app_button" src="https://github.com/user-attachments/assets/d18da89f-22a0-4742-8c4f-2e0a53c95f70" />
 | <img width="762" height="1600" alt="gaze_cali" src="https://github.com/user-attachments/assets/a1217ebd-99cf-4ffa-af26-b92a7c4c0962" />
 |

---

## 🔄 How It Works

```
App launches → Camera activates
        ↓
Google ML Kit detects face landmarks in real time
        ↓
Head position mapped to cursor coordinates on screen
        ↓
Cursor moves as user moves their head
        ↓
User holds head still at a position for 2 seconds  → registered as a tap
        ↓
Full phone navigation — no touch needed
```

---

## 👥 Team

Developed during an internship at **Wynd Technologies Pvt. Ltd.**

| Name | Role |
|---|---|
| Arathy Jose | Software Development Intern |
| Sharon Krishna | Software Development Intern |
| Fathimath Suhra | Software Development Intern |
| Vaishnav S L | Software Development Intern |

---

## 🎯 Use Cases

- Smartphone access for people with paralysis or limb differences
- Hands-free control in sterile or gloved environments
- Assistive technology for elderly users with reduced motor control
