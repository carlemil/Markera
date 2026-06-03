---
name: eval
description: Run the hole-detection evaluation on the training images and show the annotated 3x3 mosaic. Invoke as /eval [seed] [imagesDir]. Project-specific — runs the :eval Gradle module against best.onnx.
user_invocable: true
---

# Eval: Run Hole Detection and Show the Mosaic

Runs the real detection pipeline (preprocess → ONNX → filter → NMS → map-to-image → ring calibration → hit scoring) over a random 3×3 sample of target images and renders an annotated mosaic so the user can eyeball detection quality.

**Optional arguments** (positional, both may be omitted):
- `<seed>` — integer seed for the random image pick, so a run is reproducible. If omitted, a fresh random sample is used each run.
- `<imagesDir>` — folder of images to sample from. Defaults to `D:/ml/holes/dataset/images/train`. Use this to evaluate the `val` split or any other folder.
- `--no-open` — skip auto-opening the mosaic in the default image viewer (Step 3). By default the mosaic is opened on screen after each run.

Perform these steps in order. Stop and report if any step fails.

---

## Step 1 — Build the Gradle command

Base command (run from the project root with the Bash or PowerShell tool):

```
.\gradlew.bat :eval:test --tests "eval.HoleDetectionMosaicTest" --rerun-tasks
```

`--rerun-tasks` forces the test to re-run even when Gradle thinks nothing changed (inputs are external image files), so the mosaic is always regenerated.

Append system properties from the arguments:
- If `<seed>` was given: add `-Dmosaic.seed=<seed>`
- If `<imagesDir>` was given: add `-Dmosaic.images="<imagesDir>"`

Example with both: 
```
.\gradlew.bat :eval:test --tests "eval.HoleDetectionMosaicTest" --rerun-tasks -Dmosaic.seed=7 -Dmosaic.images="D:/ml/holes/dataset/images/val"
```

---

## Step 2 — Run it

Run the command. It takes ~20–40s (ONNX inference on 9 full-res images). The test prints, per image, the source size, raw vs kept detection counts, the total score, and whether the black-ring ellipse fit succeeded — plus the seed used and the output path. Capture that stdout; it's the per-image summary.

If the build fails:
- "model not found" → the ONNX model is missing at `composeApp/src/androidMain/assets/best.onnx`; tell the user.
- "images dir not found" / "no images" → the `imagesDir` is wrong or empty; report it.
- Compilation errors → show the error and stop.

---

## Step 3 — Show the mosaic

The mosaic is written to:

```
D:/source/Markera/eval/build/mosaic/hole_detection_mosaic.png
```

Do **both** of the following:

1. **Open it in the user's default image viewer** so it appears on their screen (the Read tool only renders it into Claude's view, not the user's). Run with PowerShell:
   ```
   Invoke-Item "D:\source\Markera\eval\build\mosaic\hole_detection_mosaic.png"
   ```
   Skip this only if the user passed `--no-open` in the arguments.

2. **Read** the same path with the Read tool so Claude can see the result and describe it accurately in the summary.

---

## Step 4 — Summarize

After showing the image, give a short summary based on the captured stdout:
- The seed used (so the user can reproduce the exact sample with `/eval <seed>`).
- Total images sampled and how many got a ring-fit vs. centre-fallback.
- Anything notable — images with very few/many holes, or where ring calibration failed.

Legend to remind the user what they're looking at:
- **Green boxes** = detected holes, labelled `ring  confidence%`.
- **Cyan ellipses** = the recovered scoring rings (only drawn when the black 7-ring was found); the brightest ring is the 100 mm black ring, the gold centre dot is the inner-X.
- **Caption band** (bottom of each tile) = filename, hole count, total score, ring-fit confidence.

Note for the user: the model is single-class (`hole`) — there is no digit detection — and static images have no camera intrinsics, so scoring uses the ellipse-based foreshortening fallback rather than a perspective warp.
