"""Generates RF Sentinel's offline vendor-lookup assets and verifies every
hard-coded prefix the signature tables use against the IEEE registry data.

Put these downloads in tools/data/ first (not committed):
  manuf.txt              https://www.wireshark.org/download/automated/data/manuf
                         (IEEE MA-L/MA-M/MA-S, regenerated weekly by Wireshark)
  sig_company.yaml       bitbucket.org/bluetooth-SIG/public  assigned_numbers/company_identifiers/company_identifiers.yaml
  sig_service.yaml       .../assigned_numbers/uuids/service_uuids.yaml
  sig_sdo.yaml           .../assigned_numbers/uuids/sdo_uuids.yaml
  sig_member.yaml        .../assigned_numbers/uuids/member_uuids.yaml
  sig_appearance.yaml    .../assigned_numbers/core/appearance_values.yaml

Then run:  python tools/gen_assets.py   (exits non-zero if a code prefix no longer matches)
"""
import os, re

SP = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data')
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'assets', 'vendors')
os.makedirs(OUT, exist_ok=True)

# --- IEEE MA-L / MA-M / MA-S (via Wireshark's weekly regenerated manuf) -------
prefixes = {}
header_date = None
for line in open(os.path.join(SP, 'manuf.txt'), encoding='utf-8'):
    if line.startswith('#'):
        continue
    parts = line.rstrip('\n').split('\t')
    if len(parts) < 3:
        continue
    block = parts[0].strip()
    name = parts[2].strip() or parts[1].strip()
    bits = 24
    if '/' in block:
        block, b = block.split('/')
        bits = int(b)
    hexs = block.replace(':', '').upper()[: bits // 4]
    if len(hexs) != bits // 4:
        continue
    prefixes[hexs] = name.replace('\t', ' ')

with open(os.path.join(OUT, 'oui.tsv'), 'w', encoding='utf-8', newline='\n') as f:
    f.write('# IEEE MA-L/MA-M/MA-S registrations (hex prefix, registrant).\n')
    f.write('# Source: IEEE Registration Authority via Wireshark manuf (weekly regenerated).\n')
    for k in sorted(prefixes):
        f.write(f'{k}\t{prefixes[k]}\n')
print('oui.tsv entries', len(prefixes), 'bytes', os.path.getsize(os.path.join(OUT, 'oui.tsv')))

# --- Bluetooth SIG company identifiers + 16-bit UUIDs -------------------------
def load_yaml_list(fn):
    d = {}
    s = open(os.path.join(SP, fn), encoding='utf-8').read()
    for m in re.finditer(r"- (?:value|uuid): (0x[0-9A-Fa-f]+)\s*\n\s*(?:name|id): ('?)([^\n]*)\2(?:\n\s*name: ('?)([^\n]*)\4)?", s):
        v = int(m.group(1), 16)
        name = (m.group(5) or m.group(3)).strip().strip("'\"")
        d[v] = name
    return d

comp = load_yaml_list('sig_company.yaml')
with open(os.path.join(OUT, 'bt_company.tsv'), 'w', encoding='utf-8', newline='\n') as f:
    f.write('# Bluetooth SIG company identifiers (hex id, name). Source: bitbucket.org/bluetooth-SIG/public\n')
    for k in sorted(comp):
        f.write(f'{k:04X}\t{comp[k]}\n')
print('bt_company entries', len(comp))

uu = {}
for fn in ('sig_service.yaml', 'sig_sdo.yaml', 'sig_member.yaml'):
    uu.update(load_yaml_list(fn))
with open(os.path.join(OUT, 'bt_uuid16.tsv'), 'w', encoding='utf-8', newline='\n') as f:
    f.write('# Bluetooth SIG 16-bit UUIDs: services, SDO and member (hex, name). Source: bitbucket.org/bluetooth-SIG/public\n')
    for k in sorted(uu):
        f.write(f'{k:04X}\t{uu[k]}\n')
print('bt_uuid16 entries', len(uu))

# --- Bluetooth SIG appearance values (category<<6 | subcategory) --------------
rows = []
cat = None; sub = None
for line in open(os.path.join(SP, 'sig_appearance.yaml'), encoding='utf-8'):
    m = re.match(r"\s*- category: (0x[0-9A-Fa-f]+)", line)
    if m:
        cat = int(m.group(1), 16); sub = None; continue
    m = re.match(r"\s*- value: (0x[0-9A-Fa-f]+)", line)
    if m:
        sub = int(m.group(1), 16); continue
    m = re.match(r"\s*name: (.*)", line)
    if m and cat is not None:
        name = m.group(1).strip().strip("'\"")
        rows.append((cat, sub if sub is not None else -1, name))
        sub = None
with open(os.path.join(OUT, 'bt_appearance.tsv'), 'w', encoding='utf-8', newline='\n') as f:
    f.write('# Bluetooth SIG appearance values (category hex, subcategory hex or -, name). Source: bitbucket.org/bluetooth-SIG/public\n')
    for c, sb, n in rows:
        subs = '-' if sb < 0 else format(sb, '02X')
        f.write(f'{c:03X}\t{subs}\t{n}\n')
print('appearance rows', len(rows))

# --- Verify every prefix the code tables use ----------------------------------
CODE_PREFIXES = {
    # drones (ACAB signatures.md, drone fallback table)
    '60601F': 'DJI', '34D262': 'DJI', '481CB9': 'DJI', 'E47A2C': 'DJI', '58B858': 'DJI',
    '04A85A': 'DJI', '8C5823': 'DJI', '0C9AE6': 'DJI', '882985': 'DJI', '4C43F6': 'DJI',
    '9C5A8A': 'DJI', 'EC72F7': 'DJI', '3491F0': 'DJI',
    '00121C': 'Parrot', '00267E': 'Parrot', '9003B7': 'Parrot', '903AE6': 'Parrot', 'A0143D': 'Parrot',
    '381D14': 'Skydio', 'EC5BCDE': 'Autel', 'E0B6F58': 'Yuneec', 'EC715E': 'Freefly',
    'B030C8': 'Teal', '001AF9': 'AeroVironment', '8C1F64B07': 'AeroVironment',
    '34B5F32': 'Inspired Flight', 'AC86D17': 'Quantum', '8C1F640F1': 'ideaForge',
    '8C1F64A2D': 'ACSL', '74B80F': 'Zipline', '24A10D7': 'Cyon', 'B44D43A': 'UAV Navigation',
    '14DD48': 'Shield AI', 'E8B470C': 'Anduril',
    # network cameras (ACAB signatures.md, network-camera tables)
    'A41162': 'Arlo', 'FC9C98': 'Arlo', '486264': 'Arlo',
    '3CA070': 'Blink', '70AD43': 'Blink', '741348': 'Blink', '74AB93': 'Blink', 'C819D8': 'Blink', 'F074C1': 'Blink',
    '38F25D': 'Ezviz', '14BA88': 'Uniview', '3446632': 'Amcrest', 'A4DA222': 'Wyze', '0C0EC14': 'Swann',
    '542B57': 'Night Owl', 'D0C193': 'SkyBell', 'B0B3537': 'WUUK',
    # OUI watchlist presets
    '0025DF': 'Axon', 'D81F65': 'Private', 'D42DC5': 'i-PRO', 'B41E52': 'Flock',
    '001F92': 'Motorola', '4CCC34': 'Motorola', '001885': 'Motorola', '00047D': 'Motorola',
    '10746F': 'Motorola', 'B8E28C': 'Motorola', '9C862B': 'Motorola', '0009BC': 'Utility', '0016ED': 'Utility',
    # Canada preset (2026-09 research)
    '8C1F64DF0': 'Cyberkar', '00BF15': 'Genetec', '0CBF15': 'Genetec', '0050C2BE7': 'Genetec',
    '003044': 'CradlePoint', '00E01C': 'CradlePoint',
    # Police-vehicle vendor sweep (2026-10)
    '000512': 'Zebra', '00074D': 'Zebra', '001570': 'Zebra', '002368': 'Zebra', '00A0F8': 'Zebra', '4083DE': 'Zebra', '488EB7': 'Zebra', '609532': 'Zebra', '7493A4': 'Zebra', '78B8D6': 'Zebra', '84248D': 'Zebra', '88BCAC': 'Zebra', '9075DE': 'Zebra', '94FB29': 'Zebra', 'C47DCC': 'Zebra', 'C81CFE': 'Zebra', 'FC597A': 'Zebra',
    '001BA9': 'Brother', '008077': 'Brother', '30055C': 'Brother', '3C2AF4': 'Brother', '94DDF8': 'Brother', 'B07C8E': 'Brother', 'B42200': 'Brother',
    '00116E': 'Peplink', '1056CA': 'Peplink', '6CA3D3': 'Peplink', 'D413F8': 'Peplink',
    '0015FF': 'Inseego', '18EE86': 'Inseego', '780C71': 'Inseego', 'E08614': 'Inseego',
    '4C364E': 'Panasonic', 'B8208E': 'Panasonic', 'BC3E0B': 'Panasonic',
    '00A0D5': 'Sierra', '28A331': 'Sierra', '50139D': 'Sierra', '64CE6E': 'Sierra', '84DB2F': 'Sierra', 'CC934A': 'Sierra',
}
bad = 0
for p, expect in CODE_PREFIXES.items():
    got = prefixes.get(p)
    ok = got is not None and (expect.lower().split()[0] in got.lower() or (expect == 'DJI' and 'dji' in got.lower()))
    if not ok:
        bad += 1
    print(('OK  ' if ok else 'BAD ') + f'{p:10} expect {expect:16} registry: {got}')
print('mismatches:', bad)
if bad:
    raise SystemExit(1)
