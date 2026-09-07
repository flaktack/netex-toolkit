// CLI over toolkit.harness.DbToZip for the EPIP export profile, toolkit.harness.EpipDbToZip:
// every object of a store streamed into an EPIP-shaped NeTEx zip, laid out on OTP's three file
// levels.
//
// The profile is compiled and shipped rather than written here: a JEP-458 source-launched program
// gets exactly one source root, so another checkout that runs this same exporter cannot import a
// .java from this repository, and anything shared across a checkout boundary has to be in the jar.

import toolkit.harness.DbToZip;
import toolkit.harness.EpipDbToZip;

public class EpipDbToXml {

    public static void main(String[] args) throws Exception {
        DbToZip.driverMain(EpipDbToZip.TAG, args,
                EpipDbToZip.USAGE, EpipDbToZip.flags(), EpipDbToZip::setup);
    }
}
