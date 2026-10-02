package gh.edu.clet.sfl.safetysecurity.emergency.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.policy.AccessControlPermissionMatrix;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.policy.CctvPermissionMatrix;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.DrillPermissionMatrix;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.policy.EmergencyPermissionMatrix;
import gh.edu.clet.sfl.safetysecurity.incident.domain.policy.IncidentPermissionMatrix;
import gh.edu.clet.sfl.safetysecurity.intrusion.domain.policy.IntrusionPermissionMatrix;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.policy.LifeSafetyPermissionMatrix;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy.RiskAssessmentPermissionMatrix;
import gh.edu.clet.sfl.safetysecurity.visitor.domain.policy.VisitorPermissionMatrix;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the calling actor is permitted to do in S174.
 *
 * <p>The twin of the fleet service's route of the same name, and it exists for the same reason: the
 * dashboard needs to stop offering screens the actor cannot read, and could not derive that itself
 * without transcribing 103 permissions across 26 roles into TypeScript. It answers from the same
 * matrix the enforcement path uses, so the sidebar and the service cannot disagree.
 *
 * <p>Two routes rather than one because S174 is a separate deployable with its own matrix (ADR 0004).
 * The fleet service cannot answer for emergency and does not try; the dashboard asks both and merges.
 * That also means the emergency screens degrade honestly when this service is down: the dashboard
 * simply learns nothing about S174 permissions and stops narrowing, rather than hiding every emergency
 * screen from a coordinator who is entitled to all of them.
 *
 * <p><strong>Also answers for S160, S163, S160a, S161, S162, S162a, S165 and S175</strong> - every Phase 1 SSEMP
 * system, and S165 and S175 from Phase 2 (S164 joins this union when it is built).
 * Those modules share this deployable with S174 (one process, one port, {@code
 * services/README.md}'s "SFL.SSEMP" row), and the frontend's {@code actorPermissions.ts} already has
 * exactly one URL per platform, not per module - its own comment on the {@code SSEMP} entry says
 * "S174's matrix today, joined by S160-S163 as they are built." So this stays one route that unions
 * every SSEMP module's matrix, rather than each module adding its own {@code .../actor/permissions}
 * the way a separate deployable would.
 *
 * <p><strong>Authorised like any other API route.</strong> It is not on either security chain's
 * permit-all list, so in production it falls to {@code anyRequest().authenticated()} and answers for
 * the authenticated principal. Locally, where {@code sfl.security.enabled=false}, it is reachable on
 * the {@code X-SFL-*} headers exactly as every other endpoint is.
 *
 * <p>It grants nothing either way. Every endpoint authorises independently, so an actor who overstates
 * their roles gains no access - only a sidebar that offers screens the service will refuse. Once IAM
 * lands this becomes a claim on the token and the route can go.
 */
@RestController
@RequestMapping("/api/v1/emergency/actor")
@Tag(name = "Emergency Governance")
public class ActorPermissionsController {

    private final EmergencyActorResolver actors;

    ActorPermissionsController(EmergencyActorResolver actors) {
        this.actors = actors;
    }

    @GetMapping("/permissions")
    public ApiResponse<List<String>> permissions(HttpServletRequest request) {
        ActorContext actor = actors.resolve(request);
        Set<SflRole> roles = actor.principal().roles();

        return ApiResponse.ok(Arrays.stream(SflPermission.values())
                .filter(permission -> EmergencyPermissionMatrix.grants(roles, permission)
                        || VisitorPermissionMatrix.grants(roles, permission)
                        || IncidentPermissionMatrix.grants(roles, permission)
                        || AccessControlPermissionMatrix.grants(roles, permission)
                        || CctvPermissionMatrix.grants(roles, permission)
                        || IntrusionPermissionMatrix.grants(roles, permission)
                        || LifeSafetyPermissionMatrix.grants(roles, permission)
                        || RiskAssessmentPermissionMatrix.grants(roles, permission)
                        || DrillPermissionMatrix.grants(roles, permission))
                .map(Enum::name)
                .sorted()
                .toList());
    }
}
