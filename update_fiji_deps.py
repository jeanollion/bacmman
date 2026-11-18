#!/usr/bin/env python3
"""
Keep bacmman's Maven dependencies consistent with a Fiji installation, and install into Fiji
the jars that bacmman ships itself (BACMMAN update sites).

Policy: no version mismatch between the Maven build (compilation, tests) and Fiji at runtime.
For every dependency that Fiji ships:
  - if Fiji's version works with bacmman: the bacmman pom uses Fiji's version (align), and the jar
    is not shipped by bacmman;
  - otherwise: add it to SHADOW (or --shadow G:A). bacmman keeps its version, deploy installs it in
    place of Fiji's jar, and it must be shadowed on the BACMMAN update site (upload-plan).
"Works" is decided by building and testing after align --apply (mvn clean install).
Exception, FIJI_OVERRIDES: Fiji files deliberately replaced by the tensorflow update sites (protobuf 3.x
and protobuf-java-util instead of Fiji's 4.x, javacpp): bacmman's version is kept, deployed in place of
Fiji's file and published on every tensorflow site; check reports BACMMAN-site files that replace a
Fiji file without being declared there.

Commands (align and deploy are dry runs unless --apply is given):

  align   (default) Any dependency that Fiji ships gets the Fiji version, newer or older:
            1. direct dependencies: the <version> (or the ${property} it refers to) is edited in
               the module pom.xml;
            2. transitive dependencies: a generated <dependencyManagement> section of the root
               pom.xml (between FIJI-MANAGED markers) imports the pom-scijava BOM and adds
               corrections: Fiji's version when it is older than the BOM's (newer ones are
               compatible; --fiji-versions all: always), and bacmman's own version for
               protected libraries, their dependencies and OMERO.
          Dependencies that bacmman controls (PROTECTED_GROUPS / --protect: tensorflow,
          docker-java, objectbox, mapdb, bacmman modules) and SHADOW are never changed; their own
          transitive dependencies are still pinned when Fiji ships them.

  deploy  Copies into the Fiji folder what bacmman needs at runtime and Fiji does not provide:
          the runtime classpath of DEPLOY_MODULE (bacmman-ij1_) minus the artifacts shipped by
          Fiji's own update sites (plus SHADOW artifacts, in place of Fiji's jar). Generic jars go
          to plugins/BACMMAN/ (or to the folder where a previous version is installed, e.g. jars/
          for tensorflow); platform-specific native artifacts (PLATFORM_ARTIFACTS: objectbox,
          tensorflow) are fetched for every platform and copied to jars/<platform>/ (GPU variant
          of tensorflow natives for linux if the BACMMAN-TF2-GPU site is enabled).
          Replaced files are moved to a backup folder, never deleted. --remove-duplicates also
          moves BACMMAN-site copies of jars provided by Fiji (same artifact, or same classes under
          another name, e.g. net.jpountz lz4 vs lz4-java). --with-bacmman installs the bacmman
          module jars. Requires the project to be installed in the local Maven repository.

  check   Verifies the policy on the local Fiji: every runtime artifact has the Maven version,
          everything is deployed, and no deployed jar contains classes also present in a Fiji jar
          (same classes under another artifact name). Exit code 1 if a problem is found.

  upload-plan  Lists, per update site (BACMMAN, BACMMAN-TF2, BACMMAN-TF2-GPU), the files to add,
          update, remove or shadow so that the published sites match the local deployment
          (published state read from sites.imagej.net, or the local updater db with --offline),
          and prints the Fiji updater commands for uploads (removals: updater GUI).

Fiji jars are identified by their embedded Maven metadata
(META-INF/maven/<groupId>/<artifactId>/pom.properties); when missing, the file name is parsed
(artifactId-version.jar). db.xml.gz (Fiji updater database) tells which update site provides
each file: files from update sites matching --exclude-site (default: BACMMAN) are not considered
as provided by Fiji.

Poms are edited as text (not re-serialized) so formatting and comments are preserved.

Usage:
  python3 update_fiji_deps.py [align] --fiji DIR [--apply] [--no-downgrade] [--allow-snapshot]
                              [--no-transitive] [--source local|site] [--protect G[:A] ...] [--shadow G[:A] ...] [-v]
  python3 update_fiji_deps.py deploy --fiji DIR [--apply] [--with-bacmman] [--remove-duplicates] [--backup-dir DIR]
  python3 update_fiji_deps.py check --fiji DIR
  python3 update_fiji_deps.py upload-plan --fiji DIR [--offline]

Typical workflow: align --apply; mvn clean install; deploy --with-bacmman --remove-duplicates --apply;
check; upload-plan; upload with the Fiji updater.
"""
import argparse
import filecmp
import gzip
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path


# bacmman controls these versions, never align them with Fiji
PROTECTED_GROUPS = [
    "com.github.jeanollion",   # bacmman's own modules
    "org.tensorflow",
    "com.github.docker-java",
    "org.mapdb",
    "io.objectbox",
]
# artifacts shipped by Fiji whose Fiji version does NOT work with bacmman (G or G:A): align does not
# change them, deploy installs bacmman's version in place of Fiji's (to be shadowed on the BACMMAN site)
SHADOW = []
# Fiji files deliberately replaced by the tensorflow update sites (same updater identity, e.g.
# jars/protobuf-java: Fiji's protobuf-java-4.32.0.jar is replaced by protobuf-java-3.25.8.jar when a
# tensorflow site is enabled). align keeps bacmman's version; deploy installs it in place of Fiji's file;
# upload-plan publishes it on every TF_SITES. check / upload-plan report files of the BACMMAN sites that
# replace a Fiji file without being declared here.
FIJI_OVERRIDES = {
    ("com.google.protobuf", "protobuf-java"): "tensorflow-java 1.1.0 needs protobuf 3.x (generated code), Fiji ships 4.x",
    ("com.google.protobuf", "protobuf-java-util"): "same version as protobuf-java (Fiji's util 4.x does not work with protobuf 3.x)",
    ("org.bytedeco", "javacpp"): "tensorflow-core-native 1.1.0 is built with javacpp 1.5.11",
}
# artifacts not in the Maven dependency tree, deployed with the version of another artifact
COMPANIONS = {("com.google.protobuf", "protobuf-java"): [("com.google.protobuf", "protobuf-java-util")]}
TF_SITES = ["BACMMAN-TF2", "BACMMAN-TF2-GPU"]
# update sites where bacmman publishes (upload-plan)
PUBLISH_SITES = ["BACMMAN", "BACMMAN-TF2", "BACMMAN-TF2-GPU"]
SITE_URL = "https://sites.imagej.net/{site}/db.xml.gz"
# dependencies provided to Fiji users by another update site (with their own dependencies): not deployed
OTHER_SITE_GROUPS = {"org.openmicroscopy": "OMERO update site"}

# minimal versions (security fixes, e.g. GitHub Dependabot alerts): align never pins a lower version,
# whatever Fiji ships or bacmman resolves. When Fiji ships an older version, check accepts it (the
# Maven build is newer: backward compatibility) and Fiji's jar is still the one loaded at runtime.
SECURITY_MIN = {
    # bouncycastle: GHSA-9pwp-9qqc-pr26 (critical), GHSA-qp49-qgx5-5m26 and earlier ones, all fixed in 1.85.
    # same version for the whole family. Check https://github.com/advisories before changing a value
    ("org.bouncycastle", "bcprov-jdk18on"): "1.87",
    ("org.bouncycastle", "bcpkix-jdk18on"): "1.87",
    ("org.bouncycastle", "bcutil-jdk18on"): "1.87",
    ("com.google.protobuf", "protobuf-java"): "3.25.8",  # CVE-2024-7254 (fixed 3.25.5); 3.x: compatible with tensorflow 1.1.0 (gencode 3.21)
}

# module whose runtime classpath defines what must be present in Fiji
DEPLOY_MODULE = "bacmman-ij1_"
# bacmman modules not published on the update sites (own git repository): neither they nor what only
# they depend on are deployed
UNPUBLISHED_MODULES = ["bacmman-blissed"]
BACMMAN_GROUP = "com.github.jeanollion.bacmman"
BACMMAN_DEPLOY_DIR = "plugins/BACMMAN"

