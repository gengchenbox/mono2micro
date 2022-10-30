package pt.ist.socialsoftware.mono2micro.codebase;

import org.springframework.stereotype.Service;
import pt.ist.socialsoftware.mono2micro.clusteringAlgorithm.ClusteringFactory;

import java.util.List;

@Service
public class ClusteringService {
    public List<String> getSupportedRepresentationInfoTypes(String algorithmType) {
        return ClusteringFactory.getClustering(algorithmType).getSupportedRepresentationInfoTypes();
    }
}