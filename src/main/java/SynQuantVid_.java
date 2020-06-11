import java.awt.AWTEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

import java.util.ArrayList;
import ij.*;
import ij.gui.*;
import ij.measure.ResultsTable;
import ij.plugin.*;
import ij.process.*;

/**
 * This plugin is the implemenation of "SynQuant: An Automatic Tool to Quantify Synapses from Microscopy Images". It 
 * 1. segments synapse/puncta on 2d or 3d image(s) using Probability principled method
 * 2. extracts dendrite from 2d image and use steerable filter
 * 3. quantifies the synapse detection and dendrite extraction results if input data are all 2d
 * 
 * @author Congchao Wang
 * @contact ccwang@vt.edu
 * @version 1.2
 * @date 2019-06-17
 *
 *
 */

public class SynQuantVid_ implements PlugIn, DialogListener{
	// image data
	protected ImageStack stack; // Current ImagePlus stack
	protected ImagePlus imp = null; // Current Image
	protected ImagePlus[] impVec; // Current Image
	protected ImagePlus den_imp; //denrite image
	protected int ntry=1; //decide max number of neighbors considered in zscore calculation
	protected int width; //image width
	protected int height; //image height
	protected int timePts; //image height
	protected int zSlice; //image height
	protected int numChannels; //image height
//	protected int presyn_chl; // pre-synapse channel number
//	protected int postsyn_chl; // post-synapse channel number
//	protected int den_chl; //dendrite channel
	protected double fdr = 0.05; // fdr control threshold 
	protected double zscore_thres = 0;
	protected int MinSize,MaxSize; // synapse size range
	protected double minFill, maxWHRatio;
	protected int NumSynSite=0; 
	protected ImagePlus outputImp=null; // detection results output
	int [][][][] synIdx;
	double [][][][] synZscore;
	double slideThrZ;
	int [][][] sliderSynMap; 	// synapse map after post-processing
	int way2combinePostPre = 1; // the way to combine pre- and post-channel, 0:intersect, 1: add pre-zscore to post-zscore
	double ExtendedDistance=0; // extended distance
	double zAxisMultiplier=1; // z axis extended distance multiplier
	
	boolean fastflag = true; //true: no use because we only use fast version
	
