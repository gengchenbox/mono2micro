package pt.ist.socialsoftware.mono2micro.decomposition.dto.decomposition;

import pt.ist.socialsoftware.mono2micro.decomposition.domain.PartitionsDecomposition;
import pt.ist.socialsoftware.mono2micro.functionality.domain.Functionality;

import java.util.HashMap;
import java.util.Map;

import static pt.ist.socialsoftware.mono2micro.decomposition.domain.Decomposition.DecompositionType.ACCESSES_DECOMPOSITION;

public class AccessesDecompositionDto extends DecompositionDto {
    private boolean outdated;
    private boolean expert;
    private Map<String, Functionality> functionalities = new HashMap<>(); // <functionalityName, Functionality>
    private Map<String, Short> entityIDToClusterName = new HashMap<>();

    public AccessesDecompositionDto() {this.type = ACCESSES_DECOMPOSITION;}

    public AccessesDecompositionDto(PartitionsDecomposition decomposition) {
        this.setCodebaseName(decomposition.getSimilarity().getStrategy().getCodebase().getName());
        this.setStrategyName(decomposition.getStrategy().getName());
        this.setName(decomposition.getName());
        this.type = ACCESSES_DECOMPOSITION;
        this.setMetrics(decomposition.getMetrics());
        this.outdated = decomposition.isOutdated();
        this.expert = decomposition.isExpert();
        this.clusters = decomposition.getClusters();
    }

    public boolean isOutdated() {
        return outdated;
    }

    public void setOutdated(boolean outdated) {
        this.outdated = outdated;
    }

    public boolean isExpert() {
        return expert;
    }

    public void setExpert(boolean expert) {
        this.expert = expert;
    }

    public Map<String, Functionality> getFunctionalities() {
        return functionalities;
    }

    public void setFunctionalities(Map<String, Functionality> functionalities) {
        this.functionalities = functionalities;
    }

    public Map<String, Short> getEntityIDToClusterName() {
        return entityIDToClusterName;
    }

    public void setEntityIDToClusterName(Map<String, Short> entityIDToClusterName) {
        this.entityIDToClusterName = entityIDToClusterName;
    }
}