package pt.ist.socialsoftware.mono2micro.domain.decomposition;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import pt.ist.socialsoftware.mono2micro.domain.Cluster;
import pt.ist.socialsoftware.mono2micro.domain.Functionality;
import pt.ist.socialsoftware.mono2micro.domain.FunctionalityRedesign;
import pt.ist.socialsoftware.mono2micro.domain.LocalTransaction;
import pt.ist.socialsoftware.mono2micro.domain.metrics.Metric;
import pt.ist.socialsoftware.mono2micro.domain.metrics.MetricFactory;
import pt.ist.socialsoftware.mono2micro.dto.TraceDto;
import pt.ist.socialsoftware.mono2micro.utils.Constants;
import pt.ist.socialsoftware.mono2micro.utils.FunctionalityTracesIterator;

import javax.management.openmbean.KeyAlreadyExistsException;
import java.util.*;
import java.util.stream.Collectors;

import static org.jgrapht.Graphs.successorListOf;
import static pt.ist.socialsoftware.mono2micro.domain.strategy.Strategy.StrategyType.ACCESSES_SCIPY;

public class AccessesSciPyDecomposition extends Decomposition {

    private static final String[] availableMetrics = {
            Metric.MetricType.COMPLEXITY,
            Metric.MetricType.PERFORMANCE,
            Metric.MetricType.COHESION,
            Metric.MetricType.COUPLING,
            Metric.MetricType.SILHOUETTE_SCORE
    };

    private boolean expert;

    private Map<Short, Cluster> clusters = new HashMap<>();

    private Map<String, Functionality> functionalities = new HashMap<>(); // <functionalityName, Functionality>

    private Map<Short, Short> entityIDToClusterID = new HashMap<>();

    @Override
    public String getStrategyType() {
        return ACCESSES_SCIPY;
    }

    public boolean isExpert() {
        return expert;
    }

    public void setExpert(boolean expert) {
        this.expert = expert;
    }

    public Map<Short, Short> getEntityIDToClusterID() {
        return entityIDToClusterID;
    }

    public void setEntityIDToClusterID(Map<Short, Short> entityIDToClusterID) { this.entityIDToClusterID = entityIDToClusterID; }

    public void putEntity(short entityID, Short clusterID) {
        entityIDToClusterID.put(entityID, clusterID);
    }

    public Map<Short, Cluster> getClusters() { return this.clusters; }

    public void setClusters(Map<Short, Cluster> clusters) { this.clusters = clusters; }

    public Map<String, Functionality> getFunctionalities() { return functionalities; }

    public void setFunctionalities(Map<String, Functionality> functionalities) { this.functionalities = functionalities; }

    public void addFunctionality(Functionality functionality) { this.functionalities.put(functionality.getName(), functionality); }

    public boolean functionalityExists(String functionalityName) { return this.functionalities.containsKey(functionalityName); }

    public Functionality getFunctionality(String functionalityName) {
        Functionality c = this.functionalities.get(functionalityName);

        if (c == null) throw new Error("Functionality with name: " + functionalityName + " not found");

        return c;
    }

    public boolean clusterNameExists(String clusterName) {
        for (Map.Entry<Short, Cluster> cluster :this.clusters.entrySet())
            if (cluster.getValue().getName().equals(clusterName))
                return true;
        return false;
    }

    public void addCluster(Cluster cluster) {
        Cluster c = this.clusters.putIfAbsent(cluster.getID(), cluster);

        if (c != null) throw new Error("Cluster with ID: " + cluster.getID() + " already exists");
    }

    public Cluster removeCluster(Short clusterID) {
        Cluster c = this.clusters.remove(clusterID);

        if (c == null) throw new Error("Cluster with ID: " + clusterID + " not found");

        return c;
    }

    public Cluster getCluster(Short clusterID) {
        Cluster c = this.clusters.get(clusterID);

        if (c == null) throw new Error("Cluster with ID: " + clusterID + " not found");

        return c;
    }

    public int maxClusterSize() {
        int max = 0;

        for (Cluster cluster : this.clusters.values()) {
            if (cluster.getEntities().size() > max)
                max = cluster.getEntities().size();
        }

        return max;
    }

    public static Set<LocalTransaction> getAllLocalTransactions(
            DirectedAcyclicGraph<LocalTransaction, DefaultEdge> localTransactionsGraph
    ) {
        return localTransactionsGraph.vertexSet();
    }

    public static List<LocalTransaction> getNextLocalTransactions(
            DirectedAcyclicGraph<LocalTransaction, DefaultEdge> localTransactionsGraph,
            LocalTransaction lt
    ) {
        return successorListOf(localTransactionsGraph, lt);
    }