	/***Show the dialog for parameter input, then start synQuant***/
	public void run(String arg) {
		try {
			if (showDialog()) {
				synQuant3D_real();
			}
		} catch (IndexOutOfBoundsException e) {
			e.printStackTrace();
		}
	}
	public boolean showDialog() 
	{
		// Get input parameter
		GenericDialog gd = new GenericDialog("3D Particles - Data and Parameter Setting");
		//gd.addNumericField("FDR Control for Particle Detection: ", 0.05, 3);//2.5-3.5 good
		gd.addNumericField("Z-Score for Particle Detection: ", 10,  2);//2.5-3.5 good
		gd.addNumericField("Min Particle Size: ",10, 0);//2.5-3.5 good
		gd.addNumericField("Max Particle Size: ", 200, 0);//2.5-3.5 good
		gd.addNumericField("Min fill: ", 0.5, 2);//2.5-3.5 good
		gd.addNumericField("Max WH Ratio: ", 4, 0);//2.5-3.5 good
		// Get the pointer to the pre-channel and post-channel
		int[] activeImageIDs = WindowManager.getIDList(); // get all acitve images
		String[] openData = new String[activeImageIDs.length+1]; // save all active images' names
		ImagePlus tmpImp;
		for (int i=0; i<activeImageIDs.length; i++) {
			tmpImp = WindowManager.getImage(activeImageIDs[i]);
			openData[i] = tmpImp.getTitle();
			System.out.println("Image title: "+ tmpImp.getTitle());
		}
		openData[activeImageIDs.length] = "Null";
		gd.addChoice("Post-synapse:", openData, openData[0]);
		gd.addChoice("Pre-synapse:", openData, openData[activeImageIDs.length]);
		String[] combinePrePostChl = {"intersect", "post_channel_mainly","Null"};
		gd.addChoice("Way to combine:", combinePrePostChl, combinePrePostChl[combinePrePostChl.length-1]);
		gd.addChoice("Dendrite channel", openData, openData[activeImageIDs.length]);
		gd.addNumericField("Extended distance: ", 0, 2);//0 as no extended distance
		gd.addNumericField("z distance multiplier: ", 1, 2);// 1, treat z distance the same as x-y distance
		gd.showDialog();
		if (gd.wasCanceled()){
			return false;
		}
		//fdr = gd.getNextNumber();
		zscore_thres = gd.getNextNumber();
		MinSize = (int) gd.getNextNumber();
		MaxSize = (int) gd.getNextNumber();
		minFill = gd.getNextNumber();
		maxWHRatio = gd.getNextNumber();
		if(zscore_thres < 0) {//Double.isNaN(fdr) || (fdr<=0) || (fdr>=1)){
			IJ.showMessage("Invalid zscore parameter(s).\n" + "positive value needed.");
			return false;
		}
		int post_chl = gd.getNextChoiceIndex();
		int pre_chl = gd.getNextChoiceIndex();
		way2combinePostPre = gd.getNextChoiceIndex();
		if (way2combinePostPre>1) //user forget to set or single channel
			way2combinePostPre = 1;
		int den_chl = gd.getNextChoiceIndex();
		
		ExtendedDistance = gd.getNextNumber();
		zAxisMultiplier = gd.getNextNumber();
		
		numChannels = 2; // we only care two channels: post- and pre-synaptic channel
		impVec = new ImagePlus[2]; // we only care two channels: pre- and post-synaptic channel
		if (pre_chl==activeImageIDs.length) {
			impVec[0] = null;
			numChannels--;
		}
		else {
			impVec[0] = WindowManager.getImage(activeImageIDs[pre_chl]);
			if(impVec[0].getNFrames() > 1){
				IJ.showMessage("Does not support video currently!.\n");
				return false;
			}
		}
		
		if (post_chl==activeImageIDs.length) {
			impVec[1] = null;
			numChannels--;
		}
		else {
			impVec[1] = WindowManager.getImage(activeImageIDs[post_chl]);
			if(impVec[1].getNFrames() > 1){
				IJ.showMessage("Does not support video currently!.\n");
				return false;
			}
		}
		
		if(numChannels < 1){
			IJ.showMessage("At least input one synapse channel.\n");
			return false;
		}

		if (den_chl < activeImageIDs.length) {
			den_imp = WindowManager.getImage(activeImageIDs[den_chl]);
			if(den_imp.getNFrames() > 1){
				IJ.showMessage("Does not support video currently!.\n");
				return false;
			}
		}else {
			den_imp = null;
		}

		return true;
	}
	/***
	 * The main function of synQuant.
	 * Do synapse detection channel by channel
	 * ***/
	public void synQuant3D_real() {
		//// parameter initialization
		paraQ3D q = new paraQ3D(numChannels, way2combinePostPre, 0.8);
		
		q.ExtendedDistance=ExtendedDistance;
		q.zAxisMultiplier=zAxisMultiplier;
		
		BasicMath bm = new BasicMath();
		//// data saving final results
		slideThrZ = 1000;
		ppsd3D particle3D_det = null;
		//short[][] Arr3D = null;
		for (int chl = 0; chl < impVec.length; chl++) {
			if (impVec[chl] == null)
				continue;
			imp = impVec[chl];
			stack = imp.getStack();
			timePts = imp.getNFrames();
			//numChannels = imp.getNChannels();
			zSlice = imp.getNSlices();
			width = imp.getWidth();
			height = imp.getHeight();
			if (q.NumChannelProcessed == 0) {
				q.synZscore = new double [timePts][zSlice][height][width];
				synIdx = new int [timePts][zSlice][height][width];
				synZscore = new double [timePts][zSlice][height][width];
			}

			double vox_x = imp.getCalibration().pixelWidth; 
			if(vox_x==1)//simulated data
				vox_x = 2.0757e-7;
			else
				vox_x = vox_x*1e-6;//real data

			int type = imp.getType();

			long startTime1=System.nanoTime();
			////particle detection
			for (int i=1; i <= timePts; i++)
			{
				q.curTps = i-1;
				short[][] Arr3D = stack2array(type, stack, i); // #zstack*#pixels in one slice
				paraP3D p = new paraP3D(fdr, zscore_thres,(int)bm.matrix2DMin(Arr3D),(int)bm.matrix2DMax(Arr3D),MinSize, MaxSize, minFill, maxWHRatio);
				particle3D_det = new ppsd3D(Arr3D, width, height, vox_x, p,q);

				// for the post channel, we save the output results
				if (q._NumChannel == q.NumChannelProcessed + 1 ) 
				{ // last one
					synIdx[i-1] = particle3D_det.ppsd_main.kMap;
					synZscore[i-1] = particle3D_det.ppsd_main.zMap;
					double tmpThrZ = particle3D_det.ppsd_main.thrZ;
					if (slideThrZ > tmpThrZ)
						slideThrZ = tmpThrZ;
				}else 
				{// pre-channel
					q.synZscore[q.curTps] = particle3D_det.ppsd_main.zMap;
				}
			}
			long endTime1=System.nanoTime();
			System.out.println("Finished. Data size: "+zSlice+" * "+height+" * "+width+" * "+timePts+" Total running time: "+(endTime1-startTime1)/1e9);
			q.NumChannelProcessed ++;
			q.var = 0; // reset variance, preparing for new channel
		}

		//// dendrite extraction and display
		GrowNeurite den_det = null;
		if (den_imp != null) {
			if(den_imp.getNSlices() > 1 || den_imp.getNFrames() > 1) {
				IJ.showMessage("Currently only support 2D image for dendrite extration.\n");
			}else {
				boolean displayROI = true;
				String Roititle = "Dendrite";
				double vox_x = den_imp.getCalibration().pixelWidth; 
				if(vox_x==1)//simulated data
					vox_x = 2.0757e-7;
				else
					vox_x = vox_x*1e-6;//real data
				short[][] denArr = stack2array(den_imp.getType(), den_imp.getStack(), 1);
				den_det = new GrowNeurite(denArr[0], width, height,vox_x,displayROI,Roititle);
			}
		}
		//// display synapse detection results: RGB or single channel
		outputImp = IJ.createHyperStack("Synapse detection results", width, height, 1, zSlice, timePts,24/*bitdepth*/);
		outputImp.show();
		for (int i=1; i <= timePts; i++){
			//display data
			SynapticDisplay(synIdx[i-1], i);
			System.out.println(i + "-th Frame SynNum: "+bm.matrix3DMax(synIdx[i-1]));
			//write results in txt e.g.z-score
			//zScoreWriter(particle3D_det.zscoreList, saveFilePath);
			outputImp.updateAndDraw();
		}
		//// wait to listen the change of z-score threshold
		GenericDialog gd = new NonBlockingGenericDialog("Tune zscore threshold");
		gd.addSlider("zscore threshold tuning", Math.max(0, slideThrZ), 100, Math.max(0, slideThrZ));
		// wait to listen for the changing of zscore threshold
		sliderSynMap = null;
		gd.addDialogListener(this);
		//dialogItemChanged (gd, null);
		gd.showDialog();
		
		// display the results of puncta detection results
		ImageHandling IH = new ImageHandling();
		if (imp.getNSlices() == 1) 
		{ //for 2d data use ROI manager to display results
			boolean [][] synMap2dBin = new boolean[synZscore[0][0].length][synZscore[0][0][0].length];
			if (sliderSynMap == null) 
			{
				for (int i=0; i<synZscore[0][0].length; i++) 
				{
					for (int j=0; j<synZscore[0][0][0].length;j++) 
					{
						if(synZscore[0][0][i][j]>=zscore_thres) 
						{
							synMap2dBin[i][j] = true;
						}
						else 
						{
							synMap2dBin[i][j] = false;
						}
					}
				}
			}
			else 
			{
				//boolean [][] synMap2dBin = new boolean[sliderSynMap[0].length][sliderSynMap[0][0].length];
				for (int i=0; i<sliderSynMap[0].length; i++) 
				{
					for (int j=0; j<sliderSynMap[0][0].length;j++) 
					{
						if(sliderSynMap[0][i][j]>0) 
						{
							synMap2dBin[i][j] = true;
						}
						else 
						{
							synMap2dBin[i][j] = false;
						}
					}
				}
			}
			int[][] synMap2d = IH.bwlabel(synMap2dBin, 8);
			outputImp.close();
			ImagePlus ROIImp = imp.duplicate();
			ROIImp.setTitle("Synapse detection results");
			ROIImp.show();
			IH.DisplayROI(IH.NextLabel,height,width,synMap2d, ROIImp,"Synapse detection results");
		}
		else 
		{//for 3d data use table to display statistics results
			boolean [][][] synMap3dBin = new boolean[synZscore[0].length][synZscore[0][0].length][synZscore[0][0][0].length];
			if (sliderSynMap == null) 
			{
				for (int k = 0; k<synZscore[0].length; k++) 
				{
					for (int i=0; i<synZscore[0][0].length; i++) 
					{
						for (int j=0; j<synZscore[0][0][0].length;j++) 
						{
							if(synZscore[0][k][i][j]>=zscore_thres) 
							{
								synMap3dBin[k][i][j] = true;
							}
							else 
							{
								synMap3dBin[k][i][j] = false;
							}
						}
					}
				}
			}
			else 
			{
				for (int k = 0; k<sliderSynMap.length; k++) 
				{
					for (int i=0; i<sliderSynMap[0].length; i++) 
					{
						for (int j=0; j<sliderSynMap[0][0].length;j++) 
						{
							if(sliderSynMap[k][i][j]>0) 
							{
								synMap3dBin[k][i][j] = true;
							}
							else 
							{
								synMap3dBin[k][i][j] = false;
							}
						}
					}
				}
			}
			int[][][] synMap3d = IH.bwlabel3D(synMap3dBin,26);
			double [][] puncta_feats = new double [IH.NextLabel][4]; // volume, Mean, Max, Min intensity
			for (int i=0; i < IH.NextLabel; i++) 
			{
				puncta_feats[i][0] = 0;
				puncta_feats[i][1] = 0;
				puncta_feats[i][2] = 0;
				puncta_feats[i][3] = 65536;
			}
			double[][] ArrDouble3D = stack2DoubleArray(imp.getType(), imp.getStack(), 1);
			System.out.println(bm.matrix2DMax(ArrDouble3D));
			for (int k = 0; k<synMap3d.length; k++) 
			{
				for (int i=0; i<synMap3d[0].length; i++) 
				{
					for (int j=0; j<synMap3d[0][0].length;j++) 
					{
						int punctum_id = synMap3d[k][i][j]-1;
						if (punctum_id >= 0) 
						{
							if (punctum_id >= puncta_feats.length)
								System.out.print(true);
							puncta_feats[punctum_id][0] += 1;
							
							double cur_intensity = ArrDouble3D[k][i*synMap3d[0][0].length + j];
							puncta_feats[punctum_id][1] += cur_intensity;
							
							if (cur_intensity > puncta_feats[punctum_id][2])
								puncta_feats[punctum_id][2] = cur_intensity;
							
							if (cur_intensity < puncta_feats[punctum_id][3])
								puncta_feats[punctum_id][3] = cur_intensity;
						}
					}
				}
			}
			ResultsTable det_ft_table = new ResultsTable();
			for (int i=0;i<IH.NextLabel;i++) {
				det_ft_table.incrementCounter();
				det_ft_table.addValue("Index", i+1);
				det_ft_table.addValue("Volume", (int)puncta_feats[i][0]);
				det_ft_table.addValue("Mean", (double)Math.round(10.0*puncta_feats[i][1]/puncta_feats[i][0])/10.0);
				det_ft_table.addValue("Min", puncta_feats[i][3]);
				det_ft_table.addValue("Max", puncta_feats[i][2]);
			}
			det_ft_table.showRowNumbers(false);
			det_ft_table.show("Puncta statistics on 3D data");
		}
		if(den_imp != null) 
		{
			if (den_imp.getNSlices() == 1 & imp.getNSlices() == 1) 
			{ //do quantification
				boolean [][] kSynR1 = new boolean [synZscore[0][0].length][synZscore[0][0][0].length];
				if (sliderSynMap == null) 
				{
					for (int i=0; i<kSynR1.length; i++)
					{
						for(int j=0; j<kSynR1[0].length;j++) 
						{
							kSynR1[i][j] = synZscore[0][0][i][j]>zscore_thres;
						}
					}
				}
				else 
				{
					for (int i=0; i<kSynR1.length; i++)
					{
						for(int j=0; j<kSynR1[0].length;j++) 
						{
							kSynR1[i][j] = sliderSynMap[0][i][j] > 0;
						}
					}
				}
				LinearTest LT = new LinearTest(kSynR1,den_det);
				//show features
				ResultsTable Ft_table = new ResultsTable();
				int DenCnt = 0;
				for (int i=0;i<den_det.CurNum;i++) 
				{
					Ft_table.incrementCounter();
					DenCnt = DenCnt+1;
					Ft_table.addLabel("Dendrite piece #"+DenCnt);
					Ft_table.addValue("Number of synapse on it",LT.denSynNum[i]);
					Ft_table.addValue("Dendrite length",LT.DenFeatures[i][0]);
					Ft_table.addValue("Dendrite scale",LT.DenFeatures[i][1]);
					Ft_table.addValue("Dendrite intensity",LT.DenFeatures[i][2]);
				}
				Ft_table.showRowNumbers(false);
				Ft_table.show("Synapse Quantification Feature Table");

				ResultsTable esm_table = new ResultsTable();

				esm_table.incrementCounter();
				esm_table.addLabel("Coefficents:");
				esm_table.addValue("Length",LT.betas[0]);
				esm_table.addValue("Scale",LT.betas[1]);
				esm_table.addValue("Intensity",LT.betas[2]);
				esm_table.addValue("Intercept",LT.betas[3]);
				esm_table.showRowNumbers(false);
				esm_table.show("Synapse Quantification Coefficents Table");

				ResultsTable sm_table = new ResultsTable();

				sm_table.incrementCounter();
				sm_table.addLabel("Total # of synapses:");sm_table.addValue("Value",LT.SynNum);
				sm_table.incrementCounter();
				sm_table.addLabel("Total Length of Dendrite:");sm_table.addValue("Value",den_det.totallength);
				sm_table.incrementCounter();
				sm_table.addLabel("Puncta Density per Unit Length:");sm_table.addValue("Value",(double)LT.SynNum/den_det.totallength);
				sm_table.showRowNumbers(false);
				sm_table.show("Synapse Quantification Summary Table");
			}
		}
		
	}
	/** Listener to modifications of the input fields of the dialog.
	 *  Here the parameters should be read from the input dialog.
	 *  @param gd The GenericDialog that the input belongs to
	 *  @param e  The input event
	 *  @return whether the input is valid and the filter may be run with these parameters
	 */
	public boolean dialogItemChanged (GenericDialog gd, AWTEvent e) {
		zscore_thres = gd.getNextNumber();
		if (Math.abs(zscore_thres-slideThrZ) < 0.2)
			return true;
		else
			slideThrZ = zscore_thres;
		//ImageHandling imh = new ImageHandling();
		//System.out.println("-th Frame SynNum: " + zscore_thres);
		//outputImp = IJ.createHyperStack("Found Particles", width, height, 1, zSlice, timePts,16/*bitdepth*/);
		outputImp.show();
		for (int i=1; i <= timePts; i++)
		{
			sliderSynMap = new int [zSlice][height][width];
			for (int zz=0;zz<zSlice; zz++) 
			{
				for(int yy=0;yy<height; yy++) 
				{
					for(int xx=0;xx<width; xx++) 
					{
						if(synZscore[i-1][zz][yy][xx] >= zscore_thres) 
						{
							sliderSynMap[zz][yy][xx] = 1;//synZscore[i-1][zz][yy][xx];
						}
					}
				}
			}
			//int [][][] tmpSynIdx = imh.bwlabel3D(tmpSynZ, 26);
			SynapticDisplay(sliderSynMap, i);
			outputImp.updateAndRepaintWindow();
		}
		return true;
	}
	// save the z-score, corresponding particle index and threshold into txt files 
	public void zScoreWriter(ArrayList<double[]> zscoreList, String fileRootName) {
		String zscoreTxt = fileRootName+"zScore.txt";
		String thresTxt = fileRootName+"threshold.txt";
		String pIdxTxt = fileRootName+"particleIndex.txt";
		
        try{
            // Create index file
            File file = new File(pIdxTxt);
            FileWriter fw;
            // If file doesn't exists, then create it
            if (!file.exists()) {
            	file.createNewFile();    
            	fw = new FileWriter(file.getAbsoluteFile());
            }
            else {
            	fw = new FileWriter(file.getAbsoluteFile(), true);
            }

            BufferedWriter bw = new BufferedWriter(fw);

            // Write index file
            String numberAsString = String.format ("%d", (int)zscoreList.get(0)[0]);
            bw.append(numberAsString);
            for(int i=1;i<zscoreList.size();i++) {
            	numberAsString = String.format ("%d", (int)zscoreList.get(i)[0]);
            	bw.append("," + numberAsString);
            }
            bw.append("\n");
            // Close connection
            bw.close();
            
            // Create zscore file
            file = new File(zscoreTxt);
            // If file doesn't exists, then create it
            if (!file.exists()) {
            	file.createNewFile();    
            	fw = new FileWriter(file.getAbsoluteFile());
            }
            else {
            	fw = new FileWriter(file.getAbsoluteFile(), true);
            }

            bw = new BufferedWriter(fw);

            // Write zscore file
            numberAsString = String.format ("%.3f", zscoreList.get(0)[1]);
            bw.append(numberAsString);
            for(int i=1;i<zscoreList.size();i++) {
            	numberAsString = String.format ("%.3f", zscoreList.get(i)[1]);
            	bw.append("," + numberAsString);
            }
            bw.append("\n");
            // Close connection
            bw.close();
            
            // Create threshold file
            file = new File(thresTxt);
            // If file doesn't exists, then create it
            if (!file.exists()) {
            	file.createNewFile();    
            	fw = new FileWriter(file.getAbsoluteFile());
            }
            else {
            	fw = new FileWriter(file.getAbsoluteFile(), true);
            }
            
            bw = new BufferedWriter(fw);

            // Write threshold file
            numberAsString = String.format ("%d", (int)zscoreList.get(0)[2]);
            bw.append(numberAsString);
            for(int i=1;i<zscoreList.size();i++) {
            	numberAsString = String.format ("%d", (int)zscoreList.get(i)[2]);
            	bw.append("," + numberAsString);
            }
            bw.append("\n");
            // Close connection
            bw.close();
        }
        catch(Exception e){
        	System.out.println(e);
        }
	}
	
