package dev.aies.control.routing;

import dev.aies.control.prompts.SpecialistCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Explainable deterministic pre-router. Selection grants no execution or tool permissions. */
@ApplicationScoped
public class CapabilityRouter {
    private static final int MAX_SPECIALISTS = 6;
    private static final Map<String,List<String>> TERMS = Map.ofEntries(
        Map.entry("market-researcher",List.of("mercado","market","demanda","cliente","segmento","competidor","oportunidad","negocio","validacion comercial")),
        Map.entry("growth-strategist",List.of("monetizacion","rentabilidad","ganancia","ingreso","suscripcion","pricing","precio","vender","saas","conversion","crecimiento")),
        Map.entry("geography-analyst",List.of("geografia","territorio","region","comuna","ciudad","pais","ubicacion","localizacion","geoespacial","mapa")),
        Map.entry("geopolitical-analyst",List.of("politica","geopolitica","regulacion","gobierno","ley","normativa","comercio internacional","sanciones","riesgo pais")),
        Map.entry("economist",List.of("economia","economico","inflacion","competencia","oferta","demanda","elasticidad","macroeconomia")),
        Map.entry("finance-analyst",List.of("finanzas","financiero","inversion","presupuesto","flujo de caja","rentabilidad","margen","costos","ganancia")),
        Map.entry("statistician",List.of("estadistica","probabilidad","datos","muestra","experimento","intervalo de confianza","modelo","pronostico")),
        Map.entry("anthropologist",List.of("cultura","antropologia","comunidad","etnografia","comportamiento","apoderados","habitos","entrevista")),
        Map.entry("security-engineer",List.of("seguridad","privacidad","datos personales","compliance","proteccion de datos")),
        Map.entry("backend-developer",List.of("api","backend","quarkus","spring","java","servidor")),
        Map.entry("game-developer",List.of("videojuego","unreal","gameplay","multiplayer")),
        Map.entry("ux-ui-designer",List.of("ux","interfaz","usabilidad","accesibilidad"))
    );
    private static final Pattern MARK = Pattern.compile("[^a-z0-9 ]");
    private final SpecialistCatalog catalog;
    public CapabilityRouter(SpecialistCatalog catalog) { this.catalog=catalog; }
    public record Selection(String specialistId, int score, List<String> signals, List<String> reviewers) {}
    public record RoutingPlan(String objective, List<Selection> specialists, List<String> requiredReviews,
                              List<String> caveats, boolean executable) {}
    public RoutingPlan preview(String objective) {
        if (objective==null || objective.isBlank() || objective.length()>4000)
            throw new IllegalArgumentException("objective must contain 1-4000 characters");
        String normalized = " " + MARK.matcher(Normalizer.normalize(objective.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")).replaceAll(" ").replaceAll(" +", " ").trim() + " ";
        List<Selection> candidates=new ArrayList<>();
        for(var entry:TERMS.entrySet()) {
            if(!catalog.ids().contains(entry.getKey())) continue;
            List<String> hits=new ArrayList<>();
            for(String term:entry.getValue()) if(normalized.contains(" "+term+" ") || normalized.contains(" "+term+"s ")) hits.add(term);
            if(!hits.isEmpty()) {
                var specialist=catalog.get(entry.getKey());
                candidates.add(new Selection(entry.getKey(),hits.size(),List.copyOf(hits),specialist.reviewers()));
            }
        }
        candidates.sort(Comparator.comparingInt(Selection::score).reversed().thenComparing(Selection::specialistId));
        List<Selection> chosen=List.copyOf(candidates.subList(0,Math.min(MAX_SPECIALISTS,candidates.size())));
        LinkedHashSet<String> reviews=new LinkedHashSet<>();
        for(Selection s:chosen) for(String reviewer:s.reviewers()) if(catalog.ids().contains(reviewer) && !reviewer.equals(s.specialistId())) reviews.add(reviewer);
        List<String> caveats=new ArrayList<>(List.of("Preview determinista; no ejecuta agentes, herramientas ni consultas de mercado.","Los resultados comerciales requieren fuentes, fecha, revisión humana y validación independiente."));
        if(chosen.isEmpty()) caveats.add("Sin coincidencias: requiere clasificación adicional o aclaración de objetivo.");
        if(candidates.size()>MAX_SPECIALISTS) caveats.add("Selección limitada a seis especialistas; evaluar cobertura restante en planificación.");
        return new RoutingPlan(objective,chosen,List.copyOf(reviews),List.copyOf(caveats),false);
    }
}
