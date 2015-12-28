package dbn_jsmile;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import smile.Network;
import smile.SMILEException;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.mahout.classifier.evaluation.Auc;

import com.mysql.jdbc.ResultSetMetaData;

public class DBN_12m extends Network {
	public Network net;
	public HashMap<String,Case> cases;
	public ArrayList<String> nodeNames;
	
	public DBN_12m (String file){
		// Import the Network with already learned parameters
		this.net = new Network();
		net.readFile(file); 	 
		this.cases = new HashMap<String,Case>();
		this.nodeNames = new ArrayList<String>(Arrays.asList(this.net.getAllNodeIds()));
	}
	
	public static void main(String[] args) throws ClassNotFoundException{
		//Settings
		//be aware that first month on dialysis = period 0 in DBN
		int curPeriod = 1;  //using evidence until curPeriod (including that period)
		int predPeriod = 1; //predicting prob. of survival (including that period)
	 try {
		 	//Import Network
			//String file = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/networks/grow/4_age_alb_autonomy_cci_hosp_effect.xdsl"; 
			//String file = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/networks/condition/v1.xdsl"; 
			//String file = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/networks/grow/1_age_alb_inter_alb.xdsl"; 
			String file = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/networks/dialysis_v2_13_var_12m_less_arcs.xdsl"; 

			//String file = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/networks/dialysis_v2_13_var_less_param.xdsl"; 
			//String file = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/networks/dialysis_simple_v2_24slices.xdsl"; 
			DBN_12m dbnet = new DBN_12m(file);
	
			//Import Data from DB
			dbnet.getData();
			
			//simple test for a single patients
			dbnet.simpleTest(curPeriod, predPeriod);
			
			//For each patient: set evidence and get prediction
			for (String pid: dbnet.cases.keySet()){
				//set evidence
				dbnet.setEvidenceUntil(dbnet.cases.get(pid),curPeriod);
				//set prediction
				double surv = dbnet.getHazard(predPeriod); // is not really a hazard in this case,  but the prob for death_12m
				dbnet.cases.get(pid).prediction = 1-surv;
				//set outcome
				if(dbnet.cases.get(pid).deathPeriod -12 < predPeriod) {
					dbnet.cases.get(pid).outcome = 1; //dead within 12m
				}else {
					dbnet.cases.get(pid).outcome = 0; //survived next 12m
				}
				//System.out.println("P(surviving) longer than period "+predPeriod+" = "+surv);
				//System.out.println("Actual survived? "+dbnet.cases.get(pid).outcome );
				dbnet.net.clearAllEvidence();
			}

			//Evaluate Performance
			double auc = dbnet.evaluateAUC(curPeriod);
			System.out.println("AUC: "+auc);
			//dbnet.writePredictionsCSV("pred1.csv", curPeriod);   
		 }
		 catch (SMILEException e) {
		   System.out.println(e.getMessage());
		 }
	}
	
