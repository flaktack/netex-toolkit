package noi.netex.xml;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import noi.netex.time.XmlDateTime;

/// Binds `xs:date` to [XmlDateTime] — the same carrier as `xs:dateTime`, which is mandatory
/// because the calendar code mixes date-typed and dateTime-typed values in one collection.
public final class XmlDateAdapter extends XmlAdapter<String, XmlDateTime> {

    @Override
    public XmlDateTime unmarshal(String value) {
        return value == null || value.isBlank() ? null : XmlDateTime.parseDate(value);
    }

    @Override
    public String marshal(XmlDateTime value) {
        return value == null ? null : value.toXmlFormat();
    }
}
