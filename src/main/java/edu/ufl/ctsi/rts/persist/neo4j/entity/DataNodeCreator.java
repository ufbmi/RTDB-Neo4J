package edu.ufl.ctsi.rts.persist.neo4j.entity;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;

import edu.ufl.ctsi.rts.neo4j.RtsNodeLabel;

public class DataNodeCreator extends EntityNodePersister {

	public DataNodeCreator(GraphDatabaseService db) {
		super(db);
	}

	static final String QUERY = "MERGE (n:data { dr: {value} }) return n";

	@Override
	protected String setupQuery() {
		return QUERY;
	}

	@Override
	protected Label getLabel() {
		return Label.label(RtsNodeLabel.DATA.getLabelText());
	}

}
