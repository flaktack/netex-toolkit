// CLI over toolkit.harness.DbToZip for the EPIP export profile, toolkit.harness.EpipDbToZip:
// every object of a store streamed into an EPIP-shaped NeTEx zip, laid out on OTP's three file
// levels. What each entry holds and why is in EpipDbToZip's header; nothing about it is here.
//
// The worked example for the EXPORT sandwich carrying a real PROFILE — DbToZipRaw is the same
// sandwich with none. DbToZip owns the CLI, the read-only open, the read transaction, the atomic
// .tmp/rename, the throughput line and the common tail; the profile owns the tag, the usage line,
// its own two flags and the middle. What is left here is the join.
//
// The profile is COMPILED AND SHIPPED rather than sitting in this file, for the reason
// toolkit.transform.epip's package-info gives for the EPIP generators: a JEP-458 source-launched
// program gets exactly one source root, so ../noi-pipeline — which runs this same exporter —
// cannot import a .java from this repository, and anything shared across a checkout boundary has
// to be in the jar. It used to sit here, and that repository carried a 1164-line verbatim copy.

import toolkit.harness.DbToZip;
import toolkit.harness.EpipDbToZip;

public class EpipDbToXml {

    public static void main(String[] args) throws Exception {
        // CLI via toolkit.harness.Args, the common half owned by DbToZip: unknown --x, a
        // value-taking flag with no value (also as the LAST token) or a wrong positional count ->
        // usage + exit 2; a missing input db -> exit 1. --level and --log-file come from DbToZip;
        // EpipDbToZip.flags() declares the two the harness cannot know about.
        DbToZip.driverMain(EpipDbToZip.TAG, args,
                EpipDbToZip.USAGE, EpipDbToZip.flags(), EpipDbToZip::setup);
    }
}
