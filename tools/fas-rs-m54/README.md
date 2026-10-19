# fas-rs — fork M54

Binário `fusao-v2.0/system/bin/fas-rs` do FUSAO. É o [fas-rs](https://github.com/shadow3aaa/fas-rs)
(shadow3aaa, GPL-3.0) com o [frame-analyzer-ebpf](https://github.com/shadow3aaa/frame-analyzer-ebpf)
(GPL-3.0) e quatro patches nossos. A fonte correspondente do binário é o upstream nos commits
`09ba876` (fas-rs) e `3dfd11f` (frame-analyzer-ebpf) mais `patches/`. Para reproduzir: `build.sh`.

| Patch | Por quê |
|---|---|
| frame-analyzer `0001` uprobe em `Surface::hook_queueBuffer` | O libgui do OneUI 8.5 (Android 16 QPR1+) não tem nenhum dos dois `Surface::queueBuffer` que o upstream procura: sem o fallback a sonda não anexa (issue #349 do fas-rs). Provado no aparelho: 695 frames da sonda × 704 do TimeStats na mesma janela |
| fas-rs `0001` `[patch.crates-io]` | Usa o frame-analyzer local com o fallback |
| fas-rs `0002` log `FAS working on` / `FAS stopped` | `New fas buffer on` sai ~10 s antes do controle; o FUSAO e o kernlab precisam saber quando ele controla de verdade |
| fas-rs `0003` trava de GPU saturada | Com `gpu_busy` ≥ 95% o frame abaixo do alvo é tempo de GPU: não sobe a CPU (Bleach no máximo: A78 a 2,4 GHz por 0 fps). **Não medido ainda** num jogo preso na GPU |

Medido (kernlab, Bleach nos gráficos mínimos, limite 45, 6 blocos): bateria −10,4%, fps −1,3%,
frames ≥ 33 ms de 0,13% para 0,79%. Detalhes em `status.md`, seção fas-rs.
