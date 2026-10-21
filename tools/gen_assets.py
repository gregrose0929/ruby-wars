"""Generate Ruby Wars' JSON assets and data: models, blockstates, client items,
language file, recipes, loot tables, tags, worldgen, and damage types.

Usage:  python tools/gen_assets.py [path to the Minecraft client jar for the door blockstate]
Run from the project root. Safe to re-run; it overwrites what it generates.
"""
import json
import os
import sys
import zipfile

import models3d
import models3d_blocks

NS = "rubymod"
RES = "src/main/resources"
ASSETS = f"{RES}/assets/{NS}"
DATA = f"{RES}/data"


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as f:
        json.dump(obj, f, indent="\t")
        f.write("\n")


# ---------------------------------------------------------------- items
SIMPLE_ITEMS = ["extended_mag", "red_dot_sight", "vertical_grip", "suppressor", "ruby", "light_ammo", "heavy_ammo", "shotgun_shell", "sniper_round", "ruby_cell", "frag_grenade", "smoke_grenade",
                "air_strike_flare", "rocket", "mortar_shell", "cruise_missile", "rangefinder", "breaching_charge"]
HANDHELD_ITEMS = ["builders_wand", "excavator", "ruby_sword", "ruby_pickaxe"]
ARMOR_ITEMS = ["ruby_helmet", "ruby_chestplate", "ruby_leggings", "ruby_boots"]
GUNS = ["service_pistol", "combat_shotgun", "arr_rifle", "longshot_sniper", "ruby_blaster", "rocket_launcher"]

# Guns are drawn sideways (barrel to the right). These transforms point the barrel forward in your hands.
GUN_DISPLAY = {
    # Third person uses the crossbow's aiming transform (see AvatarRendererMixin), turned 45 degrees
    # because the crossbow sprite is diagonal and ours are horizontal.
    "thirdperson_righthand": {"rotation": [-90, 0, 80], "translation": [-1.5, 0.1, -3], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_lefthand": {"rotation": [-90, 0, -20], "translation": [-1.5, 0.1, -3], "scale": [0.9, 0.9, 0.9]},
    "firstperson_righthand": {"rotation": [0, -90, 5], "translation": [1.5, 4.0, 0], "scale": [0.5, 0.5, 0.5]},
    "firstperson_lefthand": {"rotation": [0, 90, -5], "translation": [1.5, 4.0, 0], "scale": [0.5, 0.5, 0.5]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
}


def item_model(name, parent="minecraft:item/generated", display=None, texture=None):
    model = {"parent": parent, "textures": {"layer0": f"{NS}:item/{texture or name}"}}
    if display:
        model["display"] = display
    write(f"{ASSETS}/models/item/{name}.json", model)
    client_item(name, f"{NS}:item/{name}")


def fit_display(elements, gui_rotation, pad=15.0):
    """gui/ground/fixed transforms that scale a 3D model to fit and center it in an inventory slot."""
    (x0, x1), (y0, y1), (z0, z1) = (models3d_blocks.extent(elements, a) for a in range(3))
    size = max(x1 - x0, y1 - y0, z1 - z0)
    scale = round(min(1.0, pad / size), 3)
    cx, cy, cz = (x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2
    shift = [round((8 - cx) * scale, 2), round((8 - cy) * scale, 2), round((8 - cz) * scale, 2)]
    return {
        "gui": {"rotation": gui_rotation, "translation": shift, "scale": [scale] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [round(shift[0] * 0.5, 2), 2, round(shift[2] * 0.5, 2)],
                   "scale": [round(scale * 0.5, 3)] * 3},
        "fixed": {"rotation": [0, 180, 0], "translation": shift, "scale": [scale] * 3},
    }


def gun_item(name):
    """One 3D model everywhere: side profile in inventories, aimed forward in hand."""
    model = models3d.gun_model(name)
    model["gui_light"] = "front"
    model["display"] = {**GUN_DISPLAY, **fit_display(model["elements"], [0, 0, 0], 15.5)}
    write(f"{ASSETS}/models/item/{name}.json", model)
    old_icon = f"{ASSETS}/models/item/{name}_icon.json"
    if os.path.exists(old_icon):
        os.remove(old_icon)
    client_item(name, f"{NS}:item/{name}")


def vehicle_item(name, elements, block_model):
    """Vehicle items show the actual 3D vehicle."""
    display = fit_display(elements, [25, 135, 0], 15.0)
    display["thirdperson_righthand"] = {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.2, 0.2, 0.2]}
    display["firstperson_righthand"] = {"rotation": [0, 135, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]}
    write(f"{ASSETS}/models/item/{name}.json", {"parent": block_model, "gui_light": "side", "display": display})
    client_item(name, f"{NS}:item/{name}")


def client_item(name, model):
    write(f"{ASSETS}/items/{name}.json", {"model": {"type": "minecraft:model", "model": model}})


# ---------------------------------------------------------------- blocks
def cube_all(name):
    write(f"{ASSETS}/models/block/{name}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": f"{NS}:block/{name}"}})
    simple_state(name)


