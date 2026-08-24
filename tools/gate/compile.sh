#!/bin/bash
# The JEP-458 compile gate — whatever ships must at least compile, and must at least launch.
#
# Subject: tools/ and scripts/ — every source tree outside the Maven modules.
#
# The Maven gates guard the Maven tree: `assert-one-netex-model-on-classpath` reads the jars
# `package` produces, `assert-no-foreign-adapters` reads netex-model's sources, and neither can
# see tools/. These are JEP-458 scripts, outside every Maven module, so nothing else compiles
# them.
#
# Not a Maven module: making tools/ one would compile the scripts into a jar and destroy the
# property that makes them scripts (edit, run, no build step). It is javac, over a glob.
#
# GLOB, DO NOT ENUMERATE. A hand-written file list stops being complete the moment a tree grows a
# file, and a gate that has stopped covering half its subject is worse than no gate. Everything
# below is discovered from the filesystem.
#
# ---------------------------------------------------------------------------------------------
# TWO ARMS
#
# `javac` accepts any file you name on the command line regardless of where it sits: a file
# declaring `package scratch;` compiles happily from tools/checks/. The JEP-458 **source
# launcher** does not — it refuses an initial file whose path does not end with its package name:
#
#     error: end of path to source file does not match its package name scratch:
#            tools/checks/ExportTailCheck.java
#
# So a javac-only gate over tools/ would report green over a tree in which the documented
# invocation of half the tools does not start.
#
#   arm 1  LAUNCHABLE  every file has a JEP-458 source root: no package declaration (root = its
#                      own directory), or a directory path that ends with its package path.
#   arm 2  COMPILES    one javac per implied source root, with -sourcepath set to THAT root, so
#                      each file sees exactly what the launcher would let it see and no more.
#                      Compiling everything with -sourcepath <repo root> would let a
#                      default-package file resolve a packaged sibling source that the launcher
#                      would never find for it.
#
# Arm 2 subsumes arm 1's failure mode as "no source root computable", so they share a message.
#
# ---------------------------------------------------------------------------------------------
# COST. One javac process per distinct source root, against the built jar and deps/. It needs
# `mvn package` to have run (jar + deps/), which CI has already done by the time it gets here. It
# runs no tool and touches no store: classes go to a temp directory that is removed on exit.
#
# ---------------------------------------------------------------------------------------------
# USAGE
#
#   CI, or any host with a real JDK:  ./tools/compile_check.sh     (after `mvn -B -ntp package`)
#   this development host, which has no JDK:  JAVAC=./bin/javac.sh ./tools/compile_check.sh
#
#   P      source root — the JEP 458 source root, i.e. the directory holding tools/, scripts/
#          and toolkit/. Defaults to the parent of this script's directory, which is correct
#          in the repo (tools/ -> .. is the repo root). Same convention as run_suite.sh.
#   T      the trees to gate, space-separated. Defaults to every JEP-458 source tree in the
#          repository: tools/ and scripts/. `mvn package` builds toolkit/src only and the suite
#          compiles nothing outside the modules, so this gate is the only thing that compiles
#          scripts/.
#   JAVAC  the compiler. Defaults to `javac`. On a host with no JDK pass a wrapper, exactly as
#          run_suite.sh takes JAVA=./bin/java.sh.
#
# EXIT: 0 all files launchable and compiling; 1 otherwise; 2 the gate could not run (which is a
# failure, not a skip — see the operative-first block below).
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# $HERE is tools/gate, so the repo root is two levels up. The subject is the two source trees
# outside the Maven modules: tools/ and scripts/, the latter a flat directory of drivers in the
# DEFAULT PACKAGE.
P="${P:-$(cd "$HERE/../.." && pwd)}"
T="${T:-$P/tools $P/scripts}"
JAVAC="${JAVAC:-javac}"
# The subject is 21 files under tools/ + 8 under scripts/ = 29. A tripwire for "a tree moved or a
# commit removed it", not a target — set just under the true count so losing a whole tree fails
# loudly instead of passing on a shrunken subject.
MIN_FILES="${MIN_FILES:-26}"

fail_inoperative() { echo "GATE INOPERATIVE: $*" >&2; exit 2; }

# ---------------------------------------------------------------- preconditions
# Every precondition here is checked and every failure is loud: `grep -r` on a missing directory
# warns, exits non-zero, and `|| true` swallows it, leaving a green over nothing at all. Every
# named tree must exist — a missing one is a moved tree, not a smaller subject.
# shellcheck disable=SC2086 -- T is a deliberate space-separated list of trees
set -- $T
[ "$#" -ge 1 ] || fail_inoperative "T is empty (set T, or P to the source root)"
for t in "$@"; do
  [ -d "$t" ] || fail_inoperative "no tree at $t (set T, or P to the source root)"
done

