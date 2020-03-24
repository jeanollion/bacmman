/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.data_structure;

import bacmman.test_utils.TestUtils;
import bacmman.configuration.experiment.ChannelImage;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.dao.ImageDAO;
import bacmman.data_structure.dao.BasicMasterDAO;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.io.ImageFormat;
import bacmman.image.io.ImageWriter;
import bacmman.image.TypeConverter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import bacmman.plugins.PluginFactory;
import bacmman.plugins.plugins.transformations.SimpleTranslation;
import bacmman.processing.ImageTransformation;

/**
 *
 * @author Jean Ollion
 */
public class ProcessingTest {
    @Rule
    public TemporaryFolder testFolder = new TemporaryFolder();
    
    public static ImageByte[][] createDummyImagesTC(int sizeX, int sizeY, int sizeZ, int timePointNumber, int channelNumber) {
        ImageByte[][] images = new ImageByte[timePointNumber][channelNumber];
        for (int t = 0; t<timePointNumber; t++) {
            for (int c = 0; c<channelNumber;c++) {
                images[t][c] = new ImageByte("t"+t+"c"+c, sizeX, sizeY, sizeZ);
                images[t][c].setPixel(t, c, c, 1);
                images[t][c].setCalibration(0.1f, 0.23f);
            }
        }
        return images;
    }

    /*public static void main(String[] args) {
        ProcessingTest t = new ProcessingTest();
        try {
            t.testFolder.create();
        } catch (IOException e) {
            e.printStackTrace();
        }
        t.importFieldTest();
    }*/

    @Test
    public void importFieldTest() {
        // creation de l'image de test
        String title = "imageTestMultiple";
        ImageFormat format = ImageFormat.OMETIF;
        File folder = testFolder.newFolder("TestImages");
        int timePoint = 3;
        int channel = 2;
        ImageByte[][] images = createDummyImagesTC(6, 5 ,4,  timePoint, channel);
        ImageByte[][] images2 = createDummyImagesTC(6, 5 ,4, timePoint, channel+1);
        ImageByte[][] images3 = createDummyImagesTC(6, 5 ,4, timePoint+1, channel);
        
        ImageWriter.writeToFile(folder.getAbsolutePath(), title, format, images);
        File folder2 = Paths.get(folder.getAbsolutePath(), "subFolder").toFile();
        folder2.mkdir();
        ImageWriter.writeToFile(folder2.getAbsolutePath(), title, format, images);
        ImageWriter.writeToFile(folder2.getAbsolutePath(), title+"2", format, images, images, images2, images3);
        
        Experiment xp = new Experiment("testXP", new Structure("structure"));
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        xp.getChannelImages().insert(new ChannelImage("channel1"), new ChannelImage("channel2"));
        
        Processor.importFiles(xp, true, null, folder.getAbsolutePath());
        assertEquals("number of fields detected", 6-1-1, xp.getPositionCount()); // 6 - 1 (unique title) - 1 (channel number)
        assertTrue("field non null", xp.getPosition(title)!=null);
        assertTrue("images non null", xp.getPosition(title).getInputImages()!=null);
        TestUtils.assertImage("import field test", images[0][0], xp.getPosition(title).getInputImages().getImage(0, 0), 0);
    }
    
    @Test
    public void testImportFieldKeyWord() {
        // creation de l'image de test
        String title = "imageTestMultiple";
        ImageFormat format = ImageFormat.OMETIF;
        File folder = testFolder.newFolder("TestImages");
        int timePoint = 3;
        ImageByte[][] images = createDummyImagesTC(6, 5 ,4,  timePoint, 1);
        ImageByte[][] images2 = createDummyImagesTC(6, 5 ,4,  timePoint+1, 1);
        
        ImageWriter.writeToFile(folder.getAbsolutePath(), title+"_c1", format, images);
        ImageWriter.writeToFile(folder.getAbsolutePath(), title+"_c2", format, images);
        ImageWriter.writeToFile(folder.getAbsolutePath(), title+"1_c1", format, images);
        ImageWriter.writeToFile(folder.getAbsolutePath(), title+"1_c2", format, images);
        ImageWriter.writeToFile(folder.getAbsolutePath(), title+"2_c1", format, images);
        ImageWriter.writeToFile(folder.getAbsolutePath(), title+"3_c1", format, images);
        ImageWriter.writeToFile(folder.getAbsolutePath(), title+"3_c2", format, images2);
        
        File folder2 = Paths.get(folder.getAbsolutePath(),"subFolder").toFile();
        folder2.mkdir();
        ImageWriter.writeToFile(folder2.getAbsolutePath(), title+"_c1", format, images);
        ImageWriter.writeToFile(folder2.getAbsolutePath(), title+"_c2", format, images);
        ImageWriter.writeToFile(folder2.getAbsolutePath(), title+"4_c1", format, images);
        ImageWriter.writeToFile(folder2.getAbsolutePath(), title+"4_c2", format, images);
        
        Experiment xp = new Experiment("testXP", new Structure("structure"));
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.ONE_FILE_PER_CHANNEL_POSITION);
        xp.getChannelImages().insert(new ChannelImage("channel1", "_c1"), new ChannelImage("channel2", "_c2"));
        
