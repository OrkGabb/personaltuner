# Avisos de Licença — FUSAO-M54

O projeto **FUSAO-M54** (scripts do módulo, daemon `fusaod`, `observer.dex`,
aplicativo Android e ferramentas em `tools/`) é licenciado sob a **MIT License**
(consulte `LICENSE`).

Este pacote agrega o seguinte componente de terceiros como executável separado:

## fas-rs e frame-analyzer-ebpf (GNU GPL v3.0)
**Component:** `system/bin/fas-rs` — programa separado (agregado ao módulo, não linkado a ele).

    Copyright 2023-2025, shadow3 (shadow3aaa) e colaboradores.
    https://github.com/shadow3aaa/fas-rs
    https://github.com/shadow3aaa/frame-analyzer-ebpf

    This program is free software: you can redistribute it and/or modify it under
    the terms of the GNU General Public License as published by the Free Software
    Foundation, either version 3 of the License, or (at your option) any later version.
    https://www.gnu.org/licenses/gpl-3.0.txt

Binário modificado (fork M54). Fonte correspondente: os upstreams nos commits `09ba876`
(fas-rs) e `3dfd11f` (frame-analyzer-ebpf) mais os patches e a receita de build em
`tools/fas-rs-m54/` do repositório do FUSAO-M54.
