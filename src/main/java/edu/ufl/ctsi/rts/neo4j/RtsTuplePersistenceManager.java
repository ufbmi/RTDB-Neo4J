package edu.ufl.ctsi.rts.neo4j;


import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.stream.Stream;

import neo4jtest.test.App;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;

import edu.uams.dbmi.rts.ParticularReference;
import edu.uams.dbmi.rts.iui.Iui;
import edu.uams.dbmi.rts.time.TemporalReference;
import edu.uams.dbmi.rts.time.TemporalRegion;
import edu.uams.dbmi.rts.tuple.ATuple;
import edu.uams.dbmi.rts.tuple.MetadataTuple;
import edu.uams.dbmi.rts.tuple.PtoCTuple;
import edu.uams.dbmi.rts.tuple.PtoDETuple;
import edu.uams.dbmi.rts.tuple.PtoLackUTuple;
import edu.uams.dbmi.rts.tuple.PtoPTuple;
import edu.uams.dbmi.rts.tuple.PtoUTuple;
import edu.uams.dbmi.rts.tuple.RtsTuple;
import edu.uams.dbmi.util.iso8601.Iso8601DateTime;
import edu.uams.dbmi.util.iso8601.Iso8601DateTimeFormatter;
import edu.ufl.ctsi.rts.persist.neo4j.entity.EntityNodePersister;
import edu.ufl.ctsi.rts.persist.neo4j.tuple.ATuplePersister;
import edu.ufl.ctsi.rts.persist.neo4j.tuple.MetadataTuplePersister;
import edu.ufl.ctsi.rts.persist.neo4j.tuple.PtoCTuplePersister;
import edu.ufl.ctsi.rts.persist.neo4j.tuple.PtoDETuplePersister;
import edu.ufl.ctsi.rts.persist.neo4j.tuple.PtoLackUTuplePersister;
import edu.ufl.ctsi.rts.persist.neo4j.tuple.PtoPTuplePersister;
import edu.ufl.ctsi.rts.persist.neo4j.tuple.PtoUTuplePersister;
import edu.ufl.ctsi.rts.persist.neo4j.tuple.TemporalRegionPersister;

public class RtsTuplePersistenceManager {

	static String CNODE_QUERY = "MERGE (n:change_reason { c: {value} }) return n";
	static String CTNODE_QUERY = "MERGE (n:change_type { ct: {value} }) return n";
	
	public GraphDatabaseService graphDb;
	
	Label templateLabel;
	Label aTemplateLabel;
	Label ptouTemplateLabel;
	Label ptopTemplateLabel;
	Label ptolackuTemplateLabel;
	
	Label instanceLabel;
	Label temporalRegionLabel;
	Label typeLabel;
	Label relationLabel;
	Label dataLabel;
	
	Label metadataLabel;

	HashSet<RtsTuple> tuples;
	HashSet<MetadataTuple> metadata;
	HashSet<TemporalReference> tempReferences;
	HashSet<TemporalRegion> tempRegions;
	
	HashMap<Iui, Node> iuiNode;
	HashMap<String, Node> uiNode;
	HashMap<String, RtsTuple> iuiToItsAssignmentTuple;
	HashSet<String> iuisInPtoPTuples;
	HashMap<String, String> iuiToNodeLabel;
	
	Iso8601DateTimeFormatter dttmFormatter;
	
	ATuplePersister atp;
	PtoUTuplePersister pup;
	PtoPTuplePersister ppp;
	PtoLackUTuplePersister plup;
	PtoDETuplePersister pdrp;
	PtoCTuplePersister pcp;
	MetadataTuplePersister mp;
	
	TemporalRegionPersister trp;
	
