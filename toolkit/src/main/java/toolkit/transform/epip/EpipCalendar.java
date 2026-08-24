package toolkit.transform.epip;

// The EPIP service-calendar transform. Two order-critical jobs:
//
// 1. sort-then-group: grouping only merges ADJACENT equal keys, so the assignment list is sorted
//    by (dayTypeRef.ref, dayTypeRef.version) FIRST and then grouped on dayTypeRef equality — a
//    stable sort, and group boundaries at runs of equal refs. Without the sort, non-adjacent
//    assignments of one DayType would split into several groups, re-yielding the DayType and
//    emitting several UicOperatingPeriods sharing one id.
//
// 2. the 4-way union field: the binding splits it into getOperatingPeriodRef() (a JAXBElement —
//    both the OperatingPeriodRef and UicOperatingPeriodRef elements substitute into it, and the
//    element NAME distinguishes them), getOperatingDayRef(), and getDate() (the bare-date arm).
//
// getDate() is an xs:date and getFromDate()/getToDate()/getCalendarDate() are xs:dateTime, and
// all four flow into ONE LinkedHashSet whose membership decides the ValidDayBits string. That is
// why netex-model binds both schema types to a single carrier (noi.netex.time.XmlDateTime), and
// why that carrier makes hashCode() throw: a Set of carriers would compare by identity and
// silently produce a different bit string. Every read normalises with toLocalDateTime() at the
// point of use, so from `bareDates` onwards every value here is a LocalDateTime. The two WRITES
// go the other way through XmlDateTime.from(), which canonicalises.

