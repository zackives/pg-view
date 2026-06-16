package edu.upenn.cis.db.graphtrans;

import static org.junit.Assert.*;
import org.junit.BeforeClass;
import org.junit.Test;
import java.io.IOException;
import edu.upenn.cis.db.datalog.DatalogClause;
import edu.upenn.cis.db.graphtrans.catalog.SchemaMapping;
import edu.upenn.cis.db.graphtrans.parser.QueryParser;
import edu.upenn.cis.db.graphtrans.store.postgres.PostgresStore;

public class EmbeddingExtensionsTest {
    private static SchemaMapping mapping;
    private static PostgresStore store;

    @BeforeClass
    public static void setUp() throws IOException {
        Config.initialize();
        mapping = SchemaMapping.load("schema_mapping.yaml");
        Config.setSchemaMapping(mapping);
        store = new PostgresStore();
        
        System.out.println("--- Loaded path_query_overrides ---");
        if (mapping.path_query_overrides != null) {
            for (SchemaMapping.PathQueryOverride override : mapping.path_query_overrides) {
                System.out.println("Override: source=" + override.source + ", target=" + override.target + ", source_embedding=" + override.source_embedding + ", target_embedding=" + override.target_embedding);
            }
        }
    }

    @Test
    public void testUdfAndFunctionalSimilarity() {
        QueryParser parser = new QueryParser();
        DatalogClause clause = parser.Parse("MATCH (e:Entity) WHERE cosine_similarity(e.detail, 'vaccine side effects', 'gemini') > 0.85 RETURN (e)");
        
        String pgSql = store.getSqlForDatalogClauseWithMapping(clause, mapping, "postgresql");
        String duckDbSql = store.getSqlForDatalogClauseWithMapping(clause, mapping, "duckdb");

        System.out.println("PG UDF SQL: " + pgSql);
        System.out.println("DuckDB UDF SQL: " + duckDbSql);

        assertTrue(pgSql.contains("e.gem_embed <=> get_embedding('vaccine side effects')"));
        assertTrue(duckDbSql.contains("array_cosine_similarity(e.gem_embed, get_embedding('vaccine side effects'))"));
    }

    @Test
    public void testPathSimilarity() {
        QueryParser parser = new QueryParser();
        DatalogClause clause = parser.Parse("MATCH (e:Entity)-[r:<=> {threshold: 0.8}]->(t:Tag) RETURN (e), (t)");
        
        String pgSql = store.getSqlForDatalogClauseWithMapping(clause, mapping, "postgresql");
        String duckDbSql = store.getSqlForDatalogClauseWithMapping(clause, mapping, "duckdb");

        System.out.println("PG Path SQL: " + pgSql);
        System.out.println("DuckDB Path SQL: " + duckDbSql);

        assertTrue(pgSql.contains("e.entity_embed <=> t.gem_embed"));
        assertTrue(duckDbSql.contains("array_cosine_similarity(e.entity_embed, t.gem_embed)"));
    }

    @Test
    public void testSelfJoinParentOf() {
        QueryParser parser = new QueryParser();
        DatalogClause clause = parser.Parse("MATCH (parent:Entity)-[:PARENT_OF]->(child:Entity) RETURN (parent), (child)");
        
        String pgSql = store.getSqlForDatalogClauseWithMapping(clause, mapping, "postgresql");
        System.out.println("PG Parent-Of SQL: " + pgSql);

        assertTrue(pgSql.contains("child.entity_parent = parent.entity_id"));
    }

    @Test
    public void testMultiStepReasoningWalkthrough() {
        QueryParser parser = new QueryParser();
        DatalogClause clause = parser.Parse(
            "MATCH (c:Entity {type: 'claim'})-[:PARENT_OF]->(paper:Entity), " +
            "(paper)-[citation:LINKED_TO {type: 'citation'}]->(citing:Entity), " +
            "(citing)-[:HAS_TAG]->(t:Tag) " +
            "WHERE cosine_similarity(c.detail, 'vaccine side effects', 'gemini') > 0.85 AND t.name = 'field' AND t.value = 'Immunology' " +
            "RETURN (paper), (citing)"
        );
        
        String pgSql = store.getSqlForDatalogClauseWithMapping(clause, mapping, "postgresql");
        System.out.println("PG Multi-Hop SQL: " + pgSql);

        assertTrue(pgSql.contains("paper.entity_parent = c.entity_id"));
        assertTrue(pgSql.contains("citation.from_id = paper.entity_id"));
        assertTrue(pgSql.contains("citation.to_id = citing.entity_id"));
        assertTrue(pgSql.contains("e_1.entity_id = citing.entity_id"));
        assertTrue(pgSql.contains("e_1.entity_id = t.entity_id"));
        assertTrue(pgSql.contains("c.gem_embed <=> get_embedding('vaccine side effects')"));
        assertTrue(pgSql.contains("t.tag_name = 'field'"));
        assertTrue(pgSql.contains("t.tag_value = 'Immunology'"));
        assertTrue(pgSql.contains("citation.link_type = 'citation'"));
    }
}
