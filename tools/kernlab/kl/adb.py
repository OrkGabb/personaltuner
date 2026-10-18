"""Transporte ADB. Scripts vão pelo stdin do `su` — nada de aspas atravessando PowerShell/Git
Bash (onde `$` é interpolado e caminhos /data viram C:/Program Files/Git/data)."""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

ADB = os.environ.get("KL_ADB") or ("adb.exe" if os.name == "nt" else "adb")
DEVICE_FILE = Path(__file__).resolve().parent.parent / ".device"


class AdbError(RuntimeError):
    pass


def saved_serial() -> str | None:
    env = os.environ.get("KL_DEVICE")
    if env:
        return env.strip()
    if DEVICE_FILE.is_file():
        s = DEVICE_FILE.read_text(encoding="utf-8").strip()
        return s or None
    return None


def save_serial(serial: str) -> None:
    DEVICE_FILE.write_text(serial.strip() + "\n", encoding="utf-8")


class Device:
    def __init__(self, serial: str | None = None):
        self.serial = serial or saved_serial()
        if not self.serial:
            raise AdbError("aparelho não definido: rode `kernlab.py connect IP:PORTA` (a porta do Wi-Fi gira)")

    def _run(self, args: list[str], data: bytes | None = None, timeout: float = 120, check: bool = True) -> str:
        try:
            p = subprocess.run([ADB, "-s", self.serial, *args], input=data, capture_output=True,
                               timeout=timeout)
        except subprocess.TimeoutExpired as e:
            raise AdbError(f"adb {' '.join(args[:2])}: tempo esgotado ({timeout}s)") from e
        # o `adb shell su` devolve CRLF: sem normalizar, uma seção que começa logo após um
        # marcador ganha uma linha vazia na frente e o cabeçalho do cur_set some (2026-10-18)
        out = p.stdout.decode("utf-8", errors="replace").replace("\r\n", "\n")
        # adb devolve 255 quando a conexão em si falha; o rc do script remoto só importa com check
        if p.returncode == 255 or (check and p.returncode != 0):
            err = p.stderr.decode("utf-8", errors="replace").strip()
            raise AdbError(f"adb {' '.join(args[:3])} rc={p.returncode}: {err or out.strip()}")
        return out

    def connect(self) -> None:
        if ":" in self.serial:
            subprocess.run([ADB, "connect", self.serial], capture_output=True, timeout=20)

    def ensure(self) -> None:
        try:
            state = self._run(["get-state"], timeout=15).strip()
        except AdbError:
            self.connect()
            state = self._run(["get-state"], timeout=15).strip()
        if state != "device":
            raise AdbError(f"{self.serial}: estado '{state}'")

    def root(self, script: str, timeout: float = 120) -> str:
        return self._run(["shell", "su"], data=script.encode("utf-8"), timeout=timeout)

    def root_read(self, script: str, timeout: float = 60) -> str:
        """Leitura de estado: a saída vale mesmo que o último comando do script dê rc != 0."""
        return self._run(["shell", "su"], data=script.encode("utf-8"), timeout=timeout, check=False)

    def shell(self, cmd: str, timeout: float = 60) -> str:
        return self._run(["shell", cmd], timeout=timeout)

    def push(self, local: Path, remote: str) -> None:
        self._run(["push", str(local), remote], timeout=120)

    def pull(self, remote: str, local: Path) -> None:
        self._run(["pull", remote, str(local)], timeout=600)