	public void SynapticDisplay(int [][][] kSynR1, int curTimePt){
		//ImagePlus outimp = NewImage.createImage(null, width, height, 1,16, NewImage.FILL_BLACK);
		//ImageProcessor outIP =  outimp.getProcessor();
		ImageStack curStack = outputImp.getStack();
		ImageStack curInStack = imp.getStack();
		ImageProcessor inIP = null;
		ImageProcessor outIP =  null;
//		for(int k = 0;k<zSlice;k++){
//			// red channel show detection results
//			int stackNum = outputImp.getStackIndex(1, k, curTimePt);
//			if(k==0)
//				stackNum--;
//			for (int i = 0; i < width; i++) {
//				for(int j = 0;j<height;j++){
//					// put channel values in an integer
//					if(kSynR1[k][j][i]>0) {
//						//System.out.println("Number of Synapse: "+curStack.getPixels(stackNum + 1).getClass());
//						((short[]) curStack.getPixels(stackNum + 1))[i + j * width] =  (short) 255;//(short)kSynR1[k][j][i];
//					}else {
//						((short[]) curStack.getPixels(stackNum + 1))[i + j * width] =  (short) 0;
//					}
//				}
//			}
//			// green channel show original data
//			if(k==0)
//				stackNum--;
//			for (int i = 0; i < width; i++) {
//				for(int j = 0;j<height;j++){
//					// put channel values in an integer
//					if(kSynR1[k][j][i]>0) {
//						//System.out.println("Number of Synapse: "+curStack.getPixels(stackNum + 1).getClass());
//						((short[]) curStack.getPixels(stackNum + 1))[i + j * width] =  (short) 65535;//(short)kSynR1[k][j][i];
//					}else {
//						((short[]) curStack.getPixels(stackNum + 1))[i + j * width] =  (short) 0;
//					}
//				}
//			}
//		}
		double max_intensity = 0;
		if(imp.getType() == ImagePlus.GRAY16) 
		{
			for(int stackNum=1; stackNum<=curStack.getSize(); stackNum++) 
			{
				double tmp_max = curInStack.getProcessor(stackNum).getStatistics().max;
				if (max_intensity < tmp_max) 
				{
					max_intensity = tmp_max;
				}
			}
		}
		for(int stackNum=1; stackNum<=curStack.getSize(); stackNum++) 
		{
			outIP = curStack.getProcessor(stackNum);
			inIP = curInStack.getProcessor(stackNum);
			for (int i = 0; i < width; i++) 
			{
				for(int j = 0;j<height;j++)
				{
					// put channel values in an integer
					if(imp.getType() == ImagePlus.GRAY8) 
					{
						int tmp_val = (int)inIP.get(i, j) & 0xff;
						if(kSynR1[stackNum-1][j][i]>0)
							outIP.set(i, j, (((int)200 & 0xff) << 16)
									+ (tmp_val << 8)
									+  ((int)0 & 0xff));
						else
							outIP.set(i, j, (((int)0 & 0xff) << 16) 
									+ (tmp_val << 8)
									+  ((int)0 & 0xff));
					}else 
					{
//						if(inIP.get(i, j)>255*10)
//							System.out.println(" "+inIP.get(i, j));
						byte tmp_float_val = (byte) ((((double)inIP.get(i, j))/max_intensity)*255);
						int tmp_val = (int)tmp_float_val & 0xff;
						
						if(kSynR1[stackNum-1][j][i]>0)
							outIP.set(i, j, (((int)200 & 0xff) << 16)
									+ (tmp_val << 8)
									+  ((int)0 & 0xff));
						else
							outIP.set(i, j, (((int)0 & 0xff) << 16) 
									+ (tmp_val << 8)
									+  ((int)0 & 0xff));
					}

				}
			}
		}
//		for (int i = 0; i < width; i++) {
//			for(int j = 0;j<height;j++){
//			// put channel values in an integer
//			if(CurIm[j][i]>0)
//				outIP.set(i, j, (((int)dendrite[j][i] & 0xff) << 16)
//				          + (((int)200 & 0xff) << 8)
//				          +  ((int)200 & 0xff));
//			else
//				outIP.set(i, j, (((int)dendrite[j][i] & 0xff) << 16) 
//				          + (((int)0 & 0xff) << 8)
//				          +  ((int)0 & 0xff));
//
//			}
//		}
	}

