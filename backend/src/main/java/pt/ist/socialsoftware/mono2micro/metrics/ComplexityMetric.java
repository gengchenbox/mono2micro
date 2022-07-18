package pt.ist.socialsoftware.mono2micro.metrics;

import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import pt.ist.socialsoftware.mono2micro.decomposition.domain.accessesSciPy.Cluster;
import pt.ist.socialsoftware.mono2micro.functionality.domain.Functionality;
import pt.ist.socialsoftware.mono2micro.functionality.domain.FunctionalityRedesign;
import pt.ist.socialsoftware.mono2micro.functionality.domain.LocalTransaction;
import pt.ist.socialsoftware.mono2micro.decomposition.domain.AccessesSciPyDecomposition;
import pt.ist.socialsoftware.mono2micro.decomposition.domain.Decomposition;
import pt.ist.socialsoftware.mono2micro.strategy.domain.AccessesSciPyStrategy;
import pt.ist.socialsoftware.mono2micro.functionality.dto.AccessDto;
import pt.ist.socialsoftware.mono2micro.utils.Utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class ComplexityMetric extends Metric<Float> {
    public String getType() {
        return MetricType.COMPLEXITY;
    }

    // Decomposition Metric
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

        float complexity;

        // Set cluster complexity
        for (Cluster cluster : decomposition.getClusters().values()) {
            List<Functionality> functionalitiesThatAccessThisCluster = clustersFunctionalities.get(cluster.getName());

            complexity = 0;

            for (Functionality functionality : functionalitiesThatAccessThisCluster) {
                ComplexityMetric complexityMetric = (ComplexityMetric) functionality.searchMetricByType(MetricType.COMPLEXITY);
                complexity += complexityMetric.getValue();
            }

            complexity /= functionalitiesThatAccessThisCluster.size();
            complexity = BigDecimal.valueOf(complexity).setScale(2, RoundingMode.HALF_UP).floatValue();

            cluster.setComplexity(complexity);
        }

        // Return overall complexity
        complexity = 0;

        for (Functionality functionality : decomposition.getFunctionalities().values()) {
            ComplexityMetric complexityMetric = (ComplexityMetric) functionality.searchMetricByType(MetricType.COMPLEXITY);
            complexity += complexityMetric.getValue();
        }

        return BigDecimal.valueOf(complexity / decomposition.getFunctionalities().size())
                .setScale(2, RoundingMode.HALF_UP)
                .floatValue();
    }



    // Functionality Metric
    public void calculateMetric(Decomposition decomposition, Functionality functionality) {

        AccessesSciPyDecomposition accessesSciPyDecomposition = (AccessesSciPyDecomposition) decomposition;

        // Since metric calculation is always done during the creation of the functionalities, we can use createLocalTransactionGraph,
        // otherwise, if traces == null, use createLocalTransactionGraphFromScratch
        DirectedAcyclicGraph<LocalTransaction, DefaultEdge> localTransactionsGraph = functionality.createLocalTransactionGraph(accessesSciPyDecomposition.getEntityIDToClusterName());

        this.value = calculateFunctionalityComplexity(
                accessesSciPyDecomposition,
                functionality.getName(),
                Utils.getFunctionalitiesClusters(
                        accessesSciPyDecomposition.getEntityIDToClusterName(),
                        accessesSciPyDecomposition.getClusters(),
                        accessesSciPyDecomposition.getFunctionalities().values()),
                        localTransactionsGraph);
    }

    private static float calculateFunctionalityComplexity(
            AccessesSciPyDecomposition decomposition,
            String functionalityName,
            Map<String, Set<Cluster>> functionalityClusters,
            DirectedAcyclicGraph<LocalTransaction, DefaultEdge> localTransactionsGraph
    ) {
        Set<LocalTransaction> allLocalTransactions = localTransactionsGraph.vertexSet();

        if (functionalityClusters.get(functionalityName).size() == 1) {
            return 0;

        } else {
            // < entity + mode, List<functionalityName>> functionalitiesThatTouchSameEntities for a given mode
            Map<String, List<String>> cache = new HashMap<>();

            float functionalityComplexity = 0;

            for (LocalTransaction lt : allLocalTransactions) {
                // ClusterDependencies
                String clusterName = lt.getClusterName();
                if (!clusterName.equals("-1")) { // not root node

                    Set<String> functionalitiesThatTouchSameEntities = new HashSet<>();
                    Set<AccessDto> clusterAccesses = lt.getClusterAccesses();

                    for (AccessDto a : clusterAccesses) {
                        short entityID = a.getEntityID();
                        byte mode = a.getMode();

                        String key = String.join("-", String.valueOf(entityID), String.valueOf(mode));
                        List<String> functionalitiesThatTouchThisEntityAndMode = cache.get(key);

                        if (functionalitiesThatTouchThisEntityAndMode == null) {
                            functionalitiesThatTouchThisEntityAndMode = costOfAccess(
                                    functionalityName,
                                    entityID,
                                    mode,
                                    decomposition.getFunctionalities().values(),
                                    functionalityClusters
                            );

                            cache.put(key, functionalitiesThatTouchThisEntityAndMode);
                        }

                        functionalitiesThatTouchSameEntities.addAll(functionalitiesThatTouchThisEntityAndMode);
                    }

                    functionalityComplexity += functionalitiesThatTouchSameEntities.size();
                }
            }

            return functionalityComplexity;
        }
    }

    private static List<String> costOfAccess(
            String functionalityName,
            short entityID,
            byte mode,
            Collection<Functionality> functionalities,
            Map<String, Set<Cluster>> functionalityClusters
    ) {
        List<String> functionalitiesThatTouchThisEntityAndMode = new ArrayList<>();

        for (Functionality otherFunctionality : functionalities) {
            String otherFunctionalityName = otherFunctionality.getName();

            if (!otherFunctionalityName.equals(functionalityName) && functionalityClusters.containsKey(otherFunctionalityName)) {
                Byte savedMode = otherFunctionality.getEntities().get(entityID);

                if (
                        savedMode != null &&
                                savedMode != mode &&
                                functionalityClusters.get(otherFunctionalityName).size() > 1
                ) {
                    functionalitiesThatTouchThisEntityAndMode.add(otherFunctionalityName);
                }
            }
        }

        return functionalitiesThatTouchThisEntityAndMode;
    }

    public void calculateMetric(Decomposition decomposition, Functionality functionality, FunctionalityRedesign functionalityRedesign) {}
}