# platform-specific artifacts: {(groupId, artifactId-or-template): {variant: [fiji dirs]}}.
# variant is a classifier ("classifier:xxx") or an artifactId ("artifact:xxx"). Version = the one
# resolved for the key artifact in the dependency tree.
PLATFORM_ARTIFACTS = {
    ("io.objectbox", "objectbox-java"): {
        "artifact:objectbox-linux": ["jars/linux64"],
        "artifact:objectbox-linux-arm64": ["jars/linux-arm64"],
        "artifact:objectbox-macos": ["jars/macosx", "jars/macos-arm64"],  # universal binary
        "artifact:objectbox-windows": ["jars/win64"],
    },
    ("org.tensorflow", "tensorflow-core-native"): {
        "classifier:linux-x86_64": ["jars/linux64"],
        "classifier:linux-arm64": ["jars/linux-arm64"],
        "classifier:macosx-arm64": ["jars/macos-arm64"],
        "classifier:windows-x86_64": ["jars/win64"],
    },
}
PLATFORM_DIRS = {d for v in PLATFORM_ARTIFACTS.values() for ds in v.values() for d in ds}
GPU_SITE = "BACMMAN-TF2-GPU"
GPU_VARIANTS = {"classifier:linux-x86_64": "classifier:linux-x86_64-gpu"}

MANAGED_BEGIN = "<!-- BEGIN FIJI-MANAGED: generated by update_fiji_deps.py, do not edit -->"
MANAGED_END = "<!-- END FIJI-MANAGED -->"

JAR_NAME_RE = re.compile(r"^(?P<a>.+?)-(?P<v>\d[^-]*(?:-[A-Za-z0-9._]+)*)\.jar$")
COMMENT_RE = re.compile(r"<!--.*?-->", re.S)
DEP_RE = re.compile(r"<dependency>(.*?)</dependency>", re.S)
MANAGED_RE = re.compile(re.escape(MANAGED_BEGIN) + r".*?" + re.escape(MANAGED_END), re.S)
TREE_LINE_RE = re.compile(r"^(?P<prefix>[| ]*)(?:[+\\]- )(?P<coord>\S+)")


# ---------------------------------------------------------------- version comparison
def _tokens(v):
    out = []
    for t in re.findall(r"\d+|[A-Za-z]+", v):
        out.append((0, int(t), "") if t.isdigit() else (1, 0, t.lower()))
    return out


QUALIFIER_RANK = {"alpha": -5, "a": -5, "beta": -4, "b": -4, "milestone": -3, "m": -3,
                  "rc": -2, "cr": -2, "snapshot": -1, "ga": 0, "final": 0, "release": 0}


def compare_versions(v1, v2):
    """Approximate Maven ordering: numbers compare numerically, pre-release qualifiers
    (alpha/beta/rc/snapshot) sort before the release, other letters (1.54n, -scijava-2)
    sort after the numeric prefix. Returns -1, 0, 1."""
    if v1 == v2:
        return 0
    t1, t2 = _tokens(v1), _tokens(v2)
    for i in range(max(len(t1), len(t2))):
        a = t1[i] if i < len(t1) else None
        b = t2[i] if i < len(t2) else None
        if a == b:
            continue
        if a is None or b is None:  # one is a prefix of the other
            rest = b if a is None else a
            sign = 1 if a is None else -1  # longer one is bigger unless pre-release qualifier
            if rest[0] == 1 and QUALIFIER_RANK.get(rest[2], 1) < 0:
                return sign
            if rest[0] == 0 and rest[1] == 0 and all(x[0] == 0 and x[1] == 0 for x in (t2[i:] if a is None else t1[i:])):
                return 0
            return -sign
        if a[0] != b[0]:  # number vs letters: number is bigger than pre-release, smaller than suffix
            num, let = (a, b) if a[0] == 0 else (b, a)
            num_bigger = QUALIFIER_RANK.get(let[2], 1) < 0
            return (1 if num_bigger else -1) * (1 if a is num else -1)
        if a[0] == 0:
            return 1 if a[1] > b[1] else -1
        ra, rb = QUALIFIER_RANK.get(a[2]), QUALIFIER_RANK.get(b[2])
        if ra is not None and rb is not None and ra != rb:
            return 1 if ra > rb else -1
        return 1 if a[2] > b[2] else -1
    return 0


# ---------------------------------------------------------------- Fiji index
def read_jar_coordinates(jar):
    """Return list of (groupId, artifactId, version) found in the jar's Maven metadata."""
    coords = []
    try:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.startswith("META-INF/maven/") and name.endswith("/pom.properties"):
                    props = {}
                    for line in z.read(name).decode("utf-8", "replace").splitlines():
                        if "=" in line and not line.startswith("#"):
                            k, v = line.split("=", 1)
                            props[k.strip()] = v.strip()
                    if {"groupId", "artifactId", "version"} <= props.keys():
                        coords.append((props["groupId"], props["artifactId"], props["version"]))
    except (zipfile.BadZipFile, OSError) as e:
        print(f"WARNING: cannot read {jar}: {e}", file=sys.stderr)
    return coords


def read_update_db(fiji_dir):
    """Parse <fiji>/db.xml.gz (written by the Fiji updater). Returns
    {filename: (update_site, is_current)} where is_current means the file is the current
    version on its update site (has a <version> element)."""
    db = Path(fiji_dir) / "db.xml.gz"
    if not db.is_file():
        return None
    root = ET.parse(gzip.open(db)).getroot()
    files = {}
    for p in root.iter("plugin"):
        files[p.get("filename")] = (p.get("update-site"), p.find("version") is not None)
    return files


def fiji_site_keys(fiji_dir, exclude_sites, offline=False):
    """{jar_key: filename} of the current files of the active update sites that are not excluded (Fiji's
    own sites), read from their published db.xml.gz: the local db only records the site that wins when
    a BACMMAN site replaces a Fiji file. None if unavailable."""
    db = Path(fiji_dir) / "db.xml.gz"
    if offline or not db.is_file():
        return None
    import urllib.request
    out = {}
    for us in ET.parse(gzip.open(db)).getroot().iter("update-site"):
        name, url = us.get("name"), us.get("url", "")
        if site_excluded(name, exclude_sites) or not url:
            continue
        try:
            with urllib.request.urlopen(url.rstrip("/") + "/db.xml.gz", timeout=30) as r:
                root = ET.fromstring(gzip.decompress(r.read()))
        except Exception as e:
            print(f"WARNING: cannot read the database of update site {name} ({e}): Fiji files replaced by BACMMAN sites not checked")
            return None
        for pl in root.iter("plugin"):
            if pl.find("version") is not None and pl.get("filename", "").endswith(".jar"):
                out.setdefault(jar_key(pl.get("filename")), pl.get("filename"))
    return out


def fiji_overrides(fiji_dir, db, exclude_sites, offline=False):
    """Files of the BACMMAN sites that replace a file of Fiji's own sites: (declared, undeclared),
    lists of (bacmman_file, fiji_file); declared = artifact in FIJI_OVERRIDES."""
    keys = fiji_site_keys(fiji_dir, exclude_sites, offline)
    if keys is None or db is None:
        return None, None
    declared_names = {a for (_, a) in FIJI_OVERRIDES}
    declared, undeclared = [], []
    for rel, (site, current) in sorted(db.items()):
        if site_excluded(site, exclude_sites) and current and rel.endswith(".jar") and jar_key(rel) in keys:
            (declared if jar_key(rel)[1] in declared_names else undeclared).append((rel, keys[jar_key(rel)]))
    return declared, undeclared


def site_excluded(site, exclude_sites):
    return site is not None and any(re.search(rx, site) for rx in exclude_sites)


