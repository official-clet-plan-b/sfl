package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.security.SecureRandom;

/**
 * The reference a claimant is given. Random and unrelated to the item: it carries no category, date, site or
 * sequence number, so it reveals nothing about what was found and cannot be guessed from the one before it.
 * The alphabet leaves out characters that are easy to misread aloud or in handwriting.
 */
public final class ClaimReference {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ClaimReference() {
    }

    public static String generate() {
        StringBuilder builder = new StringBuilder("LF-");
        for (int i = 0; i < 8; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
