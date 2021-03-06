package de.mpg.cbs.jist.laminar;

import edu.jhu.ece.iacl.jist.pipeline.AlgorithmRuntimeException;
import edu.jhu.ece.iacl.jist.pipeline.CalculationMonitor;
import edu.jhu.ece.iacl.jist.pipeline.ProcessingAlgorithm;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamCollection;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamOption;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamVolume;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamDouble;
import edu.jhu.ece.iacl.jist.pipeline.parameter.ParamInteger;
import edu.jhu.ece.iacl.jist.structures.image.ImageHeader;
import edu.jhu.ece.iacl.jist.structures.image.ImageData;
import edu.jhu.ece.iacl.jist.structures.image.ImageDataUByte;
import edu.jhu.ece.iacl.jist.structures.image.ImageDataFloat;
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

import java.io.*;
import java.util.*;
import gov.nih.mipav.view.*;


import gov.nih.mipav.model.structures.jama.*;
import org.apache.commons.math3.util.FastMath;


public class JistLaminarIterativeSmoothing extends ProcessingAlgorithm{
	private ParamVolume dataImage;
	private ParamVolume layersImage;
	private ParamVolume maskImage;
	
	private ParamDouble fwhmParam;
	
	private ParamVolume smoothDataImage;
	
	// global variables
	private static final byte X = 0;
	private static final byte Y = 1;
	private static final byte Z = 2;

	private static final float HASQ3 = (float)(FastMath.sqrt(3.0)/2.0);


	protected void createInputParameters(ParamCollection inputParams) {
		inputParams.add(dataImage = new ParamVolume("Data Image",null,-1,-1,-1,-1));
		inputParams.add(layersImage = new ParamVolume("Profile Surface Image",null,-1,-1,-1,-1));
		inputParams.add(maskImage = new ParamVolume("ROI Mask (opt)"));
		maskImage.setMandatory(false);
		
		inputParams.add(fwhmParam = new ParamDouble("FWHM (mm)", 0.0, 50.0, 5.0));
		
		inputParams.setPackage("CBS Tools");
		inputParams.setCategory("Cortex Processing.devel");
		inputParams.setLabel("Iterative Cortical Smoothing");
		inputParams.setName("IterativeCorticalSmoothing");

		AlgorithmInformation info = getAlgorithmInformation();
		info.add(new AlgorithmAuthor("Pierre-Louis Bazin", "bazin@cbs.mpg.de","http://www.cbs.mpg.de/"));
		info.setAffiliation("Spinoza Centre | Netherlands Institute for Neuroscience | Max Planck Institute for Human Cognitive and Brain Sciences");
		info.setDescription("Smoothes data image along layer surfaces with an iterative heat kernel approach.");
		
		info.setVersion("3.1.1");
		info.setStatus(DevelopmentStatus.RC);
		info.setEditable(false);	}