def index_fiji(fiji_dir, source, exclude_sites, db):
    """Map (g, a) -> {version: [files]} and a -> {version: [files]} (name-only matches).
    source="local": jars present on disk in jars/ and plugins/ (minus files owned by excluded
    update sites and files unknown to the updater). source="site": current files of non-excluded update sites in db.xml.gz
    (i.e. what Fiji has after running the updater); groupIds are taken from local jars of the
    same artifactId when available."""
    fiji = Path(fiji_dir)
    by_ga, by_name = {}, {}
    local = sorted(p for sub in ("jars", "plugins") for p in (fiji / sub).rglob("*.jar"))
    a_to_g = {}  # artifactId -> groupId learnt from local metadata (used for source="site")
    n_excluded = 0
    for jar in local:
        rel = jar.relative_to(fiji).as_posix()
        # excluded: files of excluded update sites, and files unknown to the updater (e.g. deployed by this script,
        # not yet uploaded): they are not provided by Fiji
        if db is not None and (rel not in db or site_excluded(db[rel][0], exclude_sites)):
            n_excluded += 1
            continue
        m = JAR_NAME_RE.match(jar.name)
        file_a, file_v = (m.group("a"), m.group("v")) if m else (jar.stem, None)
        coords = read_jar_coordinates(jar)
        # a jar may embed metadata of shaded deps: keep the entry matching the file name if any
        own = [c for c in coords if c[1] == file_a] or (coords if len(coords) == 1 else [])
        for g, a, _ in own:
            a_to_g.setdefault(a, g)
        if source != "local":
            continue
        if own:
            for g, a, v in own:
                by_ga.setdefault((g, a), {}).setdefault(v, []).append(rel)
        elif file_v:
            by_name.setdefault(file_a, {}).setdefault(file_v, []).append(rel)
    if source == "site":
        if db is None:
            sys.exit("--source site requires db.xml.gz in the Fiji folder")
        for rel, (site, current) in db.items():
            if not current or not rel.endswith(".jar") or site_excluded(site, exclude_sites):
                continue
            m = JAR_NAME_RE.match(Path(rel).name)
            if not m:
                continue
            a, v = m.group("a"), m.group("v")
            if a in a_to_g:
                by_ga.setdefault((a_to_g[a], a), {}).setdefault(v, []).append(rel)
            else:
                by_name.setdefault(a, {}).setdefault(v, []).append(rel)
    return by_ga, by_name, len(local), n_excluded


def stale_report(fiji_dir, db, exclude_sites, artifacts):
    """For the given artifactIds, list differences between local jars and the current
    update-site files (local Fiji not up to date)."""
    fiji = Path(fiji_dir)
    cur, loc = {}, {}
    for rel, (site, current) in db.items():
        m = JAR_NAME_RE.match(Path(rel).name)
        if m and current and rel.endswith(".jar") and not site_excluded(site, exclude_sites):
            cur.setdefault(m.group("a"), set()).add(m.group("v"))
    for jar in (p for sub in ("jars", "plugins") for p in (fiji / sub).rglob("*.jar")):
        rel = jar.relative_to(fiji).as_posix()
        m = JAR_NAME_RE.match(jar.name)
        if m and not site_excluded(db.get(rel, (None,))[0], exclude_sites):
            loc.setdefault(m.group("a"), set()).add(m.group("v"))
    out = []
    for a in sorted(artifacts):
        if a in cur or a in loc:
            c, l = cur.get(a, set()), loc.get(a, set())
            if c != l:
                out.append((a, ", ".join(sorted(l)) or "-", ", ".join(sorted(c)) or "-"))
    return out


def best_version(versions):
    best = None
    for v in versions:
        if best is None or compare_versions(v, best) > 0:
            best = v
    return best


# ---------------------------------------------------------------- pom handling
def tag(block, name):
    m = re.search(rf"<{name}>\s*(.*?)\s*</{name}>", block, re.S)
    return m.group(1) if m else None


def is_protected(g, a, protected):
    for p in protected:
        pg, _, pa = p.partition(":")
        if (g == pg or g.startswith(pg + ".")) and (not pa or pa == a):
            return True
    return False


def masked(text):
    """Replace comments (and the generated dependencyManagement block) by spaces so offsets stay
    valid but commented / generated code is ignored."""
    text = MANAGED_RE.sub(lambda m: " " * len(m.group(0)), text)
    return COMMENT_RE.sub(lambda m: " " * len(m.group(0)), text)


def process_pom(pom, by_ga, by_name, protected, allow_downgrade, allow_snapshot, report):
    text = pom.read_text(encoding="utf-8")
    scan = masked(text)
    props_m = re.search(r"<properties>(.*?)</properties>", scan, re.S)
    props = {}
    if props_m:
        for pm in re.finditer(r"<([\w.\-]+)>([^<]*)</\1>", props_m.group(1)):
            props[pm.group(1)] = (pm.group(2).strip(), props_m.start(1) + pm.start(2), props_m.start(1) + pm.end(2))
    edits = []  # (start, end, new_text)
    prop_updates = {}
    module = pom.parent.name
    for dm in DEP_RE.finditer(scan):
        block = dm.group(1)
        g, a, v = tag(block, "groupId"), tag(block, "artifactId"), tag(block, "version")
        if not g or not a or not v:
            continue
        prop = None
        pm = re.fullmatch(r"\$\{([\w.\-]+)\}", v)
        if pm:
            prop = pm.group(1)
            if prop not in props:
                continue  # inherited / bacmman.version etc.
            v = props[prop][0]
        if "${" in g + a:
            continue
        row = dict(module=module, ga=f"{g}:{a}", current=v, fiji="", status="", src="")
        if is_protected(g, a, protected):
            row["status"] = "protected"
            report.append(row)
            continue
        if (g, a) in by_ga:
            versions, how = by_ga[(g, a)], "maven"
        elif a in by_name:
            versions, how = by_name[a], "name-match"
        else:
            row["status"] = "not-in-fiji"
            report.append(row)
            continue
        candidates = [x for x in versions if allow_snapshot or "SNAPSHOT" not in x]
        fv = best_version(candidates) if candidates else best_version(versions)
        row["fiji"], row["src"] = fv, how
        if len(versions) > 1:
            row["src"] += f" (several in Fiji: {', '.join(sorted(versions))})"
        cmp = compare_versions(fv, v)
        if "SNAPSHOT" in fv and not allow_snapshot and cmp != 0:
            row["status"] = "SNAPSHOT-skipped"
        elif cmp == 0:
            row["status"] = "up-to-date"
        elif cmp < 0 and not allow_downgrade:
            row["status"] = "DOWNGRADE-skipped"
        else:
            major_jump = (_tokens(v)[:1] != _tokens(fv)[:1])
            row["status"] = ("downgrade" if cmp < 0 else "update") + (" MAJOR" if major_jump else "")
            if prop:
                prev = prop_updates.get(prop)
                if prev and prev != fv:
                    row["status"] = f"CONFLICT-skipped (property {prop} also wanted {prev})"
                else:
                    prop_updates[prop] = fv
                    row["src"] += f" via ${{{prop}}}"
            else:
                vm = re.search(r"(<version>\s*)(.*?)(\s*</version>)", block, re.S)
                start = dm.start(1) + vm.start(2)
                edits.append((start, start + len(vm.group(2)), fv))
        report.append(row)
    for prop, fv in prop_updates.items():
        _, s, e = props[prop]
        edits.append((s, e, fv))
    if not edits:
        return text, text
    new = text
    for s, e, rep in sorted(edits, reverse=True):
        new = new[:s] + rep + new[e:]
    return text, new


def find_poms(root):
    root = Path(root)
    poms = [root / "pom.xml"]
    poms += sorted(p for p in root.glob("*/pom.xml"))
    return [p for p in poms if p.is_file()]


def reactor_modules(root):
    text = masked((Path(root) / "pom.xml").read_text(encoding="utf-8"))
    return re.findall(r"<module>\s*([^<\s]+)\s*</module>", text)


