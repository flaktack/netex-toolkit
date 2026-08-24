package noi.netex.xml;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import noi.netex.time.XmlTime;

/// Binds `xs:time` to [XmlTime]. A plain LocalTime binding drops after-midnight,
/// silently folds `24:00:00` to `00:00:00`, and discards the offset.
public final class XmlTimeAdapter extends XmlAdapter<String, XmlTime> {

    @Override
    public XmlTime unmarshal(String value) {
        return value == null || value.isBlank() ? null : XmlTime.parse(value);
    }

    @Override
    public String marshal(XmlTime value) {
        return value == null ? null : value.toXmlFormat();
    }
}
