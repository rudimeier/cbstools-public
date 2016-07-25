package de.mpg.cbs.jist.fmri;

import edu.jhu.ece.iacl.jist.pipeline.AlgorithmRuntimeException;
import edu.jhu.ece.iacl.jist.pipeline.CalculationMonitor;
import edu.jhu.ece.iacl.jist.pipeline.ProcessingAlgorithm;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamCollection;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamOption;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamVolume;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamFloat;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamInteger;
import edu.jhu.ece.iacl.jist.structures.image.ImageData;
import edu.jhu.ece.iacl.jist.structures.image.ImageDataMipav;
import edu.jhu.ece.iacl.jist.structures.image.ImageDataByte;
import edu.jhu.ece.iacl.jist.structures.image.ImageDataFloat;
import edu.jhu.ece.iacl.jist.structures.image.ImageHeader;
import edu.jhu.ece.iacl.jist.structures.image.VoxelType;

import edu.jhu.ece.iacl.jist.pipeline.DevelopmentStatus;
import edu.jhu.ece.iacl.jist.pipeline.ProcessingAlgorithm;
import edu.jhu.ece.iacl.jist.pipeline.AlgorithmInformation.AlgorithmAuthor;
import edu.jhu.ece.iacl.jist.pipeline.AlgorithmInformation.Citation;
import edu.jhu.ece.iacl.jist.pipeline.AlgorithmInformation;

import de.mpg.cbs.utilities.*;
import de.mpg.cbs.structures.*;
import de.mpg.cbs.libraries.*;
import de.mpg.cbs.methods.*;

import org.apache.commons.math3.stat.descriptive.rank.*;
import org.apache.commons.math3.util.*;
import org.apache.commons.math3.special.*;

import gov.nih.mipav.model.file.FileInfoBase;
import gov.nih.mipav.model.structures.ModelImage;
import gov.nih.mipav.model.structures.ModelStorageBase;

/*
 * @author Pierre-Louis Bazin
 */
public class JistFmriCorrelationTensors extends ProcessingAlgorithm {

	// jist containers
	
	// input 
	private ParamVolume 	dataImage;
	private ParamFloat 		extentParam;
	
	private ParamOption ngbParam;
	private static final String[] ngbTypes = {"26C_neighbors","distance_based","debug"}; 
	
	private static final byte X=0;
	private static final byte Y=1;
	private static final byte Z=2;
	
	// triangular superior tensor
	private static final byte XX=0;
	private static final byte XY=1;
	private static final byte XZ=2;
	private static final byte YY=3;
	private static final byte YZ=4;
	private static final byte ZZ=5;
	

	// ouput
	private ParamVolume tensorImage;
	private ParamVolume histImage;
	private ParamVolume meanImage;
	private ParamVolume normImage;
		
	protected void createInputParameters(ParamCollection inputParams) {
		
		inputParams.add(dataImage = new ParamVolume("fMRI Data Image",VoxelType.FLOAT,-1,-1,-1,-1));
		dataImage.setLoadAndSaveOnValidate(false);
		
		inputParams.add(ngbParam = new ParamOption("Neighborhood type", ngbTypes));
		inputParams.add(extentParam = new ParamFloat("Neighborhood distance (mm)", 0.0f, 30.0f, 2.0f));
		
		inputParams.setPackage("CBS Tools");
		inputParams.setCategory("fMRI");
		inputParams.setLabel("fMRI Correlation Tensor");
		inputParams.setName("fMRICorrelationTensor");

		AlgorithmInformation info = getAlgorithmInformation();
		info.add(new AlgorithmAuthor("Pierre-Louis Bazin", "bazin@cbs.mpg.de","http://www.cbs.mpg.de/"));
		info.setAffiliation("Max Planck Institute for Human Cognitive and Brain Sciences");
		info.setDescription("Estimates local correlation tensors for fMRI data (adapted from (Ding et al., MRI:34, 2016).");
		
		info.setVersion("3.0.9");
		info.setStatus(DevelopmentStatus.RC);
		info.setEditable(false);
	}