    public static void addLocalTransactionsSequenceToGraph(
            DirectedAcyclicGraph<LocalTransaction, DefaultEdge> localTransactionsGraph,
            List<LocalTransaction> localTransactionSequence
    ) {
        LocalTransaction graphCurrentLT = new LocalTransaction(0, (short) -1); // root

        for (int i = 0; i < localTransactionSequence.size(); i++) {
            List<LocalTransaction> graphChildrenLTs = getNextLocalTransactions(
                    localTransactionsGraph,
                    graphCurrentLT
            );

            int graphChildrenLTsSize = graphChildrenLTs.size();

            if (graphChildrenLTsSize == 0) {
                createNewBranch(
                        localTransactionsGraph,
                        localTransactionSequence,
                        graphCurrentLT,
                        i
                );

                return;
            }

            for (int j = 0; j < graphChildrenLTsSize; j++) {
                LocalTransaction graphChildLT = graphChildrenLTs.get(j);
                LocalTransaction sequenceCurrentLT = localTransactionSequence.get(i);

                if (sequenceCurrentLT.getClusterID() == graphChildLT.getClusterID()) {
                    graphChildLT.getClusterAccesses().addAll(sequenceCurrentLT.getClusterAccesses());
                    graphChildLT.getFirstAccessedEntityIDs().addAll(sequenceCurrentLT.getFirstAccessedEntityIDs());

                    graphCurrentLT = graphChildLT;
                    break;

                } else {
                    if (j == graphChildrenLTsSize - 1) {
                        createNewBranch(
                                localTransactionsGraph,
                                localTransactionSequence,
                                graphCurrentLT,
                                i
                        );

                        return;
                    }
                }
            }
        }
    }

    private static void createNewBranch(
            DirectedAcyclicGraph<LocalTransaction, DefaultEdge> localTransactionsGraph,
            List<LocalTransaction> localTransactions,
            LocalTransaction currentLT,
            int i
    ) {
        for (int k = i; k < localTransactions.size(); k++) {
            LocalTransaction lt = localTransactions.get(k);

            localTransactionsGraph.addVertex(lt);
            localTransactionsGraph.addEdge(currentLT, lt);
            currentLT = lt;
        }
    }

    public void setupFunctionalities(
            String inputFilePath,
            Set<String> profileFunctionalities,
            int tracesMaxLimit,
            Constants.TraceType traceType,
            boolean calculateRedesigns
    ) throws Exception {
        FunctionalityTracesIterator iter = new FunctionalityTracesIterator(inputFilePath, tracesMaxLimit);
        Map<String, DirectedAcyclicGraph<LocalTransaction, DefaultEdge>> localTransactionsGraphs = new HashMap<>();

        do {
            String functionalityName = iter.nextFunctionalityWithName(null);
            if (!profileFunctionalities.contains(functionalityName)) {
                iter.jumpToNextFunctionality();
                continue;
            }

            Functionality functionality = new Functionality(functionalityName);

            // Get traces according to trace type
            List<TraceDto> traceDtos = iter.getTracesByType(traceType);
            functionality.setTraces(traceDtos);

            DirectedAcyclicGraph<LocalTransaction, DefaultEdge> localTransactionGraph = functionality.createLocalTransactionGraph(this.getEntityIDToClusterID());

            localTransactionsGraphs.put(functionality.getName(), localTransactionGraph);

            findClusterDependencies(localTransactionGraph);

            addFunctionality(functionality);

            iter.jumpToNextFunctionality();
        } while (iter.hasMoreFunctionalities());

        System.out.println("Calculating functionality metrics...");

        for (Functionality functionality: functionalities.values()) {
            functionality.defineFunctionalityType();
            functionality.calculateMetrics(this);

            // Functionality Redesigns
            if (calculateRedesigns) {
                FunctionalityRedesign functionalityRedesign = functionality.createFunctionalityRedesign(
                        Constants.DEFAULT_REDESIGN_NAME,
                        true,
                        localTransactionsGraphs.get(functionality.getName()));

                functionalityRedesign.calculateMetrics(this, functionality);
            }
        }
    }

    public void findClusterDependencies(DirectedAcyclicGraph<LocalTransaction, DefaultEdge> localTransactionsGraph) {
        Set<LocalTransaction> allLocalTransactions = AccessesSciPyDecomposition.getAllLocalTransactions(localTransactionsGraph);

        for (LocalTransaction lt : allLocalTransactions) {
            // ClusterDependencies
            short clusterID = lt.getClusterID();
            if (clusterID != -1) { // not root node
                Cluster fromCluster = this.getCluster(clusterID);

                List<LocalTransaction> nextLocalTransactions = AccessesSciPyDecomposition.getNextLocalTransactions(
                        localTransactionsGraph,
                        lt);

                for (LocalTransaction nextLt : nextLocalTransactions)
                    fromCluster.addCouplingDependencies(nextLt.getClusterID(), nextLt.getFirstAccessedEntityIDs());
            }
        }
    }


