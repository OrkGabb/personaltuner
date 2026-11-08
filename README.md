# PersonalTuner — FUSAO-M54 (module + app)

Original source code repository. Third-party code, binary releases, and dead history
are kept out (see `.gitignore`).

## Layout

- `app/` — FUSAO app (Kotlin/Compose, `com.orkgabb.fusao`). Build: `cd app && ./gradlew assembleDebug`.
  The SDK is resolved via `local.properties` (`sdk.dir=...`, NOT committed — each machine generates its own)
  or via the `ANDROID_HOME`/`ANDROID_SDK_ROOT` environment variables. Requires a rooted device for actual testing.
- `fusao-v2.0/` — active KernelSU module stage (v2.9.4/1596, see `module.prop`).
- `tools/` — `fusao-build.py` (builds the flashable zip), `fusao-sweep.py` (kill-list),
  `test-fusao-offline.sh` (test harness). Commands documented in `INDICE.md`.
- `fusao-apk/LEIA-ME.md` — interface contract used by the app (`fusao` CLI + flags in
  `/data/adb/fusao/`). **Mandatory reading before modifying the app.**
- `status.md` + `handoff.md` + `nodes-m54.md` — live project context.
  `INDICE.md` — general project map.

## Contribution Workflow (e.g., app UI/responsiveness)

1. Read `fusao-apk/LEIA-ME.md` (current UI screens + critical constraints).
2. Modify only `app/src/...` + resources. Do not change `applicationId`, `minSdk`, or the
   root bridge protocol (`app/.../Fusao.kt`, `Root.kt`) without prior discussion.
3. Validate: `./gradlew assembleDebug` (or `lintDebug`). Runtime testing is impossible
   without a rooted device — state this clearly in the PR.
