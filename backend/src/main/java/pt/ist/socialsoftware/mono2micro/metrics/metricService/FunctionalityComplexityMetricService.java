package pt.ist.socialsoftware.mono2micro.metrics.metricService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pt.ist.socialsoftware.mono2micro.cluster.Cluster;
import pt.ist.socialsoftware.mono2micro.functionality.FunctionalityService;
import pt.ist.socialsoftware.mono2micro.functionality.domain.Functionality;
import pt.ist.socialsoftware.mono2micro.functionality.domain.FunctionalityRedesign;
import pt.ist.socialsoftware.mono2micro.functionality.domain.LocalTransaction;
import pt.ist.socialsoftware.mono2micro.decomposition.domain.AccessesSciPyDecomposition;
import pt.ist.socialsoftware.mono2micro.functionality.dto.AccessDto;
import pt.ist.socialsoftware.mono2micro.functionality.FunctionalityType;
import pt.ist.socialsoftware.mono2micro.functionality.LocalTransactionTypes;
import pt.ist.socialsoftware.mono2micro.utils.Utils;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Service
public class FunctionalityComplexityMetricService {
    @Autowired
    FunctionalityService functionalityService;

    public Integer calculateMetric(AccessesSciPyDecomposition decomposition, Functionality functionality, FunctionalityRedesign functionalityRedesign)
            throws IOException
    {
        int value = 0;

        if(functionality.getType() != FunctionalityType.SAGA)
            return value;

        Map<String, Set<Cluster>> functionalitiesClusters = Utils.getFunctionalitiesClusters(
                decomposition.getEntityIDToClusterName(),
                decomposition.getClusters(),
                decomposition.getFunctionalities().values());

        for (int i = 0; i < functionalityRedesign.getRedesign().size(); i++) {
            LocalTransaction lt = functionalityRedesign.getRedesign().get(i);

            if(lt.getId() != 0){
                for(AccessDto accessDto : lt.getClusterAccesses()) {
                    short entity = accessDto.getEntityID();
                    byte mode = accessDto.getMode();

                    // Functionality complexity cost of write
                    if(mode >= 2 && lt.getType() == LocalTransactionTypes.COMPENSATABLE) // 2 -> W, 3 -> RW
                        value++;

                    // Functionality complexity cost of read
                    if (mode != 2) { // 2 -> W - we want all the reads
                        for (Functionality otherFunctionality : decomposition.getFunctionalities().values()) {
                            if (!otherFunctionality.getName().equals(functionality.getName()) &&
                                    otherFunctionality.containsEntity(entity) &&
                                    functionalitiesClusters.get(otherFunctionality.getName()).size() > 1) {

                                if(otherFunctionality.getFunctionalityRedesigns().size() == 1 &&
                                        otherFunctionality.getEntities().get(entity) >= 2)
                                    value++;
                                else if(otherFunctionality.getFunctionalityRedesigns().size() > 1 &&
                                        functionalityService.getFunctionalityRedesignUsedForMetrics(otherFunctionality).semanticLockEntities().contains(entity))
                                    value++;
                            }
                        }
                    }
                }
            }
        }
        return value;
    }
}
