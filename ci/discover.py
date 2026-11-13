#!/usr/bin/env python3
"""CI helper: lists dependency versions and dumps the Cobblemon API (javap) used by CobblePulse."""
import json, os, re, subprocess, sys, urllib.request, zipfile

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/discover"
os.makedirs(OUT, exist_ok=True)
UA = {"User-Agent": "cobblepulse-ci/1.0 (github actions)"}


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def maven_versions(url):
    try:
        return re.findall(r"<version>([^<]+)</version>", get(url).decode())
    except Exception as e:
        return ["ERR " + str(e)]


lines = []
def section(title, items):
    lines.append("== " + title)
    lines.extend(items)

section("cobblemon impactdev fabric", maven_versions("https://maven.impactdev.net/repository/development/com/cobblemon/fabric/maven-metadata.xml")[-40:])
section("fabric-api 1.21.1", [v for v in maven_versions("https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml") if v.endswith("+1.21.1")][-5:])
section("fabric-loader", maven_versions("https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml")[-8:])
section("fabric-language-kotlin", maven_versions("https://maven.fabricmc.net/net/fabricmc/fabric-language-kotlin/maven-metadata.xml")[-8:])
section("fabric-loom", maven_versions("https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml")[-15:])
section("placeholder-api", [v for v in maven_versions("https://maven.nucleoid.xyz/eu/pb4/placeholder-api/maven-metadata.xml") if "1.21" in v][-8:])
section("luckperms api", maven_versions("https://repo1.maven.org/maven2/net/luckperms/api/maven-metadata.xml")[-5:])

modrinth = {}
for slug in ["cobblemon", "fabric-api", "fabric-language-kotlin", "luckperms", "placeholder-api", "shadowchat"]:
    try:
        vs = json.loads(get(f"https://api.modrinth.com/v2/project/{slug}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%221.21.1%22%5D"))
        modrinth[slug] = vs
        items = []
        for v in vs[:6]:
            f = [f for f in v["files"] if f["primary"]] or v["files"]
            items.append(f'{v["version_number"]} | id={v["id"]} | {v["date_published"]} | {f[0]["url"]}')
        section("modrinth " + slug, items)
    except Exception as e:
        section("modrinth " + slug, ["ERR " + str(e)])

with open(os.path.join(OUT, "versions.txt"), "w") as fh:
    fh.write("\n".join(lines) + "\n")
with open(os.path.join(OUT, "modrinth.json"), "w") as fh:
    json.dump({k: [{"version_number": v["version_number"], "id": v["id"], "files": v["files"]} for v in vs[:10]] for k, vs in modrinth.items()}, fh, indent=1)

# Cobblemon jar -> API dump
cob = None
for v in modrinth.get("cobblemon", []):
    if "1.8.4" in v["version_number"]:
        cob = v
        break
if cob is None and modrinth.get("cobblemon"):
    cob = modrinth["cobblemon"][0]