def column(name):
    write(f"{ASSETS}/models/block/{name}.json", {"parent": "minecraft:block/cube_column",
                                                 "textures": {"side": f"{NS}:block/{name}", "end": f"{NS}:block/{name}_top"}})
    simple_state(name)


def box_model(name, frm, to, side, top):
    faces = {}
    for face in ("north", "south", "east", "west"):
        faces[face] = {"texture": "#side"}
    faces["up"] = {"texture": "#top"}
    faces["down"] = {"texture": "#top", "cullface": "down"}
    write(f"{ASSETS}/models/block/{name}.json", {
        "parent": "minecraft:block/block",
        "textures": {"particle": f"{NS}:block/{side}", "side": f"{NS}:block/{side}", "top": f"{NS}:block/{top}"},
        "elements": [{"from": frm, "to": to, "faces": faces}],
    })


def simple_state(name):
    write(f"{ASSETS}/blockstates/{name}.json", {"variants": {"": {"model": f"{NS}:block/{name}"}}})
    client_item(name, f"{NS}:block/{name}")


def gen_blocks(client_jar):
    for name in ["ruby_block", "ruby_ore", "deepslate_ruby_ore", "ruby_heart", "sandbags", "concrete_wall", "reinforced_concrete"]:
        cube_all(name)
    column("armory")

    # Defenses and launchers are 3D models built from boxes (tools/models3d_blocks.py).
    for name in models3d_blocks.BLOCKS:
        write(f"{ASSETS}/models/block/{name}.json", models3d_blocks.block_model(name))
        if name != "claymore":
            simple_state(name)

    write(f"{ASSETS}/blockstates/claymore.json", {"variants": {
        f"facing={f}": {"model": f"{NS}:block/claymore", "y": y} for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})
    client_item("claymore", f"{NS}:block/claymore")

    write(f"{ASSETS}/models/block/barbed_wire.json", {"parent": "minecraft:block/cross", "textures": {"cross": f"{NS}:block/barbed_wire"}})
    write(f"{ASSETS}/blockstates/barbed_wire.json", {"variants": {"": {"model": f"{NS}:block/barbed_wire"}}})
    write(f"{ASSETS}/models/item/barbed_wire.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:block/barbed_wire"}})
    client_item("barbed_wire", f"{NS}:item/barbed_wire")

    # Blast door: copy the iron door's blockstate shape, swapping in our models.
    with zipfile.ZipFile(client_jar) as jar:
        state = json.loads(jar.read("assets/minecraft/blockstates/iron_door.json"))
    for variant in state["variants"].values():
        variant["model"] = variant["model"].replace("minecraft:block/iron_door", f"{NS}:block/blast_door")
    write(f"{ASSETS}/blockstates/blast_door.json", state)
    for part in ["bottom_left", "bottom_left_open", "bottom_right", "bottom_right_open",
                 "top_left", "top_left_open", "top_right", "top_right_open"]:
        write(f"{ASSETS}/models/block/blast_door_{part}.json", {
            "parent": f"minecraft:block/door_{part}",
            "textures": {"bottom": f"{NS}:block/blast_door_bottom", "top": f"{NS}:block/blast_door_top"}})
    item_model("blast_door")

    # Vehicles: never placed as blocks; VehicleRenderer draws these models scaled up.
    for name in models3d.VEHICLES:
        write(f"{ASSETS}/models/block/{name}.json", models3d.vehicle_model(name))
    write(f"{ASSETS}/models/block/tank_icon.json",
          {"textures": {"parts": f"{NS}:block/vehicle_parts", "particle": f"{NS}:block/vehicle_parts"}, "elements": models3d_blocks.TANK_ICON})
    vehicle_item("jeep", models3d.JEEP, f"{NS}:block/jeep_model")
    vehicle_item("tank", models3d_blocks.TANK_ICON, f"{NS}:block/tank_icon")
    vehicle_item("jet", models3d.JET, f"{NS}:block/jet_model")
    for name in models3d.VEHICLES:
        write(f"{ASSETS}/blockstates/{name}.json", {"variants": {"": {"model": f"{NS}:block/{name}"}}})


