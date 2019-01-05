package ij.plugin;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Plot;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Data;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.fits.ImageHDU;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import nom.tam.util.Cursor;

import static nom.tam.fits.header.Standard.NAXIS;
import static nom.tam.fits.header.Standard.BITPIX;


@SuppressWarnings("unused")
public class FITS_Reader extends ImagePlus implements PlugIn {
    // private WCS wcs;
    private ImagePlus imagePlus;
    private String directory;
    private String fileName;
    private int wi;
    private int he;
    private int de;
    private float bzero;
    private float bscale;

    // The image data comes in different types, but in the end, we turn them all into floats.
    // So no matter what type the data is, we wrap it with a lambda that takes two indices and
    // returns a float. A slicker approach would require Java to support primitive types as
    // generics - which it does not.
    private interface TableWrapper { float valueAt(int x, int y); }

    /**
     * Main processing method for the FITS_Reader object
     *
     * @param path path of FITS file
     */
    public void run(String path) {
        // wcs = null;
        imagePlus = null;

        /*
         * Extract array of HDU from FITS file using nom.tam.fits
         * This also uses the old style FITS decoder to create a FileInfo.
         */
        BasicHDU[] hdus;
        try {
            hdus = getHDU(path);
        } catch (FitsException e) {
            IJ.error("Unable to open FITS file " + path + ": " + e.getMessage());
            return;
        }

        /*
         * For fpacked files the image is in the second HDU. For uncompressed images
         * it is the first HDU.
         */
        BasicHDU displayHdu;
        if (isCompressedFormat(hdus)) {
            int imageIndex = 1;
            try {
                // A side effect of this call is that wi, he, and de are set
                displayHdu = getCompressedImageData((CompressedImageHDU) hdus[imageIndex]);
            } catch (FitsException e) {
                IJ.error("Failed to uncompress image: " + e.getMessage());
                return;
            }
        } else {
            int imageIndex = 0;
            displayHdu = hdus[imageIndex];
            try {
                // wi, he, and de are set
                fixDimensions(displayHdu, displayHdu.getAxes().length);
            } catch (FitsException e) {
                IJ.error("Failed to set image dimensions: " + e.getMessage());
                return;
            }
        }
        bzero = (float) displayHdu.getBZero();
        bscale = (float) displayHdu.getBScale();

        // Create the fileInfo.
        try {
            FileInfo fileInfo = decodeFileInfo(displayHdu);
            // ImagePlus has a private member named fileInfo. This inherited method sets it.
            setFileInfo(fileInfo);
        } catch (FitsException e) {
            IJ.error("Failed to decode fileInfo: " + e.getMessage());
            return;
        }

        setProperty("Info", getHeaderInfo(displayHdu));

        Data imgData = displayHdu.getData();

        if ((wi < 0) || (he < 0)) {
            IJ.error("This does not appear to be a FITS file. " + wi + " " + he);
            return;
        }
        if (de == 1) {
            try {
                displaySingleImage(displayHdu, imgData);
            } catch (FitsException e) {
                IJ.error("Failed to display single image: " + e.getMessage());
                return;
            }
        } else {
            displayStackedImage();
        }

        show();

        IJ.showStatus("");
    }

    // Returns a newline-delimited concatenation of the header lines
    private String getHeaderInfo(BasicHDU displayHdu) {
        Header header = displayHdu.getHeader();
        StringBuffer info = new StringBuffer();
        Cursor<String, HeaderCard> iter = header.iterator();
        while (iter.hasNext()) {
            info.append(iter.next());
            info.append('\n');
        }
        return info.toString();
    }

    private FileInfo decodeFileInfo(BasicHDU displayHdu) throws FitsException {
        Header header = displayHdu.getHeader();
        FileInfo fi = new FileInfo();
        fi.fileFormat = FileInfo.FITS;
        fi.fileName = fileName;
        fi.directory = directory;
        fi.width = wi;
        fi.height = he;
        fi.nImages = de;
        fi.pixelWidth = header.getDoubleValue("CDELT1");
        fi.pixelHeight = header.getDoubleValue("CDELT2");
        fi.pixelDepth = header.getDoubleValue("CDELT3");
        fi.unit = header.getStringValue("CTYPE1");
        int bitsPerPixel = header.getIntValue(BITPIX);
        fi.fileType = fileTypeFromBitsPerPixel(bitsPerPixel);
        fi.offset = (int)header.getOriginalSize(); // downcast because spec is allowing for a lot of headers!
        return fi;
    }