	public RtsTuplePersistenceManager() {
		tuples = new HashSet<RtsTuple>();
		metadata = new HashSet<MetadataTuple>();
		tempReferences = new HashSet<TemporalReference>();
		tempRegions = new HashSet<TemporalRegion>();
		iuiNode = new HashMap<Iui, Node>();
		uiNode = new HashMap<String, Node>();
		iuiToItsAssignmentTuple = new HashMap<String, RtsTuple>();
		iuisInPtoPTuples = new HashSet<String>();
		iuiToNodeLabel = new HashMap<String, String>();
		dttmFormatter = new Iso8601DateTimeFormatter();
		graphDb = new GraphDatabaseFactory().newEmbeddedDatabase( new File(App.DB_PATH) );
		setupSchema();
		setupMetadata();

		atp = new ATuplePersister(graphDb);
		pup = new PtoUTuplePersister(graphDb);
		ppp = new PtoPTuplePersister(graphDb);
		plup = new PtoLackUTuplePersister(graphDb);
		pdrp = new PtoDETuplePersister(graphDb);
		pcp = new PtoCTuplePersister(graphDb);
		mp = new MetadataTuplePersister(graphDb);
		trp = new TemporalRegionPersister(graphDb);
	}
	
	static final String queryInstanceNode = "match (n) where n.iui={value} return n;";
	
	public void addTuple(RtsTuple t) {
		if (t instanceof ATuple) {
			ATuple at = (ATuple)t;
			iuiToItsAssignmentTuple.put(at.getReferentIui().toString(), t);
		} else if ( (t instanceof PtoPTuple) ) {
			PtoPTuple ptop = (PtoPTuple)t;
			Iterable<ParticularReference> p = ptop.getAllParticulars();
			for (ParticularReference i : p) {
				if (i instanceof Iui) iuisInPtoPTuples.add(i.toString());
				else if (i instanceof TemporalReference) {
					TemporalReference tr = (TemporalReference)i;
					tempReferences.add(tr);
				}
			}
		} else if ( (t instanceof PtoDETuple) ) {
			PtoDETuple ptode = (PtoDETuple)t;
			ParticularReference pr = ptode.getReferent();
			if (pr instanceof TemporalReference)
				tempReferences.add((TemporalReference)pr);
		}
		if (t instanceof MetadataTuple) {
			metadata.add((MetadataTuple)t);
		} else {
			tuples.add(t);
		}
	} 
	
	public void addTuples(Collection<RtsTuple> t) {
		Iterator<RtsTuple> i = t.iterator();
		while (i.hasNext()) {
			addTuple(i.next());
		}
	}
	
	
	public void commitTuples() {
		try (Transaction tx = graphDb.beginTx() ) {
			
			/*
			 * Before we begin, let's be sure that we either have assignment templates
			 *  for each IUI that a PtoP template references or that the IUI node 
			 *  exists in the database already.  
			 */
			checkIuisInPtoP();
			
			Iso8601DateTime dt = new Iso8601DateTime();
			//Iso8601DateTimeFormatter dtf = new Iso8601DateTimeFormatter();
			//String iuid = dtf.format(dt);
			
			for (RtsTuple t : tuples) {
				if (t instanceof ATuple) {
					atp.persistTuple(t);
				} else if (t instanceof PtoUTuple) {
					pup.persistTuple(t);
				} else if (t instanceof PtoLackUTuple) {
					plup.persistTuple(t);
				} else if (t instanceof PtoDETuple) {
					pdrp.persistTuple(t);
				} else if (t instanceof PtoPTuple) {
					ppp.persistTuple(t);
				} else if (t instanceof PtoCTuple) {
					pcp.persistTuple(t);
				} 
			}
			
			for (MetadataTuple d : metadata) {
				d.setAuthoringTimestamp(dt);
				mp.persistTuple(d);
			}
			
			tx.success();
			
			/*
			 * We've sent them all to db, so we can clear.  In the future, we will
			 *  likely want to send them to some cache first.  But this class isn't the 
			 *  cache, it is merely the thing that submits a chunk of related 
			 *  templates as one transaction.
			 */
			tuples.clear();
			metadata.clear();
			tempReferences.clear();
			tempRegions.clear();
			EntityNodePersister.clearCache();
		}
	}
	