# ---------------------------------------------------------------- language
LANG = {
    "itemGroup.rubymod.ruby_wars": "Ruby Wars",
    "key.category.rubymod.ruby_wars": "Ruby Wars",
    "key.rubymod.reload": "Reload Gun",
    "item.rubymod.ruby": "Ruby",
    "item.rubymod.light_ammo": "Light Ammo",
    "item.rubymod.heavy_ammo": "Heavy Ammo",
    "item.rubymod.shotgun_shell": "Shotgun Shells",
    "item.rubymod.sniper_round": "Sniper Rounds",
    "item.rubymod.ruby_cell": "Ruby Cell",
    "item.rubymod.service_pistol": "Service Pistol",
    "item.rubymod.combat_shotgun": "Combat Shotgun",
    "item.rubymod.arr_rifle": "AR-R Rifle",
    "item.rubymod.longshot_sniper": "Longshot Sniper",
    "item.rubymod.ruby_blaster": "Ruby Blaster",
    "item.rubymod.frag_grenade": "Frag Grenade",
    "item.rubymod.smoke_grenade": "Smoke Grenade",
    "item.rubymod.air_strike_flare": "Air Strike Flare",
    "item.rubymod.rocket_launcher": "Rocket Launcher",
    "item.rubymod.rocket": "Rocket",
    "item.rubymod.mortar_shell": "Mortar Shell",
    "item.rubymod.cruise_missile": "Cruise Missile",
    "item.rubymod.rangefinder": "Rangefinder",
    "item.rubymod.breaching_charge": "Breaching Charge",
    "item.rubymod.builders_wand": "Builder's Wand",
    "item.rubymod.excavator": "Excavator Pickaxe",
    "item.rubymod.ruby_helmet": "Ruby Helmet",
    "item.rubymod.ruby_chestplate": "Ruby Chestplate",
    "item.rubymod.ruby_leggings": "Ruby Leggings",
    "item.rubymod.ruby_boots": "Ruby Boots",
    "item.rubymod.ruby_sword": "Ruby Sword",
    "item.rubymod.ruby_pickaxe": "Ruby Pickaxe",
    "item.rubymod.extended_mag": "Extended Mag",
    "item.rubymod.red_dot_sight": "Red Dot Sight",
    "item.rubymod.vertical_grip": "Vertical Grip",
    "item.rubymod.suppressor": "Suppressor",
    "item.rubymod.jeep": "Jeep",
    "item.rubymod.tank": "Tank",
    "item.rubymod.jet": "Jet",
    "entity.rubymod.jet": "Jet",
    "entity.rubymod.jeep": "Jeep",
    "entity.rubymod.tank": "Tank",
    "block.rubymod.jeep_model": "Jeep",
    "block.rubymod.tank_model": "Tank",
    "block.rubymod.tank_turret_model": "Tank Turret",
    "block.rubymod.tank_barrel_model": "Tank Barrel",
    "block.rubymod.jet_model": "Jet",
    "block.rubymod.ruby_block": "Block of Ruby",
    "block.rubymod.ruby_ore": "Ruby Ore",
    "block.rubymod.deepslate_ruby_ore": "Deepslate Ruby Ore",
    "block.rubymod.ruby_heart": "Ruby Heart",
    "block.rubymod.armory": "Armory",
    "block.rubymod.sandbags": "Sandbags",
    "block.rubymod.concrete_wall": "Concrete Wall",
    "block.rubymod.reinforced_concrete": "Reinforced Concrete",
    "block.rubymod.blast_door": "Blast Door",
    "block.rubymod.sentry_turret": "Sentry Turret",
    "block.rubymod.missile_interceptor": "Missile Interceptor",
    "block.rubymod.radar_station": "Radar Station",
    "block.rubymod.alarm_block": "Alarm Block",
    "block.rubymod.claymore": "Claymore",
    "block.rubymod.landmine": "Landmine",
    "block.rubymod.barbed_wire": "Barbed Wire",
    "block.rubymod.mortar": "Mortar",
    "block.rubymod.missile_silo": "Missile Silo",
    "entity.rubymod.frag_grenade": "Frag Grenade",
    "entity.rubymod.smoke_grenade": "Smoke Grenade",
    "entity.rubymod.rocket": "Rocket",
    "entity.rubymod.mortar_shell": "Mortar Shell",
    "entity.rubymod.cruise_missile": "Cruise Missile",
    "entity.rubymod.bomb": "Bomb",
    "entity.rubymod.air_strike_flare": "Air Strike Flare",
    "entity.rubymod.breaching_charge": "Breaching Charge",
    "death.attack.rubymod.bullet": "%1$s was shot",
    "death.attack.rubymod.bullet.player": "%1$s was shot by %2$s",
    "death.attack.rubymod.bullet.item": "%1$s was shot by %2$s using %3$s",
    "death.attack.rubymod.headshot": "%1$s took a headshot",
    "death.attack.rubymod.headshot.player": "%1$s was headshot by %2$s",
    "death.attack.rubymod.headshot.item": "%1$s was headshot by %2$s using %3$s",
    "death.attack.rubymod.turret": "%1$s walked into a sentry turret",
    "death.attack.rubymod.turret.player": "%1$s was gunned down by %2$s's sentry turret",
}


