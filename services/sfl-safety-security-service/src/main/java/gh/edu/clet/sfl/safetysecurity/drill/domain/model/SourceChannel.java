package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

/** How a change reached this module - own copy per module, same convention as visitor/incident/lifesafety/riskassessment. */
public enum SourceChannel {
    WEB, API, SYSTEM, INTEGRATION
}
