#!/usr/bin/env python3
"""Menu icons and click handlers must agree about slots.

Both menu bugs found on the Gear page came from draw and click being two
hand-maintained lists: the Hazmat button was drawn on slot 10 over the NVG
helmet (so it handed out a helmet and its page was unreachable), and the
seventh airframe was drawn on slot 35 while the handler only matched 28..33.

This parses each fill*() and its matching page handler and reports:
  * a slot drawn twice in one page  - the later icon hides the earlier one
  * a slot drawn with no handler    - a dead icon
  * a slot handled with no icon     - a click on empty air

Run:  python3 tools/check_menu_slots.py
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GUI = os.path.join(os.path.dirname(HERE), "src/main/java/com/local/warz/gui/GiveGunMenuService.java")

# Pages whose contents are paged/dynamic (guns, ammo, mags, grenades...) are
# driven by loops over a registry rather than fixed slots, so a static slot
# comparison says nothing useful about them.
STATIC_PAGES = {
    "GEAR": "fillGear",
    "HAZMAT_SUIT": "fillHazmatSuit",
    "FIRE_PROXIMITY_SUIT": "fillFireProximitySuit",
    "HOME": "fillHome",
}


def method_body(src, name):
    start = src.index(f"private void {name}(")
    depth = 0
    i = src.index("{", start)
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[i:j]
    return ""


def handler_body(src, page):
    marker = f"session.page == Page.{page})"
    if marker not in src:
        return ""
    start = src.index(marker)
    later = [src.index(m, start + 1) for m in
             [f"session.page == Page.{p})" for p in re.findall(r"session\.page == Page\.(\w+)\)", src)]
             if src.find(m, start + 1) > 0]
    end = min(later) if later else len(src)
    return src[start:end]


def main():
    src = open(GUI).read()
    problems = []

    # Slot tables shared between draw and click - the fix pattern - are named
    # *_SLOTS and resolved through a lookup, so both sides see the same list.
    shared = {}
    for m in re.finditer(r"static final int\[\] (\w+_SLOTS)\s*=\s*\{([^}]*)\}", src):
        shared[m.group(1)] = [int(x) for x in re.findall(r"\d+", m.group(2))]

    for page, fill in STATIC_PAGES.items():
        if f"private void {fill}(" not in src:
            continue
        body = method_body(src, fill)

        drawn = {}
        # Three spellings place an icon: inv.setItem(slot, ..), the placeX(inv,
        # slot, ..) helpers, and set(inv, slot, ..) which the Home page uses.
        for m in re.finditer(r"(?:inv\.setItem\(\s*(\d+)|place\w+\(inv,\s*(\d+)|\bset\(inv,\s*(\d+))", body):
            slot = int(m.group(1) or m.group(2) or m.group(3))
            line = body[:m.start()].count("\n") + 1
            drawn.setdefault(slot, []).append(line)
        for name, slots in shared.items():
            if name in body:
                for s in slots:
                    drawn.setdefault(s, []).append(0)

        for slot, lines in sorted(drawn.items()):
            if len(lines) > 1 and 0 not in lines:
                problems.append(f"{fill}: slot {slot} drawn {len(lines)}x (lines {lines}) - the last one hides the rest")

        handler = handler_body(src, page)
        if not handler:
            continue
        handled = set(int(x) for x in re.findall(r"slot ==\s*(\d+)", handler))
        for a, b in re.findall(r"slot >=\s*(\d+) && slot <=\s*(\d+)", handler):
            handled |= set(range(int(a), int(b) + 1))
        for name, slots in shared.items():
            if name.lower().replace("_slots", "") in handler.lower() or f"{name}" in handler:
                handled |= set(slots)
        # A shared table reached through a helper (droneIndexForSlot) counts too.
        for helper in re.findall(r"(\w+ForSlot)\(slot\)", handler):
            for name, slots in shared.items():
                if helper.lower().startswith(name.split("_")[0].lower()):
                    handled |= set(slots)

        for slot in sorted(set(drawn) - handled):
            problems.append(f"{page}: slot {slot} has an icon but no click handler")
        for slot in sorted(handled - set(drawn)):
            problems.append(f"{page}: slot {slot} is handled but nothing is drawn there")

    if problems:
        print(f"{len(problems)} menu slot problem(s):")
        for p in problems:
            print("   " + p)
        return 1
    print(f"menu slots consistent across {len(STATIC_PAGES)} static pages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
