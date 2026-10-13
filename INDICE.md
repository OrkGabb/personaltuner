# ÍNDICE — Downloads\test (pós-faxina 2026-10-13)

Raiz mínima. Aparelho intocado (módulo 2.9.4/1596 stageado, APK v1 instalado).

## Trabalhar aqui
- `fusao-v2.0/` — stage ATIVO (único que a build usa). `module.prop` = verdade.
- `tools/` — `fusao-build.py` (gera em `_releases/`), `fusao-sweep.py`,
  `test-fusao-offline.sh`, `build-observer.sh`, `check-dex.py`.
- `fusao-apk/` — APK + `LEIA-ME.md` (pasta p/ outra AI: design/responsividade).
  Fontes do app ficam no WSL `~/FusaoApp` (ver LEIA-ME).
- Docs SSOT (colar no novo chat): `status.md` + `handoff.md` + `nodes-m54.md`
  (`_historico/ataque.md` = plano original, cumprido — só se for mexer no módulo).

## Arquivo morto (não usar sem motivo)
- `_releases/` — 22 zips `fusao-v*-M54.zip` (atual `fusao-v2.9.4-M54.zip`).
- `_referencia/` — Encore / Stellar / Rairin + `rboost.apk` + baselines + reverts.
- `_historico/` — stages velhos (`fusao-v0.1`, `fusao-v1.0`) + Rust
  (`fusao-brain`, `fusao-guard`, `fusao-ebpf`, `fusao-observer`).

## Comandos padrão
- Build: `python tools/fusao-build.py --stage fusao-v2.0 --out _releases/fusao-vX-M54.zip`
- Kill-list: `python tools/fusao-sweep.py --stage fusao-v2.0`
- Harness: `bash tools/test-fusao-offline.sh`
- Flash: `adb push _releases/fusao-vX-M54.zip /sdcard/Download/` + `ksud module install`
