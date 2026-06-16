package edu.upenn.cis.db.graphtrans.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class SchemaMapping {
    public String version;
    public String target_dialect;
    public Map<String, NodeMapping> nodes;
    public Map<String, EdgeMapping> edges;
    public List<PathQueryOverride> path_query_overrides;

    public static class NodeMapping {
        public String table;
        public String primary_key;
        public String default_embedding;
        public Map<String, Object> properties;
    }

    public static class EdgeMapping {
        public String table;
        public SourceTargetMapping source;
        public SourceTargetMapping target;
        public Map<String, String> properties;
    }

    public static class SourceTargetMapping {
        public String node;
        public String key;
        public String ref_key;
    }

    public static class PathQueryOverride {
        public String source;
        public String target;
        public String source_embedding;
        public String target_embedding;
    }

    public static class EmbeddingInfo {
        public String column;
        public int dimension;
        public String model;

        public EmbeddingInfo(String column, int dimension, String model) {
            this.column = column;
            this.dimension = dimension;
            this.model = model;
        }
    }

    public static SchemaMapping load(String filePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(filePath), SchemaMapping.class);
    }

    public String getTableForNode(String label) {
        if (nodes != null && nodes.containsKey(label)) {
            return nodes.get(label).table;
        }
        return null;
    }

    public String getPrimaryKeyForNode(String label) {
        if (nodes != null && nodes.containsKey(label)) {
            return nodes.get(label).primary_key;
        }
        return null;
    }

    public String getColumnForNodeProperty(String label, String propName) {
        if (nodes == null || !nodes.containsKey(label)) {
            return null;
        }
        NodeMapping nm = nodes.get(label);
        if (nm.properties == null || !nm.properties.containsKey(propName)) {
            return null;
        }
        Object propObj = nm.properties.get(propName);
        if (propObj instanceof String) {
            return (String) propObj;
        } else if (propObj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) propObj;
            if (map.containsKey("column")) {
                return (String) map.get("column");
            }
        }
        return null;
    }

    public String getTableForEdge(String label) {
        if (edges != null && edges.containsKey(label)) {
            return edges.get(label).table;
        }
        return null;
    }

    public String getColumnForEdgeProperty(String label, String propName) {
        if (edges == null || !edges.containsKey(label)) {
            return null;
        }
        EdgeMapping em = edges.get(label);
        if (em.properties != null && em.properties.containsKey(propName)) {
            return em.properties.get(propName);
        }
        return null;
    }

    public EmbeddingInfo getEmbedding(String nodeLabel, String propName, String modelName) {
        if (nodes == null || !nodes.containsKey(nodeLabel)) {
            return null;
        }
        NodeMapping nm = nodes.get(nodeLabel);
        if (nm.properties == null) {
            return null;
        }

        // 1. If modelName is provided, try to find an embedding property that matches the model name
        if (modelName != null && !modelName.isEmpty()) {
            String cleanModel = modelName.toLowerCase().replace("'", "").replace("\"", "");
            for (Map.Entry<String, Object> entry : nm.properties.entrySet()) {
                String key = entry.getKey().toLowerCase();
                if (entry.getValue() instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) entry.getValue();
                    if (map.containsKey("embedding")) {
                        Map<?, ?> embed = (Map<?, ?>) map.get("embedding");
                        String model = embed.containsKey("model") ? ((String) embed.get("model")).toLowerCase() : "";
                        if (model.contains(cleanModel) || key.contains(cleanModel)) {
                            return extractEmbeddingInfo(embed);
                        }
                    }
                }
            }
        }

        // 2. Try specified property's embedding
        if (propName != null && nm.properties.containsKey(propName)) {
            Object propObj = nm.properties.get(propName);
            if (propObj instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) propObj;
                if (map.containsKey("embedding")) {
                    return extractEmbeddingInfo((Map<?, ?>) map.get("embedding"));
                }
            }
        }

        // 3. Fallback to default_embedding of the node
        if (nm.default_embedding != null && nm.properties.containsKey(nm.default_embedding)) {
            Object defaultProp = nm.properties.get(nm.default_embedding);
            if (defaultProp instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) defaultProp;
                if (map.containsKey("embedding")) {
                    return extractEmbeddingInfo((Map<?, ?>) map.get("embedding"));
                }
            }
        }

        // 4. Ultimate fallback: find any property mapping with an embedding
        for (Object val : nm.properties.values()) {
            if (val instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) val;
                if (map.containsKey("embedding")) {
                    return extractEmbeddingInfo((Map<?, ?>) map.get("embedding"));
                }
            }
        }

        return null;
    }

    private EmbeddingInfo extractEmbeddingInfo(Map<?, ?> embed) {
        String column = (String) embed.get("column");
        int dimension = embed.containsKey("dimension") ? ((Number) embed.get("dimension")).intValue() : 0;
        String model = (String) embed.get("model");
        return new EmbeddingInfo(column, dimension, model);
    }

    public PathQueryOverride getPathQueryOverride(String sourceLabel, String targetLabel) {
        if (path_query_overrides == null) {
            return null;
        }
        for (PathQueryOverride override : path_query_overrides) {
            if (override.source.equals(sourceLabel) && override.target.equals(targetLabel)) {
                return override;
            }
        }
        return null;
    }
}
