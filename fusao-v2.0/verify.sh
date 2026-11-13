#!/system/bin/sh
# FUSAO-M54 — verificacao de integridade SHA-256 durante a instalacao (MIT).

FUSAO_VERIFY_DIR="$TMPDIR/.fusao_verify"
mkdir -p "$FUSAO_VERIFY_DIR"

abort_verify() {
	ui_print "*********************************************************"
	ui_print "! Falha de integridade: $1"
	ui_print "! Instalacao cancelada para evitar arquivo corrompido."
	abort "*********************************************************"
}

verify_sha256() {
	_target="$1"
	_sum_file="$2"
	[ -f "$_target" ] || abort_verify "ausente: $_target"
	[ -f "$_sum_file" ] || abort_verify "checksum ausente: $_sum_file"
	_want=$(tr -d '[:space:]' <"$_sum_file")
	_got=$(sha256sum "$_target" 2>/dev/null | cut -d ' ' -f 1)
	[ -n "$_want" ] && [ "$_got" = "$_want" ] || abort_verify "hash divergente: $_target"
}

# extract <zip> <relpath> <destdir>
extract() {
	_zip="$1"
	_rel="$2"
	_dir="$3"
	_out="$_dir/$_rel"
	_sha="$FUSAO_VERIFY_DIR/$_rel.sha256"

	unzip -o "$_zip" "$_rel" -d "$_dir" >&2 || abort_verify "erro ao extrair $_rel"
	unzip -o "$_zip" "$_rel.sha256" -d "$FUSAO_VERIFY_DIR" >&2 || abort_verify "checksum ausente para $_rel"
	verify_sha256 "$_out" "$_sha"
}

# Confere o updater do pacote se o zip trouxer o checksum correspondente.
_upd="META-INF/com/google/android/update-binary"
unzip -o "$ZIPFILE" "META-INF/com/google/android/*" -d "$FUSAO_VERIFY_DIR" >&2
if [ -f "$FUSAO_VERIFY_DIR/$_upd.sha256" ]; then
	verify_sha256 "$FUSAO_VERIFY_DIR/$_upd" "$FUSAO_VERIFY_DIR/$_upd.sha256"
fi
