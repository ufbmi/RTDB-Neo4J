package edu.ufl.ctsi.neo4j;

public enum RtsNodeLabel {
	INSTANCE("instance"),
	TYPE("universal"),
	RELATION("relation"),
	TEMPORAL_REGION("temporal_region"),
	DATA("data"),
	TEMPLATE("template");
	
	String labelText;
	
	RtsNodeLabel(String labelText) {
		this.labelText = labelText;
	}
	
	public String getLabelText() {
		return labelText;
	}
	
	@Override
	public String toString() {
		return labelText;
	}
}
