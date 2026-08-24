package noi.netex.calls;

import jakarta.xml.bind.JAXBElement;
import java.util.Collections;
import java.util.List;
import javax.xml.namespace.QName;
import noi.netex.model.Call;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.Calls_RelStructure;

public final class Calls {

    private static final QName CALL = new QName("http://www.netex.org.uk/netex", "Call");

    private Calls() {}

    public static List<Call_VersionedChildStructure> of(Calls_RelStructure calls) {
        return Collections.<Call_VersionedChildStructure>unmodifiableList(calls.getCall());
    }

    public static void add(Calls_RelStructure calls, Call call) {
        calls.getCall().add(call);
    }

    public static JAXBElement<Call> element(Call call) {
        return new JAXBElement<>(CALL, Call.class, call);
    }
}
