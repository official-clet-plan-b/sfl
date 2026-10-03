package gh.edu.clet.sfl.facilities.catering.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CateringCheck(UUID id, String siteCode, UUID serviceId, UUID supplierId, CheckType checkType,
        HoldType holdType, BigDecimal temperatureC, CheckResult result, String notes, String checkedBy,
        Instant checkedAt) {
}