	private void checkIuisInPtoP() {
		for (String iui : iuisInPtoPTuples) {
			if (!iuiToItsAssignmentTuple.containsKey(iui)) {
				HashMap<String, Object> params = new HashMap<String, Object>();
				params.put("value", iui);
				ResourceIterator<Node> rin = graphDb.execute(queryInstanceNode, params).columnAs("n");
				if (!rin.hasNext()) {
					System.err.println("Iui " + iui + " is referenced in a PtoP template but has " +
							"no assignment template in the cache and there is no node for it already "
							+ "in the database.");
				}
				/*else {
					Node n = rin.next();
					Iterable<Label> labels = n.getLabels();
					for (Label l : labels) {
						String name = l.name();
						if (name.equals("instance")) {
							iuiToNodeLabel.put(iui, name);
							break;
						} else if (name.equals("temporal_region")) {
							iuiToNodeLabel.put(iui, name);
							break;
						}
					}
				}
				*/
			}
		}
		
	}

	static String createTemplateQuery = "CREATE (n:template { iui : {value}})";
	
	/*
	 * Above, we made sure that a template with this template IUI didn't exist already.
	 *  So we're clear to add it de novo without worrying about violating a unique
	 *  constraint on template IUIs.
	 */
	private Node createTemplateNode(RtsTuple t) {
		Node n = graphDb.createNode(templateLabel);
		n.setProperty("ui", t.getTupleIui().toString());
		return n;
	}

	public Iterator<RtsTuple> getTemplateIterator() {
		return tuples.iterator();
	}
	
	public Iterator<MetadataTuple> getMetadataTemplateIterator() {
		return metadata.iterator();
	}
	
	public Stream<RtsTuple> getTupleStream() {
		return tuples.stream();
	}
	
	public Stream<MetadataTuple> getMetadataTupleStream() {
		return metadata.stream();
	}
	
	public Stream<TemporalReference> getTemporalReferenceStream() {
		return tempReferences.stream();
	}
	
	public Stream<TemporalRegion> getTemporalRegionStream() {
		return tempRegions.stream();
	}
	/*
	private void connectToReferentNode(Node templateNode, RtsTemplate t) {
		Node referentNode;
		Iui referentIui = t.getReferentIui();
		if (iuiNode.containsKey(referentIui)) {
			referentNode = iuiNode.get(referentIui);
		} else {
			referentNode = getOrCreateEntityNode(referentIui, RtsNodeLabel.INSTANCE);
		}
		
		templateNode.createRelationshipTo(referentNode, RtsRelationshipType.iuip);
	}

	private void connectToAuthorNode(Node templateNode, RtsTemplate t) {
		Node authorNode;
		Iui authorIui = t.getAuthorIui();
		if (iuiNode.containsKey(authorIui)) {
			authorNode = iuiNode.get(authorIui);
		} else {
			authorNode = getOrCreateEntityNode(authorIui, RtsNodeLabel.INSTANCE);
		}
		
		templateNode.createRelationshipTo(authorNode, RtsRelationshipType.iuia);
	}//*/

	/*
	private void completeTeTemplate(Node n, TeTemplate t) {
		// TODO Auto-generated method stub
		n.setProperty("type", "TE");
		n.setProperty("tap", dttmFormatter.format(t.getAuthoringTimestamp()));
		
		Node typeNode = getOrCreateNode(RtsNodeLabel.TYPE, t.getUniversalUui().toString());
		n.createRelationshipTo(typeNode, RtsRelationshipType.uui);
	}
	*/
	
	static String TYPE_QUERY = "MERGE (n:universal {ui : {value}})"
			//+ "ON CREATE "
			+ "RETURN n";

	private void completeATemplate(Node n, ATuple t) {
		n.setProperty("type", "A");
		n.setProperty("tap", dttmFormatter.format(t.getAuthoringTimestamp()));
	}
	

	
	/*
	private void connectToTemporalEntityNode(Node templateNode, Iui teIui) {
		Node teNode;
		if (iuiNode.containsKey(teIui)) {
			teNode = iuiNode.get(teIui);
		} else {
			teNode = getOrCreateNode(RtsNodeLabel.TEMPORAL_REGION, teIui.toString()); 
					//getOrCreateEntityNode(teIui, RtsNodeLabel.TEMPORAL_REGION);
		}
		
		templateNode.createRelationshipTo(teNode, RtsRelationshipType.iuite);
	}

	private void connectToNamingSystemNode(Node templateNode, Iui nsIui) {
		Node teNode;
		if (iuiNode.containsKey(nsIui)) {
			teNode = iuiNode.get(nsIui);
		} else {
			teNode = getOrCreateEntityNode(nsIui, RtsNodeLabel.INSTANCE);
		}
		
		templateNode.createRelationshipTo(teNode, RtsRelationshipType.ns);
	}//*/
	
