# GQL Extensions for Relational Schemas and Vector Embeddings

This document describes the syntax extensions made to GQL (Graph Query Language), the YAML schema mapping model, and the technical implementation of the parser and compiler translating GQL to SQL (Postgres / DuckDB).

---

## 1. Schema Mapping Format (YAML)

To bridge a relational database schema to a property graph, a `schema_mapping.yaml` defines how tables and columns map to nodes, edges, properties, and vector embeddings. It supports:
- **Multi-table join relations** (join tables).
- **Direct foreign key relations**.
- **Self-referential relations** (hierarchies).
- **Path similarity overrides** (customizing node comparisons in specific paths).

### KAIR Schema Mapping Example
Below is the mapping for KAIR database tables (`entities`, `entity_link`, and `entity_tags`):

```yaml
version: "1.0"
target_dialect: postgresql

nodes:
  Entity:
    table: entities
    primary_key: entity_id
    default_embedding: gemini_detail # Prefers gem_embed by default
    properties:
      id: entity_id
      type: entity_type
      name: entity_name
      detail:
        column: entity_detail
        embedding:
          column: entity_embed
          dimension: 1536
          model: text-embedding-3-small
      gemini_detail:
        column: entity_detail
        embedding:
          column: gem_embed
          dimension: 3072
          model: gemini-embedding
      qwen_detail:
        column: entity_detail
        embedding:
          column: qwen_embed
          dimension: 3072
          model: qwen-embedding
      url: entity_url
      contact: entity_contact
      json: entity_json

  Tag:
    table: entity_tags
    primary_key: entity_id
    default_embedding: gemini_value
    properties:
      id: entity_id
      name: tag_name
      value:
        column: tag_value
        embedding:
          column: tag_embed
          dimension: 1536
          model: text-embedding-3-small
      gemini_value:
        column: tag_value
        embedding:
          column: gem_embed
          dimension: 3072
          model: gemini-embedding

edges:
  LINKED_TO:
    table: entity_link
    source:
      node: Entity
      key: from_id
      ref_key: entity_id
    target:
      node: Entity
      key: to_id
      ref_key: entity_id
    properties:
      type: link_type
      strength: entity_strength
      support: entity_support
      bidirectional: bidirectional
      distance: distance
      comment: comment

  HAS_TAG:
    table: entity_tags
    source:
      node: Entity
      key: entity_id
      ref_key: entity_id
    target:
      node: Tag
      key: entity_id
      ref_key: entity_id

  PARENT_OF:
    table: entities
    source:
      node: Entity
      key: entity_parent
      ref_key: entity_id
    target:
      node: Entity
      key: entity_id
      ref_key: entity_id

# Overrides for path similarity queries between specific node pairs
path_query_overrides:
  - source: Entity
    target: Tag
    source_embedding: detail        # Override Entity to use OpenAI entity_embed
    target_embedding: gemini_value  # Override Tag to keep gem_embed
```

---

## 2. GQL Syntax Extensions & Translation Examples

### A. User-Defined Functions (UDFs)
Any function call not natively defined in standard GQL is treated as a UDF and translated to the corresponding SQL function.
*   **GQL Query**:
    ```gql
    MATCH (u:User)
    WHERE my_namespace.clean_text(u.name) = 'alice'
    RETURN u.name
    ```
*   **Generated SQL**:
    ```sql
    SELECT u.username
    FROM users AS u
    WHERE (my_namespace.clean_text(u.username) = 'alice')
    ```

### B. Functional Vector Similarity (Multi-Model Support)
You can call `cosine_similarity(arg1, arg2, [model])` or `l2_distance(arg1, arg2, [model])`. The optional third parameter selects the embedding column (matching either the YAML property key or the model string).

*   **GQL Query (Selecting Gemini Model)**:
    ```gql
    MATCH (e:Entity)
    WHERE cosine_similarity(e.detail, 'vaccine side effects', 'gemini') > 0.85
    RETURN e.name
    ```
    *   **Generated SQL**:
        ```sql
        SELECT e.entity_name
        FROM entities AS e
        WHERE (((1 - (e.gem_embed <=> get_embedding('vaccine side effects'))) > 0.85))
        ```

*   **GQL Query (Selecting Qwen Model)**:
    ```gql
    MATCH (e:Entity)
    WHERE cosine_similarity(e.detail, 'vaccine side effects', 'qwen') > 0.85
    RETURN e.name
    ```
    *   **Generated SQL**:
        ```sql
        SELECT e.entity_name
        FROM entities AS e
        WHERE (((1 - (e.qwen_embed <=> get_embedding('vaccine side effects'))) > 0.85))
        ```

