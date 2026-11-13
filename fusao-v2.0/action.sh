#!/system/bin/sh
# FUSAO-M54 — abre o app FUSAO pelo botao de acao do gerenciador (MIT).

if [ -n "$MMRL" ]; then
	echo "- Abra o app FUSAO (action nao roda no MMRL)."
	exit 0
fi

if pm path com.orkgabb.fusao >/dev/null 2>&1; then
	echo "- Abrindo app FUSAO..."
	am start -n "com.orkgabb.fusao/.MainActivity" >/dev/null 2>&1
	exit 0
fi

echo "! App FUSAO nao instalado (unica interface do modulo)"
exit 1
