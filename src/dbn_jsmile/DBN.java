package dbn_jsmile;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;

import smile.Network;
import smile.SMILEException;

import org.apache.commons.lang3.ArrayUtils;


public class DBN extends Network {
	public Network net;
	public HashMap<String,Case> cases;
	
	public DBN (String file){
		// Import the Network with already learned parameters
		this.net = new Network();
		net.readFile(file); 	 
		this.cases = new HashMap<String,Case>();
	}
	
	public static void main(String[] args) throws ClassNotFoundException{
		
		//Settings
		int curPeriod = 3;
		int predPeriod = 5;
	 try {
		 	//Import Network
			String file = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/networks/dialysis_simple_v2_24slices.xdsl"; 
			DBN dbnet = new DBN(file);
		
			//Import Data from DB
			dbnet.getData();
			
			//For each patient: set evidence and get prediction
			for (String pid: dbnet.cases.keySet()){
				System.out.println("Patient: "+ pid);
				dbnet.setEvidenceUntil(dbnet.cases.get(pid),curPeriod);
				double surv = dbnet.getSurvivalProb(predPeriod); //predict survival in t=5
				dbnet.cases.get(pid).prediction = surv;
				if(dbnet.cases.get(pid).deathPeriod > predPeriod) {
					dbnet.cases.get(pid).outcome = 0;
				}else {
					dbnet.cases.get(pid).outcome = 1;
				}
				System.out.println("P(surviving) longer than period "+predPeriod+" = "+surv);
				System.out.println("Actual survived? "+dbnet.cases.get(pid).outcome );
				dbnet.net.clearAllEvidence();
			}

			//Evaluate Performance
			double auc = dbnet.evaluateAUC(curPeriod);
			System.out.println("AUC: "+auc);
			//dbnet.writePredictionsCSV("pred1.csv", curPeriod);
			
//			
//		   // Set Evidence
//		   String[] nodes = {"Age","Albumin","Age","Albumin"};
//		   int[] slices = {0,0,1,1};
//		   //int[] states = {0,1,1,2};
//		   String[] states = {"a_less_60","b_3_35","b_60_70","c_35_40"};
//		   dbnet.setAllEvidence(nodes, slices, states);
			
//		   // Get hazards
//		   int[] periods_of_interest = {0,1,2,3,4,5};
//		   double[][] pred = dbnet.getHazards(periods_of_interest);
//		   for (int i = 0; i< periods_of_interest.length; i++){
//			   System.out.println("P(Dead) in period "+pred[i][0]+" = "+ pred[i][1]);
//		   }
			
//		   //Get cumulated Surv. probs.
//		   int period = 5;
//		   double surv = dbnet.getSurvivalProb(period);
//		   System.out.println("P(surviving) longer than period "+period+" = "+surv);
//		   double[] survs = dbnet.getSurvivalProbs(period);
//		   for (int i = 0; i< period; i++){
//			   System.out.println("P(surviving) longer than period "+i+" = "+survs[i]);
//		   }	   
		 }
		 catch (SMILEException e) {
		   System.out.println(e.getMessage());
		 }
	}
	
	private void setAllEvidence(String[] node,  int[] slice, int[] state){
		for(int i = 0; i < node.length; i++){   
			System.out.println("Try to set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
			net.setTemporalEvidence(node[i], slice[i], state[i]);
		}
		net.updateBeliefs();
	}
	private void setAllEvidence(String[] node,  int[] slice, String[] state){
		for(int i = 0; i < node.length; i++){ 
			System.out.println("Try to set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
				net.setTemporalEvidence(node[i], slice[i], state[i]);
		}
		net.updateBeliefs();
	}
	private void setEvidenceUntil(String[] node,  int[] slice, int[] state, int maxPeriod){
		for(int i = 0; i < node.length; i++){   
			//System.out.println("Try to set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
			if(slice[i]<= maxPeriod){
				net.setTemporalEvidence(node[i], slice[i], state[i]);
			}
		}
		net.updateBeliefs();
	}
	
	private void setEvidence(Case c){
		setAllEvidence(c.getNodes(), ArrayUtils.toPrimitive(c.getSlices()),  ArrayUtils.toPrimitive(c.getStates()));
	}
	private void setEvidenceUntil(Case c, int maxPeriod){
		//use only evidence until a maximum period
		setEvidenceUntil(c.getNodes(), ArrayUtils.toPrimitive(c.getSlices()),  ArrayUtils.toPrimitive(c.getStates()), maxPeriod);
	}
	
	private double[][] getHazards(int[] periods){
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
	private double getHazard(int period){
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
	private double getSurvivalProb(int period){
		//returns cumulative survival probability 
		//includes surviving the specified period
		double prob = 1;
		for(int i=0; i<= period; i++){
			prob = prob*(1-getHazard(i));
		}
		return prob;
	}
	
	private double[] getSurvivalProbs(int period){
		//returns cumulative survival probabilities 
		//includes surviving the specified period
		double[] prob = new double[period+1];
		prob[0] = 1-(getHazard(0));
		for(int i=1; i<= period; i++){
			prob[i] = prob[i-1]*(1-getHazard(i));
			
		}
		return prob;
	}
	
	private double evaluateAUC(int curPeriod){
		Auc score = new Auc();
		int c=0;
		for (String pid: this.cases.keySet()){
			if(this.cases.get(pid).deathPeriod >= curPeriod){ //only use patients that are still alive at landmark time- check again if >= makes sense 
			score.add(this.cases.get(pid).outcome, 1-this.cases.get(pid).prediction);
			c++;
			}
		}
		System.out.println("used cases for auc: "+c);
		return score.auc();
	}
	private void writePredictionsCSV(String file, int curPeriod){
		BufferedWriter br;
		try {
			br = new BufferedWriter(new FileWriter(file));
			StringBuilder sb = new StringBuilder();
			for (String pid: this.cases.keySet()){
				if(this.cases.get(pid).deathPeriod >= curPeriod){ //only use patients that are still alive at landmark time- check again if >= makes sense 
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

	private void getData()throws ClassNotFoundException{
		Class.forName("com.mysql.jdbc.Driver");  
		Connection connection = null;
		try {
			ResultSet r = null;
			connection = DriverManager 
				      .getConnection("jdbc:mysql://localhost:3306/dbo?user=root&password="); 
			Statement s = connection.createStatement(); 
			r = s.executeQuery("SELECT pid, period, age, albumin, death, period_of_death FROM 03_model_dbn ");
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
	
	private void convertToCases(ResultSet r){
		//set structure
		String pid;
		Case curCase;
		try{
			  // iterate over results
			  while (r.next()) {
				  pid = r.getString("pid");
				  if (this.cases.containsKey(pid)){
					  curCase = this.cases.get(pid);
					  System.out.println("Add record to: "+pid);
				  }else{
					  curCase = new Case(pid);
					  this.cases.put(pid, curCase);
					  this.cases.get(pid).deathPeriod =r.getInt("period_of_death")-2; 
					  System.out.println("New Case created: "+pid);
				  }
				  //get values and add them to case
				  int curSlice = r.getInt("period")-1;
				  curCase.addObservation("Age",curSlice,r.getInt("age")-1) ; //-1 only temporary because states start to count at 0 in SMILE
				  curCase.addObservation("Albumin",curSlice,r.getInt("albumin")-1);
				  //System.out.println("current albumin value = "+r.getInt("albumin"));
				  curCase.addObservation("Death",curSlice,r.getInt("death")-1) ;
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
}