if cob:
    f = [f for f in cob["files"] if f["primary"]] or cob["files"]
    jar = os.path.join(OUT, "cobblemon.jar")
    with open(jar, "wb") as fh:
        fh.write(get(f[0]["url"]))
    z = zipfile.ZipFile(jar)
    names = z.namelist()
    with open(os.path.join(OUT, "fabric.mod.json"), "wb") as fh:
        fh.write(z.read("fabric.mod.json"))
    keys = ("battles", "events", "PartyStore", "PokemonStoreManager", "item/PokemonItem", "api/types", "pokemon/Species",
            "pokemon/Pokemon.class", "pokemon/FormData", "abilities/Ability", "moves/Move", "Cobblemon.class", "PlayerExtensions",
            "api/Priority", "reactive")
    with open(os.path.join(OUT, "classes.txt"), "w") as fh:
        for n in names:
            if n.endswith(".class") and n.startswith("com/cobblemon/mod/common/") and any(k in n for k in keys):
                fh.write(n + "\n")
    targets = os.environ.get("JAVAP_CLASSES", "").split()
    if not targets:
        targets = [
            "com.cobblemon.mod.common.Cobblemon",
            "com.cobblemon.mod.common.api.events.CobblemonEvents",
            "com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent",
            "com.cobblemon.mod.common.api.events.battles.BattleStartedEvent",
            "com.cobblemon.mod.common.api.events.battles.BattleStartedEvent$Pre",
            "com.cobblemon.mod.common.api.events.battles.BattleStartedPreEvent",
            "com.cobblemon.mod.common.api.events.battles.BattleFledEvent",
            "com.cobblemon.mod.common.api.reactive.EventObservable",
            "com.cobblemon.mod.common.api.reactive.Observable",
            "com.cobblemon.mod.common.api.Priority",
            "com.cobblemon.mod.common.battles.BattleBuilder",
            "com.cobblemon.mod.common.battles.BattleRegistry",
            "com.cobblemon.mod.common.battles.BattleFormat",
            "com.cobblemon.mod.common.battles.BattleFormat$Companion",
            "com.cobblemon.mod.common.battles.BattleStartResult",
            "com.cobblemon.mod.common.battles.SuccessfulBattleStart",
            "com.cobblemon.mod.common.battles.ErroredBattleStart",
            "com.cobblemon.mod.common.battles.BattleStartError",
            "com.cobblemon.mod.common.api.battles.model.PokemonBattle",
            "com.cobblemon.mod.common.api.battles.model.actor.BattleActor",
            "com.cobblemon.mod.common.battles.actor.PlayerBattleActor",
            "com.cobblemon.mod.common.battles.pokemon.BattlePokemon",
            "com.cobblemon.mod.common.api.storage.PokemonStoreManager",
            "com.cobblemon.mod.common.api.storage.party.PartyStore",
            "com.cobblemon.mod.common.api.storage.party.PlayerPartyStore",
            "com.cobblemon.mod.common.pokemon.Pokemon",
            "com.cobblemon.mod.common.pokemon.Species",
            "com.cobblemon.mod.common.pokemon.FormData",
            "com.cobblemon.mod.common.api.types.ElementalType",
            "com.cobblemon.mod.common.api.types.ElementalTypes",
            "com.cobblemon.mod.common.item.PokemonItem",
            "com.cobblemon.mod.common.item.PokemonItem$Companion",
            "com.cobblemon.mod.common.api.abilities.Ability",
            "com.cobblemon.mod.common.api.moves.MoveSet",
            "com.cobblemon.mod.common.api.moves.Move",
            "com.cobblemon.mod.common.api.pokemon.PokemonProperties",
            "com.cobblemon.mod.common.api.pokemon.PokemonProperties$Companion",
            "com.cobblemon.mod.common.util.PlayerExtensionsKt",
        ]
    with open(os.path.join(OUT, "javap.txt"), "w") as fh:
        for c in targets:
            r = subprocess.run(["javap", "-cp", jar, c], capture_output=True, text=True)
            fh.write(f"##### {c}\n{r.stdout}{r.stderr}\n")
    # Corps des methodes de fin de combat + gestion des deconnexions
    r = subprocess.run(["javap", "-c", "-p", "-cp", jar, "com.cobblemon.mod.common.api.battles.model.PokemonBattle"], capture_output=True, text=True)
    body = r.stdout
    out = []
    for sig in ("public final void end()", "public final void stop()"):
        i = body.find(sig)
        if i >= 0:
            j = body.find("\n\n", i)
            out.append(body[i:j if j > 0 else i + 6000][:6000])
    with open(os.path.join(OUT, "battle_end_stop.txt"), "w") as fh:
        fh.write("\n\n".join(out))
    hits = []
    for n in names:
        if not n.endswith(".class") or not n.startswith("com/cobblemon/"):
            continue
        data = z.read(n)
        if b"getBattleByParticipatingPlayer" in data and (b"isDisconnected" in data or b"Disconnect" in data or b"LOGOUT" in data or b"logout" in data.lower()):
            hits.append(n)
    with open(os.path.join(OUT, "disconnect_classes.txt"), "w") as fh:
        fh.write("\n".join(hits))
    for n in hits[:6]:
        r = subprocess.run(["javap", "-c", "-p", "-cp", jar, n[:-6].replace("/", ".")], capture_output=True, text=True)
        with open(os.path.join(OUT, "disconnect_" + n.split("/")[-1] + ".txt"), "w") as fh:
            fh.write(r.stdout[:60000])
    os.remove(jar)
print("discover done")