# ---------------------------------------------------------------- data
def shaped(name, pattern, key, result, count=1, category="misc"):
    write(f"{DATA}/{NS}/recipe/{name}.json", {"type": "minecraft:crafting_shaped", "category": category, "pattern": pattern,
                                              "key": key, "result": {"id": result, "count": count}})


def shapeless(name, ingredients, result, count=1, category="misc"):
    write(f"{DATA}/{NS}/recipe/{name}.json", {"type": "minecraft:crafting_shapeless", "category": category,
                                              "ingredients": ingredients, "result": {"id": result, "count": count}})


def gen_recipes():
    old = f"{DATA}/{NS}/recipe/ruby.json"
    if os.path.exists(old):
        os.remove(old)
    shaped("ruby_block", ["###", "###", "###"], {"#": f"{NS}:ruby"}, f"{NS}:ruby_block", category="building")
    shapeless("ruby_from_ruby_block", [f"{NS}:ruby_block"], f"{NS}:ruby", 9)
    shaped("armory", ["IRI", "ICI", "III"], {"I": "minecraft:iron_ingot", "R": f"{NS}:ruby", "C": "minecraft:crafting_table"},
           f"{NS}:armory", category="building")
    shapeless("light_ammo", ["minecraft:copper_ingot", "minecraft:gunpowder", "minecraft:iron_nugget"], f"{NS}:light_ammo", 16)
    shapeless("heavy_ammo", ["minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:gunpowder"], f"{NS}:heavy_ammo", 16)
    shapeless("shotgun_shell", ["minecraft:paper", "minecraft:gunpowder", "minecraft:iron_nugget", "minecraft:iron_nugget"],
              f"{NS}:shotgun_shell", 8)
    shapeless("sniper_round", ["minecraft:iron_ingot", "minecraft:gold_nugget", "minecraft:gunpowder", "minecraft:gunpowder"],
              f"{NS}:sniper_round", 8)
    shapeless("ruby_cell", [f"{NS}:ruby", "minecraft:redstone", "minecraft:copper_ingot"], f"{NS}:ruby_cell", 8)
    r = {"R": f"{NS}:ruby"}
    rs = {"R": f"{NS}:ruby", "S": "minecraft:stick"}
    shaped("ruby_helmet", ["RRR", "R R"], r, f"{NS}:ruby_helmet", category="equipment")
    shaped("ruby_chestplate", ["R R", "RRR", "RRR"], r, f"{NS}:ruby_chestplate", category="equipment")
    shaped("ruby_leggings", ["RRR", "R R", "R R"], r, f"{NS}:ruby_leggings", category="equipment")
    shaped("ruby_boots", ["R R", "R R"], r, f"{NS}:ruby_boots", category="equipment")
    shaped("ruby_sword", ["R", "R", "S"], rs, f"{NS}:ruby_sword", category="equipment")
    shaped("ruby_pickaxe", ["RRR", " S ", " S "], rs, f"{NS}:ruby_pickaxe", category="equipment")
    shaped("sandbags", ["SSS", "WWW"], {"S": "minecraft:sand", "W": "#minecraft:wool"}, f"{NS}:sandbags", 6, "building")
    shaped("concrete_wall", ["SGS", "GSG", "SGS"], {"S": "minecraft:stone", "G": "minecraft:gravel"}, f"{NS}:concrete_wall", 4, "building")


