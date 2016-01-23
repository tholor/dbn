package dbn_jsmile;

import java.util.ArrayList;
import java.util.Collections;

public class Case {
	// each case represents the records of one patient
	public String case_number;
	private ArrayList<String> nodes;
	private ArrayList<Integer> states;
	private ArrayList<Integer> slices;
	public double prediction;
	public Integer deathPeriod; //period where patient died, -1 if death was not observed (censored patient)
	public Double age_onset; //exact age of patient at the begin of dialysis
	public int outcome; //0 = survival, 1 = dead
	public int nObservations;
	public int fold; //for cross-validation
	
	public Case(String case_number){
		this.case_number = case_number;
		this.nodes = new ArrayList<String>();
		this.states = new ArrayList<Integer>();
		this.slices = new ArrayList<Integer>();
		this.fold = 0;
		nObservations = 0;
	}
	public void addObservation(String node, int slice, int state){
		if (slice >= 0 ) { 
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
	public int maxSlice(){
		return Collections.max(this.slices);
	}
}