def print_table(rows, cols):
    if not rows:
        return
    head = {c: c for c in cols}
    w = {c: max(len(str(r.get(c, ""))) for r in rows + [head]) for c in cols}
    print("  ".join(f"{c:<{w[c]}}" for c in cols))
    for r in rows:
        print("  ".join(f"{str(r.get(c, '')):<{w[c]}}" for c in cols))


# ---------------------------------------------------------------- maven helpers
def run_mvn(root, args, quiet=True):
    cmd = ["mvn", "-B"] + (["-q"] if quiet else []) + args
    res = subprocess.run(cmd, cwd=root, capture_output=True, text=True)
    if res.returncode != 0:
        sys.stderr.write(res.stdout[-4000:] + res.stderr[-4000:])
        sys.exit(f"Maven command failed: {' '.join(cmd)}")
    return res.stdout


def parse_coord(coord):
    """g:a:type[:classifier]:v[:scope] -> dict"""
    p = coord.split(":")
    if len(p) == 6:
        g, a, t, c, v, s = p
    elif len(p) == 5:
        g, a, t, v, s = p
        c = ""
    elif len(p) == 4:  # tree root: g:a:type:v
        g, a, t, v = p
        c, s = "", ""
    else:
        return None
    return dict(g=g, a=a, type=t, c=c, v=v, scope=s)


def dependency_trees(root, modules=None):
    """Run mvn dependency:tree on the reactor (or the given modules); returns
    {module_artifactId: [node]} with node = dict(coord..., depth, path=[ancestor nodes])."""
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "tree.txt"
        args = ["dependency:tree", f"-DoutputFile={out}", "-DappendOutput=true"]
        if modules:
            args += ["-pl", ",".join(modules)]
        run_mvn(root, args)
        lines = out.read_text().splitlines() if out.exists() else []
    trees, current, stack = {}, None, []
    for line in lines:
        m = TREE_LINE_RE.match(line)
        if not m:
            c = parse_coord(line.strip())
            if c:
                current = c["a"]
                trees[current] = []
                stack = []
            continue
        node = parse_coord(m.group("coord"))
        if node is None or current is None:
            continue
        depth = len(m.group("prefix")) // 3 + 1
        stack = stack[:depth - 1]
        node["depth"] = depth
        node["path"] = list(stack)
        stack.append(node)
        trees[current].append(node)
    return trees


def in_groups(g, groups):
    return any(g == x or g.startswith(x + ".") for x in groups)


# ---------------------------------------------------------------- transitive alignment
BOM = ("org.scijava", "pom-scijava")
BOM_METADATA_URL = "https://repo1.maven.org/maven2/org/scijava/pom-scijava/maven-metadata.xml"


def managed_block_entries(root_pom_text):
    """Returns (bom_version, {(g, a, classifier): version}) from the generated block."""
    m = MANAGED_RE.search(root_pom_text)
    if not m:
        return None, {}
    bom, out = None, {}
    for dm in DEP_RE.finditer(m.group(0)):
        b = dm.group(1)
        if tag(b, "scope") == "import":
            bom = tag(b, "version")
            continue
        out[(tag(b, "groupId"), tag(b, "artifactId"), tag(b, "classifier") or "")] = tag(b, "version")
    return bom, out


def write_managed_block(root_pom, bom_version, entries):
    """Rewrites the generated dependencyManagement section: import of the pom-scijava BOM (if
    bom_version) followed by the corrections {(g, a, classifier): version} (explicit entries take
    precedence over imported ones)."""
    text = root_pom.read_text(encoding="utf-8")
    ind = "            "
    deps = ""
    if bom_version:
        deps += (f"\n{ind}<dependency>\n{ind}    <groupId>{BOM[0]}</groupId>\n{ind}    <artifactId>{BOM[1]}</artifactId>\n"
                 f"{ind}    <version>{bom_version}</version>\n{ind}    <type>pom</type>\n{ind}    <scope>import</scope>\n{ind}</dependency>")
        if entries:
            deps += f"\n{ind}<!-- corrections: versions shipped by Fiji, or chosen by bacmman (protected) -->"
    deps += "".join(
        f"\n{ind}<dependency>\n{ind}    <groupId>{g}</groupId>\n{ind}    <artifactId>{a}</artifactId>\n"
        f"{ind}    <version>{v}</version>\n" + (f"{ind}    <classifier>{c}</classifier>\n" if c else "") + f"{ind}</dependency>"
        for (g, a, c), v in sorted(entries.items()))
    block = f"{MANAGED_BEGIN}{deps}\n            {MANAGED_END}"
    if MANAGED_RE.search(text):
        new = MANAGED_RE.sub(lambda m: block, text, count=1)
    elif re.search(r"<dependencyManagement>\s*<dependencies>", masked(text)):
        new = re.sub(r"(<dependencyManagement>\s*<dependencies>)", lambda m: m.group(1) + "\n            " + block,
                     text, count=1)
    else:
        section = f"    <dependencyManagement>\n        <dependencies>\n            {block}\n        </dependencies>\n    </dependencyManagement>\n"
        anchor = re.search(r"\n\s*<dependencies>", masked(text))
        if not anchor:
            anchor = re.search(r"\n\s*<build>", masked(text))
        pos = anchor.start() + 1 if anchor else masked(text).rindex("</project>")
        new = text[:pos] + section + text[pos:]
    if new != text:
        root_pom.write_text(new, encoding="utf-8")


def latest_bom_version():
    import urllib.request
    try:
        with urllib.request.urlopen(BOM_METADATA_URL, timeout=20) as r:
            m = re.search(r"<release>([^<]+)</release>", r.read().decode())
            return m.group(1) if m else None
    except Exception as e:
        print(f"WARNING: cannot read {BOM_METADATA_URL}: {e}")
        return None


def tree_versions(trees):
    """{(g, a, c): version} over all modules (the runtime module DEPLOY_MODULE has priority)."""
    out = {}
    for module in [DEPLOY_MODULE] + [m for m in trees if m != DEPLOY_MODULE]:
        for n in trees.get(module, []):
            out.setdefault((n["g"], n["a"], n["c"]), n["v"])
    return out


