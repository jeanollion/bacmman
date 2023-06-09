package bacmman.ui.gui;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.image.Image;
import bacmman.image.io.ImageFormat;
import bacmman.image.io.ImageWriter;
import bacmman.processing.PSFAlign;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import bacmman.utils.Utils;

import javax.swing.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class PSFCommand {
    public static JMenu getPSFMenu(Supplier<List<Selection>> selectionSupplier, Supplier<String> path) {
        JMenu menu = new JMenu("Compute PSF");
        menu.setToolTipText("Compute PSF by averaging segmented spots in selected selections");
        BoundedNumberParameter sizeXY = new BoundedNumberParameter("Radius XY", 0, 8, 2, null).setHint("PSF Size is 2 x radius + 1");
        BoundedNumberParameter sizeZ = new BoundedNumberParameter("Radius Z", 0, 0, 0, null).setHint("PSF Size is 2 x radius + 1 (0 for 2D PSF)");
        IntervalParameter filterQuantiles = new IntervalParameter("Size Filter Quantiles", 5, 0, 1, 0.1, 0.9).setHint("Filter outlier spots by size");
        BooleanParameter flipX = new BooleanParameter("FlipX", true).setHint("If true, average with flipped version along X axis");
        BooleanParameter flipY = new BooleanParameter("FlipY", true).setHint("If true, average with flipped version along Y axis");
        BooleanParameter flipZ = new BooleanParameter("FlipZ", true).setHint("If true, average with flipped version along Z axis");
        InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS);

        PropertyUtils.setPersistent(sizeXY, "psf_sizeXY");
        PropertyUtils.setPersistent(sizeZ, "psf_sizeZ");
        PropertyUtils.setPersistent(filterQuantiles, "psf_quantiles");
        PropertyUtils.setPersistent(flipX, "psf_flipX");
        PropertyUtils.setPersistent(flipY, "psf_flipY");
        PropertyUtils.setPersistent(flipZ, "psf_flipZ");
        PropertyUtils.setPersistent(interpolation.getActionableParameter(), "psf_interpolation");
        PropertyUtils.setPersistent(interpolation.getLanczosAlpha(), "psf_interpolation_lanczos_alpha");
        PropertyUtils.setPersistent(interpolation.getLanczosClipping(), "psf_interpolation_lanczos_clipping");

        ConfigurationTreeGenerator.addToMenu(sizeXY, menu);
        ConfigurationTreeGenerator.addToMenu(sizeZ, menu);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(filterQuantiles, menu);
        JMenu flipMenu = new JMenu("Average Flip");
        ConfigurationTreeGenerator.addToMenuAsSubMenu(flipX, flipMenu);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(flipY, flipMenu);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(flipZ, flipMenu);
        menu.add(flipMenu);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(interpolation, menu);

        JMenuItem compute = new javax.swing.JMenuItem();
        compute.setText("Compute PSF");
        compute.addActionListener(evt -> {
            List<Selection> sels = selectionSupplier.get();
            if (sels.isEmpty()) return;
            int ocIdx = sels.get(0).getStructureIdx();
            if (!Utils.objectsAllHaveSameProperty(sels, s->s.getStructureIdx() == ocIdx)) return;
            List<SegmentedObject> objects = sels.stream().flatMap(s -> s.getAllElements().stream()).collect(Collectors.toList());
            objects = PSFAlign.filterBySize(objects, filterQuantiles.getValuesAsDouble());
            boolean[] flip = new boolean[3];
            flip[0] = flipX.getSelected();
            flip[1] = flipY.getSelected();
            flip[2] = flipZ.getSelected();
            Image psf = PSFAlign.getPSF(objects, interpolation.getInterpolation(), flip, sizeXY.getIntValue(), sizeXY.getIntValue(), sizeZ.getIntValue());
            if (psf!=null) {
                ImageWindowManagerFactory.getImageManager().getDisplayer().showImage(psf);
                ImageWriter.writeToFile(psf, Paths.get(path.get(), "PSF").toString(), ImageFormat.TIF);
            }
        });
        if (selectionSupplier==null) compute.setEnabled(false);
        menu.add(compute);

        return menu;
    }
}