def gen_loot():
    def drop_self(name):
        write(f"{DATA}/{NS}/loot_table/blocks/{name}.json", {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "condition": {"type": "minecraft:survives_explosion"},
                       "entries": [{"type": "minecraft:item", "name": f"{NS}:{name}"}]}],
            "random_sequence": f"{NS}:blocks/{name}"})

    for name in ["ruby_block", "ruby_heart", "armory", "sandbags", "concrete_wall", "reinforced_concrete", "sentry_turret",
                 "missile_interceptor", "radar_station", "alarm_block", "claymore", "landmine", "barbed_wire", "mortar", "missile_silo"]:
        drop_self(name)

    def ore(name):
        write(f"{DATA}/{NS}/loot_table/blocks/{name}.json", {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "entries": [{"type": "minecraft:alternatives", "children": [
                {"type": "minecraft:item", "condition": "minecraft:tool/can_silk_touch", "name": f"{NS}:{name}"},
                {"type": "minecraft:item", "name": f"{NS}:ruby", "modifier": [
                    {"type": "minecraft:apply_bonus", "enchantment": "minecraft:fortune", "formula": "minecraft:ore_drops"},
                    {"type": "minecraft:explosion_decay"}]}]}]}],
            "random_sequence": f"{NS}:blocks/{name}"})

    ore("ruby_ore")
    ore("deepslate_ruby_ore")

    write(f"{DATA}/{NS}/loot_table/blocks/blast_door.json", {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "condition": {"type": "minecraft:survives_explosion"}, "entries": [{
            "type": "minecraft:item", "name": f"{NS}:blast_door",
            "condition": {"type": "minecraft:match_block", "blocks": f"{NS}:blast_door", "state": {"half": "lower"}}}]}],
        "random_sequence": f"{NS}:blocks/blast_door"})


