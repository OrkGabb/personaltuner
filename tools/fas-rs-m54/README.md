# fas-rs — fork M54

Binário `fusao-v2.0/system/bin/fas-rs` do FUSAO. É o [fas-rs](https://github.com/shadow3aaa/fas-rs)
(shadow3aaa, GPL-3.0) com o [frame-analyzer-ebpf](https://github.com/shadow3aaa/frame-analyzer-ebpf)
(GPL-3.0) e dez patches nossos. A fonte correspondente do binário é o upstream nos commits
`09ba876` (fas-rs) e `3dfd11f` (frame-analyzer-ebpf) mais `patches/`. Para reproduzir: `build.sh`.

| Patch | Por quê |
|---|---|
| frame-analyzer `0001` uprobe em `Surface::hook_queueBuffer` | O libgui do OneUI 8.5 (Android 16 QPR1+) não tem nenhum dos dois `Surface::queueBuffer` que o upstream procura: sem o fallback a sonda não anexa (issue #349 do fas-rs). Provado no aparelho: 695 frames da sonda × 704 do TimeStats na mesma janela |
| fas-rs `0001` `[patch.crates-io]` | Usa o frame-analyzer local com o fallback |
| fas-rs `0002` log `FAS working on` / `FAS stopped` | `New fas buffer on` sai ~10 s antes do controle; o FUSAO e o kernlab precisam saber quando ele controla de verdade |
| fas-rs `0003` trava de GPU saturada | Com a GPU saturada o frame abaixo do alvo é tempo de GPU: não sobe a CPU (Bleach no máximo: A78 a 2,4 GHz por 0 fps) |
| fas-rs `0004` trace (feature `m54-trace`) | Só em build de experimento: 1 linha a cada 100 ms (alvo, fps, travada, controle, trava, clock) + troca de alvo. O binário do módulo é sem a feature |
| fas-rs `0005` trava só no teto da GPU | `gpu_busy` alto sozinho não é saturação: o Mali fica ≥ 95% em clock BAIXO. Medido: a trava ativou com a GPU a 221 MHz e alternou ~120 vezes em 2,5 min no Roblox, derrubando a CPU a cada vez. Agora = busy ≥ 95% NO teto (`gpu_clock` ≥ `gpu_max_clock`) por 500 ms, solta < 85% ou fora do teto; nunca segura durante uma travada |
| fas-rs `0006` alvo com 2 s de confirmação + retomada do clock do governor | Uma travada de 0,3 s puxava o fps médio para o degrau de baixo, e cada troca zerava o buffer e parava o FAS por ~10-20 s (Roblox: 6 paradas em 2,5 min). Ao voltar, o FAS começava cravado no último clock (muitas vezes o mínimo) |
| fas-rs `0007` travada = jank | Thread parada lê ~0% de uso: o rastreio por uso segurava a CPU em 533 MHz por ~400 ms de travada |
| fas-rs `0008` sem troca de alvo durante travada < 5 s | Carregamento do NTE travou 2,9 s, mais que os 2 s; ≥ 5 s sem frame = jogo pausado, aí o FAS para como antes |
| fas-rs `0009` troca entre dois alvos sem zerar o buffer | Os frames continuam válidos (o buffer se ajusta à janela nova); o upstream zerava e ficava ~14 s sem controle a cada degrau |

Medido (kernlab, Bleach nos gráficos mínimos, limite 45, 6 blocos): bateria −10,4%, fps −1,3%,
frames ≥ 33 ms de 0,13% para 0,79%. Detalhes em `status.md`, seção fas-rs.

Medido (2026-10-20, NTE, 4 travadas injetadas de 0,5 s com SIGSTOP + as travadas naturais do carregamento,
mesmo roteiro nos dois braços, n=1 cada): binário antigo (0001-0004) = 1 "FAS stopped" após uma travada natural de
839 ms (~20 s sem controle) e 10 alternâncias da trava; binário novo (0001-0009) = 0 paradas, 0 alternâncias,
CPU no máximo em ≤ 100 ms de travada e mantida até 2,8 s, 2 trocas de alvo (60 -> 45 -> 30) sem parar.