	protected void createOutputParameters(ParamCollection outputParams) {
		outputParams.add(smoothDataImage = new ParamVolume("Smoothed Data",VoxelType.FLOAT));
		
		outputParams.setName("SmoothedData");
		outputParams.setLabel("Smoothed Data");
	}

	
	protected void execute(CalculationMonitor monitor) throws AlgorithmRuntimeException {
		
		// import the image data
		System.out.println("\n Import data");
		
		String intensname = Interface.getName(dataImage);
		ImageHeader header = Interface.getHeader(dataImage);
		
		int[] dims = Interface.getDimensions(layersImage);
		float[] res = Interface.getResolutions(layersImage);
		int nlayers = Interface.getComponents(layersImage)-1;
		int nxyz = dims[X]*dims[Y]*dims[Z];
		int nx = dims[X]; int ny = dims[Y]; int nz = dims[Z]; 
		
		// at least two layer surfaces (gwb and cgb)
		float[] layers = Interface.getFloatImage4D(layersImage);
		
		float[] data = null;
		int nt = Interface.getComponents(dataImage);
		if (Interface.isImage4D(dataImage)) {
			data = Interface.getFloatImage4D(dataImage);
		} else {
			data = Interface.getFloatImage3D(dataImage);
		}
		
		// create a set of ROI masks for all the regions and also outside of the area where layer 1 is > 0 and layer 2 is < 0
		boolean[] ctxmask = new boolean[nxyz];
		for (int xyz=0;xyz<nxyz;xyz++) {
			ctxmask[xyz] = (layers[xyz]>=0.0 && layers[xyz+nlayers*nxyz]<=0.0);
		}
		
		if (Interface.isValid(maskImage)) {
			byte[] mask = Interface.getUByteImage3D(maskImage);
			for (int xyz=0;xyz<nxyz;xyz++) if (mask[xyz]==0) ctxmask[xyz] = false;
		}
		
		// remove image boundaries
		for (int x=0;x<nx;x++) for (int y=0;y<ny;y++) for (int z=0;z<nz;z++) {
			int xyz = x+nx*y+nx*ny*z;
			
			if (x<=1 || x>=nx-2 || y<=1 || y>=ny-2 || z<=1 || z>=nz-2) {
				ctxmask[xyz] = false;
			}
		}
		
		// get estimates for partial voluming of each layer
		float[][] pvol = new float[nlayers+1][nxyz];
		for (int x=0; x<nx; x++) for (int y=0; y<ny; y++) for (int z = 0; z<nz; z++) {
			int xyz = x + nx*y + nx*ny*z;
			if (ctxmask[xyz]) {
				for (int l=0;l<=nlayers;l++) {
					pvol[l][xyz] = fastApproxPartialVolumeFromSurface(x, y, z, layers, l*nxyz, nx, ny, nz);
					//else pvol[l][xyz] = partialVolumeFromSurface(x, y, z, layers, l*nxyz, nx, ny, nz);
				}
			}
		}
		
		// iterations: number of voxels needed to reach the boundary (currently imprecise)	
		double sigma = 0.5;
		float weight = (float) FastMath.exp((double) -(res[0]*res[0])/(2*sigma*sigma));
		int iterations = (int) FastMath.round(FastMath.pow(fwhmParam.getValue().doubleValue()/(2*FastMath.sqrt(FastMath.log(4.0))), 2)/sigma);
		System.out.println("\n Number of iterations with sigma = 1: "+iterations);
		System.out.println("\n Neighbour weight = "+weight);
		
		// no black & white iterations: the layers are generally too thin
		float[] sdata = new float[nxyz*nt];
		double[] layerval = new double[nt];
		double layersum = 0.0;
	
		for (int itr=0; itr<iterations; itr++) {
			
			// here we assume a linear combination across neighbors and layers
			// other options could be to keep only the layer with largest pv
			// note that the smoothing happens only parallel to the layers here
			for (int xyz=0;xyz<nxyz;xyz++) if (ctxmask[xyz]) {
				double sumweight = 0.0;
				for (int t=0;t<nt;t++) sdata[xyz+nxyz*t] = 0.0f;
				for (int l=0;l<nlayers;l++) {
					double pvweight = pvol[l+1][xyz]-pvol[l][xyz];
					if (pvweight>0) {
						for (int t=0;t<nt;t++) layerval[t] = pvweight*data[xyz+nxyz*t];
						layersum = pvweight;
						for (byte k=0; k<26; k++) {
							int xyzn = Ngb.neighborIndex(k, xyz, nx, ny, nz);
							float dw = 1.0f/Ngb.neighborDistance(k);
							if (ctxmask[xyzn]) {
								double pv = pvol[l+1][xyzn]-pvol[l][xyzn];
								for (int t=0;t<nt;t++) layerval[t] += pv*weight*dw*data[xyzn+nxyz*t];
								layersum += pv*weight*dw;
							}
						}
						for (int t=0;t<nt;t++) layerval[t] /= layersum;
					}
					for (int t=0;t<nt;t++) sdata[xyz+nxyz*t] += (float)(pvweight*layerval[t]);
					sumweight += pvweight;
				}
				for (int t=0;t<nt;t++) sdata[xyz+nxyz*t] /= (float)sumweight;
			}
			// copy back to original data image
			for (int xyz=0;xyz<nxyz;xyz++) if (ctxmask[xyz]) {
				for (int t=0;t<nt;t++) data[xyz+nxyz*t] = sdata[xyz+nxyz*t];
			}
		}
				
		if (nt>1) Interface.setFloatImage4D(sdata, new int[]{nx,ny,nz}, nt, smoothDataImage, intensname+"_boundary", header);
		else Interface.setFloatImage3D(sdata, new int[]{nx,ny,nz}, smoothDataImage, intensname+"_boundary", header);
	}
	
	float partialVolumeFromSurface(int x0, int y0, int z0, float[] levelset, int offset, int nx, int ny, int nz) {
		int xyz0 = x0+nx*y0+nx*ny*z0;
		double phi0 = levelset[xyz0+offset];
		
		if (phi0<-HASQ3) return 1.0f;
		else if (phi0>HASQ3) return 0.0f;
		
		// use direction, distance to create a continuous plane model V(X-X0)+phi(X0) = d(X,P)
		double vx = 0.5*(levelset[xyz0+1+offset]-levelset[xyz0-1+offset]);
		double vy = 0.5*(levelset[xyz0+nx+offset]-levelset[xyz0-nx+offset]);
		double vz = 0.5*(levelset[xyz0+nx*ny+offset]-levelset[xyz0-nx*ny+offset]);
		double norm = FastMath.sqrt(vx*vx+vy*vy+vz*vz);
		
		// integrate the voxel cube over that model
		
		// first shot: numerically (other options: Heaviside integration, case-by-case geometric solution?)
		double volume = 0.0;
		for (double x=-0.45; x<=0.45; x+=0.1) for (double y=-0.45; y<=0.45; y+=0.1) for (double z=-0.45; z<=0.45; z+=0.1) {
			if (vx*x+vy*y+vz*z+norm*phi0<=0) volume+=0.001;
		}
		
		// second option: with explicit definition of the intersection (~marching cubes!)
		// requires intersecting all 12 edges from the voxel cube, then defining either case-by-case formulas or 
		// deriving the formula for an integral of the Heaviside function
		
		return (float)volume;
	}

	float fastApproxPartialVolumeFromSurface(int x0, int y0, int z0, float[] levelset, int offset, int nx, int ny, int nz) {
		return Numerics.bounded(0.5f-levelset[x0+nx*y0+nx*ny*z0+offset],0.0f,1.0f);
	}	
}
