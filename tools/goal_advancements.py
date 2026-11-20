"""Ruby Wars goals as advancements (the advancements screen, L): one per goal in Goals.java, under a Ruby Wars tab.

Usage:  python tools/goal_advancements.py   (from the project root; run it again whenever the goals change)

Goals.java awards them (criterion "done") as goals are completed; they never show a toast or a chat line (the mod's
own toast says it).
"""
import json
import os
import re
import shutil

GOALS = "src/main/java/dev/rubymod/game/Goals.java"
OUT = "src/main/resources/data/rubymod/advancement/goals"

ICONS = {
    "heart": "rubymod:ruby_heart", "ruby10": "rubymod:ruby_ore", "ruby50": "rubymod:ruby_block", "power": "rubymod:solar_panel",
    "drill": "rubymod:ruby_drill", "depot": "rubymod:trade_depot", "mk3": "rubymod:ore_refinery", "bank500": "rubymod:ruby",
    "bank2000": "minecraft:gold_block", "heart2": "rubymod:ruby_heart", "heart3": "rubymod:ruby_heart", "armory": "rubymod:armory",
    "defense": "rubymod:sentry_turret", "defense5": "rubymod:shield_generator", "vault": "rubymod:vault", "sell100": "minecraft:emerald",
    "loader100": "rubymod:belt_loader", "busy3": "rubymod:constructor", "raid": "minecraft:shield", "raidclean": "minecraft:totem_of_undying",
    "knockout": "minecraft:iron_sword", "heartkill": "minecraft:tnt", "sudden": "minecraft:wither_skeleton_skull",
    "drive": "rubymod:jeep", "fly": "rubymod:jetpack", "sail": "minecraft:oak_boat", "dig": "rubymod:tunnel_borer",
}


def write(path, data):
    with open(path, "w", newline="\n") as f:
        json.dump(data, f, indent="\t")
        f.write("\n")


def main():
    src = open(GOALS, encoding="utf-8").read()
    goals = re.findall(r'new Goal\("([a-z0-9_]+)",\s*"([^"]+)",\s*(\d+),\s*(\d+)\)', src)
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    write(f"{OUT}/root.json", {
        "criteria": {"joined": {"trigger": "minecraft:tick"}},
        "display": {"announce_to_chat": False, "show_toast": False, "background": "minecraft:gui/advancements/backgrounds/nether",
                    "icon": {"id": "rubymod:ruby"}, "title": "Ruby Wars", "description": "Goals that earn stars for upgrades (press J)"},
        "requirements": [["joined"]]})
    for gid, title, target, stars in goals:
        stars = int(stars)
        write(f"{OUT}/{gid}.json", {
            "parent": "rubymod:goals/root",
            "criteria": {"done": {"trigger": "minecraft:impossible"}},
            "display": {"announce_to_chat": False, "show_toast": False, "frame": "goal" if stars >= 2 else "task",
                        "icon": {"id": ICONS.get(gid, "minecraft:nether_star")}, "title": title,
                        "description": f"+{stars} star{'s' if stars != 1 else ''} to spend on upgrades"},
            "requirements": [["done"]]})
    print(len(goals), "goals")


if __name__ == "__main__":
    main()
