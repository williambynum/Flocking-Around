# Pixel 9 Signal Survey

Multi-shot AR RF survey tool. Point the phone at a space, sweep, and take a series of
photographs; the app identifies electronic devices in frame, listens to every radio the
Pixel 9 actually has, and burns annotations onto each photo saying what each device is and
what it is doing on the air — while stating plainly which claims were **measured** and which
are only **inferred**.

Built for the **base Pixel 9** (no UWB). Pro models light up UWB checks automatically via
`FEATURE_UWB` but nothing depends on it.

---

## What it actually does

```
ARM      pan slowly · ARCore builds depth-from-motion · radios log RSSI vs. world position
  ↓
SHOOT    pixels freeze instantly (PixelCopy) · camera matrices + depth map captured
  ↓
LISTEN   6 s behind the frozen image: Wi-Fi scan, RTT ranging, BLE integration,
         classic BT inquiry, cell poll, mDNS sweep — while the heavy classifier runs
  ↓
REPEAT   walk a few metres, shoot again — every shot shares one ARCore world frame
  ↓
RESOLVE  targets merged across shots · emitters trilaterated from the whole session
  ↓
EXPORT   annotated JPEG per shot + plan view + full JSON, EXIF-embedded per shot
```

The multi-shot design is the point. A single Wi-Fi RTT range is a sphere — "3.4 m away",
no direction. Take the same measurement from three standing positions, with ARCore supplying
centimetre-accurate poses for each, and the spheres intersect at a point. An access point
trilaterated during shots 1–2 is then drawn, correctly placed, on shot 5 — whether or not
the camera ever recognised it.

---

## Module layout

```
:app       MainActivity, permission gate, theme. No DI framework (see SignalSurveyApp.kt).
:model     Pure data: RadioObservation, SignalCatalog, DeviceOntology, SurveySession,
           CameraSnapshot (projection + unprojection), DepthSnapshot.
:ar        ARCore session config, a ~200-line GL camera-background renderer, frame capture,
           anchor placement, magnetometer heading.
:vision    ML Kit detection + tracking, custom TFLite classifier hook, rotation mapping.
:radio     Wi-Fi (+ RTT), BLE, classic BT, cellular, GNSS, mDNS. One RadioHub owns them all.
:fusion    Association scoring, trilateration, cross-shot target merging, emitter resolution.
:export    Callout layout, annotation rendering, plan view, JSON + EXIF export.
:survey    Session builder, capture orchestration, ViewModel, Compose UI.
```

**No SceneView / Filament.** The app draws zero 3D content — every annotation is 2D Compose
or Canvas — so a scene graph would be ~8 MB and a version-coupling headache for one textured
quad. `BackgroundRenderer.kt` does the job directly, and being a plain `SurfaceView` is what
makes `PixelCopy` capture trivial.

**No CameraX in the AR path.** ARCore owns the camera device; the two cannot share it.
Inference runs on `Frame.acquireCameraImage()`.

---

## The complete signal map

### Directly observable — there is a receiver and a public API

