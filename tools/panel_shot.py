"""Staged screenshots of the Axiom editor, used to locate the tool panel."""

import argparse
import os
import shutil
import subprocess
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ingame_driver as drv


def shot(out, name):
    path = os.path.join(out, name + ".png")
    drv.shell("import", "-display", os.environ["DISPLAY"], "-window", "root", path)
    if os.path.isfile(path):
        drv.log("shot %s: %s" % (name, drv.ocr_text(path)))


def focus():
    ids = drv.shell("xdotool", "search", "--name", "Minecraft").stdout.split()
    if ids:
        drv.shell("xdotool", "windowactivate", "--sync", ids[-1])
        drv.shell("xdotool", "windowfocus", "--sync", ids[-1])
    return bool(ids)


def key(name):
    drv.shell("xdotool", "key", "--clearmodifiers", name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc-dir", default="run-mc")
    ap.add_argument("--out", default="panel")
    ap.add_argument("--mod", required=True)
    ap.add_argument("--java", required=True)
    ap.add_argument("--display", default=":99")
    ap.add_argument("--wait", type=int, default=95)
    args = ap.parse_args()

    args.mc_dir = os.path.abspath(args.mc_dir)
    args.out = os.path.abspath(args.out)
    args.mod = os.path.abspath(args.mod)
    args.java = os.path.abspath(args.java)
    os.makedirs(args.out, exist_ok=True)
    os.environ["DISPLAY"] = args.display
    os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
    os.environ.setdefault("MESA_GL_VERSION_OVERRIDE", "4.5")

    import minecraft_launcher_lib as mcl

    version = drv.install(args.mc_dir, args.java)
    save = drv.superflat_world(args.mc_dir, args.java, args.out)
    drv.enable_cheats(save)

    mods = os.path.join(args.mc_dir, "mods")
    os.makedirs(mods, exist_ok=True)
    for f in os.listdir(mods):
        os.remove(os.path.join(mods, f))
    shutil.copy2(args.mod, os.path.join(mods, os.path.basename(args.mod)))
    drv.http_download(drv.AXIOM_URL, os.path.join(mods, "axiom.jar"))
    drv.fabric_api_jar(mods)
    drv.write_options(args.mc_dir)

    options = {
        "username": "ArenaTest",
        "uuid": str(uuid.uuid3(uuid.NAMESPACE_DNS, "ArenaTest")),
        "token": "",
        "executablePath": args.java,
        "gameDirectory": args.mc_dir,
        "jvmArguments": ["-Xmx2G"],
    }
    cmd = mcl.command.get_minecraft_command(version, args.mc_dir, options)
    cmd += ["--quickPlaySingleplayer", drv.WORLD_NAME,
            "--width", "1280", "--height", "720"]
    console = open(os.path.join(args.out, "console.log"), "w",
                   encoding="utf-8", errors="replace")
    proc = subprocess.Popen(cmd, cwd=args.mc_dir, stdout=console,
                            stderr=subprocess.STDOUT)

    drv.log("waiting for the world")
    deadline = time.time() + args.wait
    while time.time() < deadline:
        time.sleep(5)
        log = drv.tail(os.path.join(args.mc_dir, "logs", "latest.log"), 200)
        if "Loaded" in log and "Axiom" in log:
            break
    time.sleep(20)
    drv.log("focused: %s" % focus())
    shot(args.out, "panel-0-world")

    drv.log("opening the editor")
    key("Shift_R")
    time.sleep(8)
    shot(args.out, "panel-1-editor")

    for n in range(1, 10):
        key(str(n))
        time.sleep(2.5)
        shot(args.out, "panel-2-slot%d" % n)

    drv.log("closing")
    key("Shift_R")
    time.sleep(2)
    proc.terminate()
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()
    console.close()
    src = os.path.join(args.mc_dir, "logs", "latest.log")
    if os.path.isfile(src):
        shutil.copy2(src, os.path.join(args.out, "latest.log"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