	public short[][] stack2array(int type, ImageStack stack, int frameNum){
		int mask=0xff;
		int nPixels=width*height;
		short[][] imArray = new short[zSlice][nPixels];
		if (type == ImagePlus.GRAY16)
		{
			mask=0xffff;
			for(int zz=1;zz<=zSlice;zz++) {
				int curSliceNum = imp.getStackIndex(1, zz, frameNum);// default all data are one-channel data
				short[] pixels = (short[])stack.getPixels(curSliceNum);
				int intP = (int)(mask&pixels[0]);
				for (int i=0; i<nPixels; i++)
				{
					intP=(int)(mask&pixels[i]);
					short p = (short)(intP/2); // for sake of range, short <= 32,767 (65535/2)
					imArray[zz-1][i]=p;
				}
			}
		}  
		else if (type == ImagePlus.GRAY8) 
		{
			mask=0xff;
			for(int zz=1;zz<=zSlice;zz++) {
				int curSliceNum = imp.getStackIndex(1, zz, frameNum); // default all data are one-channel data
				byte[] pixels = (byte[])stack.getPixels(curSliceNum);
				for (int i=0; i<nPixels; i++)
				{
					short p=(short)(mask&pixels[i]);
					imArray[zz-1][i]=p;
				}
			}
		}
		else
		{
			IJ.log("Pixel format not supported");
			return null;
		}
		return imArray;
	}
	public double[][] stack2DoubleArray(int type, ImageStack stack, int frameNum){
		int mask=0xff;
		int nPixels=width*height;
		double[][] imArray = new double[zSlice][nPixels];
		if (type == ImagePlus.GRAY16)
		{
			mask=0xffff;
			for(int zz=1;zz<=zSlice;zz++) {
				int curSliceNum = imp.getStackIndex(1, zz, frameNum);// default all data are one-channel data
				short[] pixels = (short[])stack.getPixels(curSliceNum);
				int intP = (int)(mask&pixels[0]);
				for (int i=0; i<nPixels; i++)
				{
					intP=(int)(mask&pixels[i]);
					imArray[zz-1][i]=intP;
				}
			}
		}  
		else if (type == ImagePlus.GRAY8) 
		{
			mask=0xff;
			for(int zz=1;zz<=zSlice;zz++) {
				int curSliceNum = imp.getStackIndex(1, zz, frameNum); // default all data are one-channel data
				byte[] pixels = (byte[])stack.getPixels(curSliceNum);
				for (int i=0; i<nPixels; i++)
				{
					short p=(short)(mask&pixels[i]);
					imArray[zz-1][i]=p;
				}
			}
		}
		else
		{
			IJ.log("Pixel format not supported");
			return null;
		}
		return imArray;
	}
	/**
	 * Main method for debugging.
	 *
	 * For debugging, it is convenient to have a method that starts ImageJ,
	 * loads an image and calls the plugin, e.g. after setting breakpoints.
	 *
	 * @param args
	 *            unused
	 */
	public static void main(String[] args) {
		// set the plugins.dir property to make the plugin appear in the Plugins
		// menu
		Class<?> clazz = SynQuantVid_.class;
		String url = clazz.getResource("/" + clazz.getName().replace('.', '/') + ".class").toString();
		String pluginsDir = url.substring(5, url.length() - clazz.getName().length() - 6);
		System.setProperty("plugins.dir", pluginsDir);

		// start ImageJ
		new ImageJ();

		// open the Clown sample
		/*ImagePlus image = IJ
				.openImage("C:\\Users\\Congchao\\Desktop\\test series for process tracking-1.tif");//C2-3-weak-z_Maximum intensity projection.tif");
		image.show();

		 //run the plugin
		IJ.runPlugIn(clazz.getName(), "");*/
	}

	void error() {
		IJ.showMessage("3D particle", "Error");
	}
}