	public void setAllEvidence(String[] node,  int[] slice, int[] state){
		for(int i = 0; i < node.length; i++){   
			System.out.println("Try to set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
			net.setTemporalEvidence(node[i], slice[i], state[i]);
		}
		net.updateBeliefs();
	}
	public void setAllEvidence(String[] node,  int[] slice, String[] state){
		for(int i = 0; i < node.length; i++){ 
			System.out.println("Try to set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
				net.setTemporalEvidence(node[i], slice[i], state[i]);
		}
		net.updateBeliefs();
	}
	public void setEvidenceUntil(String[] node,  int[] slice, int[] state, int maxPeriod){
		for(int i = 0; i < node.length; i++){   
			//System.out.println("Try to set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
			if(slice[i]<= maxPeriod && !node[i].equals("Death")){
				net.setTemporalEvidence(node[i], slice[i], state[i]);
			}
		}
		net.updateBeliefs();
	}
	
	public void setEvidence(Case c){
		setAllEvidence(c.getNodes(), ArrayUtils.toPrimitive(c.getSlices()),  ArrayUtils.toPrimitive(c.getStates()));
	}
	public void setEvidenceUntil(Case c, int maxPeriod){
		//use only evidence until a maximum period
		setEvidenceUntil(c.getNodes(), ArrayUtils.toPrimitive(c.getSlices()),  ArrayUtils.toPrimitive(c.getStates()), maxPeriod);
	}
	
	public double[][] getHazards(int[] periods){
		//first dim of hazards = periods; second dim = probabilities of death = hazard
		double[][] hazards = new double[periods.length][2];
		//setting up first dim
		for(int i = 0; i< periods.length; i++){
		hazards[i][0] = periods[i];
		}
		// Getting the beliefs for "Dead" 
		for(int i = 0; i < periods.length; i++){
			hazards[i][1] = getHazard(i);
		   }
		return hazards;
	}
	public double getHazard(int period){
		// Get index of the outcome "Dead":
		String[] deathOutcomeIds = net.getOutcomeIds("Death");
		int outcomeIndex;
		for (outcomeIndex = 0; outcomeIndex < deathOutcomeIds.length; outcomeIndex++)
		  if ("Dead".equals(deathOutcomeIds[outcomeIndex]))
		    break;
		//Get belief value
		double[] values = net.getNodeValue("Death");
		double hazard = values[outcomeIndex+(period*2)];
		return hazard;
	}
	public double getSurvivalProb(int period){
		//returns cumulative survival probability 
		//includes surviving the specified period
		double prob = 1;
		for(int i=0; i<= period; i++){
			prob = prob*(1-getHazard(i));
		}
		return prob;
	}
	
	public double[] getSurvivalProbs(int period){
		//returns cumulative survival probabilities 
		//includes surviving the specified period
		double[] prob = new double[period+1];
		prob[0] = 1-(getHazard(0));
		for(int i=1; i<= period; i++){
			prob[i] = prob[i-1]*(1-getHazard(i));
			
		}
		return prob;
	}
	
	public double evaluateAUC(int curPeriod){
		Auc score = new Auc();
		int c=0;
		for (String pid: this.cases.keySet()){
			if(this.cases.get(pid).deathPeriod > curPeriod){ //CHANGED, was >=; only use patients that are still alive at landmark time- check again if >= makes sense 
			score.add(this.cases.get(pid).outcome, 1-this.cases.get(pid).prediction); //1- because prediction is surv prob, not death prob
			c++;
			}
		}
		System.out.println("used cases for auc: "+c);
		return score.auc();
	}
	public void writePredictionsCSV(String file, int curPeriod){
		BufferedWriter br;
		try {
			br = new BufferedWriter(new FileWriter(file));
			StringBuilder sb = new StringBuilder();
			for (String pid: this.cases.keySet()){
				if(this.cases.get(pid).deathPeriod > curPeriod){ //only use patients that are still alive at landmark time- check again if >= makes sense 
					sb.append(pid + ";");    
					sb.append(1-this.cases.get(pid).prediction + ";");    
					sb.append(this.cases.get(pid).outcome + ";");
					sb.append("\n");  
				}
			}
			br.write(sb.toString());
			br.close();	
		} catch (IOException e) {
			e.printStackTrace();
		}

	}		  

	public void getData()throws ClassNotFoundException{
		Class.forName("com.mysql.jdbc.Driver");  
		Connection connection = null;
		try {
			ResultSet r = null;
			connection = DriverManager 
				      .getConnection("jdbc:mysql://localhost:3306/dbo?user=root&password="); 
			Statement s = connection.createStatement(); 
			// prepare statement
			ArrayList<String> temp = this.nodeNames;
			//remove hidden variables from query since there won't be any observations
			temp.remove("Mortality_Risk");
			temp.remove("Infection_Risk");
			temp.remove("Cardiovascular_Risk");
			String colsToSelect = this.nodeNames.toString();
			colsToSelect = colsToSelect.substring(1, colsToSelect.length()-1).toLowerCase();
			String query = "SELECT pid, period, "+colsToSelect+" , period_of_death FROM 03_model_dbn_state_0_12m";
			System.out.println("query:"+query);
			//execute
			r = s.executeQuery(query);
			//printResultSet(r);

			//import as cases to DBN
			this.convertToCases(r);
		} catch (SQLException e) { 
		} finally { 
			if(connection != null) try { 
				if(!connection.isClosed()) connection.close();  
				  } catch(SQLException e) {
					  	e.printStackTrace(); 
				  } 
			} 
	}
	
	public void convertToCases(ResultSet r){
		//set structure
		String pid;
		Case curCase;
		try{
			  // iterate over results
			  while (r.next()) {
				  pid = r.getString("pid");
				  if (this.cases.containsKey(pid)){
					  curCase = this.cases.get(pid);
					 //System.out.println("Add record to: "+pid);
				  }else{
					  curCase = new Case(pid);
					  this.cases.put(pid, curCase);
					  this.cases.get(pid).deathPeriod =r.getInt("period_of_death")-1; //CHANGED, was -2; -1 because periods start with 0 in SMILE, -1 because time lag 
					 //System.out.println("New Case created: "+pid);
				  }
				  
				  //get values and add them to case
				  int curSlice = r.getInt("period")-1;
				  for(String name:this.nodeNames){
					  int obsState = r.getInt(name); //CHANGED, was: currently -1 because states in genie start with 0, but with 1 in DB
					  if(!r.wasNull()){ //necessary, because resultset treats null values as zeros by default
						  curCase.addObservation(name, curSlice, obsState);
					  }
				  }

				}
				}catch(SQLException e) {
				  	e.printStackTrace(); 
			  } 
	}
	
	public static void printResultSet(ResultSet r){
		try{
		  //Print results
		  while (r.next()) { 
			  //System.out.println("first_name: "+r.getString(1));
			  System.out.println("age: "+r.getString("age")+
					  ", alb: "+ r.getString("albumin")+
					  ", death: "+ r.getString("death")
					  ); 
			}
			}catch(SQLException e) {
			  	e.printStackTrace(); 
		  } 	
		}


	public void learnParameters(){
		
	}
	public void simpleTest(int curPeriod, int predPeriod){
		//set evidence
		this.setEvidenceUntil(this.cases.get("LIB0000001251"),curPeriod);
		//System.out.println("nodes: "+Arrays.toString(this.cases.get("LIB0000001251").getNodes()));

		//System.out.println("albumin should be null here: "+Arrays.toString(this.cases.get("LIB0000001251").getStates()));
		double surv2 = this.getHazard(predPeriod); //predict survival in predPeriod
		if(this.cases.get("LIB0000001251").deathPeriod -12 < predPeriod) {
			this.cases.get("LIB0000001251").outcome = 1;
		}else {
			this.cases.get("LIB0000001251").outcome = 0;
		}
		System.out.println("P(surviving) longer than period "+predPeriod+" = "+surv2);
		System.out.println("Actually dead? "+this.cases.get("LIB0000001251").outcome );
		System.out.println("Period of death = "+this.cases.get("LIB0000001251").deathPeriod);

		this.net.clearAllEvidence();
	}
}