def align_transitive(args, by_ga, by_name, protected):
    """dependencyManagement of the root pom = import of the pom-scijava BOM + corrections, so that
    every dependency resolves to: the version shipped by Fiji if Fiji ships it, otherwise the
    version bacmman resolves without the BOM (protected libraries and their dependencies are never
    changed by the BOM). Corrections are computed iteratively (versions change the tree)."""
    root = Path(args.root)
    root_pom = root / "pom.xml"
    original = root_pom.read_text(encoding="utf-8")
    old_bom, old_corr = managed_block_entries(original)
    bom = args.bom_version or old_bom or latest_bom_version()
    if not bom:
        sys.exit("No pom-scijava version: use --bom-version")

    def fiji_version(g, a, v):
        """(version, how) of the artifact shipped by Fiji, or (None, reason-if-skipped)."""
        if (g, a) in by_ga:
            versions, how = by_ga[(g, a)], "maven"
        elif a in by_name:
            versions, how = by_name[a], "name-match"
        else:
            return None, None
        cand = [x for x in versions if args.allow_snapshot or "SNAPSHOT" not in x]
        if not cand:
            return None, "SNAPSHOT-skipped"
        fv = best_version(cand)
        if how == "name-match" and v and fv.split(".")[0] != v.split(".")[0] and not exists_in_maven(g, a, fv):
            return None, "name-match-not-in-maven"  # Fiji's jar is another artifact with the same name (e.g. asm:asm vs org.ow2.asm:asm)
        return fv, how

    maven_cache = {}

    def exists_in_maven(g, a, v):
        if (g, a, v) not in maven_cache:
            maven_cache[(g, a, v)] = ensure_artifact(root, g, a, v, "") is not None
        return maven_cache[(g, a, v)]

    report = {}
    corrections = {}
    try:
        write_managed_block(root_pom, bom, {})
        bom_trees = dependency_trees(root)
        bom_only = tree_versions(bom_trees)
        # versions resolved by bacmman alone (without the BOM). Direct dependencies declared without
        # <version> (managed by the BOM, e.g. TrackMate) keep the version given by the BOM
        direct = {(n["g"], n["a"], n["c"]): n["v"] for nodes in bom_trees.values() for n in nodes
                  if n["depth"] == 1 and n["g"] != BACMMAN_GROUP}
        write_managed_block(root_pom, None, direct)
        natural = tree_versions(dependency_trees(root))
        for it in range(6):
            write_managed_block(root_pom, bom, corrections)
            trees = dependency_trees(root)
            changed = False
            for module, nodes in trees.items():
                for n in nodes:
                    g, a, c, v = n["g"], n["a"], n["c"], n["v"]
                    if g == BACMMAN_GROUP or n["depth"] == 1:
                        continue  # direct dependencies: versions declared in the module poms take precedence
                    key = (g, a, c)
                    nat = natural.get(key)
                    if is_protected(g, a, protected):
                        target, why = nat, "bacmman (protected)"
                    else:
                        fv, how = fiji_version(g, a, nat or v)
                        if fv and args.no_downgrade and nat and compare_versions(fv, nat) < 0:
                            fv, how = None, "DOWNGRADE-skipped"
                        if fv and args.fiji_versions == "older" and key not in corrections and compare_versions(fv, v) > 0:
                            continue  # Fiji ships a newer version: compatible (backward compatibility), keep the BOM's
                        if fv:
                            target, why = fv, "Fiji (older than BOM)" + (" (name-match)" if how == "name-match" else "")
                        else:
                            target, why = nat, "bacmman" + (f" ({how})" if how else "")
                    sec = SECURITY_MIN.get((g, a))
                    if sec and (target is None or compare_versions(target, sec) < 0):
                        target, why = sec, "security minimum " + sec  # lowest fixed version (BOM's may be another major)
                    if target is None or v == target:
                        continue
                    if corrections.get(key) != target:
                        corrections[key] = target
                        changed = True
                    report[key] = dict(ga=":".join(x for x in key if x), bacmman=nat or "", bom=bom_only.get(key, ""),
                                       target=target, reason=why)
            if not changed:
                break
    finally:
        if not args.apply:
            root_pom.write_text(original, encoding="utf-8")
    if args.apply:
        write_managed_block(root_pom, bom, corrections)
    for key in corrections:
        report.setdefault(key, dict(ga=":".join(x for x in key if x), bacmman=natural.get(key, ""), bom=bom_only.get(key, ""),
                                    target=corrections[key], reason=""))
    print(f"\nTransitive dependencies: pom-scijava BOM {bom} + {len(corrections)} corrections in root pom dependencyManagement")
    rows = sorted(report.values(), key=lambda r: (r["reason"], r["ga"]))
    print_table(rows, ["ga", "bacmman", "bom", "target", "reason"])
    added = sum(1 for k, v in corrections.items() if old_corr.get(k) != v)
    removed = sum(1 for k in old_corr if k not in corrections)
    print(f"\nBOM {old_bom or '-'} -> {bom}; corrections: {len(corrections)} ({added} new/changed, {removed} removed)"
          + ("" if args.apply else " - dry run, re-run with --apply"))


# ---------------------------------------------------------------- deploy
def local_repo():
    return Path.home() / ".m2" / "repository"


def artifact_path(g, a, v, c=""):
    return local_repo() / g.replace(".", "/") / a / v / (f"{a}-{v}" + (f"-{c}" if c else "") + ".jar")


def ensure_artifact(root, g, a, v, c=""):
    p = artifact_path(g, a, v, c)
    if not p.is_file():
        coord = f"{g}:{a}:{v}:jar" + (f":{c}" if c else "")
        res = subprocess.run(["mvn", "-B", "-q", "dependency:get", f"-Dartifact={coord}", "-Dtransitive=false"],
                             cwd=root, capture_output=True, text=True)
        if res.returncode != 0 or not p.is_file():
            return None
    return p


def same_artifact_files(fiji, a, c, platform_classifiers):
    """All jars in Fiji named <a>-<version>[-<c>].jar (any version)."""
    out = []
    suffix = f"-{c}.jar" if c else ".jar"
    for f in list((fiji / "jars").rglob("*.jar")) + list((fiji / "plugins").rglob("*.jar")):
        n = f.name
        if not n.startswith(a + "-") or not n.endswith(suffix):
            continue
        ver = n[len(a) + 1: len(n) - len(suffix)]
        if not ver or not ver[0].isdigit() and not ver[0] == "v":
            continue
        if not c and any(ver.endswith("-" + pc) for pc in platform_classifiers):
            continue
        out.append(f)
    return out


def jar_key(rel):
    """Identity of a file for the Fiji updater: folder + artifact name (version stripped)."""
    rel = Path(rel)
    m = JAR_NAME_RE.match(rel.name)
    return (rel.parent.as_posix(), m.group("a") if m else rel.stem)


