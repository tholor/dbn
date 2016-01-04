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
		//Be aware: first month on dialysis = period 0 in DBN (vs. period 1 in database)
		//int curPeriod = 3;  //using evidence until curPeriod (including that period)
		final int[] curPeriods = {2,5,8,11};
		final int[] predHorizons = {12};
		//int[] predPeriods = {4,6,9,15};//predicting prob. of survival(including that period)
		final int timelag = 12; // number of periods used as time lag (e.g. 1 => using covariates from t-1 together with "death" in t)
		final boolean fixed_horizon = true; //if probabilities in node "death" already reflect cum. prob. of surviving a fixed interval instead of monthly hazards (e.g. surviving the next 12 months)
		final boolean foldedCV = true;
		int numFolds = 5;
		//Learning
		final boolean learningParams = true;
		final int maxPeriod = 24; //maximum period from the data that should be used for learning
		final boolean uniformize = false;
		final boolean randomize = false;
		final int equivalentSampleSize = 1;
		final boolean fromCompleteData = true; // speficies whether one network should be learned from the complete data set (in addition to the folded sets)
		//Files
		final String path = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/networks/batch_testing/batch_12m_1/";
		final String dataFile = path+"small.txt";
		//final String[] networks = {"v9_age_inter.xdsl","v13_pneu_rev.xdsl"};
		final String networkFolder = path+"input/";
		final String tableName = "03_model_dbn_state_0";
		final String resultsFile = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/evaluation/performance.xlsx";
		final String comment = "";
		//****************************************
		
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
			dbnet.getData(tableName, timelag, maxPeriod);
			//Simple test
			//dbnet.simpleTest("LIB0000001755", curPeriod, predPeriod,timelag);

			//Learn parameters of one network from complete data set
			if(fromCompleteData){
			dbnet.learnParametersDB(path+"/output/smile_compl_"+networkName, 0, false ,randomize, equivalentSampleSize, uniformize);
			dbnet.net.clearAllEvidence();
			}
			
			//****************************************
			//Evaluate with cross validation
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
			System.out.println("Fold " + fold + ":");
			//Learn parameters
					//dbnet.learnParameters(dataFile, networkFileOut, randomize, equivalentSampleSize, uniformize);
					if(learningParams){
					dbnet.learnParametersDB(networkFileOut, fold, foldedCV, randomize, equivalentSampleSize, uniformize);
					}
					dbnet.net.clearAllEvidence();
				
			//Set evidence and get predictions
					System.out.println("Starting with evidence and prediction...");
					for(int curIndex = 0; curIndex < curPeriods.length; curIndex++){
						//for(int predIndex = 0; predIndex < predPeriods.length; predIndex++){
						for(int predIndex = 0; predIndex < predHorizons.length; predIndex++){	
							int curPeriod = curPeriods[curIndex];
							int predPeriod = curPeriod+predHorizons[predIndex];
							System.out.println("\t Predict from period "+curPeriod+" to period "+predPeriod);
							for (String pid: dbnet.cases.keySet()){
								if(dbnet.cases.get(pid).fold==fold){
									//set evidence
									dbnet.setEvidenceUntil(dbnet.cases.get(pid),curPeriod, timelag);
									//set forecasted "Age" evidence
									//dbnet.setNodeEvidenceUntil("Age",dbnet.cases.get(pid),predPeriod);
									//Predict survival for predPeriod
									double surv = dbnet.getSurvivalProb(predPeriod, timelag, fixed_horizon); 
									dbnet.cases.get(pid).prediction = surv;
									//Set real outcome
									if(dbnet.cases.get(pid).deathPeriod != -1 && dbnet.cases.get(pid).deathPeriod <= predPeriod) { 
										dbnet.cases.get(pid).outcome = 1;
									}else {
										dbnet.cases.get(pid).outcome = 0;
									}
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
						//dbnet.writePredictionsCSV("pred1.csv", curPeriod);   
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
			dbnet.writeResults(resultsFile, networkName,"", tableName, comment, uniformize, maxPeriod, timelag, avg_aucs, duration);
		}
		 catch (SMILEException e) {
		   System.out.println(e.getMessage());
		 }
		}
	}	

	public void getData(String tableName, int timelag, int maxPeriod)throws ClassNotFoundException{
		System.out.println("Starting data import...");
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
			temp.remove("Bone_Risk");
			String colsToSelect = this.nodeNames.toString();
			colsToSelect = colsToSelect.substring(1, colsToSelect.length()-1).toLowerCase();
			String query = "SELECT pid, period, "+colsToSelect+", period_of_death FROM "+tableName+" where period <="+(maxPeriod+1); //+1 because of notation differences in mySQL database
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
			this.convertToCases(r, timelag);
		} catch (SQLException e) { 
		} finally { 
			if(connection != null) try { 
				if(!connection.isClosed()) connection.close();  
				  } catch(SQLException e) {
					  	e.printStackTrace(); 
				  } 
			} 
	}	
	public void convertToCases(ResultSet r, int timelag){
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
					  int obsState = r.getInt(name); //CHANGED, was:  -1 because states in genie start with 0, but with 1 in DB
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
			System.out.println("Try to set node "+ node[i]+" in slice "+slice[i] +" to state "+state[i]);
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
				if(timelag > 0 && (slice[i] <= maxPeriod-timelag || slice[i] <= maxPeriod && !node[i].equals("Death")) ){
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
	public void setNodeEvidenceUntil(String nodeName, Case c, int predPeriod) {
		//set evidence for one particular node until a certain period
		String[] nodesIn = c.getNodes();
		Integer[] slicesIn = c.getSlices();
		Integer[] statesIn = c.getStates();
		String name;
		for(int i = 0; i< nodesIn.length; i++){
			name = nodesIn[i];
			//System.out.println("Name of node: "+name);
			if(name.equals(nodeName) && slicesIn[i]<= predPeriod && statesIn[i] != null){
				//System.out.println("set:"+nodesIn[i]+" in slice "+slicesIn[i]+" to "+statesIn[i]);
				this.net.setTemporalEvidence(nodesIn[i], slicesIn[i], statesIn[i]);	
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
	
	public double getSurvivalProb(int period, int timelag, boolean fixed_horizon){
		//returns cumulative survival probability 
		//includes surviving the specified period
		double prob = 1;
		
		//case 1: the node death contains hazard for each period
		if(!fixed_horizon){
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
		int c=0;
		for (String pid: this.cases.keySet()){
			if(this.cases.get(pid).deathPeriod == -1 || this.cases.get(pid).deathPeriod > curPeriod){ //CHANGED, was >=; only use patients that are still alive at landmark time- check again if >= makes sense 
			score.add(this.cases.get(pid).outcome, 1-this.cases.get(pid).prediction);
			c++;
			}
		}
		//System.out.println("-> used cases for auc: "+c);
		return score.auc();
	}
	public double evaluateCrossValAUC(int curPeriod, int fold){
		Auc score = new Auc();
		int c=0;
		for (String pid: this.cases.keySet()){
			if((this.cases.get(pid).deathPeriod == -1 ||this.cases.get(pid).deathPeriod > curPeriod) && this.cases.get(pid).fold == fold){ //CHANGED, was >=; only use patients that are still alive at landmark time- check again if >= makes sense 
				//System.out.println("P(dead) "+pid+"= "+(1-this.cases.get(pid).prediction)+ " outcome: "+this.cases.get(pid).outcome );
				score.add(this.cases.get(pid).outcome, 1-this.cases.get(pid).prediction);
			c++;
			}
		}
		//System.out.println("used cases for auc: "+c);
		return score.auc();
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
	private void simpleTest(String pid, int curPeriod, int predPeriod,int timelag){
		this.setEvidenceUntil(this.cases.get(pid),curPeriod, timelag);
		//Predict survival in predPeriod
		double surv = this.getSurvivalProb(predPeriod, timelag, false); 
		this.cases.get(pid).prediction = surv;
		//Set real outcome
		if(this.cases.get(pid).deathPeriod != -1 && this.cases.get(pid).deathPeriod <= predPeriod) { 
			this.cases.get(pid).outcome = 1;
		}else {
			this.cases.get(pid).outcome = 0;
		}
		System.out.println("P(surviving) longer than period "+predPeriod+" (pid: "+pid+"= "+surv);
		System.out.println("Actually dead? "+this.cases.get(pid).outcome );
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
				if(this.cases.get(pid).deathPeriod == -1 || this.cases.get(pid).deathPeriod > curPeriod){ //only use patients that are still alive at landmark time- check again if >= makes sense 
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