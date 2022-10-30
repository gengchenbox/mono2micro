package pt.ist.socialsoftware.mono2micro.clusteringAlgorithm;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import pt.ist.socialsoftware.mono2micro.decomposition.domain.Decomposition;
import pt.ist.socialsoftware.mono2micro.decomposition.domain.PartitionsDecomposition;
import pt.ist.socialsoftware.mono2micro.decomposition.dto.request.DecompositionRequest;
import pt.ist.socialsoftware.mono2micro.decomposition.dto.request.ExpertRequest;
import pt.ist.socialsoftware.mono2micro.similarity.domain.Similarity;

import javax.management.openmbean.KeyAlreadyExistsException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Expert {
    public static final String EXPERT = "Expert Clustering";

    public Expert() {}

    public String getType() {
        return EXPERT;
    }

    public Decomposition generateClusters(Similarity similarity, DecompositionRequest request) throws Exception {
        Decomposition decomposition = new PartitionsDecomposition(request);
        decomposition.setExpert(true);
        decomposition.setSimilarity(similarity);
        similarity.addDecomposition(decomposition);
        decomposition.setStrategy(similarity.getStrategy());
        similarity.getStrategy().addDecomposition(decomposition);

        ExpertRequest dto = (ExpertRequest) request;
        Map<Short, String> idToEntity;

        List<String> decompositionNames = similarity.getDecompositions().stream().map(Decomposition::getName).collect(Collectors.toList());

        if (decompositionNames.contains(dto.getExpertName()))
            throw new KeyAlreadyExistsException();
        decomposition.setName(similarity.getName() + " " + dto.getExpertName());
        decomposition.setExpert(true);

        idToEntity = similarity.getIDToEntityName();

        InputStream is = new BufferedInputStream(dto.getExpertFile().get().getInputStream());
        JSONObject clustersJSON = new JSONObject(IOUtils.toString(is, StandardCharsets.UTF_8)).getJSONObject("clusters");
        SciPyClustering.addClustersAndEntities(decomposition, clustersJSON, idToEntity);
        is.close();

        return decomposition;
    }
}