def plan_deploy(args):
    """Compute the deployment: what bacmman needs at runtime in Fiji and is not provided by Fiji
    (plus SHADOW artifacts, whose Fiji version is replaced). Returns a dict (see keys below)."""
    root, fiji = Path(args.root), Path(args.fiji)
    db = read_update_db(fiji) or {}
    gpu = any(site == GPU_SITE for site, _ in db.values())
    fiji_ga, fiji_names, _, _ = index_fiji(fiji, "local", args.exclude_site, db)
    fiji_artifacts = {a for (_, a) in fiji_ga} | set(fiji_names)
    shadow = SHADOW + args.shadow

    trees = dependency_trees(root, [DEPLOY_MODULE])
    nodes = trees.get(DEPLOY_MODULE)
    if nodes is None:
        sys.exit(f"Could not compute the dependency tree of {DEPLOY_MODULE}")
    platform_classifiers = {v.split(":", 1)[1] for d in PLATFORM_ARTIFACTS.values() for v in d if v.startswith("classifier:")}
    platform_classifiers |= {GPU_VARIANTS[k].split(":", 1)[1] for k in GPU_VARIANTS}
    platform_artifact_ids = {v.split(":", 1)[1] for d in PLATFORM_ARTIFACTS.values() for v in d if v.startswith("artifact:")}

    def fiji_owned(f):
        site = db.get(f.relative_to(fiji).as_posix(), (None,))[0]
        return site is not None and not site_excluded(site, args.exclude_site)

    _needed = []

    def needed_elsewhere():
        """artifacts used by the published modules (Maven shows a shared dependency only once, possibly
        under an unpublished module)"""
        if not _needed:
            others = dependency_trees(root, [m for m in reactor_modules(root) if m not in UNPUBLISHED_MODULES + [DEPLOY_MODULE]])
            _needed.append({(x["g"], x["a"], x["c"]) for ns in others.values() for x in ns})
        return _needed[0]

    plan, skipped, provided = [], [], []  # provided: runtime artifacts taken from Fiji
    seen = set()
    for n in nodes:
        g, a, v, c = n["g"], n["a"], n["v"], n["c"]
        if n["scope"] not in ("compile", "runtime"):
            continue
        if c in platform_classifiers or a in platform_artifact_ids:
            continue  # handled with PLATFORM_ARTIFACTS
        key = (g, a, c)
        if key in seen:
            continue
        seen.add(key)
        if a in UNPUBLISHED_MODULES or any(p["a"] in UNPUBLISHED_MODULES for p in n["path"]):
            if a in UNPUBLISHED_MODULES or key not in needed_elsewhere():
                skipped.append(dict(ga=f"{g}:{a}", version=v, reason="unpublished module (UNPUBLISHED_MODULES)"
                                    if a in UNPUBLISHED_MODULES else "only used by an unpublished module"))
                continue
        if g == BACMMAN_GROUP:
            if args.with_bacmman:
                plan.append(dict(g=g, a=a, v=v, c=c, dest=BACMMAN_DEPLOY_DIR, reason="bacmman module"))
            continue
        chain = [p["g"] for p in n["path"]] + [g]
        other = next((OTHER_SITE_GROUPS[x] for x in OTHER_SITE_GROUPS if any(in_groups(cg, [x]) for cg in chain)), None)
        if other:
            skipped.append(dict(ga=f"{g}:{a}", version=v, reason=f"provided by {other}"))
            continue
        if (g, a) in FIJI_OVERRIDES:
            plan.append(dict(g=g, a=a, v=v, c=c, dest="jars", reason="replaces Fiji's file (FIJI_OVERRIDES)", override=True))
            for cg, ca in COMPANIONS.get((g, a), []):
                plan.append(dict(g=cg, a=ca, v=v, c="", dest="jars", reason=f"companion of {a} (same version)", override=True))
            continue
        if a in fiji_artifacts:
            if is_protected(g, a, shadow):
                plan.append(dict(g=g, a=a, v=v, c=c, dest=None, reason="SHADOW (replaces Fiji's version)", shadow=True))
            else:
                provided.append(dict(g=g, a=a, v=v))
            continue
        plan.append(dict(g=g, a=a, v=v, c=c, dest=None, reason="not provided by Fiji"))
    if args.with_bacmman:
        plan.append(dict(g=BACMMAN_GROUP, a=DEPLOY_MODULE, v=next((p["v"] for p in nodes if p["g"] == BACMMAN_GROUP), None) or "", c="", dest=BACMMAN_DEPLOY_DIR, reason="bacmman module"))
        root_version = re.search(r"<version>([^<]+)</version>", masked((root / "pom.xml").read_text()))
        plan[-1]["v"] = root_version.group(1) if root_version else plan[-1]["v"]
    # platform-specific artifacts
    for (kg, ka), variants in PLATFORM_ARTIFACTS.items():
        ver = next((n["v"] for n in nodes if n["g"] == kg and n["a"] == ka), None)
        if ver is None:
            continue
        for variant, dirs in variants.items():
            if gpu and variant in GPU_VARIANTS:
                variant = GPU_VARIANTS[variant]
            kind, val = variant.split(":", 1)
            a, c = (val, "") if kind == "artifact" else (ka, val)
            for d in dirs:
                plan.append(dict(g=kg, a=a, v=ver, c=c, dest=d, reason="platform native" + (" (GPU)" if c.endswith("gpu") else "")))

    # resolve sources and destinations
    actions, moves, problems = [], [], []
    for p in plan:
        src = ensure_artifact(root, p["g"], p["a"], p["v"], p["c"])
        if src is None:
            problems.append(f"{p['g']}:{p['a']}:{p['v']}" + (f":{p['c']}" if p["c"] else "") + " not found in Maven repositories")
            continue
        same = same_artifact_files(fiji, p["a"], p["c"], platform_classifiers)
        if p.get("override"):  # replaces any installed version, Fiji's included (same updater identity)
            olds = [f for f in same if f.parent.relative_to(fiji).as_posix() == p["dest"]]
        elif p.get("shadow"):  # replace Fiji's file, in the same folder (same updater identity)
            olds = same
            fiji_files = [f for f in same if fiji_owned(f)]
            if p["dest"] is None and fiji_files:
                p["dest"] = fiji_files[0].parent.relative_to(fiji).as_posix()
        else:  # other installed versions, except files of Fiji's own update sites
            olds = [f for f in same if not fiji_owned(f)]
        native = p["dest"] is not None and p["dest"] in PLATFORM_DIRS
        if p["dest"] is None:  # same folder as an installed version, else BACMMAN folder
            cand = [f for f in olds if f.parent.relative_to(fiji).as_posix() not in PLATFORM_DIRS]
            p["dest"] = cand[0].parent.relative_to(fiji).as_posix() if cand else BACMMAN_DEPLOY_DIR
        dest = fiji / p["dest"] / src.name
        if native:  # natives: only replace versions in the same platform folder
            olds = [f for f in olds if f.parent == dest.parent and f != dest]
        else:
            olds = [f for f in olds if f.parent.relative_to(fiji).as_posix() not in PLATFORM_DIRS and f != dest]
        if dest.is_file():
            status = "up-to-date" if filecmp.cmp(dest, src, shallow=False) else "changed"  # same file name, different content (e.g. rebuilt)
            if status == "changed":
                olds = olds + [dest]  # the replaced file is moved to backup, as other versions
        else:
            status = "update" if olds else "new"
        actions.append(dict(file=src.name, dest=p["dest"], status=status, reason=p["reason"], src=src, g=p["g"],
                            shadow=bool(p.get("shadow")), override=bool(p.get("override")), replaces=", ".join(f.name for f in olds)))
        moves += olds

    # files of BACMMAN sites that are not part of the deployment
    deployed = {(Path(a["dest"]) / a["file"]).as_posix() for a in actions}
    moved = {f.relative_to(fiji).as_posix() for f in moves}
    orphans = sorted(rel for rel, (site, _) in db.items() if site_excluded(site, args.exclude_site) and rel.endswith(".jar")
                     and (fiji / rel).is_file() and rel not in deployed and rel not in moved)
    duplicates = [o for o in orphans if jar_key(o)[1] in fiji_artifacts]
    candidates = [o for o in orphans if o not in duplicates and not o.split("/")[-1].startswith("bacmman-")
                  and Path(o).parent.as_posix() not in PLATFORM_DIRS]
    if candidates:  # same classes under another artifact name (e.g. net.jpountz.lz4:lz4 vs org.lz4:lz4-java)
        fiji_classes = set()
        for sub in ("jars", "plugins"):
            for f in (fiji / sub).rglob("*.jar"):
                if f.parent.relative_to(fiji).as_posix() not in PLATFORM_DIRS and fiji_owned(f):
                    fiji_classes |= jar_classes(f)
        for o in candidates:
            classes = jar_classes(fiji / o)
            if classes and len(classes & fiji_classes) >= 0.5 * len(classes):
                duplicates.append(o)
    others = [o for o in orphans if o not in duplicates]
    return dict(root=root, fiji=fiji, db=db, gpu=gpu, nodes=nodes, actions=actions, moves=moves, problems=problems,
                skipped=skipped, provided=provided, duplicates=duplicates, others=others, fiji_owned=fiji_owned,
                fiji_ga=fiji_ga, fiji_names=fiji_names)


def deploy(args):
    P = plan_deploy(args)
    fiji, actions, moves = P["fiji"], P["actions"], list(P["moves"])
    print(f"Deploy {DEPLOY_MODULE} runtime classpath into {fiji}" + (" (GPU tensorflow natives: BACMMAN-TF2-GPU site enabled)" if P["gpu"] else ""))
    rows = [dict(a) for a in actions if args.verbose or a["status"] != "up-to-date"]
    print_table(sorted(rows, key=lambda r: (r["dest"], r["file"])), ["file", "dest", "status", "replaces", "reason"])
    if P["skipped"]:
        print("\nNot deployed:")
        print_table(sorted({s["ga"]: s for s in P["skipped"]}.values(), key=lambda r: r["ga"]), ["ga", "version", "reason"])
    for pb in P["problems"]:
        print("WARNING: " + pb)
    if P["duplicates"]:
        print("\nFiles of the BACMMAN update sites that duplicate a jar provided by Fiji (another version, or the same classes under another name)"
              + (": moved to backup" if args.remove_duplicates else ": not touched, use --remove-duplicates to move them to backup"))
        for o in P["duplicates"]:
            print("  " + o)
        if args.remove_duplicates:
            moves += [fiji / o for o in P["duplicates"]]
    if P["others"]:
        print("\nOther files of the BACMMAN update sites not part of this deployment (not touched):")
        for o in P["others"]:
            print("  " + o)
    todo = [a for a in actions if a["status"] != "up-to-date"]
    if not args.apply:
        print(f"\n{len(todo)} file(s) to copy, {len(set(moves))} file(s) to move to backup. Dry run: re-run with --apply")
        return
    backup = Path(args.backup_dir) if args.backup_dir else fiji.parent / f"{fiji.name}.bacmman-backup" / time.strftime("%Y%m%d-%H%M%S")
    for f in sorted(set(moves)):
        target = backup / f.relative_to(fiji)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(f), str(target))
    for a in todo:
        (fiji / a["dest"]).mkdir(parents=True, exist_ok=True)
        target = fiji / a["dest"] / a["file"]
        if target.exists() or target.is_symlink():
            target.unlink()  # never write into an existing file (it may be a hard link)
        shutil.copy2(a["src"], target)
    print(f"\nCopied {len(todo)} file(s)" + (f", moved {len(set(moves))} replaced file(s) to {backup}" if moves else ""))
    print("Next: python3 update_fiji_deps.py check, then upload-plan")


