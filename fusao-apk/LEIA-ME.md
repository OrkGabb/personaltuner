# fusao-apk — entrega p/ outra AI (APK, design, responsividade)

Pasta separada do resto bagunçado de `Downloads\test`. Não mover o resto sem
atualizar `handoff.md`/`status.md` (têm paths absolutos).

## O APK da sessão anterior
- Origem (WSL, fonte da verdade): `~/FusaoApp/app/build/outputs/apk/debug/app-debug.apk`
  - package `com.orkgabb.fusao`, version `1.0.0 (code 1)`, 24.6MB, build 2026-10-13 16:02
  - Stack: Kotlin/Compose + libsu 6.0.0 (JitPack), AGP 8.7.3, minSdk 29, target/compile 36
  - Zero permissões (sem INTERNET; lista de apps via `<queries>` LAUNCHER)
- Cópia nesta pasta: `FusaoApp-v1-debug.apk` (byte-idêntica à do WSL em 2026-10-13)
- Estado no aparelho ao fechar (ver `../status.md` FECHAMENTO 2026-10-13):
  instalado, aberto, única interface (WebUI extinta na v2.9.1). Flags ao fechar:
  fusaod+framework+perf+preload+ramclean+dnd on, gos pausado, módulo 2.9.4/1596 stageado.

## Fontes (ficam no WSL, NÃO copiados p/ cá)
- `~/FusaoApp/` — projeto (5 .kt em `app/src/main/java/com/orkgabb/fusao/`):
  `MainActivity.kt`, `FusaoApp.kt`, `AppViewModel.kt`, `Fusao.kt`, `Root.kt`
  + `app/src/main/AndroidManifest.xml`
- Para a outra AI trabalhar: ou aponte ela p/ `\\wsl$\Ubuntu\home\ork\FusaoApp`,
  ou peça p/ copiar a árvore p/ cá antes de mexer em design/responsividade.

## Contrato que o APK usa (não quebrar no redesign)
- O app NUNCA escreve sysfs. Tudo via CLI `fusao` como root (libsu) + flags em
  `/data/adb/fusao/` + logs (`fusao.log`/`fusaod.log`) + packs
  `/data/adb/fusao/preload/*.pack` + `gamelist.txt`. Abas atuais:
  Status / Flags (7 toggles) / Preload (status, now, record 20s + ramclean now) /
  Jogos (lista + picker) / Log (+ bugreport). Root re-checado a cada abertura.
- Detalhe v2.9.1+: DND é armado (só liga no próximo game-enter), `action.sh` do
  módulo abre o app FUSAO.

## Mapa do resto (fora desta pasta, intocado após faxina 2026-10-13)
- Stage ATIVO: `../fusao-v2.0/` (= 2.9.4/1596, ver `module.prop`) — build via
  `../tools/fusao-build.py --out ../_releases/fusao-vX-M54.zip`, teste
  `../tools/test-fusao-offline.sh`, sweep `../tools/fusao-sweep.py`.
  NÃO mover/renomear sem atualizar as tools.
- Históricos: `../_historico/fusao-v1.0/`, `../_historico/fusao-v0.1/`,
  `../_historico/fusao-brain/`, `../_historico/fusao-guard/`,
  `../_historico/fusao-ebpf/`, `../_historico/fusao-observer/`
- Releases: `../_releases/fusao-v*-M54.zip` (22 zips, atual `fusao-v2.9.4-M54.zip`)
- Referência (não é FUSAO): `../_referencia/Rairin/`,
  `../_referencia/Stellar-v22.5-*/`, `../_referencia/encore-*/`,
  `../_referencia/rboost.apk`, baselines/reverts em `../_referencia/`.
