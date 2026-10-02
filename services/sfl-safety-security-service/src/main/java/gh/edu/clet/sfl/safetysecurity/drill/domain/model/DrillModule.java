package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

/**
 * The platform modules a combined drill can exercise - SRS-SFL-S175-05 names S162a, S160a, S166 and S171; the
 * notification (S174) and visitor (S160) paths every drill touches can be named too.
 */
public enum DrillModule {
    S160_VISITOR_MANAGEMENT,
    S160A_ACCESS_CONTROL,
    S162A_LIFE_SAFETY,
    S166_FLEET,
    S171_DISPATCH,
    S174_MASS_NOTIFICATION,
    OTHER
}
