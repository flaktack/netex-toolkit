package noi.netex.xml;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import noi.netex.time.XmlDuration;

/// Binds `xs:duration` to [XmlDuration]. The default `java.time.Duration` binding rejects the
/// year/month lexical forms.
public final class XmlDurationAdapter extends XmlAdapter<String, XmlDuration> {

    @Override
    public XmlDuration unmarshal(String value) {
        return value == null || value.isBlank() ? null : XmlDuration.parse(value);
    }

    @Override
    public String marshal(XmlDuration value) {
        return value == null ? null : value.toXmlFormat();
    }
}
