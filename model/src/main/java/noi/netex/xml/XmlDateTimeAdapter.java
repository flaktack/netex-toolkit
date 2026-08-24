package noi.netex.xml;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import noi.netex.time.XmlDateTime;

/// Binds `xs:dateTime` to [XmlDateTime]. A plain LocalDateTime binding parses the timezone offset
/// and discards it, and can retain no lexical form.
public final class XmlDateTimeAdapter extends XmlAdapter<String, XmlDateTime> {

    @Override
    public XmlDateTime unmarshal(String value) {
        return value == null || value.isBlank() ? null : XmlDateTime.parse(value);
    }

    @Override
    public String marshal(XmlDateTime value) {
        return value == null ? null : value.toXmlFormat();
    }
}