files=()
while IFS= read -r -d '' f; do files+=("$f"); done \
  < <(find "$@" -name '*.java' -type f -print0 | sort -z)
n=${#files[@]}
[ "$n" -ge "$MIN_FILES" ] || fail_inoperative \
  "only $n .java files under $T (expect >= $MIN_FILES). A tree moved, or a commit removed it."

JAR="$P/toolkit/target/netex-toolkit.jar"
DEPS="$P/toolkit/target/deps"
[ -f "$JAR" ]  || fail_inoperative "no jar at $JAR — run 'mvn -B -ntp package' first"
[ -d "$DEPS" ] || fail_inoperative "no $DEPS — run 'mvn -B -ntp package' first"
njars=$(find "$DEPS" -name '*.jar' | wc -l)
[ "$njars" -ge 10 ] || fail_inoperative "only $njars jars in $DEPS (expect ~28)"
command -v "${JAVAC%% *}" >/dev/null 2>&1 || [ -x "${JAVAC%% *}" ] \
  || fail_inoperative "no javac ($JAVAC). Install a JDK, or pass JAVAC=<wrapper>."

CP="$JAR:$DEPS/*"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "P=$P"
echo "T=$T"
echo "  ($n .java files)"
echo "CP=$JAR + $njars jars"

# ---------------------------------------------------------------- arm 1: launchable
# The JEP-458 rule for an INITIAL source file, from JDK 25's SourceLauncher: if the file declares
# a package, the file's directory must end with the package's directory path, and the source root
# is what remains. With no package declaration the root is the file's own directory. Nothing else
# is accepted — the `java` launcher has no --source-path to override it (both `--source-path` and
# `-sourcepath` are rejected as unrecognized JVM options).
declare -A GROUP=()
bad_root=0
for f in "${files[@]}"; do
  d=$(dirname "$f")
  pkg=$(sed -n 's/^[[:space:]]*package[[:space:]]\{1,\}\([A-Za-z0-9_.]\{1,\}\)[[:space:]]*;.*/\1/p' "$f" | head -1)
  if [ -z "$pkg" ]; then
    root=$d
  else
    rel=${pkg//.//}
    case "$d" in
      */"$rel") root=${d%"/$rel"} ;;
      "$rel")   root=. ;;
      *)
        if [ "$bad_root" = 0 ]; then
          echo
          echo "NOT LAUNCHABLE — the JEP-458 source launcher refuses these as initial files:"
        fi
        bad_root=$((bad_root+1))
        echo "  ${f#"$P"/}  declares 'package $pkg' but does not sit under .../$rel"
        continue ;;
    esac
  fi
  GROUP["$root"]="${GROUP["$root"]:-} $f"
done

if [ "$bad_root" != 0 ]; then
  echo
  echo "Each of the $bad_root files above fails at the FIRST LINE of its documented invocation:"
  echo "    error: end of path to source file does not match its package name"
  echo "javac does NOT reproduce this — it accepts any file you name, wherever it sits — so a"
  echo "compile-only gate reports green over exactly this defect. That is why this arm exists."
  echo "Fix the package declaration to match the shipped path, or ship the file at the path its"
  echo "package names. Do not delete this arm."
fi

# ---------------------------------------------------------------- arm 2: compiles
rc=0
i=0
for root in "${!GROUP[@]}"; do
  i=$((i+1))
  # shellcheck disable=SC2086 -- GROUP holds a space-separated list of find-produced paths
  set -- ${GROUP["$root"]}
  shown=${root#"$P"}; shown=${shown#/}; shown=${shown:-.}
  d="$OUT/$i"; mkdir -p "$d"
  log="$OUT/$i.log"
  if $JAVAC -nowarn -proc:none -Xmaxerrs 200 -d "$d" -cp "$CP" -sourcepath "$root" "$@" > "$log" 2>&1; then
    produced=$(find "$d" -name '*.class' | wc -l)
    # javac exiting 0 having emitted nothing would mean this gate compiled nothing. Say so.
    if [ "$produced" -lt "$#" ]; then
      echo "GATE INOPERATIVE: javac reported success for root $shown but emitted $produced" >&2
      echo "  class files for $# sources. It did not compile what it was given." >&2
      rc=2
    else
      printf '  [ok]   %-28s %2d sources -> %3d classes\n' "$shown/" "$#" "$produced"
    fi
  else
    echo
    echo "COMPILE FAILURE under source root $shown ($# sources):"
    grep -E '\.java:[0-9]+: error:|^[0-9]+ error' "$log" | head -40
    rc=1
  fi
done

echo
if [ "$bad_root" != 0 ] && [ "$rc" = 0 ]; then rc=1; fi
if [ "$rc" = 0 ]; then
  echo "[gate] OK — $n sources across ${#GROUP[@]} source roots: all launchable, all compile"
else
  echo "[gate] FAILED — $bad_root not launchable; see compile errors above"
fi
exit $rc
