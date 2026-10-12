package dev.aies.control.team;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;
/** Deterministic, explainable initial planner. Human/LLM planning can replace this behind the same contract. */
@ApplicationScoped
public class TeamPlanner {
 public TeamPlan plan(String objective) {
  if (objective == null || objective.isBlank() || objective.length()>4000) throw new IllegalArgumentException("Objective must contain 1..4000 characters");
  String q=objective.toLowerCase(Locale.ROOT);
  List<TeamPlan.Step> steps=new ArrayList<>();
  if (q.matches("(?s).*(mercado|negocio|monetiz|rentabil|comercial|clientes|colegios|economía|economia).*")) {
   steps.add(step("market", "market-researcher", objective+"; investigar demanda, competencia y fuentes", Set.of(), Set.of("acceptance-reviewer")));
   steps.add(step("geo", "geography-analyst", objective+"; analizar segmentación territorial", Set.of(), Set.of("acceptance-reviewer")));
   steps.add(step("econ", "economist", objective+"; analizar viabilidad económica", Set.of(), Set.of("acceptance-reviewer")));
   steps.add(step("stats", "statistician", "Validar evidencia y supuestos cuantitativos", Set.of("market","geo","econ"), Set.of("acceptance-reviewer")));
   steps.add(step("growth", "growth-strategist", "Consolidar estrategia comercial con fuentes y riesgos", Set.of("stats"), Set.of("acceptance-reviewer")));
  } else if(q.matches("(?s).*(implement|program|código|codigo|api|software|quarkus|java|backend).*")) {
   steps.add(step("architecture", "architect", objective, Set.of(), Set.of("feasibility-reviewer")));
   steps.add(step("implementation", "backend-developer", objective, Set.of("architecture"), Set.of("architect","qa-engineer","security-engineer")));
   steps.add(step("qa", "qa-engineer", "Validar criterios de aceptación y pruebas", Set.of("implementation"), Set.of("acceptance-reviewer")));
  } else {
   steps.add(step("analysis", "planner", objective, Set.of(), Set.of("acceptance-reviewer")));
  }
  return new TeamPlan(objective, steps);
 }
 private TeamPlan.Step step(String id,String specialist,String objective,Set<String> dependencies,Set<String> reviewers) {return new TeamPlan.Step(id,specialist,objective,dependencies,reviewers);}
}
