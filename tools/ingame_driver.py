#!/usr/bin/env python3
"""Automated in-game test driver.

Installs Minecraft 1.21.10 + Fabric Loader, generates a superflat creative
world with the vanilla server, drops the built mod, Axiom and Fabric API into
mods/, then launches the real client on a virtual X display (Xvfb + Mesa
llvmpipe). The client enters the world directly through quick play; the mod's
self test builds a line with the real tool, and the driver captures screenshots
with xdotool/ImageMagick and writes everything to --out.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid

AXIOM_URL = ("https://cdn.modrinth.com/data/N6n5dqoA/versions/65TQdBZB/"
             "Axiom-5.4.2-for-MC1.21.10.jar")
FABRIC_API_QUERY = ("https://api.modrinth.com/v2/project/fabric-api/version"
                    "?game_versions=%5B%221.21.10%22%5D&loaders=%5B%22fabric%22%5D")
MC_VERSION = "1.21.10"
LOADER_VERSION = "0.19.3"
WORLD_NAME = "railtest"


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def http_download(url, dest):
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "railwaytool-ci"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    os.replace(tmp, dest)
    return dest


def fabric_api_jar(mods_dir):
    dest = os.path.join(mods_dir, "fabric-api.jar")
    if os.path.isfile(dest):
        return dest
    with urllib.request.urlopen(FABRIC_API_QUERY, timeout=120) as r:
        versions = json.load(r)
    if not versions:
        raise RuntimeError("no Fabric API build for 1.21.10")
    files = versions[0]["files"]
    files.sort(key=lambda f: not f.get("primary", False))
    log(f"Fabric API {versions[0]['version_number']}")
    return http_download(files[0]["url"], dest)


def install(mc_dir, java):
    import minecraft_launcher_lib as mcl
    version_id = f"fabric-loader-{LOADER_VERSION}-{MC_VERSION}"
    profile = os.path.join(mc_dir, "versions", version_id, version_id + ".json")
    if not os.path.isfile(profile):
        log("installing Minecraft " + MC_VERSION)
        mcl.install.install_minecraft_version(MC_VERSION, mc_dir)
        log("installing Fabric loader " + LOADER_VERSION)
        mcl.fabric.install_fabric(MC_VERSION, mc_dir,
                                  loader_version=LOADER_VERSION, java=java)
    loader_jar = os.path.join(
        mc_dir, "libraries", "net", "fabricmc", "fabric-loader",
        LOADER_VERSION, f"fabric-loader-{LOADER_VERSION}.jar")
    if not os.path.isfile(loader_jar):
        log("fabric loader jar missing, fetching from maven")
        http_download(
            f"https://maven.fabricmc.net/net/fabricmc/fabric-loader/"
            f"{LOADER_VERSION}/fabric-loader-{LOADER_VERSION}.jar",
            loader_jar)
    log("fabric loader jar: " + str(os.path.isfile(loader_jar)))
    return version_id


def version_document(mc_dir):
    path = os.path.join(mc_dir, "versions", MC_VERSION, MC_VERSION + ".json")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def superflat_world(mc_dir, java, out):
    """Generate a flat creative world with the vanilla server."""
    save = os.path.join(mc_dir, "saves", WORLD_NAME)
    if os.path.isdir(save):
        log("reusing generated world")
        return save
    doc = version_document(mc_dir)
    server_url = doc.get("downloads", {}).get("server", {}).get("url")
    if not server_url:
        raise RuntimeError("version document has no server download")
    server_dir = os.path.join(mc_dir, "server")
    os.makedirs(server_dir, exist_ok=True)
    jar = os.path.join(server_dir, "server.jar")
    http_download(server_url, jar)
    with open(os.path.join(server_dir, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    props = {
        "level-name": "world",
        "level-type": "minecraft\\:flat",
        "generator-settings": json.dumps({
            "layers": [
                {"block": "minecraft:bedrock", "height": 1},
                {"block": "minecraft:dirt", "height": 3},
                {"block": "minecraft:grass_block", "height": 1},
            ],
            "biome": "minecraft:plains",
            "structure_overrides": [],
        }),
        "gamemode": "creative",
        "difficulty": "peaceful",
        "online-mode": "false",
        "spawn-monsters": "false",
        "max-players": "1",
        "view-distance": "4",
        "simulation-distance": "5",
        "spawn-protection": "0",
    }
    with open(os.path.join(server_dir, "server.properties"), "w") as f:
        for k, v in props.items():
            f.write(f"{k}={v}\n")
    log("generating flat world (vanilla server)")
    proc = subprocess.Popen(
        [java, "-Xmx1G", "-jar", "server.jar", "--nogui"],
        cwd=server_dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, errors="replace")
    deadline = time.time() + 300
    started = False
    while time.time() < deadline:
        line = proc.stdout.readline()
        if not line:
            if proc.poll() is not None:
                break
            continue
        if "Done (" in line:
            started = True
            break
    if started:
        time.sleep(5)
    try:
        proc.stdin.write("stop\n")
        proc.stdin.flush()
    except Exception:
        pass
    try:
        proc.wait(timeout=120)
    except subprocess.TimeoutExpired:
        proc.kill()
    if not started:
        raise RuntimeError("server did not start; see server/logs/latest.log")
    os.makedirs(os.path.join(mc_dir, "saves"), exist_ok=True)
    shutil.copytree(os.path.join(server_dir, "world"), save)
    lock = os.path.join(save, "session.lock")
    if os.path.exists(lock):
        os.remove(lock)
    log("world ready: " + save)
    return save


def enable_cheats(save):
    """Axiom needs command level permissions to apply a build."""
    import nbtlib
    path = os.path.join(save, "level.dat")
    nbt = nbtlib.load(path)
    data = nbt["Data"]
    data["allowCommands"] = nbtlib.Byte(1)
    nbt.save(gzipped=True)
    log("cheats enabled in " + path)


def write_options(mc_dir):
    options = {
        "version": "3955",
        "guiScale": "2",
        "overrideWidth": "1280",
        "overrideHeight": "720",
        "fullscreen": "false",
        "maxFps": "30",
        "renderDistance": "10",
        "simulationDistance": "8",
        "graphicsMode": "0",
        "ao": "false",
        "entityShadows": "false",
        "pauseOnLostFocus": "false",
        "onboardAccessibility": "false",
        "tutorialStep": "none",
        "skipMultiplayerWarning": "true",
        "soundCategory_master": "0.0",
        "autoJump": "false",
        "chatVisibility": "0",
        "showSubtitles": "false",
        "fov": "70",
        "gamma": "1.0",
    }
    with open(os.path.join(mc_dir, "options.txt"), "w", encoding="utf-8") as f:
        for k, v in options.items():
            f.write(f"{k}:{v}\n")


def shell(*cmd, **kw):
    return subprocess.run(cmd, check=False, capture_output=True, text=True, **kw)


def screenshot(display, path):
    shell("import", "-display", display, "-window", "root", path)
    return os.path.isfile(path)


def ocr_text(path):
    out = subprocess.run(["tesseract", path, "stdout", "--psm", "6"],
                         capture_output=True, text=True, check=False).stdout
    return " ".join(out.split())[:200]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc-dir", default="run-mc")
    ap.add_argument("--out", default="ingame")
    ap.add_argument("--mod", required=True)
    ap.add_argument("--java", required=True)
    ap.add_argument("--display", default=":99")
    args = ap.parse_args()

    args.mc_dir = os.path.abspath(args.mc_dir)
    args.out = os.path.abspath(args.out)
    args.mod = os.path.abspath(args.mod)
    args.java = os.path.abspath(args.java)
    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.mc_dir, exist_ok=True)
    os.environ["DISPLAY"] = args.display
    os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
    os.environ.setdefault("MESA_GL_VERSION_OVERRIDE", "4.5")

    import minecraft_launcher_lib as mcl

    version_id = install(args.mc_dir, args.java)
    save = superflat_world(args.mc_dir, args.java, args.out)
    enable_cheats(save)

    mods = os.path.join(args.mc_dir, "mods")
    os.makedirs(mods, exist_ok=True)
    for f in os.listdir(mods):
        os.remove(os.path.join(mods, f))
    shutil.copy2(args.mod, os.path.join(mods, os.path.basename(args.mod)))
    http_download(AXIOM_URL, os.path.join(mods, "axiom.jar"))
    fabric_api_jar(mods)
    write_options(args.mc_dir)

    offline_uuid = str(uuid.uuid3(uuid.NAMESPACE_DNS, "ArenaTest"))
    options = {
        "username": "ArenaTest",
        "uuid": offline_uuid,
        "token": "",
        "executablePath": args.java,
        "gameDirectory": args.mc_dir,
        "jvmArguments": ["-Xmx2G", "-Dbte.railtool.selftest=true"],
    }
    cmd = mcl.command.get_minecraft_command(version_id, args.mc_dir, options)
    cmd += ["--quickPlaySingleplayer", WORLD_NAME,
            "--width", "1280", "--height", "720"]
    log("main class index: " + str(cmd.index("-cp") if "-cp" in cmd else -1))
    for part in cmd:
        if "fabric-loader" in part or "KnotClient" in part:
            log("classpath entry: " + part[:200])
    log("launching client")
    console_path = os.path.join(args.out, "console.log")
    console = open(console_path, "w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(cmd, cwd=args.mc_dir, stdout=console,
                            stderr=subprocess.STDOUT)

    time.sleep(20)
    shell("xdotool", "search", "--name", "Minecraft", "windowfocus")
    menu_shot = os.path.join(args.out, "axiom-menu.png")
    result = os.path.join(args.mc_dir, "selftest-result.txt")
    stage_file = os.path.join(args.mc_dir, "selftest-stage.txt")
    last_stage = ""
    start = time.time()
    deadline = start + 2400
    while time.time() < deadline and proc.poll() is None:
        if os.path.isfile(stage_file):
            stage = open(stage_file, encoding="utf-8", errors="replace").read().strip()
            if stage and stage != last_stage:
                last_stage = stage
                shot = os.path.join(args.out, "stage-" + stage + ".png")
                if screenshot(args.display, shot):
                    log("stage " + stage + ": " + ocr_text(shot))
                if stage.startswith("done-"):
                    break
        if not os.path.isfile(menu_shot) and time.time() - start > 55:
            shell("xdotool", "key", "alt")
            time.sleep(4)
            screenshot(args.display, menu_shot)
            shell("xdotool", "key", "alt")
            log("axiom menu: " + ocr_text(menu_shot))
        if os.path.isfile(result):
            log("selftest result detected")
            break
        time.sleep(2)

    time.sleep(2)
    shell("xdotool", "key", "F1")
    time.sleep(1)
    shell("xdotool", "key", "F2")
    time.sleep(1)
    screenshot(args.display, os.path.join(args.out, "world.png"))
    try:
        proc.wait(timeout=60)
    except subprocess.TimeoutExpired:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
    console.close()

    for name in ("logs/latest.log", "logs/debug.log", "selftest-result.txt"):
        src = os.path.join(args.mc_dir, name)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(args.out, os.path.basename(src)))
    shots_dir = os.path.join(args.mc_dir, "screenshots")
    if os.path.isdir(shots_dir):
        for name in sorted(os.listdir(shots_dir))[-3:]:
            shutil.copy2(os.path.join(shots_dir, name),
                         os.path.join(args.out, "ingame-" + name))
    status = "UNKNOWN"
    if os.path.isfile(result):
        status = open(result, encoding="utf-8", errors="replace").read()
    with open(os.path.join(args.out, "driver.log"), "w", encoding="utf-8") as f:
        f.write(status + "\n")
    first = status.strip().splitlines()[0] if status.strip() else "no result"
    log("done: " + first)
    return 0


if __name__ == "__main__":
    sys.exit(main())
