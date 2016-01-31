package dbn_jsmile;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.math.BigDecimal;

import smile.Network;
import smile.SMILEException;
import smile.learning.DataMatch;
import smile.learning.DataSet;
import smile.learning.EM;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.mahout.classifier.evaluation.Auc;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.mysql.jdbc.ResultSetMetaData; 

public class DBN  {
	public Network net;
	public HashMap<String,Case> cases;
	public ArrayList<String> nodeNames;
	
	public DBN (String file){
		// Import the Network with already learned parameters
		this.net = new Network();
		net.readFile(file); 	 
		this.cases = new HashMap<String,Case>();
		this.nodeNames = new ArrayList<String>(Arrays.asList(this.net.getAllNodeIds()));
	}
	
	public static void main(String[] args) throws ClassNotFoundException{
		//****************************************
		//SETTINGS
		//Prediction
		int[] curPeriods = {3,6,9,12}; //landmarks s from which we want to predict
		final int[] predHorizons = {1,3,6,12}; //horizon w = number of months predicting ahead from each landmark (prob. of survival including that last period)
		final int timelag = 1; // number of periods used as time lag (e.g. 1 => using covariates from t-1 together with "death" in t)
		final boolean fixedHorizon = false; //if probabilities in node "death" already reflect cum. prob. of surviving a fixed interval instead of monthly hazards (e.g. surviving the next 12 months)
		final int exludingPeriodZero = 0; //1 means period zero is not included, the number of the other periods has to be adjusted by -1
		final boolean foldedCV = true; //Cross validation
		int numFolds = 5;
		final int repeats = 1;
		final boolean writePredictions = false;
		//Learning
		final boolean learningParams = true; //shall parameter be learned
		final int maxPeriod = 36; //maximum period from the data that should be used for learning
		final boolean uniformize = false; //uniformize initial parameters of CPTs
		final boolean randomize = false; // randomize initial parameters of CPTs
		final int equivalentSampleSize = 1; // strength of prior beliefs of initial parameters in the CPTs
		final boolean fromCompleteData = false; // specifies whether one network should be learned from the complete data set (in addition to the folded sets)
		//Files
		final String path = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/networks/batch_testing/batch10/";
		//final String dataFile = path+"small.txt";
		//final String[] networks = {"v9_age_inter.xdsl","v13_pneu_rev.xdsl"};
		final String networkFolder = path+"input/";
		final String tableName = "03_model_dbn_state_0";
		final String resultsFile = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/evaluation/performance.xlsx";
		final String comment = "high variation inter-arcs, forecasted age, time";
		//****************************************
		for (int i = 0; i < curPeriods.length; i++) curPeriods[i] -= exludingPeriodZero; //adjust periods, if period zero is excluded

		File folder = new File(networkFolder);
		File[] listOfFiles = folder.listFiles();
		for(File network:listOfFiles){
			long start_time = System.currentTimeMillis();
			String networkFile = network.getAbsolutePath();
			String networkName = network.getName();
			String networkFileOut = path+"/output/smile_"+networkName;
		try {
		 	//Import network
			System.out.println("*****Network: "+networkName+"*****");
			DBN dbnet = new DBN(networkFile);

			if(!dbnet.net.isTarget("Death"))dbnet.net.setTarget("Death",true); //set target node for faster inference
			//Import data from database
			dbnet.getData(tableName, timelag, maxPeriod, exludingPeriodZero);
			//Simple test
			//dbnet.simpleTest("LIB0000001964",  6,  18, 1, false);
			//System.out.println("missingRatio"+dbnet.missingData("LIB0000000074", 13));

			//Learn parameters of one network from complete data set
			if(fromCompleteData){
				dbnet.learnParametersDB(path+"/output/smile_compl_"+networkName, 0, false ,randomize, equivalentSampleSize, uniformize);
				dbnet.net.clearAllEvidence();
			}
			
			//****************************************
			//Evaluate with cross validation
			for(int rep = 0; rep < repeats; rep++){
			//divide data set for k-fold CV and prepare array for AUCs
			Double[][][] auc;
			if(foldedCV) {
				dbnet.foldDataSet(numFolds);
				auc = new Double[curPeriods.length][predHorizons.length][numFolds];
			}else{
				auc = new Double[curPeriods.length][predHorizons.length][1];
				numFolds = 1;
			}
			//Learn, predict, evaluate (for each fold)
			for (int fold = 0; fold < numFolds; fold++){
			System.out.println("*** Fold " + fold + ":");
			//Learn parameters
					if(learningParams){
					dbnet.learnParametersDB(networkFileOut, fold, foldedCV, randomize, equivalentSampleSize, uniformize);
					}
					dbnet.net.clearAllEvidence();
				
			//Set evidence and get predictions
					System.out.println("Starting with evidence and prediction...");
					for(int curIndex = 0; curIndex < curPeriods.length; curIndex++){
						for(int predIndex = 0; predIndex < predHorizons.length; predIndex++){	
							int curPeriod = curPeriods[curIndex];
							int predPeriod = curPeriod+predHorizons[predIndex];
							System.out.println("\t Predict from period "+curPeriod+" to period "+predPeriod);
							for (String pid: dbnet.cases.keySet()){
								if(dbnet.cases.get(pid).fold==fold){
									//set evidence
									//System.out.println("Set evidence for patient "+pid);
									dbnet.setEvidenceUntil(dbnet.cases.get(pid),curPeriod, timelag);
									//set "forecasted" evidence
									dbnet.setNodeEvidenceUntil("Age",dbnet.cases.get(pid),curPeriod,predPeriod-timelag); // for age
									if(dbnet.nodeNames.contains("Time_dialysis"))dbnet.setNodeEvidenceUntil("Time_dialysis",dbnet.cases.get(pid),curPeriod,predPeriod-timelag); //for time on dialysis
									//if(dbnet.nodeNames.contains("High_cci"))dbnet.setNodeEvidenceUntil("High_cci",dbnet.cases.get(pid),curPeriod,predPeriod-timelag); //for CCI

									//Predict survival for predPeriod
									double surv = dbnet.getSurvivalProb(predPeriod, timelag, fixedHorizon); 
									dbnet.cases.get(pid).prediction = surv;
									//Set real outcome
									if(dbnet.cases.get(pid).deathPeriod != null && dbnet.cases.get(pid).deathPeriod <= predPeriod) { //-1 results from "day of death => null" in the data base
										dbnet.cases.get(pid).outcome = 1;
									}else {
										dbnet.cases.get(pid).outcome = 0;
									}
									if(writePredictions)dbnet.writePredictionsCSV("pred6_from_complete_data.csv", curPeriod);   
									//System.out.println("P(surviving) longer than period "+predPeriod+" (pid: "+pid+"= "+surv);
									//System.out.println("Actually dead? "+dbnet.cases.get(pid).outcome );
									dbnet.net.clearAllEvidence();
								}
							}
			
						//Evaluate Performance of current fold
						//System.out.println("\t Evaluating AUC...");
						//double auc = dbnet.evaluateAUC(curPeriod);
						auc[curIndex][predIndex][fold] = dbnet.evaluateCrossValAUC(curPeriod, fold);
						System.out.println("\t -> AUC = "+auc[curIndex][predIndex][fold]);
						}
					}
			}
			//****************************************
			//get average AUCs	
			System.out.println("********Summary*********");
			String[] avg_aucs = new String[curPeriods.length*predHorizons.length];
			int c = 0;
			for(int to = 0; to < predHorizons.length; to++){
				for(int from = 0; from < curPeriods.length; from++){
					double avg_auc = 0;
					if(foldedCV){
						for(double i:auc[from][to]){
							//System.out.println("AUC from period "+curPeriods[from]+" to period "+(curPeriods[from]+predHorizons[to])+": "+i);
							avg_auc += i;
						}
						avg_auc = avg_auc/auc[from][to].length;
						avg_aucs[c] = String.format("%.3g%n",avg_auc);
					}else{
						avg_auc = auc[from][to][0];
					}
				System.out.println("Average AUC "+curPeriods[from]+" -> "+(curPeriods[from]+predHorizons[to])+"= " + avg_auc);
				c++;
				}
			}
			//write results to excel file
			long elapsed_time = System.currentTimeMillis()-start_time;
			String duration = Long.toString(TimeUnit.MINUTES.convert(elapsed_time, TimeUnit.MILLISECONDS))+ " Min";
			dbnet.writeResults(resultsFile, networkName,"", tableName, comment+", rep: "+rep, uniformize, maxPeriod, timelag, avg_aucs, duration);
		}
		}
		 catch (SMILEException e) {
		   System.out.println(e.getMessage());
		 }
		}
	}	