    private int fileTypeFromBitsPerPixel(int bitsPerPixel) throws FitsException {
        switch (bitsPerPixel) {
            case 8:
                return FileInfo.GRAY8;
            case 16:
                return FileInfo.GRAY16_SIGNED;
            case 32:
                return FileInfo.GRAY32_INT;
            case -32:
                return FileInfo.GRAY32_FLOAT;
            case -64:
                return FileInfo.GRAY64_FLOAT;
            default:
                throw new FitsException("BITPIX must be 8, 16, 32, -32 or -64, but BITPIX=" + bitsPerPixel);
        }
    }

    private boolean isCompressedFormat(BasicHDU[] basicHDU) {
        return basicHDU[0].getHeader().getIntValue(NAXIS) == 0;
    }

    private ImageHDU getCompressedImageData(CompressedImageHDU hdu) throws FitsException {
        wi = hdu.getHeader().getIntValue("ZNAXIS1");
        he = hdu.getHeader().getIntValue("ZNAXIS2");
        de = 1;

        return hdu.asImageHDU();
    }

    private void displayStackedImage() {
        ImageStack stack = imagePlus.getStack();
        for (int i = 1; i <= stack.getSize(); i++) {
            stack.getProcessor(i).flipVertical();
        }
        setStack(fileName, stack);
    }

    private void displaySingleImage(BasicHDU hdu, Data imgData)
            throws FitsException {
        ImageProcessor imageProcessor = null;
        int dim = hdu.getAxes().length;

        if (hdu.getHeader().getIntValue(NAXIS) == 2) {
            imageProcessor = process2DimensionalImage(hdu, imgData);
        } else if (hdu.getHeader().getIntValue(NAXIS) == 3
                && hdu.getAxes()[dim - 2] == 1 && hdu.getAxes()[dim - 3] == 1) {
            imageProcessor = process3DimensionalImage(hdu, imgData);
        }

        if (imageProcessor == null) {
            imageProcessor = imagePlus.getProcessor();
            imageProcessor.flipVertical();
            setProcessor(fileName, imageProcessor);
        }
    }

    private ImageProcessor process3DimensionalImage(BasicHDU hdu, Data imgData)
            throws FitsException {
        short[][][] itab = (short[][][]) imgData.getKernel();
        float[] xValues = new float[wi];
        float[] yValues = new float[wi];

        for (int y = 0; y < wi; y++) {
            yValues[y] = bzero + bscale * itab[0][0][y];
        }

        String unitY = "IntensityRS ";
        String unitX = "FrequencyRS ";
        float CRPIX1 = getCRPIX1(hdu);
        float CDELT1 = getCDELT1(hdu);
        int div = 1;
        float CRVAL1 = getCRVAL1ProcessX(hdu, xValues, CRPIX1, CDELT1);
        if (CRVAL1 > 2000000000) {
            div = 1000000000;
            unitX += "(Ghz)";
        } else if (CRVAL1 > 1000000000) {
            div = 1000000;
            unitX += "(Mhz)";
        } else if (CRVAL1 > 1000000) {
            div = 1000;
            unitX += "(Khz)";
        } else {
            unitX += "(Hz)";
        }

        for (int x = 0; x < wi; x++) {
            xValues[x] = xValues[x] / div;
        }

        @SuppressWarnings("deprecation") Plot P = new Plot(
                "PlotWinTitle" + " " + fileName,
                "X: " + unitX, "Y: " + unitY, xValues, yValues);
        P.draw();

        FloatProcessor imgtmp;
        imgtmp = new FloatProcessor(wi, he);
        ImageProcessor ip = getImageProcessor2(yValues, imgtmp);
        setProcessor(fileName, ip);
        return ip;
    }

    private float getCDELT1(BasicHDU hdu) {
        float CDELT1 = 0;
        if (hdu.getHeader().getStringValue("CDELT1") != null) {
            CDELT1 = Float
                    .parseFloat(hdu.getHeader().getStringValue("CDELT1"));
        }
        return CDELT1;
    }