def gen_tags():
    pickaxe = ["ruby_block", "ruby_ore", "deepslate_ruby_ore", "armory", "concrete_wall", "reinforced_concrete", "blast_door",
               "sentry_turret", "missile_interceptor", "radar_station", "alarm_block", "claymore", "landmine", "mortar", "missile_silo"]
    write(f"{DATA}/minecraft/tags/block/mineable/pickaxe.json", {"values": [f"{NS}:{n}" for n in pickaxe]})
    write(f"{DATA}/minecraft/tags/block/mineable/shovel.json", {"values": [f"{NS}:sandbags"]})
    write(f"{DATA}/minecraft/tags/block/mineable/axe.json", {"values": [f"{NS}:barbed_wire"]})
    write(f"{DATA}/minecraft/tags/block/needs_iron_tool.json",
          {"values": [f"{NS}:{n}" for n in ["ruby_block", "ruby_ore", "deepslate_ruby_ore", "reinforced_concrete", "blast_door"]]})


def gen_item_tags():
    write(f"{DATA}/{NS}/tags/item/ruby_repair_materials.json", {"values": [f"{NS}:ruby"]})
    for tag, items in (("head_armor", ["ruby_helmet"]), ("chest_armor", ["ruby_chestplate"]), ("leg_armor", ["ruby_leggings"]),
                       ("foot_armor", ["ruby_boots"]), ("swords", ["ruby_sword"]), ("pickaxes", ["ruby_pickaxe"])):
        write(f"{DATA}/minecraft/tags/item/{tag}.json", {"values": [f"{NS}:{i}" for i in items]})


def gen_worldgen():
    def rule(tag):
        return {"predicate_type": "minecraft:tag_match", "tag": tag}

    write(f"{DATA}/{NS}/worldgen/feature/ore_ruby.json", {
        "type": "minecraft:ore", "discard_chance_on_air_exposure": 0.5, "size": 5,
        "targets": [{"state": f"{NS}:ruby_ore", "target": rule("minecraft:stone_ore_replaceables")},
                    {"state": f"{NS}:deepslate_ruby_ore", "target": rule("minecraft:deepslate_ore_replaceables")}]})
    write(f"{DATA}/{NS}/worldgen/placed_feature/ore_ruby.json", {
        "feature": f"{NS}:ore_ruby",
        "placement": [{"type": "minecraft:count", "count": 4}, {"type": "minecraft:in_square"},
                      {"type": "minecraft:height_range", "height": {"type": "minecraft:trapezoid",
                                                                    "max_inclusive": {"absolute": 0},
                                                                    "min_inclusive": {"above_bottom": 0}}},
                      {"type": "minecraft:biome"}]})


def gen_damage_types():
    for name in ["bullet", "headshot", "turret"]:
        write(f"{DATA}/{NS}/damage_type/{name}.json", {"exhaustion": 0.1, "message_id": f"{NS}.{name}", "scaling": "never"})
    write(f"{DATA}/minecraft/tags/damage_type/is_projectile.json", {"values": [f"{NS}:bullet", f"{NS}:headshot", f"{NS}:turret"]})


def main():
    client_jar = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.gradle/caches/fabric-loom/26.3/minecraft-client.jar")
    for name in SIMPLE_ITEMS:
        item_model(name)
    for name in HANDHELD_ITEMS:
        item_model(name, parent="minecraft:item/handheld")
    for name in ARMOR_ITEMS:
        item_model(name)
    write(f"{ASSETS}/equipment/ruby.json", {"layers": {
        "humanoid": [{"texture": f"{NS}:ruby"}],
        "humanoid_leggings": [{"texture": f"{NS}:ruby"}]}})
    for name in GUNS:
        gun_item(name)
    gen_blocks(client_jar)
    write(f"{ASSETS}/lang/en_us.json", LANG)
    gen_recipes()
    gen_loot()
    gen_tags()
    gen_item_tags()
    gen_worldgen()
    gen_damage_types()
    print("assets and data written")


if __name__ == "__main__":
    main()
