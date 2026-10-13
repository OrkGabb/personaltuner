# PersonalTuner — FUSAO-M54 (módulo + app)

Repositório do código autoral. Terceiros, releases binárias e histórico morto
ficam fora (ver `.gitignore`).

## Layout

- `app/` — app FUSAO (Kotlin/Compose, `com.orkgabb.fusao`). Build: `cd app && ./gradlew assembleDebug`
  (SDK via `local.properties`, não commitado). Requer device root p/ testar de verdade.
- `fusao-v2.0/` — stage ativo do módulo KernelSU (v2.9.4/1596, ver `module.prop`).
- `tools/` — `fusao-build.py` (gera o zip flashável), `fusao-sweep.py` (kill-list),
  `test-fusao-offline.sh` (harness). Comandos em `INDICE.md`.
- `fusao-apk/LEIA-ME.md` — contrato que o app usa (CLI `fusao` + flags em
  `/data/adb/fusao/`). **Leitura obrigatória antes de mexer no app.**
- `status.md` + `handoff.md` + `nodes-m54.md` — contexto vivo do projeto.
  `INDICE.md` — mapa geral.

## Fluxo p/ contribuir (ex.: design/responsividade do app)

1. Ler `fusao-apk/LEIA-ME.md` (telas atuais + o que não pode quebrar).
2. Mexer só em `app/src/...` + recursos. Não mudar `applicationId`, `minSdk` nem o
   protocolo da ponte root (`app/.../Fusao.kt`, `Root.kt`) sem combinar.
3. Validar: `./gradlew assembleDebug` (ou `lintDebug`). Sem device root não dá p/
   testar runtime — declarar isso no PR.