        Processor.importFiles(xp, true, null, folder.getAbsolutePath());
        assertEquals("number of fields detected", 6-1-1-1, xp.getPositionCount()); // 6 - 1 (unique title) - 1 (channel number)-1(timepoint number)
        TestUtils.assertImage("test import field keyword", images[0][0], xp.getPosition(title).getInputImages().getImage(0, 0), 0);
    }
    
    @Test
    public void preProcessingTest() {
        // set-up XP
        File daoFolder = testFolder.newFolder("TestPreProcessingDAOFolder");
        Experiment xp = new Experiment("test");
        xp.setImportImageMethod(Experiment.IMPORT_METHOD.SINGLE_FILE);
        ChannelImage ci1 = xp.getChannelImages().createChildInstance();
        ChannelImage ci2 = xp.getChannelImages().createChildInstance();
        xp.getChannelImages().insert(ci1, ci2);
        xp.setOutputDirectory(daoFolder.getAbsolutePath());
        //xp.setOutputImageDirectory("/tmp");
        xp.setImageDAOType(Experiment.ImageDAOTypes.LocalFileSystem);
        
        // import fields
        ImageByte[][] images = createDummyImagesTC(6, 5 ,4, 3, 2);
        images[0][0].setPixel(0, 0, 0, 1);
        File folder = testFolder.newFolder("TestImagesPreProcessing");
        ImageWriter.writeToFile(folder.getAbsolutePath(), "field1", ImageFormat.OMETIF, images);
        Processor.importFiles(xp, true, null, folder.getAbsolutePath());
        Position f = xp.getPosition(0);
        assertEquals("number of fields detected", 1, xp.getPositionCount());
        
        //set-up pre-processing chains
        PluginFactory.findPlugins("bacmman.plugins.plugins.transformations");
        SimpleTranslation t = new SimpleTranslation(1, 0, 0).setInterpolationScheme(ImageTransformation.InterpolationScheme.LINEAR);;
        f.getPreProcessingChain().addTransformation(0, null, t);
        SimpleTranslation t2 = new SimpleTranslation(0, 1, 0).setInterpolationScheme(ImageTransformation.InterpolationScheme.LINEAR);;
        f.getPreProcessingChain().addTransformation(0, null, t2);
        
        //pre-process
        BasicMasterDAO masterDAO = new BasicMasterDAO(xp, new SegmentedObjectAccessor());
        try {
            Processor.preProcessImages(masterDAO);
        } catch (Exception ex) {
            assertTrue("Failed to preprocess images", false);
        }
       
        // test 
        ImageDAO dao = xp.getImageDAO();
        Image image = dao.openPreProcessedImage(0, 0, "field1");
        assertTrue("Image saved in DAO", image!=null);
        SimpleTranslation tInv = new SimpleTranslation(-1, -1, 0).setInterpolationScheme(ImageTransformation.InterpolationScheme.LINEAR);
        Image imageInv = tInv.applyTransformation(0, 0, image);
        TestUtils.assertImage("preProcessing: simple translation", images[0][0], TypeConverter.toByte(imageInv, null), 0);
    }
    
    /*private static ImageByte getMask(StructureObject root, int[] pathToRoot) {
        ImageByte mask = new ImageByte("mask", root.getMask());
        int startLabel = 1;
        for (StructureObject o : StructureObjectUtils.getAllObjects(root, pathToRoot)) mask.appendBinaryMasks(startLabel++, o.getMask().addOffset(o.getRelativeBoundingBox(null)));
        return mask;
    }*/
    
    
    
    
        // creation des images @t0: 
        //ImageByte maskMC = getMask(root[0], xp.getPathToRoot(0));
        //ImageByte maskBactos = getMask(root[0], xp.getPathToRoot(1));
        //return new ImageByte[]{maskMC, maskBactos};

    
    /*public static void main(String[] args) throws IOException {
        ProcessingTest t = new ProcessingTest();
        t.testFolder.create();
        Image[] images = t.StructureObjectTest();
        for (Image i : images) showImageIJ(i);
    }*/
}
