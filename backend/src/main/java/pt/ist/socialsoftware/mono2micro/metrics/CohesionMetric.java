package pt.ist.socialsoftware.mono2micro.metrics;

import pt.ist.socialsoftware.mono2micro.decomposition.domain.accessesSciPy.Cluster;
import pt.ist.socialsoftware.mono2micro.functionality.domain.Functionality;
import pt.ist.socialsoftware.mono2micro.functionality.domain.FunctionalityRedesign;
import pt.ist.socialsoftware.mono2micro.decomposition.domain.AccessesSciPyDecomposition;
import pt.ist.socialsoftware.mono2micro.decomposition.domain.Decomposition;
import pt.ist.socialsoftware.mono2micro.strategy.domain.AccessesSciPyStrategy;
import pt.ist.socialsoftware.mono2micro.utils.Utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CohesionMetric extends Metric<Float> {
    public String getType() {
        return MetricType.COHESION;
    }

    public void calculateMetric(Decomposition decomposition) {
        switch (decomposition.getStrategyType()) {
            case AccessesSciPyStrategy.ACCESSES_SCIPY:
                this.value = calculateMetricAccessesSciPy((AccessesSciPyDecomposition) decomposition);
                break;
            default:
                throw new RuntimeException("Decomposition strategy '" + decomposition.getStrategyType() + "' not known.");
        }
    }

    private float calculateMetricAccessesSciPy(AccessesSciPyDecomposition decomposition) {
        Map<String, List<Functionality>> clustersFunctionalities = Utils.getClustersFunctionalities(
                decomposition.getEntityIDToClusterName(),
                decomposition.getClusters(),
                decomposition.getFunctionalities().values()
        );

        float totalCohesion = 0;

        for (Cluster cluster : decomposition.getClusters().values()) {
            List<Functionality> FunctionalitiesThatAccessThisCluster = clustersFunctionalities.get(cluster.getName());

            float clusterCohesion = 0;

            for (Functionality functionality : FunctionalitiesThatAccessThisCluster) {
                float numberEntitiesTouched = 0;

                Set<Short> functionalityEntities = functionality.getEntities().keySet();

                for (short entityID : functionalityEntities)
                    if (cluster.containsEntity(entityID))
                        numberEntitiesTouched++;

                clusterCohesion += numberEntitiesTouched / cluster.getEntities().size();
            }

            clusterCohesion /= FunctionalitiesThatAccessThisCluster.size();
            clusterCohesion = BigDecimal.valueOf(clusterCohesion).setScale(2, RoundingMode.HALF_UP).floatValue();
            cluster.setCohesion(clusterCohesion);
            totalCohesion += clusterCohesion;
        }

        int graphClustersAmount = decomposition.getClusters().size();

        return BigDecimal.valueOf(totalCohesion / graphClustersAmount)
                .setScale(2, RoundingMode.HALF_UP)
                .floatValue();
    }

    public void calculateMetric(Decomposition decomposition, Functionality functionality) {}

    public void calculateMetric(Decomposition decomposition, Functionality functionality, FunctionalityRedesign functionalityRedesign) {}
}