import noi.netex.model.DayType;
import noi.netex.model.DayTypeAssignment;
import noi.netex.model.DayTypeRefStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.OperatingDay;
import noi.netex.model.OperatingPeriod;
import noi.netex.model.OperatingPeriodRefStructure;
import noi.netex.model.ServiceCalendar;
import noi.netex.model.UicOperatingPeriod;
import noi.netex.time.XmlDateTime;
import toolkit.model.NetexUtils;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import jakarta.xml.bind.JAXBElement;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class EpipCalendar {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// The log keys already emitted — each is logged on its FIRST occurrence only.
    private static final Set<String> LOGGED_ONCE = new HashSet<>();

    private EpipCalendar() {}

    private static synchronized void logOnce(String key, String message) {
        if (LOGGED_ONCE.add(key)) Log.warn("%s:%s", key, message);
    }

    /// The service-calendar transform. `generatorDefaults` is part of the uniform generator
    /// signature but unused on the live path.
    ///
    /// The indexes are all materialized before the first emission, as is the group-loop output.
    public static Iterator<Object> epipServiceCalendar(Store db, Txn txn,
                                                       GeneratorDefaults generatorDefaults) {
        Log.info("Calendar creation...");

        List<ServiceCalendar> serviceCalendars = new ArrayList<>();
        for (Object o : db.iterOnlyObjects(txn, ServiceCalendar.class)) {
            serviceCalendars.add((ServiceCalendar) o);
        }

        // Index the calendar-embedded objects first, then the db class scan; on a duplicate id the
        // LAST one wins, which is LinkedHashMap.put's rule.
        Map<String, DayType> dayTypes = new LinkedHashMap<>();
        for (ServiceCalendar sc : serviceCalendars) {
            if (sc.getDayTypes() != null) {
                for (JAXBElement<?> el : sc.getDayTypes().getDayTypeRefOrDayType_()) {
                    if (el.getValue() instanceof DayType dt) dayTypes.put(dt.getId(), dt);
                }
            }
        }
        for (Object o : db.iterOnlyObjects(txn, DayType.class)) {
            DayType dt = (DayType) o;
            dayTypes.put(dt.getId(), dt);
        }

        // The uic/plain operating-period indexes read the SAME compound calendar list twice,
        // split by concrete type: UicOperatingPeriod and OperatingPeriod are sibling classes, so
        // an instanceof check is what separates them.
        Map<String, UicOperatingPeriod> uicOperatingPeriods = new LinkedHashMap<>();
        for (ServiceCalendar sc : serviceCalendars) {
            if (sc.getOperatingPeriods() != null) {
                for (JAXBElement<?> el
                        : sc.getOperatingPeriods().getOperatingPeriodRefOrOperatingPeriodOrUicOperatingPeriod()) {
                    if (el.getValue() instanceof UicOperatingPeriod p) {
                        uicOperatingPeriods.put(p.getId(), p);
                    }
                }
            }
        }
        for (Object o : db.iterOnlyObjects(txn, UicOperatingPeriod.class)) {
            UicOperatingPeriod p = (UicOperatingPeriod) o;
            uicOperatingPeriods.put(p.getId(), p);
        }

        Map<String, OperatingPeriod> operatingPeriods = new LinkedHashMap<>();
        for (ServiceCalendar sc : serviceCalendars) {
            if (sc.getOperatingPeriods() != null) {
                for (JAXBElement<?> el
                        : sc.getOperatingPeriods().getOperatingPeriodRefOrOperatingPeriodOrUicOperatingPeriod()) {
                    if (el.getValue() instanceof OperatingPeriod p) {
                        operatingPeriods.put(p.getId(), p);
                    }
                }
            }
        }
        for (Object o : db.iterOnlyObjects(txn, OperatingPeriod.class)) {
            OperatingPeriod p = (OperatingPeriod) o;
            operatingPeriods.put(p.getId(), p);
        }

        Map<String, OperatingDay> operatingDays = new LinkedHashMap<>();
        for (ServiceCalendar sc : serviceCalendars) {
            if (sc.getOperatingDays() != null) {
                for (Object entry : sc.getOperatingDays().getOperatingDayRefOrOperatingDay()) {
                    Object v = entry instanceof JAXBElement<?> je ? je.getValue() : entry;
                    if (v instanceof OperatingDay d) operatingDays.put(d.getId(), d);
                }
            }
        }
        for (Object o : db.iterOnlyObjects(txn, OperatingDay.class)) {
            OperatingDay d = (OperatingDay) o;
            operatingDays.put(d.getId(), d);
        }

        List<DayTypeAssignment> dayTypeAssignments = new ArrayList<>();
        for (ServiceCalendar sc : serviceCalendars) {
            if (sc.getDayTypeAssignments() != null) {
                dayTypeAssignments.addAll(sc.getDayTypeAssignments().getDayTypeAssignment());
            }
        }
        for (Object o : db.iterOnlyObjects(txn, DayTypeAssignment.class)) {
            dayTypeAssignments.add((DayTypeAssignment) o);
        }

        // Grouping only merges ADJACENT equal keys: sort by the grouping key first (List.sort is
        // stable).
        dayTypeAssignments.sort(Comparator
                .comparing((DayTypeAssignment d) -> nz(d.getDayTypeRef().getValue().getRef()))
                .thenComparing(d -> nz(d.getDayTypeRef().getValue().getVersion())));

        List<Object> out = new ArrayList<>();

        int i = 0;
        int n = dayTypeAssignments.size();
        while (i < n) {
            // Group boundary: an adjacent run of equal dayTypeRef keys. The beans have no
            // equals(), so the key is (ref, version, nameOfRefClass).
            DayTypeRefStructure dayTypeRef = dayTypeAssignments.get(i).getDayTypeRef().getValue();
            List<DayTypeAssignment> t = new ArrayList<>();
            t.add(dayTypeAssignments.get(i));
            int j = i + 1;
            while (j < n && sameGroupKey(dayTypeRef, dayTypeAssignments.get(j).getDayTypeRef().getValue())) {
                t.add(dayTypeAssignments.get(j));
                j++;
            }
            i = j;

            DayType myDayType = dayTypes.get(dayTypeRef.getRef());
            if (myDayType == null) {
                // A ref whose DayType is not loaded must not abort the whole calendar.
                logOnce("dangling-day-type-ref",
                        "DayTypeAssignment(s) reference DayType " + dayTypeRef.getRef()
                                + " which is not loaded; " + t.size()
                                + " assignment(s) skipped (further occurrences suppressed)");
                continue;
            }

            for (DayTypeAssignment dta : t) {
                String rid = unionRefId(dta);
                if (rid != null && !uicOperatingPeriods.containsKey(rid)
                        && !operatingPeriods.containsKey(rid) && !operatingDays.containsKey(rid)) {
                    // A period ref whose target is in none of the loaded indexes is dropped by the
                    // id-based matching below.
                    logOnce("dangling-operating-period-ref",
                            "DayTypeAssignment " + dta.getId() + ": operating period " + rid
                                    + " not loaded; assignment ignored (further occurrences suppressed)");
                }
            }

            // A ref counts as a UIC operating period when its target is one of the loaded
            // UicOperatingPeriods. Matching by target id — rather than the ref's element type or
            // its nameOfRefClass — also covers a feed whose <OperatingPeriodRef> carries a
            // NameOfClass that is not "UicOperatingPeriod".
            List<UicOperatingPeriod> myUicOperatingPeriods = new ArrayList<>();
            for (DayTypeAssignment dta : t) {
                String rid = unionRefId(dta);
                if (rid != null && uicOperatingPeriods.containsKey(rid)) {
                    myUicOperatingPeriods.add(uicOperatingPeriods.get(rid));
                }
            }

            // OperatingPeriodRef and UicOperatingPeriodRef are SIBLING elements over one structure;
            // the binding funnels both into getOperatingPeriodRef(), so the JAXBElement's element
            // name takes over the discrimination.
            List<OperatingPeriod> myOperatingPeriods = new ArrayList<>();
            for (DayTypeAssignment dta : t) {
                JAXBElement<OperatingPeriodRefStructure> el = dta.getOperatingPeriodRef();
                if (el != null
                        && "OperatingPeriodRef".equals(el.getName().getLocalPart())
                        && (el.getValue().getNameOfRefClass() == null
                                || "OperatingPeriod".equals(el.getValue().getNameOfRefClass()))
                        && (dta.isIsAvailable() == null || dta.isIsAvailable())
                        && operatingPeriods.containsKey(el.getValue().getRef())) {
                    myOperatingPeriods.add(operatingPeriods.get(el.getValue().getRef()));
                }
            }

            // The BARE-DATE arm of the 4-way union. getDate() is an xs:date, getFromDate() an
            // xs:dateTime, and both arrive in the SAME LinkedHashSet below — which is why
            // netex-model binds the two schema types to one carrier.
            List<LocalDateTime> bareDates = new ArrayList<>();
            for (DayTypeAssignment dta : t) {
                if (dta.getDate() != null && (dta.isIsAvailable() == null || dta.isIsAvailable())) {
                    bareDates.add(dta.getDate().toLocalDateTime());
                }
            }

            LocalDateTime myFrom = null;
            LocalDateTime myTo = null;

            if (myUicOperatingPeriods.isEmpty()) {
                // The operational dates: a list before the properties branch, a set after it.
                Collection<LocalDateTime> myOperationalDates = bareDates;

                if (myDayType.getProperties() != null
                        && myDayType.getProperties().getPropertyOfDay().size() == 1) {
                    Set<DayOfWeek> byweekday = ServiceCalendarEpip.mapDaysOfWeekToByWeekday(
                            myDayType.getProperties().getPropertyOfDay().get(0).getDaysOfWeek());

                    for (OperatingPeriod op : myOperatingPeriods) {
                        // NORMALISATION BOUNDARY: everything from here down is LocalDateTime, so
                        // the min/max comparisons, the rrule walk and the Set membership behave.
                        LocalDateTime opFrom = op.getFromDate().toLocalDateTime();
                        LocalDateTime opTo = op.getToDate().toLocalDateTime();
                        if (myFrom == null || myFrom.isAfter(opFrom)) {
                            myFrom = opFrom;
                        }
                        if (myTo == null || myTo.isBefore(opTo)) {
                            myTo = opTo;
                        }
                        myOperationalDates.addAll(
                                ServiceCalendarEpip.rruleDaily(byweekday, opFrom, opTo));
                    }

                    Set<LocalDateTime> dateSet = new LinkedHashSet<>(myOperationalDates);
                    myOperationalDates = dateSet;
                    for (DayTypeAssignment dta : t) {
                        if (dta.getOperatingDayRef() != null) {
                            // A missing OperatingDay is a hard failure; the NPE here is that crash.
                            LocalDateTime dt = operatingDays
                                    .get(dta.getOperatingDayRef().getRef())
                                    .getCalendarDate().toLocalDateTime();
                            if (dta.isIsAvailable() == null || dta.isIsAvailable()) {
                                dateSet.add(dt);
                            } else {
                                dateSet.remove(dt);   // absent is fine
                            }
                        } else if (dta.getDate() != null) {
                            LocalDateTime dt = dta.getDate().toLocalDateTime();
                            if (dta.isIsAvailable() == null || dta.isIsAvailable()) {
                                dateSet.add(dt);
                            } else {
                                dateSet.remove(dt);
                            }
                        }
                    }
                }

                if (!myOperationalDates.isEmpty()) {
                    // Every date on this path is already a LocalDateTime.
                    List<LocalDateTime> validDays = new ArrayList<>(myOperationalDates);
                    Collections.sort(validDays);
                    if (myFrom == null) myFrom = validDays.get(0);
                    if (myTo == null) myTo = validDays.get(validDays.size() - 1);

                    Set<LocalDateTime> members = new HashSet<>(validDays);
                    long span = ChronoUnit.DAYS.between(myFrom, myTo);
                    StringBuilder validDayBits = new StringBuilder();
                    for (long d = 0; d <= span; d++) {
                        validDayBits.append(members.contains(myFrom.plusDays(d)) ? '1' : '0');
                    }

                    UicOperatingPeriod uicOperatingPeriod = new UicOperatingPeriod();
                    uicOperatingPeriod.setId(
                            dayTypeRef.getRef().replace(":DayType:", ":UicOperatingPeriod:"));
                    uicOperatingPeriod.setVersion(dayTypeRef.getVersion());
                    uicOperatingPeriod.setValidDayBits(validDayBits.toString());
                    // SYNTHESIS SITE, not a copy, and the distinction is a byte diff. myFrom and
                    // myTo are computed values, so the carrier is built with from(), which retains
                    // NO lexical and re-emits canonically. Copying a parsed carrier through instead
                    // would re-emit whatever the SOURCE feed wrote.
                    uicOperatingPeriod.setFromDate(XmlDateTime.from(myFrom));
                    uicOperatingPeriod.setToDate(XmlDateTime.from(myTo));

                    // id rewrite :DayType: -> :DayTypeAssignment:.
                    DayTypeAssignment resDta = NetexUtils.project(myDayType, DayTypeAssignment.class);
                    // project() copies JAXB's DEFAULTED getters into real fields, so the
                    // synthesized assignment HOLDS modification=NEW status=ACTIVE
                    // publication=PUBLIC — three attributes that must never reach the wire. The
                    // omission is enforced at marshal time instead (toolkit/codec/SchemaDefaults).
                    resDta.setOrder(BigInteger.ONE);
                    resDta.setIsAvailable(Boolean.TRUE);
                    resDta.setDayTypeRef(t.get(0).getDayTypeRef());   // the groupby key object
                    // A ref to the UicOperatingPeriod: an OperatingPeriodRef element with
                    // nameOfRefClass="UicOperatingPeriod" (see NetexUtils.getRef).
                    resDta.setOperatingPeriodRef(FACTORY.createOperatingPeriodRef(
                            NetexUtils.getRef(uicOperatingPeriod, OperatingPeriodRefStructure.class)));

                    out.add(resDta);
                    out.add(uicOperatingPeriod);
                } else {
                    Log.warn("There are no operatingdays for DayType %s", myDayType.getId());
                }
            } else {
                out.addAll(t);
                out.addAll(myUicOperatingPeriods);
            }
            out.add(myDayType);
        }

        return out.iterator();
    }

    /// The `ref` of the 4-way union value: the two ref arms have one, the bare-date arm (and an
    /// absent union) yields null.
    private static String unionRefId(DayTypeAssignment dta) {
        if (dta.getOperatingPeriodRef() != null) {
            return dta.getOperatingPeriodRef().getValue().getRef();
        }
        if (dta.getOperatingDayRef() != null) {
            return dta.getOperatingDayRef().getRef();
        }
        return null;
    }

    private static boolean sameGroupKey(DayTypeRefStructure a, DayTypeRefStructure b) {
        return Objects.equals(a.getRef(), b.getRef())
                && Objects.equals(a.getVersion(), b.getVersion())
                && Objects.equals(a.getNameOfRefClass(), b.getNameOfRefClass());
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }
}