	private void connectNodeToNode(Node sourceNode, RtsRelationshipType relType, 
			RtsNodeLabel targetNodeLabel, String targetNodeUi) {
		Node target;
		if (uiNode.containsKey(targetNodeUi)) {
			target = uiNode.get(targetNodeUi);
		} else {
			target = getOrCreateNode(targetNodeLabel, targetNodeUi);
		}
		
		sourceNode.createRelationshipTo(target, relType);
	}
	
	static String nodeQueryBase = "MERGE (n:[label] { ui : {value} }) return n";
	
	private Node getOrCreateNode(RtsNodeLabel targetNodeLabel,
			String targetNodeUi) {
		//build the query and parameters
		String query = nodeQueryBase.replace("[label]", targetNodeLabel.getLabelText());
		HashMap<String, Object> parameters = new HashMap<String, Object>();
		parameters.put("value", targetNodeUi);
	    
		//run the query.
	    ResourceIterator<Node> resultIterator = graphDb.execute( query, parameters ).columnAs( "n" );
	    Node n = resultIterator.next();
	    
	    //add node to cache
	    uiNode.put(targetNodeUi, n);
	    
	    //TODO change this to also throw a new type of exception, and transaction 
	    //	should roll back
	    if ( targetNodeLabel.equals(RtsNodeLabel.INSTANCE) ) {
	    	if ( !iuiToItsAssignmentTuple.containsKey(targetNodeUi) ) {
		    	System.err.println("ERROR: creating new entity with IUI " + targetNodeUi +
		    			" but this IUI has no corresponding assignment template!");
	    	}
	    }
		
		return n;
	}

	//static String templateByIuiQuery = "START n=node:nodes(iui = {value}) RETURN n";
	static String templateByIuiQuery = "MATCH (n:template { ui : {value} }) return n";
	
	boolean isTemplateInDb(RtsTuple t) {
		HashMap<String, Object> parameters = new HashMap<String, Object>();
		parameters.put("value", t.getTupleIui().toString());
		return graphDb.execute(templateByIuiQuery, parameters).hasNext();
	}
	
	/*
	static String INST_QUERY = "MERGE (n:instance {ui : {value}})"
			//+ "ON CREATE "
			+ "RETURN n";
			
	
	static String TR_QUERY = "MERGE (n:temporal_region {ui : {value}})"
			//+ "ON CREATE "
			+ "RETURN n";
			//*/
	
	static String ENTITY_QUERY = "MERGE (n:[label] {ui : {value}})"
			//+ "ON CREATE "
			+ "RETURN n";
	
	/*Node getOrCreateEntityNode(Iui iui, RtsNodeLabel label) {
		//setup the query with the proper node label and iui property
		String query = ENTITY_QUERY.replace("[label]", label.toString());
	    HashMap<String, Object> parameters = new HashMap<>();
	    parameters.put( "value", iui.toString() );
	    
	    //run the query.
	    ResourceIterator<Node> resultIterator = ee.execute( query, parameters ).columnAs( "n" );
	    Node result = resultIterator.next();
	    
	    //add the node to the cache
	    iuiNode.put(iui, result);
	    

	    if (!iuiToAssignmentTemplate.containsKey(iui)) {

	    }
	    return result;
	}
	
	private Node getOrCreateTypeNode(Uui universalUui) {
		//setup the query parameters with the uui
	    HashMap<String, Object> parameters = new HashMap<>();
	    parameters.put( "value", universalUui.toString() );
	    
	    //run the query.
	    ResourceIterator<Node> resultIterator = ee.execute( TYPE_QUERY, parameters ).columnAs( "n" );
	    Node result = resultIterator.next();
	    
	    return result;	
	}
	
	private Node getOrCreateRelationNode(String rui) {
		//TODO
		return null;
	}
	
	private Node getOrCreateDrNode(String data) {
		//TODO
		return null;
	}//*/
	
