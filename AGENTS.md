# 框一下 Region Snap · io.embodit.regionsnap

Factory app `region-snap`, design package `spatial-app-factory/apps/region-snap/design-package.json` (skeleton D,
STAGE_MIXED, one system window). Draw a frame on a real wall / table (or floating) with a controller ray + trigger or
eye gaze + pinch, shoot, get a straightened picture (4-point perspective, true aspect), save it to the album and
optionally pin it back on the wall as a note (world anchor).

Spatial SDK 6.1.9 (BOM), SpatialUI + PicoTheme only. Built from the teleop-dojo skeleton (Full Space stage, system
window + TabBar, debug receiver), plane / anchor gateways from cam-coverage, SpatialML Readback from low-vision v0.1.7
(the design's primary reference spatial-ruler is not on this machine).

## Build / run (kj-alienware, Git Bash)

```
tools/dev.sh build | test | install | launch | relaunch | log REGEX | crash   # DEV=emulator-5556 AVD=PICO_6_1_kj-alienware_2
tools/cap.sh OUT.png               # PrintWindow capture of the emulator window (pico-cli screenshots come back half black here)
python -I tools/strings.py         # copy table -> values / values-zh / values-en
python -I tools/make_samples.py    # emulator sample frames (whiteboard / a4 / plate) + their known quads
python -I tools/loudness.py OUT    # loudness evidence (rendered at default gain)
python -I tools/icon512.py OUT     # 512 px launcher icon
```

Emulator facts: no passthrough camera (SpatialML frames → S18; use `sample …`), controller provider reports
DEVICE_NOT_SUPPORTED, eye provider WITHOUT_PERMISSION, the hand provider is NOT started on the emulator (it breaks the
emulator's own gaze + pinch). The emulator room reports ~57 planes (walls, tables...). It renders 1-4 fps unless
`tools/harness/emu.ps1 -Avd PICO_6_1_kj-alienware_2 fast on` (cap.sh does it). The system permission dialog renders as
a black slab in Full Space and leaves the stage stuck: grant with `adb shell pm grant io.embodit.regionsnap android.permission.CAMERA`.

## Code map

- `domain/` (pure JVM, unit-tested): `Frame.kt` (SnapFrame, handles, rotate / level snap, push, ray-plane aim),
  `Judge.kt` (StrokeJudge box / line / loop, PressJudge = design pressPriority, PinchDetector, InputArbiter, OneEuro),
  `Snapper.kt` (the drawing engine: InputFrame → SnapEvents; the controller and the eye + hand go through the same code),
  `Rectify.kt` (projection, preview region, homography, output size, calibrate-once planar pose), `Library.kt` (shots /
  pins, delete order, placeUpright, pinInFront, pins-out-of-sight).
- `platform/`: `input/Controllers.kt`, `input/HandsEyes.kt`, `tracking/HeadTracker.kt`, `sense/World.kt` (planes as maths
  only, anchors), `capture/Camera.kt` (SpatialML Readback: rectifiedVSTAccess → RGBA global tensor, burst ≤ 3, sharpest
  kept; sample frames), `capture/Gallery.kt` (Matrix.setPolyToPoly straighten, MediaStore Pictures/RegionSnap, private
  copy, preview cache), `Store.kt` (prefs, shots json, pins.json), `Sound.kt`.
- `ui/RsController.kt` state machine (design S00-S24); `ui/scene/RsScene.kt` the frame system (input → Snapper →
  entities; shutter bar, pin bar, hint plate); `ui/window/` the system window (TabBar 取景 / 已截 / 设置).

## Debug broadcast (debug builds only; every input command goes through the same InputFrame path)

```
adb -s emulator-5556 shell "am broadcast -a io.embodit.regionsnap.DEBUG -p io.embodit.regionsnap --es cmd 'goto S09'"
tools/dev.sh cmd 'goto S09'
```

| cmd | what |
|---|---|
| `goto S01…S24` | walk to a design state with the user's steps (S00 = cold start, S23 = Home key: logged) |
| `input eye\|controller\|finger\|head\|eyenohand\|off` | which source the stand-in plays / panel input line (S17 / S17b) |
| `ray ahead d [right up]` / `ray x,y,z,dx,dy,dz` / `press` / `release` / `tap` | the stand-in aim and press (one step per engine frame) |
| `box w h [d] [hold] [right]` / `loop r [d]` / `line [d]` / `drag x,y,z x,y,z [hold]` / `handle i dx dy [hold]` | strokes, handle drags |
| `key a\|b` / `stick x y` / `controller on` / `hands live\|lost` | controller keys, stick (turn / push), provider overrides |
| `plane wall d\|table\|clear` | stand-in planes (the emulator room has its own too) |
| `frame w,h[,floating]` / `same` / `redraw` / `rotate` | a frame at the aim |
| `sample whiteboard\|a4\|plate\|none` / `shoot` | sample frames (emulator) / shoot |
| `rectify on\|off` / `corner i x y` / `save` / `savepin` / `retake` | preview (S09) |
| `pin n` / `front n` / `unpin n` / `pinbar n` / `open n` / `delete [yes]` | shots and notes |
| `calib start\|ok\|degraded\|cancel\|clear` | S20 |
| `noPermission camera\|eye [off]` / `anchorsFail [off]` | S16 / S17 / S19 |
| `tab snap\|shots\|settings` `lang zh\|en\|auto` `mute [off]` `pinch auto\|box\|loop` `res 1080|2k|4k` `howto`\|lo` `stick2surface [off]` | settings |
| `close` / `openpanel` / `recall` / `quit` / `exit` / `start` / `onboard` / `about` | window, quit, onboarding |
| `bar long\|normal` / `hint NAME n` / `pitch deg` / `fit [reset]` / `where` / `panels` / `dump` | QA helpers (`bar long` keeps a hint past 3 s, but never past the state it describes, q1-4) |
| `rotate left\|right\|face` / `grip on\|off` (+ `stick x y`) | v0.1.4: [左转] / [右转] (15°) / [回正]; the stand-in controller grip, with the stick it turns the frame about the vertical axis (60°/s, catches on facing) |
| `hold shrink\|off` | S05: freeze the loop → rect shrink on its middle frame (`goto S05` turns it on; `hold off` lets it finish) |

Log prefixes: STATE INPUT AIM FRAME SHOOT CAM PROJ RECT SAVE PIN ANCHOR PANEL GATE PERM HINT DUMP CMD (tag `RegionSnap`).

## Screenshot list for QA (each in `lang zh` and `lang en`)

S00 (`pitch 30` at cold start), S01, S03, S04 (`goto S04`, hold), S05 (`goto S05`: hold on, wait for `STATE S05`), S06, S07, S09 (`sample
whiteboard|a4|plate`), S10, S12 (note on the wall, F↑ upright), S13 (empty + with shots), S14 + delete confirm, S15,
S16, S17, S17b, S18, S19, S20 (+ calibration preview), S21, S22 (plate + bar ⌂), S24. Put a stand-in wall first
(`plane wall 1.4`); `goto S06` draws to the right of the window (the system opens the window straight ahead).

## Main window vs the frame (v0.1.1, QA #1 q1-1)

SDK 6.1 opens the Planar window straight ahead of the gaze (measured on the emulator: about 0.82 m out, 600 dp = 0.48 m,
so it spans ±16.3°) and cannot move it. `domain/WindowSpot.kt` is the one placement function:
- **Tuck**: while a frame is being drawn / adjusted / framed and the open window covers any of it or its shutter bar (gap
  < 1°), the app closes the window (`PANEL tuck`), the plate says "The panel stepped aside…" + [Open panel], the bar
  gets [Panel]. Design state stays S04 / S05 / S06 (not S22). A·X still shoots while tucked with a frame.
- **Gate**: every open (launch, [Open panel] / [Panel] / A·X / long press in S22, preview after a shot, recall, restore
  after −) waits until a window opened along the current gaze keeps the frame and bar ≥ 10° clear (`TURN hint=±1
  need=N`, plate "Look a little to the left / right"). Restore after − only when the old spot is still clear.
- **No frame yet (v0.1.2, QA #2 q2-1)**: the Snap page has "Panel in the way of your shot? [Move aside]", and a short tap
  on the window's empty glass (a tap no control consumed) in S02 / S03 does the same (`PANEL tuck why=key|blank tap`).
  While tucked, a held press is a stroke and A·X shoots; [Panel] / [Open panel] bring the window back. Debug: `moveaside`.
- **Plate (q2-2)**: `WindowSpot.plateSpot` keeps the hint plate ≤ 25° off the gaze, below (18° / 22° / 25°) or above the
  frame first, then 10° / 20° sideways; a turn over 20° reads "Turn about 40° to the right →".
- Emulator: turn the camera with a right-button drag (`emu.ps1 drag X1 Y X2 Y -Button right`, ~26 fb px per degree);
  `goto S06` then a real tap on the plate's [Open panel], turn left ~15°, and the window opens beside the frame.

## Frame orientation (v0.1.4)

New frames stand upright and face the user (normal = level direction from the frame centre to the eyes at the press,
roll 0); on a wall / table the centre is put back on the surface along the line of sight. Turning is about the vertical
axis only: grip + stick left / right (`Snapper.stepStick`), the shutter bar / Snap page [Left] [Right] (15°, a step
that passes facing stops on it) and [Face me] (`Snapper.yawStep` / `faceUser`). `FacingTest` covers both paths.

## One ray, output tiers (v0.1.5)

No app-drawn controller ray: in Full Space the system ray is the only one (profile D41; the reticle stays, it is a
hit mark). Output tiers `OutRes` 1080P / 2K / 4K (long side 1920 / 2560 / 3840, short side from the frame's aspect,
2K by default, prefs key `outRes`): settings row + preview row (SpatialUI SegmentControl). The tier also asks the camera
for a 4:3 readback that big (`Camera.prepareAny` steps down to 640 × 480 when a session will not build); the picture is
never upscaled, the preview line says when the camera pixels fell short. `OutResTest` covers sizes, aspect, honesty and
persistence. Settings has a [How to] page (`how_to_*` strings, gate how-to).
