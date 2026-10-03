package gh.edu.clet.sfl.facilities.lostfound.application.ports;

/** The S152 estate register, read-only: is this a real site. */
public interface LostFoundEstatePort {

    boolean siteExists(String siteCode);
}
