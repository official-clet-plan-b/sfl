package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

/** How a roll-call gap was followed up - SRS-SFL-S175-02: "Gaps are listed for immediate follow-up". */
public enum GapFollowUp {
    /** Found safe - reached another point, or checked in late. */
    ACCOUNTED_FOR,
    /** Not actually on site - the baseline was wrong (left without badging out, or a stale record). */
    NOT_ON_SITE,
    /** Never accounted for during the drill. In a real evacuation this is the person a search team looks for. */
    UNACCOUNTED
}