| Family | Standard / band | API | Notes |
|---|---|---|---|
| Wi-Fi APs | 802.11 b/g/n/ac/ax/**be**, 2.4 / 5 / **6 GHz** | `WifiManager` | SSID, BSSID, RSSI, channel width, security, `wifiStandard` |
| Wi-Fi 7 MLO | multi-link 2.4+5+6 | `getAffiliatedMloLinks()` (API 34) | One logical AP across several radios |
| **Wi-Fi RTT** | 802.11mc FTM | `WifiRttManager` | **True distance ±1–2 m.** The only real ranging on a base Pixel 9 |
| BLE | Bluetooth 5.3, 1M/2M/Coded PHY | `BluetoothLeScanner` | Extended advertising, manufacturer data, service UUIDs |
| Bluetooth BR/EDR | 2.4 GHz FHSS | `startDiscovery()` | Class of Device gives a real device category |
| Auracast | LE Audio broadcast | BLE scan on UUID `0x1852` | |
| 5G NR | sub-6 + mmWave (SKU-dependent) | `CellInfoNr` | NCI, PCI, TAC, NRARFCN→Hz, ssRsrp |
| LTE / UMTS / GSM | all legacy | `CellInfoLte` etc. | Includes neighbour cells, timing advance |
| GNSS | GPS L1/**L5**, Galileo, GLONASS, BeiDou, QZSS | `GnssStatus` | Azimuth + elevation → projectable onto the photo |
| mDNS / DNS-SD | over IP | `NsdManager` | How Wi-Fi *clients* get found at all |
| NFC | 13.56 MHz | `NfcAdapter` | Contact range only |

### Not observable — always rendered as inferred, with the reason

Zigbee · Thread / Matter-over-Thread · Z-Wave · LoRa · 315/433/868/915 MHz ISM (TPMS, key
fobs, garage doors) · DECT · proprietary 2.4 GHz (Logitech Unifying, RC links) · IR remotes ·
satellite downlink · **Wi-Fi client devices** (no monitor mode on Android) · **UWB** (no radio
on base Pixel 9, and never passively sniffable — it needs a BLE out-of-band handshake).

`SignalCatalog` carries an `Observability` for every profile and `DeviceCapabilities`
supplies the per-device reason, so a card never says "not detected" when the truth is
"cannot be detected".

---

## Ranging hierarchy

With no UWB, distances come from — in descending order of trust:

1. **Wi-Fi RTT** (`RangeSource.WIFI_RTT`) — millimetres with a stated std dev
2. **Multi-shot RTT trilateration** (`PositionMethod.RTT_TRILATERATION`) — least squares +
   Gauss–Newton over ranges from ≥3 distinct positions
3. **ARCore depth** (`AR_DEPTH`) — depth-from-motion, ±5 % to ~8 m
4. **RSSI gradient** (`RSSI_GRADIENT`) — coarse-to-fine grid search over the sampled field;
   reference power eliminated analytically. Several metres of error, and labelled as such
5. **Path loss** (`PATH_LOSS`) — an estimate, always rendered with a `~`

`RangeSource.isMeasured` and `PositionMethod.isMeasured` drive the visual language: a filled
dot for measured, hollow for inferred, everywhere including the plan view.

---

## Build

Toolchain is pinned to what is already cached on this machine:
AGP 8.7.2 · Kotlin 2.0.21 · Gradle 8.9 · compileSdk 35 · JDK 17 · **minSdk 33**.

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:installDebug
```

minSdk 33 is the floor for `NEARBY_WIFI_DEVICES`, `ScanResult.getWifiSsid()` and
`WIFI_STANDARD_11BE`. Target hardware runs Android 14/15, so going lower buys nothing and
costs a great deal of version branching.

The debug APK is ~104 MB, almost entirely ML Kit's bundled detection model and ARCore
natives. A release build with `isMinifyEnabled = true` and ABI splits (`arm64-v8a` only)
brings that down substantially.

### While developing

Wi-Fi scans are throttled to four per two minutes for foreground apps. Turn that off on the
dev device or you will misdiagnose your own code:

```bash
adb shell settings put global wifi_scan_throttle_enabled 0
```

---

## Bring your own classifier

The app runs today without a model: ML Kit detects and tracks objects class-agnostically and
labels them `unknown_device`. Anchors, RF fusion, trilateration and export all work — only
the labels are missing.

To add labels, drop a TFLite image classifier with ML Kit metadata at:

```
app/src/main/assets/device_classifier.tflite
```

Its output labels must match `DeviceOntology.labels`:

```
wireless_router · mesh_node · ceiling_access_point · smart_speaker · security_camera
smart_tv · cell_tower · small_cell · smart_lock · ble_tracker · printer · laptop
smartphone · smartwatch · smart_thermostat · smart_bulb · iot_hub · vehicle
satellite_dish · antenna_mast
```

MediaPipe Model Maker (EfficientNet-Lite0 or MobileNetV3) exports this format directly.
Budget 300–800 images per class — no public dataset covers these categories, and collecting
it is the long pole of the project.

Optional: drop the full IEEE OUI registry at `app/src/main/assets/oui.csv` as
`prefix,vendor` lines to widen vendor matching beyond the built-in table.

---

## Export bundle

One folder per survey, containing everything:

```
Download/SignalSurvey/<yyyy-MM-dd_HHmm>_<label>/
├── report.html      the whole survey as a web page, images inline — start here
├── summary.txt      plain text, readable anywhere
├── emitters.csv     one row per emitter — opens in Excel / Sheets / pandas
├── devices.csv      one row per signal claim against each identified device
├── survey.json      complete machine-readable record
├── plan_view.png    top-down: path, shot wedges, located emitters, error rings
└── shot_01.jpg …    annotated photographs, shot record embedded in EXIF UserComment
```

Written to the **Downloads** collection rather than Pictures, because it is the only
MediaStore collection that accepts arbitrary MIME types — which is what lets the images, the
CSVs and the HTML share one directory. The trade-off is that the shots do not appear in the
system gallery; a survey is a document, not a photo album. If MediaStore is unavailable the
exporter falls back to `Android/data/com.pixel9.signalsurvey/files/Documents/SignalSurvey/`
so an export never silently produces nothing.

Every format keeps provenance attached. `emitters.csv` has explicit `range_is_measured` and
`position_is_measured` columns so a spreadsheet filter can drop the estimates in one click;
`devices.csv` has a `claim_status` column that is `MEASURED` or `INFERRED` per row, with the
supporting evidence or the reason it could not be confirmed alongside it. Collapsing that
distinction is how a survey turns into confident nonsense.

`ReportBuilder` is covered by a JVM test that generates a full sample bundle to
`export/build/sample-report/` — useful for iterating on the report without a device:

```bash
./gradlew :export:testDebugUnitTest
```

---

## Roadmap

| Phase | Status |
|---|---|
| 1. Radio layer + list UI | done — every scanner emits `RadioObservation` |
| 2. ARCore session, capture, projection | done |
| 3. Multi-shot session, callout rendering, plan view | done |
| 4. Custom classifier | **needs a dataset** — the app runs unlabelled until then |
| 5. Fusion, trilateration, RSSI localisation | done — worth validating against a tape measure |
| 6. USB-OTG SDR for sub-GHz | not started; the only honest route to "all signals" |

Known gaps worth attention before field use:

- **BLE address rotation** (~15 min) means long sessions can split one tracker into several
  entries. `RadioObservation.fingerprint` exists for this; the session merger does not use
  it yet.
- **Trilateration accuracy** is untested against ground truth on real hardware.
- **Heading outdoors** could be much better with ARCore Geospatial — see below.

---

## Heading

Only one feature needs to know where north is: projecting GNSS satellites onto a shot.
Trilateration, RSSI localisation, visual anchors, the plan view and every annotation live
purely in the ARCore world frame and are unaffected by heading error.

A single magnetometer reading indoors is close to worthless — near rebar, a motor or a
laptop it can be 40° out and perfectly confident about it. `HeadingResolver` addresses that
three ways:

**Distortion rejection.** `GeomagneticField` says what the field *should* measure here.
Samples whose magnitude deviates >20%, or whose dip angle deviates >15°, are discarded
rather than averaged in. The dip test is the sharper of the two — local distortion tilts the
field out of plane long before it changes the magnitude much.

**Averaging over the walk.** ARCore supplies accurate *relative* orientation, so every
sample along the arming sweep independently estimates the same fixed offset, and
position-dependent distortion largely cancels over a few metres. Samples are combined with a
circular mean and trimmed at 2.5σ. Sampling is gated on movement or rotation so standing
still cannot manufacture false confidence.

**A measured error bar.** The circular variance of the surviving samples *is* the
uncertainty — nothing is assumed. That number drives the rendering: satellites are drawn as
an arc spanning the heading error, not a point; above 15° nothing is projected at all and the
report says why; the plan view's compass rose shows the uncertainty as a wedge, and reads
"no heading — orientation is arbitrary" when none was resolved.

The maths lives in `CircularStats` (`:model`) rather than inside the resolver so it can be
tested on the JVM — angle wrap-around is where this goes wrong silently, and the arithmetic
mean of 350° and 10° is 180°, pointing exactly backwards.

```bash
./gradlew :model:testDebugUnitTest
```

**What is deliberately not used:** ARCore Geospatial. Its sub-degree heading comes from VPS,
which derives from Street View and therefore exists *outdoors*. Indoors — where the
magnetometer is worst — it falls back to fusing GPS with this same magnetometer. It is the
right answer for outdoor surveys where tower bearings matter, and no answer at all for the
case that actually hurts. If added, gate it with `Session.checkVpsAvailability(lat, lng)`
and note that `GeospatialPose.getHeading()` is deprecated as of ARCore 1.40 in favour of
`getEastUpSouthQuaternion()`.

---

## Privacy

Everything stays on the device; there is no network egress. Exports contain BSSIDs, BLE
addresses, cell identifiers and, if location is granted, precise coordinates — treat an
exported bundle as sensitive. Shipping to Play would require a privacy policy and prominent
disclosure under the User Data policy.

The Wi-Fi and Bluetooth scan permissions are deliberately declared **without**
`usesPermissionFlags="neverForLocation"`, because the app genuinely does derive position from
those signals. Claiming otherwise would be a false declaration.