    void setupSchema() {
    	
    	/*
    	templateLabel = DynamicLabel.label("template");
    	instanceLabel = DynamicLabel.label("instance");
    	typeLabel = DynamicLabel.label("universal");
    	relationLabel = DynamicLabel.label("relation");
    	temporalRegionLabel = DynamicLabel.label("temporal_region");
    	dataLabel = DynamicLabel.label("data");
    	metadataLabel = DynamicLabel.label("metadata");
    	*/
    	
    	templateLabel = Label.label("template");
    	instanceLabel = Label.label("instance");
    	typeLabel = Label.label("universal");
    	relationLabel = Label.label("relation");
    	temporalRegionLabel = Label.label("temporal_region");
    	dataLabel = Label.label("data");
    	metadataLabel = Label.label("metadata");
    	
  	
        try ( Transaction tx2 = graphDb.beginTx() )
        {
            graphDb.schema()
                    .constraintFor( templateLabel )
                    .assertPropertyIsUnique( "iui" )
                    .create();
            
            graphDb.schema()
            		.constraintFor( instanceLabel )
            		.assertPropertyIsUnique( "iui" )
            		.create();
            
            graphDb.schema()
            		.constraintFor( typeLabel )
            		.assertPropertyIsUnique( "uui" )
            		.create();
            
            graphDb.schema()
            		.constraintFor( relationLabel )
            		.assertPropertyIsUnique( "rui" )
            		.create();
            
            graphDb.schema()
    				.constraintFor( dataLabel )
    				.assertPropertyIsUnique( "dr" )
    				.create();
            
            graphDb.schema()
            		.constraintFor( metadataLabel )
            		.assertPropertyIsUnique("c")
            		.create();
            
            graphDb.schema()
    				.constraintFor( metadataLabel )
    				.assertPropertyIsUnique("ct")
    				.create();         
            
            graphDb.schema()
    				.constraintFor( temporalRegionLabel )
    				.assertPropertyIsUnique("tref")
    				.create();   
            
            tx2.success();
        }
    }
    
    void setupMetadata() {
    	/*
    	 * Experimented with representing change types and reasons as nodes.  Seems like
    	 * too much overhead.
    	 * 
    	 */
    	/*
    	try ( Transaction tx3 = graphDb.beginTx() ) {
    		RtsChangeReason[] reasons = RtsChangeReason.values();
    		for (RtsChangeReason r : reasons) {
    			String value = r.toString();
    			/*Set up parameters of query.  
    			  *//*
    			HashMap<String, Object> parameters = new HashMap<String, Object>();
    			parameters.put("value", value);
    			
    			//run the query.
    			ExecutionResult er = ee.execute( CNODE_QUERY, parameters );
    			System.out.println(er.dumpToString());
    			List<String> cs = er.columns();
    			for (String c : cs) {
    				System.out.println(c);
    			}

    		    //Node n = (Node) er.columnAs("n").next();
    		}
    		
    		RtsChangeType[] types = RtsChangeType.values();
    		for (RtsChangeType t : types) {
    			String value = t.toString();
    			/*Set up parameters of query.  
    			  *//*
    			HashMap<String, Object> parameters = new HashMap<String, Object>();
    			parameters.put("value", value);
    			
    			//run the query.
    			ExecutionResult er = ee.execute( CTNODE_QUERY, parameters );
    			System.out.println(er.dumpToString());
    			List<String> cs = er.columns();
    			for (String c : cs) {
    				System.out.println(c);
    			}

    		    //Node n = (Node) er.columnAs("n").next();
    		}
    		
    		tx3.success();
    	}*/
    }
    
	public void addTemporalReference(TemporalReference t) {
		tempReferences.add(t);
	}
	
	public void addTemporalRegion(TemporalRegion t) {
		tempRegions.add(t);
	}
}