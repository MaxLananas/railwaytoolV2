"""Staged screenshots of the Axiom editor, used to locate the tool panel."""

import argparse
import os
import shutil
import subprocess
import sys
import time
import traceback
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ingame_driver as drv


def shot(out, name):
    path = os.path.join(out, name + ".png")
    drv.shell("import", "-display", os.environ["DISPLAY"], "-window", "root", path)
    ok = os.path.isfile(path)
    drv.log("shot %s: %s %s" % (name, ok, drv.ocr_text(path) if ok else ""))
    return ok


def focus():
    ids = drv.shell("xdotool", "search", "--name", "Minecraft").stdout.split()
    if not ids:
        return False
    drv.shell("xdotool", "windowactivate", "--sync", ids[-1])
    drv.shell("xdotool", "windowfocus", "--sync", ids[-1])
    return True


def key(name):
    drv.shell("xdotool", "key", "--clearmodifiers", name)


def move_to(x, y, steps=24):
    """Glide the pointer: Axiom's editor tracks the cursor through deltas."""
    cur = drv.shell("xdotool", "getmouselocation", "--shell").stdout
    cx, cy = x, y
    for line in cur.splitlines():
        if line.startswith("X="):
            cx = int(line[2:])
        elif line.startswith("Y="):
            cy = int(line[2:])
    for i in range(1, steps + 1):
        nx = cx + (x - cx) * i // steps
        ny = cy + (y - cy) * i // steps
        drv.shell("xdotool", "mousemove", str(nx), str(ny))
        time.sleep(0.02)


def click_at(x, y):
    move_to(x, y)
    time.sleep(0.25)
    drv.shell("xdotool", "click", "1")


def click_text(word, out, timeout=1.0):
    """Click the first on-screen word matching `word` (OCR bounding box)."""
    path = os.path.join(out, "_ocr.png")
    drv.shell("import", "-display", os.environ["DISPLAY"], "-window", "root", path)
    tsv = subprocess.run(["tesseract", path, "stdout", "--psm", "11", "tsv"],
                         capture_output=True, text=True, check=False).stdout
    lines = tsv.splitlines()
    if not lines:
        return False
    header = lines[0].split("\t")
    try:
        idx = {name: header.index(name) for name in ("left", "top", "width",
                                                     "height", "conf", "text")}
    except ValueError:
        return False
    target = word.lower()
    for line in lines[1:]:
        cells = line.split("\t")
        if len(cells) <= max(idx.values()):
            continue
        text = cells[idx["text"]].strip().lower()
        try:
            conf = float(cells[idx["conf"]])
        except ValueError:
            continue
        if conf < 30 or not text:
            continue
        if target not in text:
            continue
        x = int(cells[idx["left"]]) + int(cells[idx["width"]]) // 2
        y = int(cells[idx["top"]]) + int(cells[idx["height"]]) // 2
        drv.shell("xdotool", "mousemove", "--sync", str(x), str(y))
        time.sleep(0.3)
        drv.shell("xdotool", "click", "1")
        drv.log("clicked '%s' at %d,%d" % (word, x, y))
        time.sleep(timeout)
        return True
    drv.log("text not found: " + word)
    return False


GRID_X = 23
GRID_Y = 67
GRID_DX = 40.4
GRID_DY = 38.9
GRID_COLS = 7
GRID_ROWS = 5
MARKERS = ("rail", "bte", "density", "preset", "spline", "build")


def ocr_full(path):
    out = subprocess.run(["tesseract", path, "stdout", "--psm", "6"],
                         capture_output=True, text=True, check=False).stdout
    return " ".join(out.split())


def panel_crop(full, dest):
    """Tool Options column, where a tool renders its own settings."""
    drv.shell("convert", full, "-crop", "330x820+0+250", "+repage", dest)
    return dest


def scan_tools(out):
    """Click every tool icon until the rail panel shows up in Tool Options."""
    for index in range(GRID_COLS * GRID_ROWS):
        col = index % GRID_COLS
        row = index // GRID_COLS
        x = int(GRID_X + col * GRID_DX)
        y = int(GRID_Y + row * GRID_DY)
        click_at(x, y)
        time.sleep(1.2)
        full = os.path.join(out, "_scan.png")
        drv.shell("import", "-display", os.environ["DISPLAY"], "-window", "root", full)
        crop = panel_crop(full, os.path.join(out, "scan-%02d.png" % index))
        text = ocr_full(crop)
        drv.log("tool %d (%d,%d): %s" % (index, x, y, text[:200]))
        if any(marker in text.lower() for marker in MARKERS):
            drv.log("rail tool found at index %d" % index)
            shutil.copy2(full, os.path.join(out, "20-tool-selected.png"))
            return index
    drv.log("rail tool not in the grid")
    return -1


def place_points(out):
    for x, y in ((700, 820), (1000, 860), (1300, 800)):
        move_to(x, y)
        time.sleep(0.3)
        drv.shell("xdotool", "click", "3")
        time.sleep(2.0)
    time.sleep(2.0)
    shot(out, "21-tool-with-line")


