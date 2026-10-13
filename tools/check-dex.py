import struct
import sys
raw = open(sys.argv[1], 'rb').read()
assert raw[:4] == b'dex\n', "magic dex invalido"
f = struct.unpack_from('<20I', raw, 32)
(string_ids_size, string_ids_off) = (f[6], f[7])
(type_ids_size, type_ids_off) = (f[8], f[9])
(class_defs_size, class_defs_off) = (f[16], f[17])
strs = []
for i in range(string_ids_size):
    (off,) = struct.unpack_from('<I', raw, string_ids_off + 4 * i)
    while raw[off] > 0x7F:
        off += 1
    ln = raw[off]
    off += 1
    strs.append(raw[off:off + ln].decode('utf-8', 'ignore'))
types = []
for i in range(type_ids_size):
    (idx,) = struct.unpack_from('<I', raw, type_ids_off + 4 * i)
    types.append(strs[idx])
defs = []
for i in range(class_defs_size):
    (cidx,) = struct.unpack_from('<I', raw, class_defs_off + 32 * i)
    defs.append(types[cidx])
print("class_defs:", defs)
assert any('Lcom/fusao/observer/ObserverMain' in d for d in defs), "observer ausente"
assert not any(d.startswith('Landroid/') for d in defs), "stub android.* no dex"
print("dex ok: observer definido, zero definicoes android.* (refs resolvem no framework)")
