package inspect.nx;

import java.util.Arrays;

/// `nx` — the source-launched debugging tool tree. One entry point, one subcommand per tool:
/// `prov`, `xsd`, `lens`, `bind`, `geo`, `db`.
///
/// ```
/// cd <the repository root>
/// CP="toolkit/target/netex-toolkit.jar:toolkit/target/deps/*"
/// ./bin/java.sh -cp "$CP" tools/inspect/nx/Nx.java prov check <db>...
/// ```
public final class Nx {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (cmd) {
            case "prov" -> System.exit(ProvCmd.run(rest));
            case "xsd" -> System.exit(XsdCmd.run(rest));
            case "lens" -> System.exit(LensCmd.run(rest));
            case "bind" -> System.exit(BindCmd.run(rest));
            case "geo" -> System.exit(GeoCmd.run(rest));
            case "db" -> System.exit(DbCmd.run(rest));
            case "-h", "--help", "help" -> usage();
            default -> {
                System.err.println("nx: unknown subcommand '" + cmd + "'");
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.err.println("""
                nx <subcommand> ...

                  prov check <db>...            provenance staleness gate (exit 1 on any stale input)
                  prov show  <db>...            print the sidecar
                  prov caps                     print the running jar's capability vector
                  prov stamp <db> --caps a,b --why "..."   operator attestation for a pre-tool store

                  lens <id>[@<version>] [--class C] [--db …] [--source …] [--export …]
                                                one object, rendered identically across every layer
                                                it can reach -- S source / F stored fragment /
                                                B bound bean (declared-but-null fields INCLUDED) /
                                                B0 naive bean / M re-marshal / E export / P python --
                                                plus one verdict line per adjacent boundary.
                                                A DIAGNOSTIC, NEVER A GATE.
                  lens find --db <store> [--class C] [--contains STR]
                                                locate an address: raw byte scan, no decode.

                  bind sweep --db <store> [--sample N] [--workers N] [--report f.tsv]
                                                the silent-null sweep: every (class, field) where a
                                                NON-EMPTY fragment value binds to NULL, over a corpus.
                                                Binds twice -- B0 (plain JAXB, no toolkit
                                                interception, i.e. the fix DISABLED) and B
                                                (FragmentCodec.decode) -- so B0-null/B-bound
                                                distinguishes "repaired" from "still broken" without
                                                a pre-fix jar. Also censuses the enum-ATTRIBUTE hole
                                                #104 left recorded-not-fixed, and proves BeanRender's
                                                path vocabulary corpus-wide.
                                                Point it at a LOADER-CAPTURE store: a re-marshalled
                                                one has already collapsed the padding away.
                                                A DIAGNOSTIC, NEVER A GATE.
                  bind explain <name> [<value>]  what the model binds this name to, and what each
                                                arm does with that value.
                  bind table [--attrs|--elements|--all]   the reflection-built enum name tables.
                  bind probe [--tsv f] [--only ids]  THE VERSION ARM (#155). No store: the same
                                                B0/B arms over SYNTHETIC fragments for the cells
                                                the 2.0 schema diff found, plus an M arm that
                                                re-marshals and asks whether the element survived.
                                                Version-agnostic by construction, so the same
                                                binary runs under a 1.15.2 and a 2.0 model jar;
                                                ci/bind20-check.sh runs it at both and diffs.
                                                It REFUSES rather than reporting a zero.

                  geo census  --db <store>...   what geometry a store holds, before you extract it:
                                                point/line/polygon counts, the point-count histogram,
                                                the srsName buckets (declared / absent / not-a-CRS --
                                                that last one is BUG-6's Europe/Rome), and, for lines,
                                                how wrong the straight line OTP draws actually is.
                  geo extract --db <store>... --out f.geojson [--html f.html]
                                                GeoJSON, SCOPED (--class/--bbox/--around/--dev-min/
                                                --kind/--srs) with a hard --max that truncates
                                                LOUDLY.  38,253,940 coordinate points exist
                                                nationally; no single page should try.
                  geo html    --extract f.geojson --out map.html
                                                ONE self-contained file.  No basemap, no tiles, no
                                                network: it opens from disk.
                  geo selftest                  the geometry reader against synthetic fragments, no
                                                store needed.  Polygon, MultiSurface and the
                                                reprojection path occur ZERO times in this corpus,
                                                so they are checked here or nowhere.
                  geo compare --a A.lmdb --b B.lmdb --out cmp.geojson
                                                per-vertex displacement WITH A TOLERANCE, because
                                                reprojection moves ~1e-6 deg and a truncated link
                                                moves kilometres, and those are not one finding.
                                                A DIAGNOSTIC, NEVER A GATE.

                  db  stat  --db <store> [--diff other.lmdb] [--tsv]
                                                entries per sub-database with CLASS NAMES, and two
                                                stores side by side: what moved.  Subsumes
                                                tools/task100/DbiCount.java (--tsv reproduces its
                                                pinned rows byte for byte).
                  db  get / versions / emb / embof     one object's stored bytes; every version of
                                                an id (duplicate ids are LEGAL -- the key is
                                                (id, version)); what an object is embedded IN and
                                                what a container EMBEDS.
                  db  refs / rrefs / unres / grep / path
                                                the reference graph.  rrefs is the capability that
                                                has been BUILT AND UNREACHABLE since A4: Csr.in()
                                                answers it and no command line ever called it.
                                                COMMANDS ARE TIERED BY WHAT THEY OPEN and each one
                                                says so: everything except `path` (and the opt-in
                                                --csr cross-check) avoids loadEdgeState, which #124
                                                measured at 3.77 GB and 50 s and #144 made lazy.
                  db  check --db <store>        THE PIN. Store.scanEdges/scanUnresolved (streaming,
                                                one block resident) against iterEdges/iterUnresolved
                                                (loadEdgeState's materialised arrays) on an
                                                ORDER-SENSITIVE digest, with a negative control so
                                                it can be seen to fail and a refusal on a store
                                                where the block-order hazard is unexercised.
                                                A DIAGNOSTIC, NEVER A GATE.

                  xsd scan --zip <export.zip> --out <f.tsv>   XSD-validate an export, aggregated ledger
                  xsd diff <baseline.tsv> <new.tsv>          exit 1 on a NEW row or a count increase
                      ADVISORY, NEVER A GATE. Both arms carry ~1e6 schema issues and XSD caught 0 of
                      the 3 defects this campaign was named for -- NeTEx declares nearly everything
                      optional, so a validator cannot see data LOSS. It sees ORDER and LEXICAL
                      validity, which nothing else in the stack sees.
                      `xsd` needs no classpath: ../bin/java.sh scratch/nx/XsdCmd.java scan ...

                `lens` is built to its CORE only -- there is no source index, so layer S is a
                linear scan of the feeds you name. There is no corpus census subcommand.""");
    }

    private Nx() {}
}
