package dbn_jsmile;

import java.util.ArrayList;
import java.util.Arrays;

import smile.Network;
import smile.learning.DataMatch;
import smile.learning.DataSet;
import smile.learning.EM;

public class tut {
	
	public static void main(String[] args){
	String path = "YOUR PATH"; //path to directory with networks and data file
	learnNetwork(path, "original.xdsl", "small_example.txt", "first_training.xdsl");
	learnNetwork(path, "first_training.xdsl", "small_example.txt", "second_training.xdsl");
	}
	
	private static void learnNetwork(String path, String inputNet, String inputData, String outputNet){
		Network net = new Network();
		net.readFile(path+inputNet); //use this network in second run (is the result of the first run)
		DataSet ds = new DataSet();
		ds.readFile(path+inputData); 
		// matching
		int numCol = ds.getVariableCount();
		int numNodes = net.getNodeCount();
		int numSlices = numCol /numNodes;
		ArrayList<DataMatch> tempMatching = new ArrayList<DataMatch>();
		String colName;
		String[] nameSlice = new String[2];
		String curNodeName;
		int curSlice;
		int nodeNum;
		for(int col = 0; col < numCol; col++){
				//get name of current column in the data set
				colName = ds.getVariableId(col);
				//separate string in name and slice
				nameSlice = colName.split("_");
				curNodeName = nameSlice[0];
				if(nameSlice.length==2){
					curSlice = Integer.parseInt(nameSlice[1]);	
				}else{
					curSlice = 0;
				}
				// find related node number in the network
				if(Arrays.asList(net.getAllNodeIds()).contains(curNodeName)){
					nodeNum = net.getNode(curNodeName);
					System.out.println("Match -> colName "+colName+", nodeName:"+curNodeName+", slice: "+ curSlice+",NodeNum="+ nodeNum);
					tempMatching.add(new DataMatch(col,nodeNum,curSlice)); //associate: column, node, slice
				}else{
					System.out.println("No node found for columnname: "+colName);
				}
		}
		//Convert dataMatch array
		DataMatch[] matching = tempMatching.toArray(new DataMatch[tempMatching.size()]);
		//Learn parameters
		final EM em = new EM();
		em.setRandomizeParameters(false);
		//em.setSeed(2);
		em.setEqSampleSize(1);
		em.setUniformizeParameters(true);
		em.learn(ds, net, matching);
		net.writeFile(path+outputNet);
		
		//display exemplary probability (will be different in both networks)
		double[] probs = net.getNodeTemporalDefinition("B",1);
		System.out.println("P(B_t = false | A_t = false, B_t-1 = false) = " + probs[0]);
	}
}
