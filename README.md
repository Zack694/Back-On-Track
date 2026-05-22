# Back On Track

A lightweight Fabric mod for **Minecraft 1.21.11** that records raw gameplay
straight to MP4 via **FFmpeg**. Inspired by
[Flashback](https://modrinth.com/mod/flashback) and
[Replay Mod](https://www.replaymod.com/), but without the editing pipeline:
just press a button, play, press again, get a video.

- **Author:** Zack858
- **License:** MIT
- **Default output:** 1280x720 (720p), 60 fps, libx264, MP4
- **Soft dependency:** [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat)

## Features

- One-button start/stop recording from the **Pause menu** (and a configurable
  hotkey, default `F8`).
- **100% raw capture**, including the HUD, hotbar, chat, and any open screens.
  Tap the framebuffer at the very end of the render loop, exactly like
  Flashback's "raw" mode.
- **Hardware-friendly encoding**: pipes raw frames to FFmpeg over a stdin
  pipe, with software (`libx264`/`libx265`) and hardware
  (`h264_nvenc`, `hevc_nvenc`, `h264_qsv`, `h264_vaapi`) encoder presets
  selectable from the in-game settings screen.
- Configurable resolution, framerate, bitrate, encoder, preset, and container
  (MP4/MKV/MOV).
- **Async, lock-free** frame queue with a dedicated writer thread so recording
  has minimal effect on game framerate. Drops frames if the encoder ever
  falls behind, rather than stalling rendering.
- **Audio capture** via the system loopback device (Stereo Mix / PulseAudio
  monitor / VB-CABLE / BlackHole), muxed into the final video as AAC.
- **Simple Voice Chat support** as a soft dependency: incoming voice samples
  are routed into the recording when the loopback path can't pick them up.
- Configurable on-screen `REC` indicator with elapsed time and frame counter.
- Persisted JSON config at `config/back-on-track.json`.

## Requirements

| Requirement | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | >=0.16.14 |
| Fabric API | >=0.141.2+1.21.11 |
| Java | 21+ |
| FFmpeg | 4.x or newer, on `PATH` (or set the path in settings) |

### Installing FFmpeg

The mod calls out to a system FFmpeg binary; it is not bundled in the jar to
keep the download small.

- **Windows**: download from <https://www.gyan.dev/ffmpeg/builds/> and add the
  `bin/` folder to your `PATH`, or paste the full path to `ffmpeg.exe` into
  the mod settings.
- **macOS**: `brew install ffmpeg`
- **Linux**: install via your package manager, e.g. `sudo apt install ffmpeg`
  or `sudo dnf install ffmpeg`.

### Installing a system loopback (for audio)

Java's audio API can capture only from input lines, not from the speaker
output. To record game audio you need a loopback device the OS exposes as an
input:

- **Windows**: enable `Stereo Mix` in your sound settings, or install
  [VB-CABLE](https://vb-audio.com/Cable/).
- **macOS**: install [BlackHole](https://existential.audio/blackhole/) and
  route output through a Multi-Output Device.
- **Linux (PulseAudio/PipeWire)**: every output already exposes a
  `Monitor of …` source; the mod auto-detects it.

If no loopback device is found the mod records video only and logs a warning.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for 1.21.11.
2. Drop the latest `back-on-track-*.jar` and the matching
   [Fabric API](https://modrinth.com/mod/fabric-api/version/0.141.2+1.21.11)
   into your `mods/` folder.
3. (Optional) Drop in
   [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) if you
   want voice in your recordings.

## Usage

- **Start / Stop recording**
  - Press **F8** (rebindable in *Options → Controls → Back On Track*), **or**
  - Open the **Pause menu** and click **Start Recording / Stop Recording**.
- **Settings:** *Pause menu → Recording Settings*, or edit
  `config/back-on-track.json` directly while the game is closed.
- **Output files** land in `<game-dir>/recordings/` by default, named
  `back-on-track_YYYY-MM-DD_HH-mm-ss.mp4`.
- A blinking red **REC** indicator with elapsed time and frame count is shown
  in the top-left while a recording is active (toggleable in settings).

## Configuration

`config/back-on-track.json` (created on first launch):

| Key | Default | Notes |
|---|---|---|
| `width` / `height` | `1280 / 720` | Output resolution. FFmpeg scales the captured framebuffer to this size with Lanczos filtering. |
| `fps` | `60` | Target output framerate. Capped 1-240. |
| `bitrateKbps` | `8000` | Video bitrate. |
| `videoCodec` | `libx264` | Any encoder your FFmpeg supports: `libx264`, `libx265`, `h264_nvenc`, `hevc_nvenc`, `h264_qsv`, `h264_vaapi`, etc. |
| `preset` | `veryfast` | x264/x265 preset. Faster = larger files, less CPU. |
| `container` | `mp4` | `mp4`, `mkv`, or `mov`. |
| `captureGui` | `true` | Capture HUD/menus (true Flashback-style raw recording). |
| `captureAudio` | `true` | Record system loopback audio. |
| `captureVoiceChat` | `true` | Mix Simple Voice Chat samples in if available. |
| `audioSampleRate` | `48000` | |
| `audioChannels` | `2` | Stereo. |
| `audioBitrateKbps` | `192` | AAC bitrate for the muxed audio track. |
| `ffmpegPath` | `ffmpeg` | Either a `PATH` lookup or absolute path to `ffmpeg`. |
| `outputDir` | `recordings` | Relative to the game directory. |
| `showHud` | `true` | Show the REC overlay while recording. |

## Building from source

```sh
git clone https://github.com/Zack694/Back-On-Track.git
cd Back-On-Track
./gradlew build
```

The compiled jar lands in `build/libs/back-on-track-<version>.jar`.

GitHub Actions builds every push and PR; tagged builds (`v*`) are published
as a GitHub Release with the jar attached. See
[`.github/workflows/build.yml`](.github/workflows/build.yml).

## How it works (short version)

1. **Frame capture.** A Mixin into `GameRenderer.render` runs at the very
   end of every frame. It calls `ScreenshotRecorder.takeScreenshot(...)` to
   read back the main framebuffer (which already contains the GUI, exactly
   like a screenshot). The async readback hands us a `NativeImage`.
2. **Conversion.** Each pixel int (`ABGR` little-endian) is written
   straight into a `byte[]` via an `IntBuffer` view -- no per-pixel boxing.
3. **Encoder pipeline.** `FFmpegEncoder` spawns
   `ffmpeg -f rawvideo -pix_fmt rgba -s WxH -r FPS -i - -vf scale=… -c:v … out.mp4`
   and pipes frames into stdin. A bounded queue + writer thread decouples
   render-thread submission from encoder backpressure.
4. **Audio.** `AudioCapture` finds a loopback `TargetDataLine`, dumps PCM
   into a sibling WAV file. Simple Voice Chat samples (if any) are mixed
   into the same stream via the `voicechat` Fabric plugin entrypoint.
5. **Finalisation.** When you stop, an `AudioMuxer` runs a quick
   `ffmpeg -c:v copy -c:a aac` mux pass to combine video and audio into the
   final container. Done off-thread; the game keeps responding.

## Limitations / non-goals

- **No editor.** This mod is intentionally write-only. If you need
  smooth-camera replays, use Replay Mod or Flashback.
- **No bundled FFmpeg.** Saves ~80 MB per platform; you install FFmpeg once.
- **Audio capture relies on the OS loopback.** See the install notes above.
- **Window resize while recording stops the recording** rather than producing
  a corrupted file. Set your size before you start.

## License

MIT -- see [LICENSE](LICENSE).
