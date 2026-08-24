package noi.netex.text;

import noi.netex.model.PublicCodeStructure;
import noi.netex.model.Quay;

public final class Codes {

    private Codes() {}

    public static String publicCode(Quay q) {
        PublicCodeStructure c = q.getPublicCode();
        return c == null ? null : c.getValue();
    }
}