    public Metric searchMetricByType(String metricType) {
        for (Metric metric: this.getMetrics())
            if (metric.getType().equals(metricType))
                return metric;
        return null;
    }

    public void calculateMetrics() throws Exception {
        System.out.println("Calculating decomposition metrics...");

        for(String metricType: availableMetrics) {
            Metric metric = searchMetricByType(metricType);
            if (metric == null) {
                metric = MetricFactory.getFactory().getMetric(metricType);
                this.addMetric(metric);
            }
            metric.calculateMetric(this);
        }
    }

    @JsonIgnore
    public short getNewClusterID() {
        return (short) (Collections.max(clusters.keySet()) + 1);
    }

    public void mergeClusters(
            Short cluster1ID,
            Short cluster2ID,
            String newName
    ) {
        Cluster cluster1 = getCluster(cluster1ID);
        Cluster cluster2 = getCluster(cluster2ID);

        Cluster mergedCluster = new Cluster(getNewClusterID(), newName);

        for(short entityID : cluster1.getEntities()) {
            entityIDToClusterID.replace(entityID, mergedCluster.getID());
            removeFunctionalityWithEntity(entityID);
        }

        for(short entityID : cluster2.getEntities()) {
            entityIDToClusterID.replace(entityID, mergedCluster.getID());
            removeFunctionalityWithEntity(entityID);
        }

        Set<Short> allEntities = new HashSet<>(cluster1.getEntities());
        allEntities.addAll(cluster2.getEntities());
        mergedCluster.setEntities(allEntities);

        transferCouplingDependencies(cluster1.getEntities(), cluster1.getID(), mergedCluster.getID());
        transferCouplingDependencies(cluster2.getEntities(), cluster2.getID(), mergedCluster.getID());

        removeCluster(cluster1ID);
        removeCluster(cluster2ID);

        addCluster(mergedCluster);
    }

    public void renameCluster(
            Short clusterID,
            String newName
    ) {
        if (clusterNameExists(newName)) throw new KeyAlreadyExistsException("Cluster with name: " + newName + " already exists");

        Cluster removedCluster = removeCluster(clusterID);

        removedCluster.setName(newName);

        addCluster(new Cluster(removedCluster));
    }

    public void splitCluster(
            Short clusterID,
            String newName,
            String[] entities
    ) {
        Cluster currentCluster = getCluster(clusterID);
        Cluster newCluster = new Cluster(getNewClusterID(), newName);

        for (String stringifiedEntityID : entities) {
            short entityID = Short.parseShort(stringifiedEntityID);

            if (currentCluster.containsEntity(entityID)) {
                newCluster.addEntity(entityID);
                currentCluster.removeEntity(entityID);
                entityIDToClusterID.replace(entityID, newCluster.getID());
                removeFunctionalityWithEntity(entityID);
            }
        }
        transferCouplingDependencies(newCluster.getEntities(), currentCluster.getID(), newCluster.getID());
        addCluster(newCluster);
    }

    //TODO: if possible, use something more fine grained
    private void removeFunctionalityWithEntity(short entityID) {
        this.setFunctionalities(this.getFunctionalities().entrySet()
                .stream()
                .filter(functionalityEntry -> !functionalityEntry.getValue().containsEntity(entityID))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private void transferCouplingDependencies(Set<Short> entities, short currentClusterID, short newClusterID) {
        for (Cluster cluster : this.getClusters().values())
            cluster.transferCouplingDependencies(entities, currentClusterID, newClusterID);
    }

    public void transferEntities(
            Short fromClusterID,
            Short toClusterID,
            String[] entitiesString
    ) {
        Cluster fromCluster = getCluster(fromClusterID);
        Cluster toCluster = getCluster(toClusterID);
        Set<Short> entities = Arrays.stream(entitiesString).map(Short::valueOf).collect(Collectors.toSet());

        for (Short entityID : entities) {

            if (fromCluster.containsEntity(entityID)) {
                toCluster.addEntity(entityID);
                fromCluster.removeEntity(entityID);
                entityIDToClusterID.replace(entityID, toCluster.getID());
                removeFunctionalityWithEntity(entityID);
            }
        }
        transferCouplingDependencies(entities, fromClusterID, toClusterID);
    }
}