    private ImageProcessor process2DimensionalImage(BasicHDU hdu, Data imgData)
            throws FitsException {
        ImageProcessor ip;
        ///////////////////////////// 16 BITS ///////////////////////
        if (hdu.getBitPix() == 16) {
            short[][] itab = (short[][]) imgData.getKernel();
            TableWrapper wrapper = (x, y) -> itab[x][y];
            ip = getImageProcessor(wrapper);

        } // 8 bits
        else if (hdu.getBitPix() == 8) {
            // Only in the 8 case is the signed-to-unsigned correction done -- oversight?!?
            byte[][] itab = (byte[][]) imgData.getKernel();
            TableWrapper wrapper = (x, y) -> (float)(itab[x][y] < 0 ? itab[x][y] + 256 : itab[x][y]);
            ip = getImageProcessor(wrapper);
        } // 16-bits
        ///////////////// 32 BITS ///////////////////////
        else if (hdu.getBitPix() == 32) {
            int[][] itab = (int[][]) imgData.getKernel();
            TableWrapper wrapper = (x, y) -> (float)itab[x][y];
            ip = getImageProcessor(wrapper);
        } // 32 bits
        /////////////// -32 BITS ?? /////////////////////////////////
        else if (hdu.getBitPix() == -32) {
            float[][] itab = (float[][]) imgData.getKernel();
            TableWrapper wrapper = (x, y) -> (float)itab[x][y];
            ip = getImageProcessor(wrapper);

            // special spectre optique transit
            if ((hdu.getHeader().getStringValue("STATUS") != null) && (hdu
                    .getHeader().getStringValue("STATUS")
                    .equals("SPECTRUM")) && (
                    hdu.getHeader().getIntValue(NAXIS) == 2)) {
                //IJ.log("spectre optique");
                float[] xValues = new float[wi];
                float[] yValues = new float[wi];
                for (int y = 0; y < wi; y++) {
                    yValues[y] = itab[0][y];
                    if (yValues[y] < 0) {
                        yValues[y] = 0;
                    }
                }
                String unitY = "IntensityRS ";
                String unitX = "WavelengthRS ";
                float CRPIX1 = getCRPIX1(hdu);
                float CDELT1 = getCDELT1(hdu);
                float odiv = 1;
                float CRVAL1 = getCRVAL1ProcessX(hdu, xValues, CRPIX1, CDELT1);
                if (CRVAL1 < 0.000001) {
                    odiv = 1000000;
                    unitX += "(" + "\u00B5" + "m)";
                } else {
                    unitX += "ADU";
                }

                for (int x = 0; x < wi; x++) {
                    xValues[x] = xValues[x] * odiv;
                }

                @SuppressWarnings("deprecation") Plot P = new Plot(
                        "PlotWinTitle "
                                + fileName, "X: " + unitX, "Y: " + unitY,
                        xValues, yValues);
                P.draw();
            } //// end of special optique
        } // -32 bits
        else {
            ip = imagePlus.getProcessor();
        }
        return ip;
    }

    private float getCRPIX1(BasicHDU hdu) {
        float CRPIX1 = 0;
        if (hdu.getHeader().getStringValue("CRPIX1") != null) {
            CRPIX1 = Float.parseFloat(
                    hdu.getHeader().getStringValue("CRPIX1"));
        }
        return CRPIX1;
    }

    private float getCRVAL1ProcessX(BasicHDU hdu, float[] xValues, float CRPIX1, float CDELT1) {
        float CRVAL1 = 0;
        if (hdu.getHeader().getStringValue("CRVAL1") != null) {
            CRVAL1 = Float
                    .parseFloat(hdu.getHeader().getStringValue("CRVAL1"));
        }
        for (int x = 0; x < wi; x++) {
            xValues[x] = CRVAL1 + (x - CRPIX1) * CDELT1;
        }
        return CRVAL1;
    }

