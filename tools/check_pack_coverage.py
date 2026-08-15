#!/usr/bin/env python3
"""Every model the plugin asks for must exist in the resource pack.

A CustomModelData only renders if the base item has an item definition to
dispatch on. Nothing enforced that, and the two sides drifted three separate
ways: CMDs stamped onto bases with no definition file (handcuffs, lockpick, the
fire suit), models authored but never packed into the zip (five foods), and
definitions lost outright in a server migration (every round in the game).

Run:  python3 tools/check_pack_coverage.py [--pack DIR_OR_ZIP]
Exit 0 when every (base material, CMD) the plugin can produce resolves.
"""
import argparse
import glob
import json
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PLUGIN = os.path.dirname(HERE)
DEFAULT_PACK = os.path.join(os.path.dirname(PLUGIN), "MineniteWARZ")
SRC = os.path.join(PLUGIN, "src/main/java/com/local/warz/")


def plugin_pairs():
    """(base material, cmd, string id, what asked for it)"""
    pairs = set()
    item_factory = os.path.join(SRC, "runtime/ItemFactory.java")
    src = open(item_factory).read()
    consts = {m.group(1): int(m.group(2))
              for m in re.finditer(r"int (CMD_[A-Z0-9_]+)\s*=\s*(\d+)", src)}

    sigs = [(m.start(), m.group(1)) for m in
            re.finditer(r"\b(?:public|private|protected)\s+(?:static\s+)?ItemStack\s+(\w+)\s*\(", src)]
    for i, (pos, name) in enumerate(sigs):
        body = src[pos:(sigs[i + 1][0] if i + 1 < len(sigs) else len(src))]
        mats = re.findall(r"new ItemStack\(\s*Material\.(\w+)", body)
        if not mats:
            continue
        for cmd, sid in re.findall(
                r'applyCmd\(\s*\w+\s*,\s*([A-Za-z0-9_.()\s]+?)\s*(?:,\s*"([^"]*)")?\)\s*;', body):
            value = consts.get(cmd.strip())
            if value:
                pairs.add((mats[0].lower(), value, sid or None, name))

    # Suit pieces take their CMD as an argument.
    for m in re.finditer(r"createSuitPiece\(Material\.(\w+),[^;]*?(CMD_\w+),", src, re.S):
        pairs.add((m.group(1).lower(), consts[m.group(2)], None, "createSuitPiece"))

    # Rounds are data, not code.
    for f in glob.glob(os.path.join(PLUGIN, "src/main/resources/defaults/rounds/*")):
        text = open(f).read()
        mat = re.search(r"(?im)^material=(\w+)", text)
        cmd = re.search(r"(?im)^customModelData=(\d+)", text)
        if mat and cmd:
            pairs.add((mat.group(1).lower(), int(cmd.group(1)), None,
                       "round:" + os.path.basename(f)))

    # Enums that carry their own CMD. Magazines, optics, grips, lasers and
    # suppressors are all built on the stick base regardless of the Material the
    # enum names, which is only used for menu icons.
    for enum in ("MagazineType", "OpticType", "GripType", "LaserModColor", "SuppressorType"):
        path = os.path.join(SRC, "runtime", enum + ".java")
        if not os.path.exists(path):
            continue
        text = open(path).read()
        for value in re.findall(r"Material\.\w+,\s*(\d{4})", text):
            pairs.add(("stick", int(value), None, enum))
        names = re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\(", text, re.M)
        for base in re.findall(r"(\d{4})\s*\+\s*ordinal\(\)", text):
            # ordinal 0 is the NONE member in these enums and never becomes an item.
            for ordinal in range(len(names)):
                if names[ordinal] != "NONE":
                    pairs.add(("stick", int(base) + ordinal, None, enum))

    for enum, base in (("NvgGear", "carved_pumpkin"), ("ThermalGear", "carved_pumpkin")):
        text = open(os.path.join(SRC, "runtime", enum + ".java")).read()
        for value in re.findall(r"\((\d{4}),", text):
            pairs.add((base, int(value), None, enum))

    # Guns share one base material and take their CMD from the pack table.
    gun_base = re.search(r"this\.baseMaterial\s*=\s*configured\s*!=\s*null\s*\?\s*configured\s*:\s*Material\.(\w+)", src)
    gun_base = gun_base.group(1).lower() if gun_base else "bone"
    table = open(os.path.join(SRC, "config/ResourcePackCmd.java")).read()
    for gun, cmd in re.findall(r'put\(guns, models, "([a-z0-9_]+)", (\d+),', table):
        if int(cmd) > 1:  # 1 is the documented "no art, fall back to vanilla" value
            pairs.add((gun_base, int(cmd), None, "gun:" + gun))
    return pairs


def pack_coverage(pack):
    """base material -> (numeric thresholds, string cases)"""
    out = {}
    if pack.endswith(".zip"):
        with zipfile.ZipFile(pack) as z:
            items = {n: z.read(n).decode() for n in z.namelist()
                     if re.match(r"assets/minecraft/items/[^/]+\.json$", n)}
    else:
        items = {p: open(p).read()
                 for p in glob.glob(os.path.join(pack, "assets/minecraft/items/*.json"))}
    for name, text in items.items():
        base = os.path.basename(name)[:-5]
        out[base] = (set(int(x) for x in re.findall(r'"threshold"\s*:\s*(\d+)', text)),
                     set(re.findall(r'"when"\s*:\s*"([^"]+)"', text)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", default=DEFAULT_PACK)
    args = ap.parse_args()

    if not os.path.exists(args.pack):
        print(f"pack not found: {args.pack}")
        return 2

    pairs = plugin_pairs()
    pack = pack_coverage(args.pack)
    gaps = []
    for base, cmd, sid, who in sorted(pairs):
        thresholds, cases = pack.get(base, (set(), set()))
        if cmd in thresholds or (sid and sid in cases):
            continue
        why = "no item definition for this base" if base not in pack else "no entry for this CMD"
        gaps.append((base, cmd, who, why))

    print(f"checked {len(pairs)} (base material, CMD) pairs against {args.pack}")
    if not gaps:
        print("all covered")
        return 0
    print(f"{len(gaps)} without a model:")
    for base, cmd, who, why in gaps:
        print(f"   {base}:{cmd}  ({who}) - {why}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
