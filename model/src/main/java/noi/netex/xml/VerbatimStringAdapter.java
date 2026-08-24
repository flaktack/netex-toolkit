package noi.netex.xml;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;

/// Identity `String` ↔ `String` binding for `xs:normalizedString`.
public final class VerbatimStringAdapter extends XmlAdapter<String, String> {

    @Override
    public String unmarshal(String value) {
        return value;
    }

    @Override
    public String marshal(String value) {
        return value;
    }
}