    private ImageProcessor getImageProcessor(TableWrapper wrapper) {
        ImageProcessor ip;
        int idx = 0;
        float[] imgtab;
        FloatProcessor imgtmp;
        imgtmp = new FloatProcessor(wi, he);
        imgtab = new float[wi * he];
        for (int x = 0; x < wi; x++) {
            for (int y = 0; y < he; y++) {
                imgtab[idx] = bzero + bscale * wrapper.valueAt(x, y);
                idx++;
            }
        }
        ip = getImageProcessor2(imgtab, imgtmp);
        this.setProcessor(fileName, ip);
        return ip;
    }

    private ImageProcessor getImageProcessor2(float[] imgtab, FloatProcessor imgtmp) {
        ImageProcessor ip;
        imgtmp.setPixels(imgtab);
        imgtmp.resetMinAndMax();

        if (he == 1) {
            imgtmp = (FloatProcessor) imgtmp.resize(wi, 100);
        }
        if (wi == 1) {
            imgtmp = (FloatProcessor) imgtmp.resize(100, he);
        }
        ip = imgtmp;
        ip.flipVertical();
        return ip;
    }

    private void fixDimensions(BasicHDU hdu, int dim) throws FitsException {
        wi = hdu.getAxes()[dim - 1];
        he = hdu.getAxes()[dim - 2];
        if (dim > 2) {
            de = hdu.getAxes()[dim - 3];
        } else {
            de = 1;
        }
    }

    private BasicHDU[] getHDU(String path) throws FitsException {
        OpenDialog od = new OpenDialog("Open FITS...", path);
        directory = od.getDirectory();
        fileName = od.getFileName();
        if (fileName == null) {
            throw new FitsException("Null filename.");
        }
        IJ.showStatus("Opening: " + directory + fileName);
        IJ.log("Opening: " + directory + fileName);

        Fits fits = new Fits(directory + fileName);

        return fits.read();
    }

    // The following code is nice, but it is causing a dependency on skyview.geometry.WCS, so bye-bye.
    //
    //    /**
    //     * Gets the locationAsString attribute of the FITS object
    //     *
    //     * @param x Description of the Parameter
    //     * @param y Description of the Parameter
    //     * @return The locationAsString value
    //     */
    //    public String getLocationAsString(int x, int y) {
    //        String s;
    //        if (wcs != null) {
    //            double[] in = new double[2];
    //            in[0] = (double) (x);
    //            in[1] = getProcessor().getHeight() - y - 1.0;
    //            //in[2]=0.0;
    //            double[] out = wcs.inverse().transform(in);
    //            double[] coord = new double[2];
    //            skyview.geometry.Util.coord(out, coord);
    //            CoordinateFormatter cf = new CoordinateFormatter();
    //            String[] ra = cf.sexagesimal(Math.toDegrees(coord[0]) / 15.0, 8).split(" ");
    //            String[] dec = cf.sexagesimal(Math.toDegrees(coord[1]), 8).split(" ");
    //
    //            s = "x=" + x + ",y=" + y + " (RA=" + ra[0] + "h" + ra[1] + "m" + ra[2] + "s,  DEC="
    //                    + dec[0] + "\u00b0" + " " + dec[1] + "' " + dec[2] + "\"" + ")";
    //
    //        } else {
    //            s = "x=" + x + " y=" + y;
    //        }
    //        if (getStackSize() > 1) {
    //            s += " z=" + (getCurrentSlice() - 1);
    //        }
    //        return s;
    //    }

    // This code also has a dependency on skyview.geometry.WCS. Why did we need to write a temporary FITS
    // file anyway? writeTemporaryFITSFile(displayHdu) the last thing done in run().
    //
    //    private void writeTemporaryFITSFile(BasicHDU hdu) throws FileNotFoundException, FitsException {
    //        File file = new File(IJ.getDirectory("home") + ".tmp.fits");
    //        FileOutputStream fis = new FileOutputStream(file);
    //        DataOutputStream dos = new DataOutputStream(fis);
    //        fits.write(dos);
    //        try {
    //            wcs = new WCS(hdu.getHeader());
    //        } catch (Exception e) {
    //            Logger.getLogger(FITS_Reader.class.getName()).log(Level.SEVERE, null, e);
    //        } finally {
    //            try {
    //                fis.close();
    //            } catch (IOException ex) {
    //                Logger.getLogger(FITS_Reader.class.getName()).log(Level.SEVERE, null, ex);
    //            }
    //        }
    //    }

}