# ---------------------------------------------------------------- check
def jar_classes(path):
    try:
        with zipfile.ZipFile(path) as z:
            return {n for n in z.namelist() if n.endswith(".class") and not n.startswith("META-INF/")
                    and not n.endswith(("module-info.class", "package-info.class"))}
    except (zipfile.BadZipFile, OSError):
        return set()


def check(args):
    """Verify that the runtime classpath of bacmman in Fiji matches the Maven build: every artifact
    has the resolved version (Fiji's jar, or the deployed one), and deployed jars do not contain
    classes also present in Fiji's own jars (same classes under another artifact name)."""
    P = plan_deploy(args)
    fiji, issues = P["fiji"], []
    newer, security = [], []
    # 1. artifacts provided by Fiji must have the resolved version (or an older one: --fiji-versions older)
    for d in sorted(P["provided"], key=lambda d: (d["g"], d["a"])):
        versions = set(P["fiji_ga"].get((d["g"], d["a"]), {})) | set(P["fiji_names"].get(d["a"], {}))
        if versions and d["v"] not in versions:
            if args.fiji_versions == "older" and all(compare_versions(fv, d["v"]) > 0 for fv in versions):
                newer.append(f"{d['a']} {d['v']}->{best_version(versions)}")
                continue  # Fiji newer than the Maven build: compatible (backward compatibility)
            sec = SECURITY_MIN.get((d["g"], d["a"]))
            if sec and compare_versions(d["v"], sec) >= 0 and all(compare_versions(fv, d["v"]) < 0 for fv in versions):
                security.append(f"{d['a']} {d['v']} (Fiji: {best_version(versions)})")
                continue  # raised to the security minimum: Fiji's older jar is loaded at runtime
            issues.append(dict(artifact=f"{d['g']}:{d['a']}", maven=d["v"], fiji=", ".join(sorted(versions)),
                               problem="version mismatch: run align --apply (or add to SHADOW if Fiji's version does not work)"))
    # 2. deployed artifacts must be installed, in the resolved version only
    for a in P["actions"]:
        if a["status"] != "up-to-date":
            issues.append(dict(artifact=a["file"], maven="", fiji=a["replaces"], problem=f"not deployed ({a['status']}): run deploy --apply"))
    for o in P["duplicates"]:
        issues.append(dict(artifact=o, maven="", fiji="", problem="BACMMAN-site copy of a jar provided by Fiji: deploy --remove-duplicates --apply"))
    # 3. same classes in a deployed jar and in a Fiji jar (artifacts renamed / relocated, e.g. lz4 vs lz4-java)
    deployed = [fiji / a["dest"] / a["file"] for a in P["actions"] if a["dest"] not in PLATFORM_DIRS and not a["shadow"]]
    deployed = [f for f in deployed if f.is_file() and not f.name.startswith("bacmman-")]
    fiji_jars = [f for sub in ("jars", "plugins") for f in (fiji / sub).rglob("*.jar")
                 if f.parent.relative_to(fiji).as_posix() not in PLATFORM_DIRS and P["fiji_owned"](f)]
    owner = {}
    for f in fiji_jars:
        for c in jar_classes(f):
            owner.setdefault(c, f.name)
    for f in deployed:
        shared = {}
        for c in jar_classes(f):
            if c in owner:
                shared[owner[c]] = shared.get(owner[c], 0) + 1
        for other, n in sorted(shared.items()):
            issues.append(dict(artifact=f.name, maven="", fiji=other, problem=f"{n} classes also in Fiji jar (duplicate classes)"))
    print(f"Runtime classpath of {DEPLOY_MODULE}: {len(P['provided'])} artifacts from Fiji, {len(P['actions'])} deployed by bacmman"
          + (f", SHADOW: {', '.join(SHADOW + args.shadow)}" if SHADOW + args.shadow else ""))
    if newer:
        print(f"{len(newer)} artifacts built against an older version than Fiji's (accepted, backward compatibility): " + ", ".join(sorted(newer)))
    if security:
        print(f"{len(security)} artifacts raised to a security minimum (SECURITY_MIN) above Fiji's version: " + ", ".join(sorted(security))
              + ". Fiji's jar is loaded at runtime: the fix only reaches users when Fiji updates it")
    declared, undeclared = fiji_overrides(fiji, P["db"], args.exclude_site)
    if declared:
        print(f"{len(declared)} Fiji files replaced by the BACMMAN sites (FIJI_OVERRIDES): "
              + ", ".join(f"{Path(r).name} (Fiji: {Path(f).name})" for r, f in declared))
    for rel, ff in undeclared or []:
        issues.append(dict(artifact=rel, maven="", fiji=ff, problem="replaces a Fiji file: declare it in FIJI_OVERRIDES (or remove it from the BACMMAN site)"))
    if issues:
        print(f"\n{len(issues)} problem(s):")
        print_table(issues, ["artifact", "maven", "fiji", "problem"])
        sys.exit(1)
    print("OK: no version mismatch between the Maven build and Fiji")


# ---------------------------------------------------------------- upload plan
def published_files(fiji, site, local_db, offline):
    """Current files of an update site: {jar_key: filename}. Read from the site's db.xml.gz
    (sites.imagej.net), or from the local updater db if offline / unreachable."""
    root = None
    if not offline:
        try:
            import urllib.request
            with urllib.request.urlopen(SITE_URL.format(site=site), timeout=20) as r:
                root = ET.fromstring(gzip.decompress(r.read()))
        except Exception as e:
            print(f"WARNING: cannot read {SITE_URL.format(site=site)} ({e}): using the local updater database")
    out = {}
    if root is not None:
        for pl in root.iter("plugin"):
            if pl.find("version") is not None and pl.get("filename", "").endswith(".jar"):
                out[jar_key(pl.get("filename"))] = pl.get("filename")
    else:
        for rel, (s, current) in local_db.items():
            if s == site and current and rel.endswith(".jar"):
                out[jar_key(rel)] = rel
    return out