	public void getData(String tableName, int timelag, int maxPeriod, int exludingPeriodZero)throws ClassNotFoundException{
		System.out.println("Starting data import...");
		Class.forName("com.mysql.jdbc.Driver");  
		Connection connection = null;
		try {
			ResultSet r = null;
			connection = DriverManager 
				      .getConnection("jdbc:mysql://localhost:3306/dbo?user=root&password="); 
			Statement s = connection.createStatement(); 
			// prepare statement
			//remove hidden variables from query since there won't be any observations
			ArrayList<String> temp = new ArrayList<String>(this.nodeNames);
			temp.remove("Mortality_Risk");
			temp.remove("Infection_Risk");
			temp.remove("Cardiovascular_Risk");
			temp.remove("Bone_Risk");
			temp.remove("General_condition");

			String colsToSelect = temp.toString();
			colsToSelect = colsToSelect.substring(1, colsToSelect.length()-1).toLowerCase();
			String query = "SELECT pid, period, "+colsToSelect+", period_of_death, age_onset FROM "+tableName+" where period <="+(maxPeriod+exludingPeriodZero)+" AND period !=" + (exludingPeriodZero-1);//+" AND pid like 'LIB0000022%'"; //+1 because of notation differences in mySQL database
			//" where period < 4 AND pid in ('LIB0000000074','LIB0000001251','LIB0000001755','LIB0000001867','LIB0000001964')
			System.out.println("Query: "+query);
			//execute
			r = s.executeQuery(query);
			//printResultSet(r);
			//number of rows
			r.last();
			int nrows = r.getRow();
			r.beforeFirst();
			System.out.println("-> Result: table with "+nrows+" rows and "+r.getMetaData().getColumnCount()+" columns ");

			//import as cases to DBN object
			this.convertToCases(r, timelag, exludingPeriodZero);
		} catch (SQLException e) { 
		} finally { 
			if(connection != null) try { 
				if(!connection.isClosed()) connection.close();  
				  } catch(SQLException e) {
					  	e.printStackTrace(); 
				  } 
			} 
	}	
	public void convertToCases(ResultSet r, int timelag, int exludingPeriodZero){
		//set structure
		String pid;
		Case curCase;
		ArrayList<String> nodeNames = new ArrayList<String>(this.nodeNames);
		//remove hidden variables from query since there won't be any observations
		nodeNames.remove("Mortality_Risk");
		nodeNames.remove("Infection_Risk");
		nodeNames.remove("Cardiovascular_Risk");
		nodeNames.remove("Bone_Risk");
		nodeNames.remove("General_condition");

		try{
			  // iterate over results
			  while (r.next()) {
				  pid = r.getString("pid");
				  if (this.cases.containsKey(pid)){
					  curCase = this.cases.get(pid);
					 //System.out.println("Add record to: "+pid);
				  }else{
					  //System.out.println("New Case created: "+pid);
					  curCase = new Case(pid);
					  this.cases.put(pid, curCase);
					  int periodDeath = r.getInt("period_of_death");
					  Double age_onset = r.getDouble("age_onset");
					  if(!r.wasNull()){
						  this.cases.get(pid).deathPeriod = periodDeath-exludingPeriodZero; //CHANGED, was -1 because periods start with 0 in SMILE
						  this.cases.get(pid).age_onset = age_onset;
						  //System.out.println("Period of death = "+periodDeath);
					  }
				  }			  
				  //get values and add them to case
				  int curSlice = r.getInt("period")-exludingPeriodZero;

				  for(String name:nodeNames){
					  int obsState = r.getInt(name);
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

	public void learnParameters(String dataFile, String networkFileOut,boolean foldedCV,boolean randomize, int equivalentSampleSize, boolean uniformize){
		System.out.print("\t Starting parameter learning...");
		//import DataSet from File
		DataSet ds = new DataSet();
		ds.readFile(dataFile);
		
		//matching columns with variables
		//Requirement: states match exactly the values in the data set; Missing values are coded as blank ""
		int numCol = ds.getVariableCount();
		ArrayList<DataMatch> tempMatching = new ArrayList<DataMatch>();
		String colName;
		String curNodeName;
		int curSlice;
		int nodeNum;
		for(int col = 0; col < numCol; col++){
				//get name of current column in the data set
				colName = ds.getVariableId(col);
				//separate string in name and slice number
				Pattern pattern = Pattern.compile("\\d+");
				Matcher matcher = pattern.matcher(colName);
				if(matcher.find()){
					curSlice = Integer.parseInt(matcher.group(0));
					curNodeName = StringUtils.capitalize(colName.substring(0,matcher.start()-1));
					//System.out.println("cur node name"+ curNodeName);
				}else{
					//if the column name does not contain any number => period 0 (GENIE naming convention)
					curSlice = 0;
					curNodeName = StringUtils.capitalize(colName);
				}

				// find related node number in the network
				if(Arrays.asList(net.getAllNodeIds()).contains(curNodeName)){
					nodeNum = net.getNode(curNodeName);
				    //System.out.println("Colname: "+colName+", nodeName: "+curNodeName+", slice: "+ curSlice+",NodeNum: "+ nodeNum);
					tempMatching.add(new DataMatch(col,nodeNum,curSlice)); //associate: column, node, slice
				}else{
					//remove variable from the dataSet
					System.out.println("No node found for columnname: "+colName+" nodeName: "+curNodeName+", slice: "+ curSlice);
				}
		}

		//Convert dataMatch array
		DataMatch[] matching = tempMatching.toArray(new DataMatch[tempMatching.size()]);
		//learning
		final EM em = new EM();
		
		em.setRandomizeParameters(randomize);
		//em.setSeed(2);
		em.setEqSampleSize(equivalentSampleSize);
		em.setUniformizeParameters(uniformize);
		em.setRelevance(false);
		em.learn(ds, net, matching);
		net.writeFile(networkFileOut);
		System.out.println("Done");
	}
	public void learnParametersDB(String networkFileOut, int fold, boolean foldedCV,boolean randomize, int equivalentSampleSize, boolean uniformize){
		int varIndex;
		int curRecord;
		String str;
		String[] curNodes;
		Integer[] curStates;
		Integer[] curSlices;
		DataSet ds = new DataSet();
		System.out.print("Starting parameter learning...");
		//add variables
		for(String varName : this.nodeNames){
			ds.addIntVariable(varName);
			//System.out.println("added "+varName+" to data set (index = "+ds.findVariable(varName)+")");
			//add the variables for further time slices (e.g. name_1, name_2 ...)
			for(int i = 1; i <= this.getMaxSlice(); i++){
				//prepare string
				 str = varName+"_"+i;
				//add variable
				ds.addIntVariable(str);	
				//System.out.println("added "+str+" to data set(index = "+ds.findVariable(str)+")");
			}
		}
		//add records
		curRecord = 0;
		for(String pid: this.cases.keySet()){
			//System.out.println("curPatient: "+pid);
			//if the patient is in one of the folds for training or k-fold-cv is not used
			if(this.cases.get(pid).fold!=fold || !foldedCV){
				//System.out.println("curPatient is in correct fold: "+pid);
				ds.addEmptyRecord();
				curNodes = this.cases.get(pid).getNodes();
				curStates = this.cases.get(pid).getStates();
				curSlices = this.cases.get(pid).getSlices();
				//add each observation
				for(int i =0; i < this.cases.get(pid).nObservations; i++){
					if (curSlices[i] == 0){
						varIndex = ds.findVariable(curNodes[i]);
					}else{
						varIndex = ds.findVariable(curNodes[i]+"_"+curSlices[i]);
					}
					ds.setInt(varIndex, curRecord, curStates[i]);	
					//System.out.println("added record: varIndex="+varIndex+" curRecord: "+curRecord+" curState: "+curStates[i]);
				}
				curRecord++;
			}
		}

		//matching columns with variables
		//Requirement: states match exactly the values in the data set; Missing values are coded as blank ""
		int numCol = ds.getVariableCount();
		ArrayList<DataMatch> tempMatching = new ArrayList<DataMatch>();
		String colName;
		String curNodeName;
		int curSlice;
		int nodeNum;
		for(int col = 0; col < numCol; col++){
				//get name of current column in the data set
				colName = ds.getVariableId(col);
				//separate string in name and slice number
				Pattern pattern = Pattern.compile("\\d+");
				Matcher matcher = pattern.matcher(colName);
				if(matcher.find()){
					curSlice = Integer.parseInt(matcher.group(0));
					curNodeName = StringUtils.capitalize(colName.substring(0,matcher.start()-1));
					//System.out.println("cur node name"+ curNodeName);
				}else{
					//if the column name does not contain any number => period 0 (GENIE naming convention)
					curSlice = 0;
					curNodeName = StringUtils.capitalize(colName);
				}

				// find related node number in the network
				if(Arrays.asList(net.getAllNodeIds()).contains(curNodeName)){
					nodeNum = net.getNode(curNodeName);
				    //System.out.println("Colname: "+colName+", nodeName: "+curNodeName+", slice: "+ curSlice+",NodeNum: "+ nodeNum);
					tempMatching.add(new DataMatch(col,nodeNum,curSlice)); //associate: column, node, slice
				}else{
					System.out.println("No node found for columnname: "+colName+" nodeName: "+curNodeName+", slice: "+ curSlice);
				}
		}
		//Convert dataMatch array
		DataMatch[] matching = tempMatching.toArray(new DataMatch[tempMatching.size()]);
		//learning
		final EM em = new EM();
		
		em.setRandomizeParameters(randomize);
		//em.setSeed(2);
		em.setEqSampleSize(equivalentSampleSize);
		em.setUniformizeParameters(uniformize);
		em.setRelevance(false);
		em.learn(ds, net, matching);
		net.writeFile(networkFileOut);
		System.out.println("Done");
	}
	
	public void setAllEvidence(String[] node,  int[] slice, int[] state){
		for(int i = 0; i < node.length; i++){   
			//System.out.println("Try to set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
			net.setTemporalEvidence(node[i], slice[i], state[i]);
		}
		net.updateBeliefs();
	}
	public void setAllEvidence(String[] node,  int[] slice, String[] state){
		for(int i = 0; i < node.length; i++){ 
			//System.out.println("Try to set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
				net.setTemporalEvidence(node[i], slice[i], state[i]);
		}
		net.updateBeliefs();
	}
	public void setEvidenceUntil(String[] node,  int[] slice, int[] state, int maxPeriod, int timelag){
		for(int i = 0; i < node.length; i++){   
			//System.out.println("Try to set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
			//1) only set evidence until the period where a prediction should be made
			//2) if a time lag is used for the observations in contrast to "death" event: do not use the observations of "death" that would look into the future
			//no time lag
			if(timelag == 0 && slice[i] <= maxPeriod){
				net.setTemporalEvidence(node[i], slice[i], state[i]);
				//System.out.println("Successfully set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
			}else{
			//time lag  
				if(timelag > 0 && (slice[i] <= maxPeriod-timelag || (slice[i] <= maxPeriod && !node[i].equals("Death")))){
					net.setTemporalEvidence(node[i], slice[i], state[i]);
					//System.out.println("Successfully set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);	
				}
			}
		}
		net.updateBeliefs();
	}	
	public void setEvidence(Case c){
		setAllEvidence(c.getNodes(), ArrayUtils.toPrimitive(c.getSlices()),  ArrayUtils.toPrimitive(c.getStates()));
	}
	public void setEvidenceUntil(Case c, int maxPeriod, int timelag){
		//use only evidence until a maximum period
		setEvidenceUntil(c.getNodes(), ArrayUtils.toPrimitive(c.getSlices()),  ArrayUtils.toPrimitive(c.getStates()), maxPeriod, timelag);
	}
	public void setNodeEvidenceUntil(String nodeName, Case c,int curPeriod, int predPeriod) {
		//set evidence for one particular node from curPeriod until predPeriod
		//for the deterministic nodes age and time_dialysis the states will be calculated
		//for other nodes the last observation before curPeriod will be used 
		String[] nodesIn = c.getNodes();
		Integer[] slicesIn = c.getSlices();
		Integer[] statesIn = c.getStates();
		String name;
		
		//get last observation before landmark
		int lastPeriod = 0;
		Integer lastState = null;
		if(!nodeName.equals("Age") && !nodeName.equals("Time_dialysis")){
			for(int i = 0; i < nodesIn.length; i++){
				name = nodesIn[i];
				if(name.equals(nodeName) && slicesIn[i]<= predPeriod && statesIn[i] != null){
					lastState = statesIn[i];
				}
			}
		}
		// set states for all period between landmark and predicted period
		for(int p = curPeriod; p <= predPeriod; p++){
			switch(nodeName){ 		
			case "Age": 
				//calculate age in period p
				int age = (int)(c.age_onset+((double)p/12));
				if(age < 60) age = 0; 
				if(age >= 60 && age < 70) age = 1;
				if(age >= 70 && age < 80) age = 2;
				if(age >= 80) age = 3;
				this.net.setTemporalEvidence(nodeName, p, age);
				//System.out.println("Set: age = "+age+" for "+c.case_number+" in period "+p);
				break;
			case "Time_dialysis":
				//get state for time node
				int time;
				if(p <= 6){
					time = 0;
				}else{
					time = 1;
				}
				this.net.setTemporalEvidence(nodeName, p, time);
				//System.out.println("Set: time = "+time+" for "+c.case_number+" in period "+p);
				break;
			default:
				this.net.setTemporalEvidence(nodeName, p, lastState); //all other nodes use last observation
				//System.out.println("Set: "+nodeName+" = "+lastState+" for "+c.case_number+" in period "+p);
				break;
			}	
		}		
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
		//returns the probability of the state "dead" of the node "death" in a certain period
		// Get index of the outcome "Dead":
		String[] deathOutcomeIds = net.getOutcomeIds("Death");
		int outcomeIndex;
		for (outcomeIndex = 0; outcomeIndex < deathOutcomeIds.length; outcomeIndex++)
		  if ("Dead".equals(deathOutcomeIds[outcomeIndex]))
		    break;
		//Get belief value
		double[] values = net.getNodeValue("Death");
		double hazard = values[outcomeIndex+(period*2)];
		//System.out.println("hazard in period "+period +"= "+hazard);
		return hazard;
	}
	
	public double getSurvivalProb(int period, int timelag, boolean fixedHorizon){
		//returns cumulative survival probability 
		//includes surviving the specified period
		double prob = 1;
		
		//case 1: the node death contains hazard for each period
		if(!fixedHorizon){
			for(int i=0; i<= period-timelag; i++){
				prob = prob*(1-getHazard(i));
			}
		}
		//case 2: the node "death" already contains probability for surviving the interval until a fixed horizon (e.g. next 12 months)
		else{
			prob = 1-getHazard(period-timelag); //should result in the same as curPeriod
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
		int c = 0;
		int dead = 0;
		int alive = 0;
		for (String pid: this.cases.keySet()){
			if(this.cases.get(pid).deathPeriod == null || this.cases.get(pid).deathPeriod > curPeriod){ //CHANGED, was >=; only use patients that are still alive at landmark time- check again if >= makes sense 
				score.add(this.cases.get(pid).outcome, 1-this.cases.get(pid).prediction);
				//System.out.println("added pid: "+pid+", outcome: "+this.cases.get(pid).outcome+", prediction: "+(1-this.cases.get(pid).prediction));
				c++;
				if(this.cases.get(pid).outcome==0){
					alive++;
				}else{
					dead++;
				}
			}
		}
		System.out.println("\t -> used cases for auc: "+c+"(dead = "+dead+" /alive = "+ alive+")");
		return score.auc();
	}
	public double evaluateCrossValAUC(int curPeriod, int fold){
		Auc score = new Auc();
		int c=0;
		int dead = 0;
		int alive = 0;
		for (String pid: this.cases.keySet()){
			if((this.cases.get(pid).deathPeriod == null ||this.cases.get(pid).deathPeriod > curPeriod) && this.cases.get(pid).fold == fold && missingData(pid, curPeriod) == 0){ //CHANGED, was >=; only use patients that are still alive at landmark time- check again if >= makes sense 
				//System.out.println("P(dead) "+pid+"= "+(1-this.cases.get(pid).prediction)+ " outcome: "+this.cases.get(pid).outcome + " deathPeriod = "+this.cases.get(pid).deathPeriod);
				score.add(this.cases.get(pid).outcome, 1-this.cases.get(pid).prediction);
				c++;
				if(this.cases.get(pid).outcome==0){
					alive++;
				}else{
					dead++;
				}
			}
		}
		System.out.println("\t -> used cases for auc: "+c+"(dead = "+dead+" /alive = "+ alive+")");	
		return score.auc();
	}
	private Double missingData(String pid, int curPeriod){
		//number of variables
		//remove hidden variables 
		ArrayList<String> temp = new ArrayList<String>(this.nodeNames);
		temp.remove("Mortality_Risk");
		temp.remove("Infection_Risk");
		temp.remove("Cardiovascular_Risk");
		temp.remove("Bone_Risk");
		temp.remove("General_condition");
		double numVars = (double)temp.size();
		//System.out.println("numVars:"+numVars);
		//number of observed values
		Integer[] slices = this.cases.get(pid).getSlices();
		double i = 0;
		for(int s : slices){
			if(s == curPeriod)i++;
		}
		//System.out.println("numVal:"+i);

		Double missingRatio =  (1-(i/numVars));
		return missingRatio;
	}
	public DataSet convertStateNumToStateName(DataSet ds){
		int ncol = ds.getVariableCount();
		int nrec = ds.getRecordCount();
		int stateNum;
		String stateName;
		String colName; 
		String curNodeName; 
		
		for(int col = 0; col > ncol; col++){
			//get name of current column in the data set
			colName = ds.getVariableId(col);
			//separate string in name and slice number
			Pattern pattern = Pattern.compile("\\d+");
			Matcher matcher = pattern.matcher(colName);
			if(matcher.find()){
				curNodeName = StringUtils.capitalize(colName.substring(0,matcher.start()-1));
			}else{
				//if the column name does not contain any number => period 0 (GENIE naming convention)
				curNodeName = StringUtils.capitalize(colName);
			}
			for(int rec = 0; rec <nrec; rec++){
				//get value
				stateNum = ds.getInt(col, rec);
				//translate
				stateName = this.net.getOutcomeId(curNodeName, stateNum);
				//update
				//ds.setInt(col, rec,);
				
				
			}
		}
		
		return ds;
	}
	private void simpleTest(String pid, int curPeriod, int predPeriod,int timelag, boolean fixedHorizon){
		//set evidence
		this.setEvidenceUntil(this.cases.get(pid),curPeriod, timelag);
		//Predict survival for predPeriod
		double surv = this.getSurvivalProb(predPeriod, timelag, fixedHorizon); 
		this.cases.get(pid).prediction = surv;
		//Set real outcome
		if(this.cases.get(pid).deathPeriod != null && this.cases.get(pid).deathPeriod <= predPeriod) {  //-1 results from "day of death => null" in the data base
			this.cases.get(pid).outcome = 1;
		}else {
			this.cases.get(pid).outcome = 0;
		}
		//dbnet.writePredictionsCSV("pred1.csv", curPeriod);   
		System.out.println("P(surviving) longer than period "+predPeriod+" (pid: "+pid+"= "+surv);
		System.out.println("Actually dead? "+this.cases.get(pid).outcome );
		System.out.println("death period? "+this.cases.get(pid).deathPeriod);
		evaluateAUC(curPeriod);
		this.net.clearAllEvidence();
	}
	private void foldDataSet(int numFolds){
		ArrayList<String> allCases = new ArrayList<String>(this.cases.keySet());
		Collections.shuffle(allCases);
		int ncases = allCases.size();
		int foldSize = ncases / numFolds;
		
		//update fold info in each case object
		for (int i = 0; i< ncases; i++){
			int foldNum = i/foldSize; 
			//System.out.println("i: "+i+", foldNum: "+foldNum);
			this.cases.get(allCases.get(i)).fold = 0; //= foldNum; currently for testing all in one fold
			this.cases.get(allCases.get(i)).fold = foldNum; //= foldNum; currently for testing all in one fold
		}
	}
	private int getMaxSlice(){
		int maxSlice = 0;
		for(String pid: this.cases.keySet()){
			if(maxSlice <= cases.get(pid).maxSlice()){
				maxSlice =cases.get(pid).maxSlice();
			}
		}
		return maxSlice;
	}

	public void writePredictionsCSV(String file, int curPeriod){
		BufferedWriter br;
		try {
			br = new BufferedWriter(new FileWriter(file));
			StringBuilder sb = new StringBuilder();
			for (String pid: this.cases.keySet()){
				if(this.cases.get(pid).deathPeriod == null || this.cases.get(pid).deathPeriod > curPeriod){ //only use patients that are still alive at landmark time
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
	public void writeResults(String resultsFile, String networkFile, String networkFileOut, String tableName, String comment, boolean uniformize, int maxPeriod, int timelag, String[] auc, String duration) {
		FileInputStream fis;
		try {
			fis = new FileInputStream(resultsFile);
			XSSFWorkbook wb = new XSSFWorkbook (fis); // Finds the workbook instance for XLSX file 
			XSSFSheet sheet = wb.getSheetAt(2); // Return first sheet from the XLSX workbook 

			int lastRowNum = sheet.getLastRowNum();
			//prepare row content
			String nodes = this.nodeNames.toString();
			String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH:mm").format(Calendar.getInstance().getTime());
			String[] desc = {timeStamp,duration, networkFile, networkFileOut, tableName, nodes, comment,Integer.toString(maxPeriod), Integer.toString(timelag), Boolean.toString(uniformize)};
			String[] content = (String[])ArrayUtils.addAll(desc, auc);

			//append row to sheet
	        XSSFRow row = sheet.createRow(lastRowNum+1);
	        int cellNum = 0;
	        for (String obj: content){
	        	XSSFCell cell = row.createCell(cellNum++);
		        cell.setCellValue(obj);
	        }
	        //write and close
	        FileOutputStream os = new FileOutputStream(resultsFile);
	        wb.write(os);
	        wb.close();
		} catch (IOException e) {
			e.printStackTrace();
		}        
	}
}