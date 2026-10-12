package dev.aies.control.team;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
/** Planning endpoint only. No implicit permission to execute model calls or tools. */
@Path("/api/v1/teams")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TeamPlanningResource {
 @Inject TeamPlanner planner;
 public record Request(String objective) {}
 @POST @Path("/plan") public TeamPlan plan(Request request) {
  if(request==null) throw new BadRequestException("Missing request");
  try { return planner.plan(request.objective()); } catch(IllegalArgumentException e) {throw new BadRequestException(e.getMessage());}
 }
}