	@Override
	protected void createOutputParameters(ParamCollection outputParams) {
		outputParams.add(tensorImage = new ParamVolume("Tensor Map",VoxelType.FLOAT,-1,-1,-1,-1));
		
		outputParams.add(histImage = new ParamVolume("Histograms",VoxelType.FLOAT,-1,-1,-1,-1));
		outputParams.add(meanImage = new ParamVolume("Mean Map",VoxelType.FLOAT,-1,-1,-1,-1));
		outputParams.add(normImage = new ParamVolume("Norm Map",VoxelType.FLOAT,-1,-1,-1,-1));
		
		outputParams.setLabel("fMRI Correlation Tensor");
		outputParams.setName("fMRICorrelationTensor");
	}

	@Override
	protected void execute(CalculationMonitor monitor){
		
		ImageDataFloat	dataImg = new ImageDataFloat(dataImage.getImageData());
		int nx = dataImg.getRows();
		int ny = dataImg.getCols();
		int nz = dataImg.getSlices();
		int nt = dataImg.getComponents();
		float rx = dataImg.getHeader().getDimResolutions()[0];
		float ry = dataImg.getHeader().getDimResolutions()[1];
		float rz = dataImg.getHeader().getDimResolutions()[2];
		
		String dataName = dataImg.getName();
		ImageHeader dataHeader = dataImg.getHeader();
		
		float[][][][] data = dataImg.toArray4d();
		dataImg.dispose();
		dataImage.dispose();
		
		System.out.println("fmri data loaded");

		// center and norm
		float[][][] center = new float[nx][ny][nz];
		for (int x=0;x<nx;x++) for (int y=0;y<ny;y++) for (int z=0;z<nz;z++) {
			double mean = 0.0;
			for (int t=0;t<nt;t++) mean += data[x][y][z][t];
			center[x][y][z] = (float)(mean/nt);
		}
		float[][][] norm = new float[nx][ny][nz];
		for (int x=0;x<nx;x++) for (int y=0;y<ny;y++) for (int z=0;z<nz;z++) {
			for (int t=0;t<nt;t++) data[x][y][z][t] -= center[x][y][z];
			double var = 0.0;
			for (int t=0;t<nt;t++) var += data[x][y][z][t]*data[x][y][z][t];
			norm[x][y][z] = (float)FastMath.sqrt(var/(nt-1.0));
		}
		// mask from zero norm
		boolean[][][] mask = new boolean[nx][ny][nz];
		for (int x=0;x<nx;x++) for (int y=0;y<ny;y++) for (int z=0;z<nz;z++) {
			mask[x][y][z] = (norm[x][y][z]>0);
		}

		// how to deal with artefactual correlations in different directions??
		int ncx=0, ncy=0, ncz=0;
		for (int x=1;x<nx-1;x++) for (int y=1;y<ny-1;y++) for (int z=1;z<nz-1;z++) if (mask[x][y][z]){
			if (mask[x+1][y][z]) ncx++;
			if (mask[x][y+1][z]) ncy++;
			if (mask[x][y][z+1]) ncz++;
		}
		double[] corrX = new double[ncx];
		double[] corrY = new double[ncy];
		double[] corrZ = new double[ncz];
		
		int xc=0, yc=0, zc=0;
		for (int x=1;x<nx-1;x++) for (int y=1;y<ny-1;y++) for (int z=1;z<nz-1;z++) if (mask[x][y][z]){
			if (mask[x+1][y][z]) {
				corrX[xc] = 0.0;
				for (int t=0;t<nt;t++) corrX[xc] += data[x][y][z][t]*data[x+1][y][z][t];
				corrX[xc] /= nt*norm[x][y][z]*norm[x+1][y][z];
			}
			if (mask[x][y+1][z]) {
				corrY[yc] = 0.0;
				for (int t=0;t<nt;t++) corrY[yc] += data[x][y][z][t]*data[x][y+1][z][t];
				corrY[yc] /= nt*norm[x][y][z]*norm[x][y+1][z];
			}
			if (mask[x][y][z+1]) {
				corrZ[zc] = 0.0;
				for (int t=0;t<nt;t++) corrZ[zc] += data[x][y][z][t]*data[x][y][z+1][t];
				corrZ[zc] /= nt*norm[x][y][z]*norm[x][y][z+1];
			}
		}
		Histogram histX = new Histogram(corrX, 1000, ncx);
		float offsetx = histX.argmax();
		Histogram histY = new Histogram(corrY, 1000, ncy);
		float offsety = histY.argmax();
		Histogram histZ = new Histogram(corrZ, 1000, ncz);
		float offsetz = histZ.argmax();
		System.out.println("correlation offsets: "+offsetx+", "+offsety+", "+offsetz);
		float maxx = histX.max();
		float maxy = histY.max();
		float maxz = histZ.max();
		
		float[][][][] tensor = new float[nx][ny][nz][6];
		if (ngbParam.getValue().equals("26C_neighbors")) {
			for (int x=1;x<nx-1;x++) for (int y=1;y<ny-1;y++) for (int z=1;z<nz-1;z++) if (mask[x][y][z]) {
				// for now, skip all voxels on the boundary
				boolean boundary=false;
				double[] tens = new double[6];
				for (int n=0;n<26 && !boundary;n++) {
					if (mask[x+Ngb.x[n]][y+Ngb.y[n]][z+Ngb.z[n]]) {
						double correlation = 0.0;
						for (int t=0;t<nt;t++) correlation += data[x][y][z][t]*data[x+Ngb.x[n]][y+Ngb.y[n]][z+Ngb.z[n]][t];
						correlation /= nt*norm[x][y][z]*norm[x+Ngb.x[n]][y+Ngb.y[n]][z+Ngb.z[n]];
						
						float[] v = Ngb.directionVector(n);
						tens[XX]	+= correlation*v[X]*v[X];
						tens[XY]	+= correlation*v[X]*v[Y];
						tens[XZ]	+= correlation*v[X]*v[Z];
						tens[YY]	+= correlation*v[Y]*v[Y];
						tens[YZ]	+= correlation*v[Y]*v[Z];
						tens[ZZ]+= correlation*v[Z]*v[Z];
					} else {
						boundary=true;
					}
				}
				if (!boundary) {
					tensor[x][y][z][XX] = (float)(tens[XX]/26.0);
					tensor[x][y][z][XY] = (float)(tens[XY]/26.0);
					tensor[x][y][z][XZ] = (float)(tens[XZ]/26.0);
					tensor[x][y][z][YY] = (float)(tens[YY]/26.0);
					tensor[x][y][z][YZ] = (float)(tens[YZ]/26.0);
					tensor[x][y][z][ZZ] = (float)(tens[ZZ]/26.0);
				}
			}
		} else if (ngbParam.getValue().equals("debug")) {
			for (int x=1;x<nx-1;x++) for (int y=1;y<ny-1;y++) for (int z=1;z<nz-1;z++) if (mask[x][y][z]) {
				// for now, skip all voxels on the boundary
				boolean boundary=false;
				double[] tens = new double[6];
				for (int n=0;n<6 && !boundary;n++) {
					if (mask[x+Ngb.x[n]][y+Ngb.y[n]][z+Ngb.z[n]]) {
						double corrXY = 0.0;
						for (int t=0;t<nt;t++) corrXY += data[x][y][z][t]*data[x+Ngb.x[n]][y+Ngb.y[n]][z+Ngb.z[n]][t];
						corrXY /= nt*norm[x][y][z]*norm[x+Ngb.x[n]][y+Ngb.y[n]][z+Ngb.z[n]];
						// debug
						float[] v = new float[3];
						float vn = 0.0f;
						v[X] = Ngb.x[n];
						v[Y] = Ngb.y[n];
						v[Z] = Ngb.z[n];
						vn = (float)FastMath.sqrt(v[X]*v[X]+v[Y]*v[Y]+v[Z]*v[Z]);
						v[X] /= vn;
						v[Y] /= vn;
						v[Z] /= vn;
						
						//float[] v = Ngb.directionVector(n);
						/*
						tens[XX]	+= correlation*v[X]*v[X];
						tens[XY]	+= correlation*v[X]*v[Y];
						tens[XZ]	+= correlation*v[X]*v[Z];
						tens[YY]	+= correlation*v[Y]*v[Y];
						tens[YZ]	+= correlation*v[Y]*v[Z];
						tens[ZZ]+= correlation*v[Z]*v[Z];
						*/
						if (n==0 || n==3) corrXY = (corrXY-offsetx)/(maxx-offsetx);
						if (n==1 || n==4) corrXY = (corrXY-offsety)/(maxy-offsety);
						if (n==2 || n==5) corrXY = (corrXY-offsetz)/(maxz-offsetz);
						tens[n] = corrXY;
					} else {
						boundary=true;
					}
				}
				if (!boundary) {
					tensor[x][y][z][XX] = (float)(tens[XX]);
					tensor[x][y][z][XY] = (float)(tens[XY]);
					tensor[x][y][z][XZ] = (float)(tens[XZ]);
					tensor[x][y][z][YY] = (float)(tens[YY]);
					tensor[x][y][z][YZ] = (float)(tens[YZ]);
					tensor[x][y][z][ZZ] = (float)(tens[ZZ]);
				}
			}
		} else if (ngbParam.getValue().equals("distance_based")) {
			float maxdist = extentParam.getValue().floatValue()/Numerics.min(rx,ry,rz);
			int dmax = Numerics.floor(maxdist);
			
			for (int x=dmax;x<nx-dmax;x++) for (int y=dmax;y<ny-dmax;y++) for (int z=dmax;z<nz-dmax;z++) if (mask[x][y][z]) {
				// for now, skip all voxels on the boundary
				boolean boundary=false;
				double[] tens = new double[6];
				double nsample=0.0;
				for (int i=-dmax;i<=dmax;i++) for (int j=-dmax;j<=dmax;j++) for (int l=-dmax;l<=dmax;l++) if (!boundary) {
					if (mask[x+i][y+j][z+l]) {
						double dist = FastMath.sqrt(i*i+j*j+l*l);
						if (dist<=maxdist) {
							double correlation = 0.0;
							for (int t=0;t<nt;t++) correlation += data[x][y][z][t]*data[x+i][y+j][z+l][t];
							correlation /= nt*norm[x][y][z]*norm[x+i][y+j][z+l];
							double[] v = new double[]{i/dist,j/dist,l/dist};
							tens[XX]	+= correlation*v[X]*v[X];
							tens[XY]	+= correlation*v[X]*v[Y];
							tens[XZ]	+= correlation*v[X]*v[Z];
							tens[YY]	+= correlation*v[Y]*v[Y];
							tens[YZ]	+= correlation*v[Y]*v[Z];
							tens[ZZ]	+= correlation*v[Z]*v[Z];
							nsample++;
						}
					} else {
						// only if a boundary, not if beyond max distance!
						boundary=true;
					}
				}
				if (!boundary) {
					tensor[x][y][z][XX] = (float)(tens[XX]/nsample);
					tensor[x][y][z][XY] = (float)(tens[XY]/nsample);
					tensor[x][y][z][XZ] = (float)(tens[XZ]/nsample);
					tensor[x][y][z][YY] = (float)(tens[YY]/nsample);
					tensor[x][y][z][YZ] = (float)(tens[YZ]/nsample);
					tensor[x][y][z][ZZ] = (float)(tens[ZZ]/nsample);
				}
			}		
		}
		
		System.out.println("output..");
		
		// output
		ImageDataFloat tensorImg = new ImageDataFloat(tensor);		
		tensorImg.setHeader(dataHeader);
		tensorImg.setName(dataName + "_tensor");
		tensorImage.setValue(tensorImg);
		
		histo = new byte[1000][1000][3];
		byte[][] tmp = histX.plotLogHistogram();
		for (int n=0;n<1000;n++) for (int m=0;m<1000;m++) histo[n][m][0] = tmp HERE
		
		ImageDataFloat histImg = new ImageDataFloat(histo);		
		histImg.setHeader(dataHeader);
		histImg.setName(dataName + "_hist");
		histImage.setValue(histImg);
		
		ImageDataFloat meanImg = new ImageDataFloat(center);		
		meanImg.setHeader(dataHeader);
		meanImg.setName(dataName + "_mean");
		meanImage.setValue(meanImg);
		ImageDataFloat normImg = new ImageDataFloat(norm);		
		normImg.setHeader(dataHeader);
		normImg.setName(dataName + "_norm");
		normImage.setValue(normImg);
	}

}