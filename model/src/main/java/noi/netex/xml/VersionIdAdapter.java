package noi.netex.xml;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

/// Binds `VersionIdType` — every `version` attribute in the model.
public final class VersionIdAdapter extends XmlAdapter<String, String> {

    private static final String ANY = "any";

    @Override
    public String unmarshal(String value) {
        return ANY.equals(value) ? ANY : value;
    }

    @Override
    public String marshal(String value) {
        return value;
    }
}
