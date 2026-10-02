package gh.edu.clet.sfl.safetysecurity.drill.domain.event;

/** S175 integration events, canonical {@code sfl.ssemp.{name}.v1}. Catalogued in {@code docs/integration/event-catalog.md}. */
public enum DrillEventType {

    DRILL_SCHEDULED("sfl.ssemp.drill-scheduled.v1"),
    DRILL_STARTED("sfl.ssemp.drill-started.v1"),
    DRILL_COMPLETED("sfl.ssemp.drill-completed.v1"),
    DRILL_CLOSED("sfl.ssemp.drill-closed.v1"),
    DRILL_COMPLIANCE_GAP("sfl.ssemp.drill-compliance-gap.v1");

    public static final String AGGREGATE_TYPE = "Drill";
    public static final String REQUIREMENT_AGGREGATE_TYPE = "DrillFrequencyRequirement";

    private final String eventType;

    DrillEventType(String eventType) {
        this.eventType = eventType;
    }

    public String eventType() {
        return eventType;
    }

    public int version() {
        return Integer.parseInt(eventType.substring(eventType.lastIndexOf(".v") + 2));
    }
}
