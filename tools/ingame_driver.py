#!/usr/bin/env python3
"""Automated in-game test driver.

Installs Minecraft 1.21.10 + Fabric Loader, drops the built mod, Axiom and
Fabric API into the mods folder, launches the real client on a virtual X
display (Xvfb + Mesa llvmpipe), drives the menus with screenshots + tesseract
OCR + xdotool clicks, then waits for the mod's in-game self test to write its
result. Everything useful (logs, screenshots, result) is copied to --out so
the workflow can commit it back to the repository.
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


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def http_download(url, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "railwaytool-ci"})
    with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    os.replace(tmp, dest)
    return dest


def fabric_api_jar(mods_dir):
    dest = os.path.join(mods_dir, "fabric-api.jar")
    if os.path.isfile(dest):
        return dest
    with urllib.request.urlopen(FABRIC_API_QUERY, timeout=60) as r:
        versions = json.load(r)
    if not versions:
        raise RuntimeError("no Fabric API build for 1.21.10")
    files = versions[0]["files"]
    files.sort(key=lambda f: not f.get("primary", False))
    url = files[0]["url"]
    log(f"Fabric API {versions[0]['version_number']}")
    return http_download(url, dest)


def install(mc_dir, java):
    import minecraft_launcher_lib as mcl
    version_id = f"fabric-loader-{LOADER_VERSION}-{MC_VERSION}"
    if not os.path.isdir(os.path.join(mc_dir, "versions", version_id)):
        log("installing Minecraft " + MC_VERSION)
        mcl.install.install_minecraft_version(MC_VERSION, mc_dir)
        log("installing Fabric loader")
        mcl.fabric.install_fabric(MC_VERSION, mc_dir,
                                  loader_version=LOADER_VERSION, java=java)
    return version_id


def write_options(mc_dir):
    options = {
        "version": "3955",
        "guiScale": "2",
        "overrideWidth": "1280",
        "overrideHeight": "720",
        "fullscreen": "false",
        "maxFps": "30",
        "renderDistance": "2",
        "simulationDistance": "6",
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
    }
    os.makedirs(mc_dir, exist_ok=True)
    with open(os.path.join(mc_dir, "options.txt"), "w", encoding="utf-8") as f:
        for k, v in options.items():
            f.write(f"{k}:{v}\n")


def xdotool(*args):
    subprocess.run(["xdotool"] + list(args), check=False,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def screenshot(display, path):
    subprocess.run(["import", "-display", display, "-window", "root", path],
                   check=False, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)
    return os.path.isfile(path)


def ocr(path):
    out = subprocess.run(
        ["tesseract", path, "stdout", "--psm", "11", "tsv"],
        capture_output=True, text=True, check=False).stdout
    words = []
    for line in out.splitlines()[1:]:
        cols = line.split("\t")
        if len(cols) < 12:
            continue
        try:
            conf = float(cols[10])
        except ValueError:
            continue
        text = cols[11].strip()
        if not text or conf < 30:
            continue
        left, top = int(cols[6]), int(cols[7])
        width, height = int(cols[8]), int(cols[9])
        words.append((text, left + width // 2, top + height // 2, conf))
    return words


def click(display, x, y):
    subprocess.run(["xdotool", "mousemove", "--sync", str(x), str(y)],
                   check=False)
    subprocess.run(["xdotool", "click", "1"], check=False, timeout=10)


def find(words, needle):
    needle = needle.lower()
    return [w for w in words if needle in w[0].lower()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc-dir", default="run-mc")
    ap.add_argument("--out", default="ingame")
    ap.add_argument("--mod", required=True)
    ap.add_argument("--java", required=True)
    ap.add_argument("--display", default=":99")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    os.makedirs(args.mc_dir, exist_ok=True)
    os.environ["DISPLAY"] = args.display
    os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
    os.environ.setdefault("MESA_GL_VERSION_OVERRIDE", "4.5")

    import minecraft_launcher_lib as mcl

    version_id = install(args.mc_dir, args.java)
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
    log("launching client")
    time.sleep(5)
    subprocess.run(["xdotool", "search", "--name", "Minecraft", "windowfocus"],
                   check=False, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)
    console = open(os.path.join(args.mc_dir, "console.log"), "w",
                   encoding="utf-8", errors="replace")
    proc = subprocess.Popen(cmd, cwd=args.mc_dir, stdout=console,
                            stderr=subprocess.STDOUT)
    result = os.path.join(args.mc_dir, "selftest-result.txt")
    shots = []
    clicked = set()
    deadline = time.time() + 900
    while time.time() < deadline and proc.poll() is None:
        shot = os.path.join(args.out, f"shot-{int(time.time())}.png")
        if screenshot(args.display, shot):
            shots.append(shot)
            words = ocr(shot)
            low = " ".join(w[0].lower() for w in words)
            log("ocr: " + " ".join(w[0] for w in words)[:180])
            if "continue" in low:
                c = find(words, "continue")
                click(args.display, c[0][1], c[0][2])
                time.sleep(2)
            elif "singleplayer" in low and "singleplayer" not in clicked:
                shutil.copy2(shot, os.path.join(args.out, "title.png"))
                c = find(words, "singleplayer")
                click(args.display, c[0][1], c[0][2])
                clicked.add("singleplayer")
                time.sleep(3)
            elif "superflat" in low and "created" not in clicked:
                c = find(words, "create")
                if c:
                    click(args.display, c[-1][1], c[-1][2])
                    clicked.add("created")
                    time.sleep(5)
            elif "default" in low and "singleplayer" in clicked \
                    and "created" not in clicked:
                c = find(words, "default")
                if c:
                    click(args.display, c[0][1], c[0][2])
                    time.sleep(2)
            elif "create" in low and "created" not in clicked:
                c = find(words, "create")
                if c:
                    click(args.display, c[-1][1], c[-1][2])
                    clicked.add("created")
                    time.sleep(5)
        if os.path.isfile(result):
            log("selftest result detected")
            break
        time.sleep(3)

    if os.path.isfile(result):
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

    for name in ("logs/latest.log", "console.log", "selftest-result.txt"):
        src = os.path.join(args.mc_dir, name)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(args.out,
                                           os.path.basename(name)))
    shots.sort()
    keep = shots[-6:]
    for s in shots:
        if s not in keep and os.path.basename(s) != "world.png":
            os.remove(s)
    status = "UNKNOWN"
    if os.path.isfile(result):
        status = open(result, encoding="utf-8", errors="replace").read()
    with open(os.path.join(args.out, "driver.log"), "w", encoding="utf-8") as f:
        f.write("status:\n" + status + "\n")
        f.write("clicked: " + repr(sorted(clicked)) + "\n")
    first = status.strip().splitlines()[0] if status.strip() else "no result"
    log("done: " + first)
    return 0


if __name__ == "__main__":
    sys.exit(main())
