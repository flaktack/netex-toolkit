package toolkit.transform.common;

// Give every Line an OTP-resolvable authority.
//
// A consumer resolves a Line's agency through its authorityRef, or through a representedByGroupRef
// whose Network carries one. A feed that publishes neither leaves its Lines unattributed, and the
// pass repairs that from what the feed does publish: an Authority already in scope, or the Line's
// Operator, which NeTEx makes interchangeable with an Authority in all but the element name.

import noi.netex.model.Authority;
import noi.netex.model.AuthorityRef;
import noi.netex.model.AuthorityRefStructure;
import noi.netex.model.Codespace;
import noi.netex.model.GroupOfLines;
import noi.netex.model.Line;
import noi.netex.text.Mls;
import noi.netex.model.MultilingualString;
import noi.netex.model.Network;
import noi.netex.model.ObjectFactory;
import noi.netex.model.Operator;
import noi.netex.model.OperatorRefStructure;
import noi.netex.model.OrganisationTypeEnumeration;
import noi.netex.model.TransportOrganisation_VersionStructure;
import noi.netex.model.TransportOrganisationRefStructure;
import jakarta.xml.bind.JAXBElement;
import toolkit.model.NetexUtils;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class LineAuthorities {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    private LineAuthorities() {}

    /// Repaired Networks, Lines given an authorityRef, then the synthesised Authorities.
    ///
    /// `fallback` names the codespace a minted Authority's id takes when the Operator's carries no
    /// class token to derive from. A [Codespace] rather than the generators' defaults record: this
    /// package is the shared leaf and does not depend on a profile.
    public static List<Object> backfillLineAuthorities(Store db, Txn txn, Codespace fallback) {
        // The Authority of an id's codespace, where the codespace holds exactly one. An id is
        // `<codespace>:<Class>:<identifier>`, so the part ahead of the class token is what a Line,
        // its Operator and its Authority share when one publisher issued all three. A codespace
        // holding several Authorities names none of them, and is dropped.
        //
        // The prefix comes from NetexUtils, which finds the token by asking whether it names a
        // NeTEx class rather than matching one spelling per class. It is a JOIN KEY, so what
        // matters is that every side computes it the same way: it keeps the separator that a
        // hand-written `:Class:` match would have cut before, and a mix of the two conventions
        // builds a key that never matches.
        //
        // A minted Authority must not enter this index: it answers for the one Operator it was
        // derived from, not for every later Operator of the same codespace.
        Map<String, String> authByPrefix = new LinkedHashMap<>();
        Set<String> ambiguousPrefix = new HashSet<>();
        Map<String, String> authorityVersion = new LinkedHashMap<>();
        Set<String> published = new HashSet<>();
        for (Object o : db.iterOnlyObjects(txn, Authority.class)) {
            Authority auth = (Authority) o;
            String id = auth.getId();
            if (id == null || id.isEmpty()) continue;
            published.add(id);
            authorityVersion.put(id, auth.getVersion());
            String prefix = NetexUtils.codespacePrefix(id);
            if (prefix == null) continue;
            String seen = authByPrefix.put(prefix, id);
            if (seen != null && !seen.equals(id)) ambiguousPrefix.add(prefix);
        }
        authByPrefix.keySet().removeAll(ambiguousPrefix);

        Map<String, Operator> operators = new LinkedHashMap<>();
        for (Object o : db.iterOnlyObjects(txn, Operator.class)) {
            Operator op = (Operator) o;
            if (op.getId() != null && !op.getId().isEmpty()) operators.put(op.getId(), op);
        }
        Map<String, Authority> synthAuthority = new LinkedHashMap<>();
        List<Object> out = new ArrayList<>();

        Function<String, String> authorityForOperator = opId -> {
            if (opId == null || opId.isEmpty()) return null;
            // The prefix lookup keeps the primitive's null: an id with no class token shares no
            // codespace, so there is nothing to look up. Minting below has a fallback instead, so
            // such an Operator still yields an Authority rather than leaving its Line unattributed.
            String prefix = NetexUtils.codespacePrefix(opId);
            String existing = prefix == null ? null : authByPrefix.get(prefix);
            if (existing != null) return existing;
            String authId = NetexUtils.deriveId(opId, Authority.class, fallback);
            // A publisher may already issue the Authority under the id this derives — an Operator
            // and its Authority differing only in the class token is how a feed says one
            // organisation holds both roles. Minting there would write a second object over a
            // published one, so the derived id is a lookup before it is a name.
            if (published.contains(authId)) return authId;
            if (!synthAuthority.containsKey(authId)) {
                Operator op = operators.get(opId);
                MultilingualString name = op != null
                        ? (op.getName() != null ? op.getName() : op.getLegalName()) : null;
                Authority a = new Authority();
                a.setId(authId);
                a.setVersion(op != null ? op.getVersion() : "1");
                a.setName(name != null ? name : Mls.of(lastSegment(opId)));
                a.getOrganisationType().add(OrganisationTypeEnumeration.AUTHORITY);
                // The EPIP profile makes Address mandatory on an AUTHORITY. Its id is the
                // owner's with the class token swapped, the way the toolkit derives one.
                TransportOrganisation_VersionStructure.Address address =
                        new TransportOrganisation_VersionStructure.Address();
                address.setId(NetexUtils.deriveId(authId,
                        TransportOrganisation_VersionStructure.Address.class, fallback));
                address.setVersion(a.getVersion());
                a.setAddress(address);
                synthAuthority.put(authId, a);
                authorityVersion.put(authId, a.getVersion());
            }
            return authId;
        };

        long netFixed = 0;
        Set<String> resolvableGroups = new HashSet<>();
        for (Object o : db.iterOnlyObjects(txn, Network.class)) {
            Network net = (Network) o;
            JAXBElement<? extends TransportOrganisationRefStructure> orgEl = net.getTransportOrganisationRef();
            TransportOrganisationRefStructure org = orgEl == null ? null : orgEl.getValue();
            if (org instanceof OperatorRefStructure opRef) { // repair Operator-typed org ref
                String authId = authorityForOperator.apply(opRef.getRef());
                if (authId != null) {
                    AuthorityRef ar = authorityRef(authId, authorityVersion);
                    net.setTransportOrganisationRef(FACTORY.createAuthorityRef(ar));
                    org = ar;
                    out.add(net);
                    netFixed++;
                }
            }
            boolean hasAuth = org instanceof AuthorityRefStructure
                    && org.getRef() != null && !org.getRef().isEmpty();
            if (hasAuth && net.getGroupsOfLines() != null) {
                for (GroupOfLines g : net.getGroupsOfLines().getGroupOfLines()) {
                    if (g.getId() != null && !g.getId().isEmpty()) resolvableGroups.add(g.getId());
                }
            }
        }

        long lineFixed = 0;
        for (Object o : db.iterOnlyObjects(txn, Line.class)) {
            Line line = (Line) o;
            var grp = line.getRepresentedByGroupRef();
            if (line.getAuthorityRef() != null
                    || (grp != null && resolvableGroups.contains(grp.getRef()))) {
                continue; // already resolvable
            }
            // The Line's own codespace first: it is the only source a Line with no operatorRef has.
            String authId = authByPrefix.get(NetexUtils.codespacePrefix(line.getId()));
            if (authId == null && line.getOperatorRef() != null) {
                authId = authorityForOperator.apply(line.getOperatorRef().getRef());
            }
            if (authId == null) continue;
            if (grp != null) line.setRepresentedByGroupRef(null); // dead end -> authorityRef wins
            line.setAuthorityRef(authorityRef(authId, authorityVersion));
            out.add(line);
            lineFixed++;
        }

        out.addAll(synthAuthority.values());
        Log.info("[backfill_line_authorities] gave %d Lines an authorityRef, repaired "
                + "%d Network org refs (%d Authorities synthesised)", lineFixed, netFixed, synthAuthority.size());
        return out;
    }

    /// A reference at `authId`, versioned as the Authority it names is. A ref carries the version of
    /// its target, not of the object that holds it.
    private static AuthorityRef authorityRef(String authId, Map<String, String> authorityVersion) {
        AuthorityRef ar = new AuthorityRef();
        ar.setRef(authId);
        ar.setVersion(authorityVersion.get(authId));
        return ar;
    }

    private static String lastSegment(String id) {
        int i = id.lastIndexOf(':');
        return i < 0 ? id : id.substring(i + 1);
    }
}