def upload_plan(args):
    """What to upload / remove / shadow on the BACMMAN update sites so that they match the local
    deployment (run deploy --apply first)."""
    P = plan_deploy(args)
    fiji, db = P["fiji"], P["db"]
    pending = [a for a in P["actions"] if a["status"] != "up-to-date"]
    if pending or P["duplicates"]:
        print("WARNING: the local Fiji is not up-to-date with the deployment (run deploy --apply first)\n")
    local = {}  # jar_key -> action
    for a in P["actions"]:
        local[jar_key(Path(a["dest"]) / a["file"])] = a
    published = {s: published_files(fiji, s, db, args.offline) for s in PUBLISH_SITES}
    rows, uploads, removes = [], {}, {}
    for k, a in sorted(local.items()):
        rel = (Path(a["dest"]) / a["file"]).as_posix()
        gpu_file = a["file"].endswith("-gpu.jar")
        sites = [s for s in PUBLISH_SITES if k in published[s] and (gpu_file == (s == GPU_SITE) or a["dest"] not in PLATFORM_DIRS)]
        if a["shadow"]:
            rows.append(dict(site="BACMMAN", action="SHADOW", file=rel, published="(Fiji: " + a["replaces"] + ")"))
            uploads.setdefault(("BACMMAN", True), []).append(rel)
            continue
        if a.get("override"):  # published on every tensorflow site (each one replaces Fiji's file)
            for s in TF_SITES:
                pub = published[s].get(k)
                if pub is None or Path(pub).name != a["file"]:
                    rows.append(dict(site=s, action="add" if pub is None else "update", file=rel, published=Path(pub).name if pub else ""))
                    uploads.setdefault((s, False), []).append(rel)
            continue
        if not sites:  # new file
            site = (GPU_SITE if gpu_file else "BACMMAN-TF2") if a["g"] == "org.tensorflow" else "BACMMAN"
            rows.append(dict(site=site, action="add", file=rel, published=""))
            uploads.setdefault((site, False), []).append(rel)
            continue
        for s in sites:
            pub = published[s][k]
            if Path(pub).name != a["file"]:
                rows.append(dict(site=s, action="update", file=rel, published=Path(pub).name))
                uploads.setdefault((s, False), []).append(rel)
            elif a["status"] == "changed" or (a["g"] == BACMMAN_GROUP and args.verbose):
                rows.append(dict(site=s, action="update (same name)", file=rel, published=Path(pub).name))
                uploads.setdefault((s, False), []).append(rel)
    # files published on BACMMAN but not part of the deployment
    for k, pub in sorted(published["BACMMAN"].items()):
        if k not in local:
            rows.append(dict(site="BACMMAN", action="remove", file=pub, published=""))
            removes.setdefault("BACMMAN", []).append(pub)
    # tensorflow sites: report published files that are not deployed (other variant of the natives is normal)
    for s in PUBLISH_SITES:
        if s == "BACMMAN":
            continue
        for k, pub in sorted(published[s].items()):
            if k not in local:
                rows.append(dict(site=s, action="remove?", file=pub, published="not in the deployment: check"))
    declared, undeclared = fiji_overrides(fiji, db, args.exclude_site, args.offline)
    if undeclared:
        print("WARNING: files of the BACMMAN sites that replace a Fiji file without being declared in FIJI_OVERRIDES:")
        for rel, ff in undeclared:
            print(f"  {rel} (Fiji: {ff})")
        print()
    if not rows:
        print("Update sites are up-to-date with the deployment")
        return
    print_table(rows, ["site", "action", "file", "published"])
    launcher = next((n for n in ("fiji-linux-x64", "ImageJ-linux64", "fiji") if (fiji / n).is_file()), "fiji")
    print("\nCommands (run with the update site's upload credentials configured; check first with --simulate):")
    for (s, shadow), files in sorted(uploads.items()):
        print(f"  cd {fiji} && ./{launcher} --update upload --simulate --update-site {s}" + (" --force-shadow" if shadow else "")
              + " " + " ".join(files))
    if removes:
        print("Files to remove (mark obsolete): Fiji updater > Advanced mode > select the files > 'Remove it' > Apply changes:")
        for s, files in removes.items():
            for f in files:
                print(f"  {s}: {f}")
    if any(s != "BACMMAN" and s not in {x for x, _ in db.values()} for (s, _) in uploads):
        print("Note: uploading to an update site requires it to be active in this Fiji (Manage update sites).")


# ---------------------------------------------------------------- main
def align(args):
    db = read_update_db(args.fiji)
    if db is None:
        print("WARNING: no db.xml.gz in Fiji folder: cannot tell which jars come from the BACMMAN update site\n")
    by_ga, by_name, njars, nexcl = index_fiji(args.fiji, args.source, args.exclude_site, db)
    print(f"Fiji ({args.source}): {njars} local jars, {nexcl} ignored (update sites {args.exclude_site}); "
          f"reference: {len(by_ga)} artifacts with groupId, {len(by_name)} by file name only\n")
    protected = PROTECTED_GROUPS + SHADOW + args.protect + args.shadow + [f"{g}:{a}" for g, a in FIJI_OVERRIDES]  # bacmman keeps its version
    report, changed = [], []
    for pom in find_poms(args.root):
        old, new = process_pom(pom, by_ga, by_name, protected, not args.no_downgrade, args.allow_snapshot, report)
        if new != old:
            changed.append(pom)
            if args.apply:
                pom.write_text(new, encoding="utf-8")

    quiet = {"protected", "not-in-fiji", "up-to-date"}
    rows = [r for r in report if args.verbose or r["status"] not in quiet]
    print("Direct dependencies (module poms):")
    rows = sorted(rows, key=lambda r: (r["status"], r["module"], r["ga"]))
    print_table([dict(r, dependency=r["ga"], pom=r["current"]) for r in rows], ["module", "dependency", "pom", "fiji", "status", "src"])
    counts = {}
    for r in report:
        k = r["status"].split(" ")[0]
        counts[k] = counts.get(k, 0) + 1
    print("Summary: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    if changed:
        verb = "Modified" if args.apply else "Would modify (re-run with --apply)"
        print(f"{verb}: " + ", ".join(str(p.relative_to(args.root)) for p in changed))
    if db is not None:
        stale = stale_report(args.fiji, db, args.exclude_site,
                             {r["ga"].split(":")[1] for r in report if r["status"] != "protected"})
        if stale:
            print("\nWARNING: local Fiji differs from its update sites for these artifacts "
                  "(run the Fiji updater, or use --source site):")
            wa = max(len(s[0]) for s in stale)
            for a, l, c in stale:
                print(f"  {a:<{wa}}  local: {l:<25} update site: {c}")
    if not args.no_transitive:
        if changed and not args.apply:
            print("\n(transitive analysis below uses the current poms: direct changes above are not applied yet)")
        align_transitive(args, by_ga, by_name, protected)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="?", choices=["align", "deploy", "check", "upload-plan"], default="align")
    ap.add_argument("--fiji", required=True, help="Fiji installation directory")
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent), help="bacmman root dir")
    ap.add_argument("--apply", action="store_true", help="write the changes (default: dry run)")
    ap.add_argument("--exclude-site", nargs="*", default=["BACMMAN"], metavar="REGEX",
                    help="update sites whose files are not considered as provided by Fiji (default: BACMMAN)")
    ap.add_argument("--verbose", "-v", action="store_true", help="also list protected / not-in-fiji / up-to-date")
    ap.add_argument("--shadow", nargs="*", default=[], metavar="G[:A]",
                    help="additional artifacts shipped by Fiji whose Fiji version does not work with bacmman (see SHADOW)")
    g = ap.add_argument_group("align")
    g.add_argument("--no-downgrade", action="store_true",
                   help="do not apply Fiji versions older than the current ones (they are applied by default: Fiji's jar is the one loaded at runtime)")
    g.add_argument("--allow-snapshot", action="store_true", help="also apply -SNAPSHOT versions shipped by Fiji")
    g.add_argument("--no-transitive", action="store_true", help="only align direct dependencies")
    g.add_argument("--source", choices=["local", "site"], default="local",
                   help="local: jars present in the Fiji folder (default); site: current files of the update sites "
                        "according to db.xml.gz (= state after running the Fiji updater)")
    g.add_argument("--protect", nargs="*", default=[], metavar="G[:A]",
                   help="additional groupId or groupId:artifactId that bacmman controls")
    d = ap.add_argument_group("deploy")
    d.add_argument("--with-bacmman", action="store_true", help="also install the bacmman module jars")
    d.add_argument("--remove-duplicates", action="store_true",
                   help="move to backup the files of the BACMMAN sites that duplicate a jar provided by Fiji (same artifact, or same classes)")
    d.add_argument("--backup-dir", help="where replaced files are moved (default: <fiji>.bacmman-backup/<date>)")
    u = ap.add_argument_group("upload-plan")
    ap.add_argument("--fiji-versions", choices=["older", "all"], default="older",
                    help="older (default): Fiji's version is applied only when it is older than the one given by the BOM "
                         "(a newer Fiji version is compatible by backward compatibility); all: always apply Fiji's version")
    g.add_argument("--bom-version", help="pom-scijava version to import (default: the one in the pom, else the latest release)")
    u.add_argument("--offline", action="store_true", help="use the local updater database instead of the published site databases")
    args = ap.parse_args()
    if args.command in ("check", "upload-plan"):
        args.with_bacmman = True
    {"deploy": deploy, "check": check, "upload-plan": upload_plan}.get(args.command, align)(args)


if __name__ == "__main__":
    main()
