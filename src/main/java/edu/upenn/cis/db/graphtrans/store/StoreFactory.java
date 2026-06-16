package edu.upenn.cis.db.graphtrans.store;

import edu.upenn.cis.db.graphtrans.store.logicblox.LogicBloxStore;
import edu.upenn.cis.db.graphtrans.store.neo4j.Neo4jStore;
import edu.upenn.cis.db.graphtrans.store.postgres.PostgresStore;
import edu.upenn.cis.db.graphtrans.store.simpledatalog.SimpleDatalogStore;
import edu.upenn.cis.db.graphtrans.store.duckdb.DuckDBStore;

public class StoreFactory {
	public Store getStore(String storeType) {
		if (storeType == null) {
			return null;
		} else if (storeType.equalsIgnoreCase("lb")) {
			return new LogicBloxStore();
		} else if (storeType.equalsIgnoreCase("pg")) {
			return new PostgresStore();
		} else if (storeType.equalsIgnoreCase("dd") || storeType.equalsIgnoreCase("duckdb")) {
			return new DuckDBStore();
		} else if (storeType.equalsIgnoreCase("n4")) {
			return new Neo4jStore();
		} else if (storeType.equalsIgnoreCase("sd")) {
			return new SimpleDatalogStore();
		}
		throw new UnsupportedOperationException("Not supported platform [" + storeType +"]");
	}
}
