package dbn_jsmile;

import java.util.ArrayList;

public class Case {
	// each case represents the records of one patient
	public String case_number;
	private ArrayList<String> nodes;
	private ArrayList<Integer> states;
	private ArrayList<Integer> slices;
	public double prediction;
	public double deathPeriod; //period where patient died
	public int outcome; //0 = survival, 1 = dead
	public int nObservations;
	
	public Case(String case_number){
		this.case_number = case_number;
		this.nodes = new ArrayList<String>();
		this.states = new ArrayList<Integer>();
		this.slices = new ArrayList<Integer>();
		nObservations = 0;
	}
	public void addObservation(String node, int slice, int state){
		if (slice >= 0 && state >= 0 ) { //null values show up as -1 and get filtered out
			nodes.add(node);
			slices.add(slice);
			states.add(state);
			nObservations++;
		}
	}
	public String[] getNodes(){
		return nodes.toArray(new String[nodes.size()]);
	}
	public Integer[] getStates(){
		return states.toArray(new Integer[states.size()]);
	}
	public Integer[] getSlices(){
		return slices.toArray(new Integer[slices.size()]);
	}
	public String[] getNodes(int maxPeriod){
		//only return nodes until maxPeriod
		
		return nodes.toArray(new String[nodes.size()]);
	}
	public Integer[] getStates(int maxPeriod){
		return states.toArray(new Integer[states.size()]);
	}
	public Integer[] getSlices(int maxPeriod){
		return slices.toArray(new Integer[slices.size()]);
	}
}