### C. Path-Based Approximate Match Navigation
Allows similarity edges directly inside match paths using `<=>` (Cosine similarity) and `<->` (L2 distance).

*   **GQL Query (Defaulting to Preferred `gem_embed` Column)**:
    ```gql
    MATCH (u:User)-[r:<=> {threshold: 0.85}]->(p:Paper)
    RETURN u.name, p.title
    ```
    *   **PostgreSQL**:
        ```sql
        SELECT u.username, p.title
        FROM users AS u
        JOIN papers AS p ON 1 - (u.gem_embed <=> p.gem_embed) > 0.85
        ```

*   **GQL Query (Path-Query Embedding Override)**:
    When joining `Entity` to `Tag`, the `path_query_overrides` rules apply:
    `Entity` uses `detail` (`entity_embed`), and `Tag` uses `gemini_value` (`gem_embed`).
    ```gql
    MATCH (e:Entity)-[r:<=> {threshold: 0.8}]->(t:Tag)
    RETURN e.name, t.value
    ```
    *   **Generated SQL**:
        ```sql
        SELECT e.entity_name, t.tag_value
        FROM entities AS e
        JOIN entity_tags AS t ON 1 - (e.entity_embed <=> t.gem_embed) > 0.8
        ```

### D. Arrow-Direction Join Resolution (Self-Joins)
For self-joins where node labels are ambiguous (e.g. `Entity` to `Entity` via `PARENT_OF`), the visual arrow direction (`->` vs `<-`) determines GQL source and target.

*   **GQL Query (Right Arrow)**:
    ```gql
    MATCH (parent:Entity)-[:PARENT_OF]->(child:Entity)
    RETURN parent.name, child.name
    ```
    *   **Generated SQL**:
        ```sql
        SELECT parent.entity_name, child.entity_name
        FROM entities AS parent
        JOIN entities AS child ON child.entity_parent = parent.entity_id
        ```

*   **GQL Query (Left Arrow)**:
    ```gql
    MATCH (child:Entity)<-[:PARENT_OF]-(parent:Entity)
    RETURN parent.name, child.name
    ```
    *   **Generated SQL**:
        ```sql
        SELECT parent.entity_name, child.entity_name
        FROM entities AS child
        JOIN entities AS parent ON child.entity_parent = parent.entity_id
        ```

---

## 3. Multi-Step Reasoning Walkthrough

A complete multi-step reasoning query chaining 4 hops:
```gql
MATCH (c:Entity {type: 'claim'})-[:PARENT_OF]->(paper:Entity)-[citation:LINKED_TO {link_type: 'citation'}]->(citing:Entity)-[:HAS_TAG]->(t:Tag)
WHERE cosine_similarity(c.detail, 'vaccine side effects', 'gemini') > 0.85 AND t.name = 'field' AND t.value = 'Immunology'
RETURN paper.name, citing.name
```

Will compile to the following PostgreSQL query:
```sql
SELECT paper.entity_name, citing.entity_name 
FROM entities AS c 
JOIN entities AS paper ON paper.entity_parent = c.entity_id 
JOIN entity_link AS citation ON citation.from_id = paper.entity_id 
JOIN entities AS citing ON citation.to_id = citing.entity_id 
JOIN entity_tags AS t ON t.entity_id = citing.entity_id 
WHERE (((c.entity_type = 'claim') 
  AND ((1 - (c.gem_embed <=> get_embedding('vaccine side effects'))) > 0.85)) 
  AND (t.tag_name = 'field') 
  AND (t.tag_value = 'Immunology'))
```

---

## 4. Technical Implementation Details

The system is structured as three distinct modules under `datagraph_package_xwang/gql_parser`:

### A. Schema Mapping Loader (`mapping.py`)
Loads schema mappings and exposes helpers to look up tables, keys, property columns, embedding columns, and custom `path_query_overrides` mapping pairs.

### B. Lexer & Parser (`parser.py`)
Tokenizes and parses MATCH paths, WHERE expressions, and RETURN items into an Abstract Syntax Tree (AST), supporting UDFs and vector operator syntax.

### C. SQL Code Generator (`translator.py`)
Compiles the AST to dialect-specific SQL:
1.  **Variable Resolution**: Resolves node and edge variables.
2.  **Join Compiler**:
    *   *Self-Joins*: Detects when both endpoint tables are identical and resolves foreign key join directions based on the GQL path arrow (`->` vs `<-`).
    *   *Path Overrides*: Swaps default node embedding columns with path overrides configuration if matched.
3.  **Expression Transpiler**: Compiles similarity expressions to PostgreSQL `pgvector` or DuckDB functions, resolving 3-argument model selections.
