package toolkit.transform.epip;

// The statics of the EPIP service-calendar frame: mapDayOfWeekToWeekday, mapDaysOfWeekToByWeekday,
// flattenAvailabilityCondition, positiveAvailabilityCondition, validDaysToBits and rruleDaily.
//
// AvailabilityCondition.getFromDate()/getToDate() are noi.netex.time.XmlDateTime carriers, and this
// file is why the carrier forbids hashing: flatten() unions dates into a Set whose cardinality
// decides validDaysToBits. Two carriers naming the same day can differ in lexical form and in offset
// presence, so a Set of carriers would silently produce a different bit string. Every carrier is
// normalised with toLocalDateTime() at the boundary — the two call sites below.

import noi.netex.model.AvailabilityCondition;
import noi.netex.model.DayOfWeekEnumeration;
import noi.netex.model.DayType;
import noi.netex.model.DayTypes_RelStructure;
import toolkit.util.Log;

import jakarta.xml.bind.JAXBElement;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ServiceCalendarEpip {

    private ServiceCalendarEpip() {}

    /// Warn-once: a message prints only on its FIRST occurrence per process. The double
    /// `flattenAvailabilityCondition` call in [#positiveAvailabilityCondition] would otherwise print
    /// the multiple-DayTypes warning twice.
    private static final Set<String> WARNED = new HashSet<>();

    private static synchronized void warnOnce(String message) {
        if (WARNED.add(message)) Log.warn("%s", message);
    }

    /// The weekday constants, or `null` — the sentinel for EVERYDAY and for any unmatched value,
    /// checked by [#mapDaysOfWeekToByWeekday].
    public static List<DayOfWeek> mapDayOfWeekToWeekday(DayOfWeekEnumeration dayOfWeek) {
        if (dayOfWeek == null) return null;
        return switch (dayOfWeek) {
            case EVERYDAY -> null;
            case MONDAY -> List.of(DayOfWeek.MONDAY);
            case TUESDAY -> List.of(DayOfWeek.TUESDAY);
            case WEDNESDAY -> List.of(DayOfWeek.WEDNESDAY);
            case THURSDAY -> List.of(DayOfWeek.THURSDAY);
            case FRIDAY -> List.of(DayOfWeek.FRIDAY);
            case SATURDAY -> List.of(DayOfWeek.SATURDAY);
            case SUNDAY -> List.of(DayOfWeek.SUNDAY);
            // Deprecated options (locale conflicts) — kept.
            case WEEKDAYS -> List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
            case WEEKEND -> List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            default -> null;                                      // e.g. NONE
        };
    }

    /// Sentinel semantics: hitting EVERYDAY (or any unmatched value) returns an EMPTY collection,
    /// which downstream (rrule byweekday) means NO weekday filter, i.e. every day matches; it does
    /// NOT mean "no days". An empty input likewise yields the empty no-filter set.
    public static Set<DayOfWeek> mapDaysOfWeekToByWeekday(List<DayOfWeekEnumeration> daysOfWeek) {
        Set<DayOfWeek> byweekday = new LinkedHashSet<>();
        for (DayOfWeekEnumeration dayOfWeek : daysOfWeek) {
            List<DayOfWeek> mapping = mapDayOfWeekToWeekday(dayOfWeek);
            if (mapping == null) {
                return Set.of();
            }
            byweekday.addAll(mapping);
        }
        return byweekday;
    }

    /// The operational dates, days of week and availability flag returned by
    /// [#flattenAvailabilityCondition]; `null` for the unknown-limitation case.
    public record Flattened(Set<LocalDateTime> operationalDates,
                            List<DayOfWeekEnumeration> daysOfWeek,
                            boolean available) {}

    /// Flatten one AvailabilityCondition into a set of dates. Two arms: no DayType -> ValidDayBits
    /// expansion from FromDate; DayType(s) -> daily rrule between FromDate and ToDate filtered by
    /// the first DayType's first PropertyOfDay DaysOfWeek. (A first entry that is a DayTypeRef
    /// rather than an inline DayType raises a ClassCastException.)
    public static Flattened flattenAvailabilityCondition(AvailabilityCondition ac) {
        List<DayOfWeekEnumeration> daysOfWeek = List.of();
        Collection<LocalDateTime> operationalDates;

        DayTypes_RelStructure dayTypes = ac.getDayTypes();
        List<JAXBElement<?>> dayTypeList =
                dayTypes != null ? dayTypes.getDayTypeRefOrDayType_() : null;

        if (dayTypeList != null && dayTypeList.size() > 1) {
            warnOnce("AvailabilityCondition " + ac.getId() + " has multiple DayTypes, we cannot "
                    + "handle this (yet), as we don't know the intention. Only the first DayType "
                    + "is being evaluated.");
        }

        if (dayTypeList == null || dayTypeList.isEmpty()) {
            String bits = ac.getValidDayBits();
            if (bits != null && !bits.isEmpty() && ac.getFromDate() != null) {
                operationalDates = new ArrayList<>();
                // Normalisation boundary: the carrier to a LocalDateTime, offset dropped.
                LocalDateTime from = ac.getFromDate().toLocalDateTime();
                for (int i = 0; i < bits.length(); i++) {
                    if (bits.charAt(i) == '1') operationalDates.add(from.plusDays(i));
                }
            } else {
                warnOnce("AvailabilityCondition " + ac.getId() + " is using an unknown date limitation.");
                return null;
            }
        } else {
            DayType dayType = (DayType) dayTypeList.get(0).getValue();
            daysOfWeek = dayType.getProperties().getPropertyOfDay().get(0).getDaysOfWeek();
            Set<DayOfWeek> byweekday = mapDaysOfWeekToByWeekday(daysOfWeek);
            // Normalisation boundary. rruleDaily keeps its LocalDateTime signature: the walk is
            // calendar arithmetic and must not see a carrier.
            operationalDates = rruleDaily(byweekday,
                    ac.getFromDate().toLocalDateTime(), ac.getToDate().toLocalDateTime());
        }

        // KNOWN BUG: the availability flag returned here is ALWAYS true, so an
        // AvailabilityCondition marked UNAVAILABLE is still reported available and negative
        // conditions never subtract dates in positiveAvailabilityCondition.
        return new Flattened(new LinkedHashSet<>(operationalDates), daysOfWeek, true);
    }

    /// The sorted positive dates and days of week of [#positiveAvailabilityCondition].
    public record Positive(List<LocalDateTime> validDays, List<DayOfWeekEnumeration> daysOfWeek) {}

    /// Combine flattened conditions into one positive sorted date list. The condition is flattened
    /// twice — once for the null check, again to destructure — and the always-available bug above
    /// makes the negative branch dead. The days-of-week list is the LAST flattened condition's.
    public static Positive positiveAvailabilityCondition(List<AvailabilityCondition> acs) {
        Set<LocalDateTime> positiveDates = new HashSet<>();
        Set<LocalDateTime> negativeDates = new HashSet<>();
        List<DayOfWeekEnumeration> daysOfWeek = List.of();

        for (AvailabilityCondition ac : acs) {
            Flattened output = flattenAvailabilityCondition(ac);
            if (output != null) {
                Flattened f = flattenAvailabilityCondition(ac);
                daysOfWeek = f.daysOfWeek();
                if (f.available()) {
                    positiveDates.addAll(f.operationalDates());
                } else {
                    negativeDates.addAll(f.operationalDates());   // unreachable (bug above)
                }
            }
        }

        if (!negativeDates.isEmpty()) {
            positiveDates.removeAll(negativeDates);
        }

        List<LocalDateTime> sorted = new ArrayList<>(positiveDates);
        Collections.sort(sorted);
        return new Positive(sorted, daysOfWeek);
    }

    /// The '1'/'0' day-bit string spanning first..last valid day inclusive; membership is by
    /// datetime equality.
    public static String validDaysToBits(List<LocalDateTime> validDays) {
        List<LocalDateTime> sorted = new ArrayList<>(validDays);
        Collections.sort(sorted);
        Set<LocalDateTime> members = new HashSet<>(sorted);
        LocalDateTime first = sorted.get(0);
        LocalDateTime last = sorted.get(sorted.size() - 1);
        long days = ChronoUnit.DAYS.between(first, last);
        StringBuilder sb = new StringBuilder();
        for (long i = 0; i <= days; i++) {
            sb.append(members.contains(first.plusDays(i)) ? '1' : '0');
        }
        return sb.toString();
    }

    /// Every day from dtstart to until inclusive (time-of-day carried from dtstart, until compared
    /// exactly), keeping only days whose weekday is in `byweekday` — unless `byweekday` is EMPTY,
    /// which means no filter.
    public static List<LocalDateTime> rruleDaily(Set<DayOfWeek> byweekday,
                                                 LocalDateTime dtstart, LocalDateTime until) {
        List<LocalDateTime> out = new ArrayList<>();
        for (LocalDateTime d = dtstart; !d.isAfter(until); d = d.plusDays(1)) {
            if (byweekday.isEmpty() || byweekday.contains(d.getDayOfWeek())) {
                out.add(d);
            }
        }
        return out;
    }
}