def run_script(out, script):
    """Tiny action list: click/move/wheel/key/drag/sleep/shot."""
    for step in script.split(";"):
        parts = step.split()
        if not parts:
            continue
        verb, rest = parts[0], parts[1:]
        try:
            if verb == "click":
                click_at(int(rest[0]), int(rest[1]))
            elif verb == "move":
                move_to(int(rest[0]), int(rest[1]))
            elif verb == "wheel":
                drv.shell("xdotool", "mousemove", "--sync", rest[0], rest[1])
                button = "5" if len(rest) > 3 and rest[3] == "down" else "4"
                for _ in range(int(rest[2])):
                    drv.shell("xdotool", "click", button)
                    time.sleep(0.15)
            elif verb == "key":
                drv.shell("xdotool", "key", "--clearmodifiers", rest[0])
            elif verb == "drag":
                drv.shell("xdotool", "mousemove", "--sync", rest[0], rest[1])
                drv.shell("xdotool", "mousedown", "1")
                time.sleep(0.2)
                drv.shell("xdotool", "mousemove", "--sync", rest[2], rest[3])
                time.sleep(0.2)
                drv.shell("xdotool", "mouseup", "1")
            elif verb == "scan_tools":
                scan_tools(out)
            elif verb == "place_points":
                place_points(out)
            elif verb == "clicktext":
                click_text(" ".join(rest), out)
            elif verb == "sleep":
                time.sleep(float(rest[0]))
            elif verb == "shot":
                shot(out, rest[0])
            else:
                drv.log("unknown action: " + step)
        except Exception:
            drv.log("action failed (%s):\n%s" % (step, traceback.format_exc()))


def save_logs(mc_dir, out):
    src = os.path.join(mc_dir, "logs", "latest.log")
    if os.path.isfile(src):
        shutil.copy2(src, os.path.join(out, "latest.log"))
    for extra in ("logs/debug.log",):
        src = os.path.join(mc_dir, extra)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(out, os.path.basename(extra)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc-dir", default="run-mc")
    ap.add_argument("--out", default="panel")
    ap.add_argument("--mod", required=True)
    ap.add_argument("--java", required=True)
    ap.add_argument("--display", default=":99")
    ap.add_argument("--wait", type=int, default=240)
    ap.add_argument("--size", default="1280x720")
    ap.add_argument("--script", default="")
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

    proc = None
    console = None
    try:
        drv.log("mod: %s (%s)" % (args.mod, os.path.isfile(args.mod)))
        version = drv.install(args.mc_dir, args.java)
        drv.log("version: " + version)
        save = drv.superflat_world(args.mc_dir, args.java, args.out)
        drv.enable_cheats(save)

        mods = os.path.join(args.mc_dir, "mods")
        os.makedirs(mods, exist_ok=True)
        for f in os.listdir(mods):
            os.remove(os.path.join(mods, f))
        shutil.copy2(args.mod, os.path.join(mods, os.path.basename(args.mod)))
        drv.http_download(drv.AXIOM_URL, os.path.join(mods, "axiom.jar"))
        drv.fabric_api_jar(mods)
        width, height = (int(v) for v in args.size.lower().split("x"))
        drv.write_options(args.mc_dir)
        options_path = os.path.join(args.mc_dir, "options.txt")
        with open(options_path, encoding="utf-8") as f:
            text = f.read()
        text = text.replace("overrideWidth:1280", "overrideWidth:%d" % width)
        text = text.replace("overrideHeight:720", "overrideHeight:%d" % height)
        with open(options_path, "w", encoding="utf-8") as f:
            f.write(text)

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
                "--width", str(width), "--height", str(height)]
        console = open(os.path.join(args.out, "console.log"), "w",
                       encoding="utf-8", errors="replace")
        drv.log("launching: " + " ".join(cmd[:6]) + " ...")
        proc = subprocess.Popen(cmd, cwd=args.mc_dir, stdout=console,
                                stderr=subprocess.STDOUT)

        drv.log("waiting for the world")
        log_path = os.path.join(args.mc_dir, "logs", "latest.log")
        deadline = time.time() + args.wait
        ready = False
        while time.time() < deadline and proc.poll() is None:
            time.sleep(5)
            text = drv.tail(log_path, 400)
            if "Started serving on" in text or "Loaded" in text and "Axiom" in text:
                ready = True
                break
            if "Sound engine started" in text:
                ready = True
                break
        drv.log("ready=%s alive=%s" % (ready, proc.poll() is None))
        time.sleep(12)
        drv.log("focused: %s" % focus())
        shot(args.out, "panel-0-world")

        drv.log("opening the editor")
        key("Shift_R")
        time.sleep(8)
        shot(args.out, "panel-1-editor")

        if args.script:
            run_script(args.out, args.script)
        else:
            for n in range(1, 10):
                key(str(n))
                time.sleep(2.5)
                shot(args.out, "panel-2-slot%d" % n)

        drv.log("closing")
        key("Shift_R")
        time.sleep(2)
    except Exception:
        drv.log("driver failed:\n" + traceback.format_exc())
    finally:
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
        if console is not None:
            console.close()
        save_logs(args.mc_dir, args.out)
        drv.log("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
