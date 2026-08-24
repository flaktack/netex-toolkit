package noi.netex.sites;

import noi.netex.model.LevelRefStructure;
import noi.netex.model.SitePathLinkEndStructure;

public final class PathLinkEnds {

    public static final Class<?> CARRIER = SitePathLinkEndStructure.class;

    private PathLinkEnds() {}

    public static boolean isEnd(Object o) {
        return o instanceof SitePathLinkEndStructure;
    }

    public static LevelRefStructure levelRef(Object o) {
        return o instanceof SitePathLinkEndStructure e ? e.getLevelRef() : null;
    }

    public static void clearLevelRef(Object o) {
        ((SitePathLinkEndStructure) o).setLevelRef(null);
    }
}
