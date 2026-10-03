package gh.edu.clet.sfl.facilities.waste.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A unit of measure and what one of it weighs. Configuration, so a new unit is a row, not a release. */
public record WasteUnit(String code, String name, BigDecimal kilogramsPerUnit) {

    /** Normalised kilograms to four places; the original quantity and unit are kept by the caller. */
    public BigDecimal toKilograms(BigDecimal quantity) {
        return quantity.multiply(kilogramsPerUnit).setScale(4, RoundingMode.HALF_UP);
    }
}
