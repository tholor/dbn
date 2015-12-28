package dbn_jsmile;

import java.util.ArrayList;
import java.util.Arrays;

import smile.Network;
import smile.learning.DataMatch;
import smile.learning.DataSet;
import smile.learning.EM;

public class tut {
	
	public static void main(String[] args){
	Network net = new Network();
	net.readFile("C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/example forum/original.xdsl");
	//String file = "C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/networks/dialysis_v2_13_var.xdsl"; 
	DataSet ds = new DataSet();
	//ds.readFile("C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/input data/genie_data_set.txt" );
	ds.readFile("C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/example forum/small_example_NA.txt"); 
	System.out.println("Missing 1,1? = "+ds.isMissing(0, 0));

	//automated matching
	int numCol = ds.getVariableCount();
	int numNodes = net.getNodeCount();
	int numSlices = numCol /numNodes;
	System.out.println("numCol:"+numCol+" ,numNodes: "+numNodes+"numSlices: "+numSlices);

	//matching
	//DataMatch[] matching = new DataMatch[numCol];
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
				System.out.println("Colname"+colName+", nodeName:"+curNodeName+", slice: "+ curSlice+",NodeNum="+ nodeNum);
				tempMatching.add(new DataMatch(col,nodeNum,curSlice)); //associate: column, node, slice
			}else{
				System.out.println("No node found for columnname: "+colName);
			}
	}
	//Convert dataMatch array
	DataMatch[] matching = tempMatching.toArray(new DataMatch[tempMatching.size()]);
	
	final EM em = new EM();
	
	em.setRandomizeParameters(false);
	//em.setSeed(2);
	em.setEqSampleSize(1);
	em.setUniformizeParameters(true);
	em.learn(ds, net, matching);

	net.writeFile("C:/Users/Malte/Dropbox/FIM/08 Masterarbeit/06 Coding/Smile/example forum/missing data/smile_learned.xdsl");
	

	
	}
}
