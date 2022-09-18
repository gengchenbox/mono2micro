package pt.ist.socialsoftware.mono2micro.metrics.metricService;

import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.springframework.stereotype.Service;
import pt.ist.socialsoftware.mono2micro.cluster.AccessesSciPyCluster;
import pt.ist.socialsoftware.mono2micro.cluster.Cluster;
import pt.ist.socialsoftware.mono2micro.functionality.domain.Functionality;
import pt.ist.socialsoftware.mono2micro.functionality.domain.LocalTransaction;
import pt.ist.socialsoftware.mono2micro.decomposition.domain.AccessesSciPyDecomposition;
import pt.ist.socialsoftware.mono2micro.functionality.dto.AccessDto;
import pt.ist.socialsoftware.mono2micro.utils.Utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class ComplexityMetricService {
    public Double calculateMetric(AccessesSciPyDecomposition decomposition, Map<String, List<Functionality>> clustersFunctionalities) {
        double complexity;

        // Set cluster complexity
        for (Cluster c : decomposition.getClusters().values()) {
            AccessesSciPyCluster cluster = (AccessesSciPyCluster) c;
            List<Functionality> functionalitiesThatAccessThisCluster = clustersFunctionalities.get(cluster.getName());

            complexity = 0;

            for (Functionality functionality : functionalitiesThatAccessThisCluster) {
                Object complexityMetric = functionality.getMetric(MetricType.COMPLEXITY);
                complexity += (Double) complexityMetric;
            }

            complexity /= functionalitiesThatAccessThisCluster.size();
            complexity = BigDecimal.valueOf(complexity).setScale(2, RoundingMode.HALF_UP).doubleValue();

            cluster.setComplexity(complexity);
        }

        // Return overall complexity
        complexity = 0;

        for (Functionality functionality : decomposition.getFunctionalities().values()) {
            Double complexityMetric = (Double) functionality.getMetric(MetricType.COMPLEXITY);
            complexity += complexityMetric;
        }

        return BigDecimal.valueOf(complexity / decomposition.getFunctionalities().size())
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public Double calculateMetric(AccessesSciPyDecomposition decomposition, Functionality functionality) {
        double value;

        // Since metric calculation is always done during the creation of the functionalities, we can use createLocalTransactionGraph,
        // otherwise, if traces == null, use createLocalTransactionGraphFromScratch
        DirectedAcyclicGraph<LocalTransaction, DefaultEdge> localTransactionsGraph = functionality.createLocalTransactionGraph(decomposition.getEntityIDToClusterName());

        Map<String, Set<Cluster>> functionalityClusters = Utils.getFunctionalitiesClusters(
                decomposition.getEntityIDToClusterName(),
                decomposition.getClusters(),
                decomposition.getFunctionalities().values());

        Set<LocalTransaction> allLocalTransactions = localTransactionsGraph.vertexSet();

        if (functionalityClusters.get(functionality.getName()).size() == 1) {
            value = 0F;
        } else {
            // < entity + mode, List<functionalityName>> functionalitiesThatTouchSameEntities for a given mode
            Map<String, List<String>> cache = new HashMap<>();

            double functionalityComplexity = 0;

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
                                    functionality.getName(),
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
            value = functionalityComplexity;
        }

        return value;
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
}
