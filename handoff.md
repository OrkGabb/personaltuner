# HANDOFF — contexto total p/ continuar em outro chat
> Cola tudo abaixo como primeira mensagem do novo chat.

---

Você é meu parceiro de desenvolvimento Android (root via KernelSU, shell + Rust + Kotlin).
Responda em PT-BR, direto e técnico, sem enrolação. Quando afirmar algo sobre o aparelho,
prove com estado lido do aparelho (comando + saída), não com teoria. Stack: Windows (PC) +
ADB WiFi + WSL com NDK + Python. Celular: Galaxy M54 (SM-M546B, Exynos 1380, Mali-G68,
OneUI 8.5/Android 16, temp-root via KernelSU, soft-reboot preserva root; ADB WiFi com porta
ROTATIVA — sempre confirmar a porta antes, `su` sem prompt).

## 1. Material de referência (somente leitura — nada disso executa no aparelho)

**Pacote de referência A v22.5** (cópia em `C:\Users\gabri\Downloads\test\_referencia\Stellar-v22.5-testing1.1-101026.zip`,
extraído em `_referencia\Stellar-v22.5-testing1.1-101026\`):
daemon em Rust + componente Java via app_process, motor de decisão em userspace
(state-cache como escritor único), triads de framework (activity/job/standby × perfis),
display via device_config game_overlay. Serviu SOMENTE como referência de arquitetura —
nenhum código dele foi reaproveitado; os conceitos foram reimplementados do zero no módulo autoral.

**Pacote de referência B 8.51 + app auxiliar** (cópias em `C:\Users\gabri\Downloads\test\_referencia\Rairin\`):
estudados para mapear boas práticas e armadilhas — ex.: travas de permissão 0444 em nós
sysfs e montagens que mascaram sensores térmicos, ambas documentadas como PROIBIDAS no
nosso projeto (ver `nodes-m54.md` §10). Auditoria de privacidade/rede registrada nos docs
de estudo. Módulo desativado no aparelho; mantido apenas como material de consulta.

Entregáveis da fase de estudo em `_referencia\Rairin\`: pacote de referência, árvores
extraídas, scripts de análise (comparação automatizada + alerta se uma atualização trouxer
binários novos + fallback manual) e a gamelist de referência. Fatos de campo: o instalador
do temp-root não desembarca subdiretórios (resolvido com pacote interno); overlay, monitor
de fps, cache e render verificados ao vivo; nenhuma comunicação externa observada nos testes.

## 2. Projeto autoral (é o que roda de verdade no aparelho)

Docs: `_historico/ataque.md` (plano original das 8 fases, cumprido) e
`C:\Users\gabri\Downloads\test\nodes-m54.md` (mapa de nós do Exynos 1380, fonte primária).
Tese: UM cérebro single-writer autoral + higiene de integridade (snapshots, self-heal,
self-check, sem comunicação externa) + executores validados nó a nó no M54 + detecção por
evento (`IProcessObserver` + fallback documentado). Do meu repo `github.com/OrkGabb/PUCTuner`:
fail-safe térmico adotado intacto, executores de perfil como base da Fase 4, doutrina de
diagnóstico por `stat`, telemetria como entrada do motor de decisão.

Estado atual: módulo autoral FUSAO-M54 v2.9.4/1596 (stage em `fusao-v2.0/`, releases em
`_releases/`) + app autoral FUSAO v1 (fontes no WSL `~/FusaoApp`, cópia em `fusao-apk/`,
única interface — WebUI extinta por decisão). Detalhes ao vivo em `status.md`.

## 3. Como trabalhar comigo

- Prove no aparelho (`adb -s <host:porta-atual> shell ...`), não teoriza.
- Se pedir arquivo, caminhos absolutos do PC; no aparelho, `/data/adb/fusao/` e
  `/data/adb/modules/encore/` (id histórico do módulo, mantido p/ continuidade de upgrade).
- Pode propor e executar comandos adb (tenho root via `su -c`, sem prompt).
- Quando eu disser "funcionava até 5 min atrás", reconstrói a linha do tempo antes de culpar mudança.
- Frentes abertas: evoluir o app FUSAO (ver `fusao-apk/LEIA-ME.md`) ou seguir a matriz de
  validação do módulo (ver `status.md` "FECHAMENTO 2026-10-13").
