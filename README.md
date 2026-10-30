# ClearView

A video player for Android that cleans up and sharpens old, low-quality video (pixelated 1080p, compressed
clips, low-resolution uploads) while you watch.

How it works: ExoPlayer decodes the video into a GL texture. Each frame can be

1. upscaled 4x on the phone's NPU (Snapdragon Hexagon, via ONNX Runtime QNN) from the real decoded frame, so it works on
   the actual source detail (this is why it helps far more here than on a game that already renders at screen resolution);
2. cleaned (edge-preserving smoothing of compression blocks and noise), sharpened, and graded
   (clarity, HDR-style tone mapping, deeper blacks, contrast, colour).

Styles: Off, Clean (default), Crisp, HDR, Cinema. "Compare" shows enhanced vs original side by side.

## Setup
1. Build the APK with the GitHub Actions workflow (`bash termux-ship.sh push`, then `bash termux-ship.sh apk`).
2. Open ClearView, tap **Pick AI model** and choose the AI Hub w8a8 ONNX zip (XLSR or QuickSRNetSmall). Pick the
   original .zip so `metadata.json` (quantisation scale) comes along. Without a model the filters still work.
3. Open a video file or link and tap the screen for controls. **Enhance** opens the picture settings.

## Notes
* Protected (DRM) streams are not supported; this is a player for files and normal links.
* NPU-only: if the model cannot run entirely on the NPU, the app tells you and falls back to filters only (there is no
  CPU fallback, it would be too slow).
* The player shows AI status and timings under Enhance > "Show AI status and timings".
* Crashes are saved and shown on the home screen next time you open the app.
