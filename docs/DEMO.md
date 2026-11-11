# Caldera Demo Runbook

Caldera is a client-side Fabric mod that compiles its own Vulkan/GLSL shader packs. It does not load Iris or OptiFine shaderpacks.

There is currently no hosted demo and no published gameplay video. This runbook is the reproducible demo path for reviewers.

## 1. Prerequisites

| Component | Version |
| --- | --- |
| Minecraft | 26.3 |
| Fabric Loader | >= 0.19.5 |
| Fabric API | >= 0.160.5 |
| Sodium | >= 0.9.2 |
| Java | 25 |
| GPU backend | Vulkan |

The current validation environment is NVIDIA. AMD and Intel devices have not been validated.

## 2. Prepare the visual pack

The bundled visual pack is intentionally not committed:

```text
src/main/resources/caldera-bundled/Caldera-Realistic.zip
```

Place the pack at that exact path before building or launching the development client. Without it, the build may compile, but the JAR will not contain the bundled shader pack used by the default experience.

## 3. Launch the development client

Set `JAVA_HOME` to the JDK root directory, not the `bin` directory.

macOS/Linux:

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew runClient
```

Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
.\gradlew.bat runClient
```

## 4. Enable Caldera

1. Open **Video Settings**.
2. Confirm the graphics backend is **Vulkan**.
3. Open **Shaders** and enable Caldera.
4. Select **Caldera Realistic** if it is not already selected.
5. Apply the settings and load a world.

If the game falls back to OpenGL, Caldera cannot run. The mod should leave normal OpenGL gameplay unaffected.

## 5. Expected visual checklist

Capture a short sequence that makes each subsystem visible:

| Check | What to show |
| --- | --- |
| Cascaded shadows | Terrain and vegetation casting shadows near the player and farther into the scene |
| Volumetric clouds | Cloud movement, internal light attenuation, weather response, and daily color change |
| Atmosphere | Height-dependent fog, weather haze, and world-space sun shafts |
| Water | Depth-based refraction, wave normals, absorption, and screen-space reflections |
| Post-processing | HDR, bloom, exposure, contrast, and color grading |
| Settings surface | The shader settings screen with the 28 pack options |

## 6. Recommended recording

A useful 20-30 second clip can be recorded without editing:

1. Start in daylight with shadows clearly visible.
2. Pan toward water and show the reflection changing with the view.
3. Look up at moving clouds.
4. Move to sunset or night to show atmosphere and sky changes.
5. Open the shader settings screen for two or three seconds.

Do not use footage from another shaderpack or a different version. The recording should match the `26.3` Fabric/Vulkan build in this repository.

## 7. What CI does and does not prove

The GitHub Actions workflow runs:

```bash
./gradlew build
```

That verifies compilation, packaging, and the JUnit suite. It does **not** launch Minecraft, compile GPU pipelines, or compare screenshots. Visual correctness requires the manual run above.

Known limitations are listed in the repository [README](../README.md#known-limitations).
