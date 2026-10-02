package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

/**
 * What kind of hazard - the dimension SRS-SFL-S165-03's hazard-frequency analytics rolls up.
 *
 * <p>A fixed list on purpose. Hazard frequency is only meaningful if two officers describing a fall from
 * a ladder count against the same category. (Activity types are the opposite case: S165-03 requires
 * those to come from what the platform actually uses, never a static list - see
 * {@link ObservedActivityType}.) {@link #OTHER} plus the hazard's own description covers what this list
 * does not name.
 */
public enum HazardType {
    WORKING_AT_HEIGHT,
    HOT_WORK_FIRE,
    ELECTRICAL,
    CONFINED_SPACE,
    MANUAL_HANDLING,
    SLIPS_TRIPS_FALLS,
    CROWD_OCCUPANCY,
    HAZARDOUS_SUBSTANCES,
    MACHINERY_EQUIPMENT,
    STRUCTURAL,
    VEHICLE_TRAFFIC,
    NOISE_VIBRATION,
    BIOLOGICAL,
    ENVIRONMENTAL,
    SECURITY_VIOLENCE,
    OTHER
}
