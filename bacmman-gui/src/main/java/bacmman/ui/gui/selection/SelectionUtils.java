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
package bacmman.ui.gui.selection;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.MeasurementFilterParameter;
import bacmman.configuration.parameters.SimpleListParameter;
import bacmman.configuration.parameters.ui.StayOpenMenuItem;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.plugins.plugins.processing_pipeline.Duplicate;
import bacmman.ui.GUI;

import static bacmman.ui.gui.image_interaction.InteractiveImageKey.inferType;

import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.gui.image_interaction.*;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.SelectionDAO;
import bacmman.image.BoundingBox;
import bacmman.image.Image;

import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.*;

import bacmman.utils.Pair;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class SelectionUtils {
    public static final Logger logger = LoggerFactory.getLogger(SelectionUtils.class);
    public static boolean validSelectionName(MasterDAO db, String name, boolean ignoreDuplicate, boolean prompteOverwriteDuplicate) {
        if (!Utils.isValid(name, false)) {
            logger.error("Selection name should not contain special characters");
            if (GUI.hasInstance()) GUI.getInstance().setMessage("Selection name should not contain special characters");
            return false;
        }
        if (ignoreDuplicate) return true;
        boolean exists = SelectionUtils.selectionNameExists(db, name);
        if (exists) {
            if (prompteOverwriteDuplicate) {
                if (Utils.promptBoolean("Selection name already exists. Overwrite ?", GUI.getInstance()))
                    db.getSelectionDAO().delete(name);
                else return false;
            } else return false;
        }
        return true;
    }

    public static boolean selectionNameExists(MasterDAO db, String name) {
        if (db.getSelectionDAO()==null) return false;
        List<Selection> sel = db.getSelectionDAO().getSelections();
        return Utils.transform(sel, Selection::getName).contains(name);
    }
    
    public static List<SegmentedObject> getSegmentedObjects(InteractiveImage i, List<Selection> selections) {
        if (i==null) ImageWindowManagerFactory.getImageManager().getCurrentImageObjectInterface();
        if (i==null) return Collections.EMPTY_LIST;
        String fieldName = i.getParent().getPositionName();
        if (selections==null || selections.isEmpty()) return Collections.EMPTY_LIST;
        selections.removeIf(s -> s.getStructureIdx()!=selections.get(0).getStructureIdx());
        List<String> allStrings=  new ArrayList<>();
        for (Selection s : selections) allStrings.addAll(s.getElementStrings(fieldName));
        return Pair.unpairKeys(getSegmentedObjects(i, allStrings));
    }

    public static Collection<Pair<SegmentedObject, BoundingBox>> getSegmentedObjects(InteractiveImage i, Collection<String> indices) {
        if (i instanceof HyperStack) {
            // need to get objects from all frames of selection
            HyperStack h = (HyperStack)i;
            Stream<Integer> frames = indices.stream().map(idx -> Selection.parseIndices(idx)[0]).distinct();
            Stream<Pair<SegmentedObject, BoundingBox>> objects = frames.flatMap(frame -> h.getObjects(frame).stream());
            return SelectionOperations.filterPairs(objects, indices);
        } else return SelectionOperations.filterPairs(i.getObjects().stream(), indices);
    }

    public static InteractiveImage fixIOI(InteractiveImage i, int structureIdx) {
        ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
        if (i!=null && i.getChildStructureIdx()!=structureIdx) {
            Image im = iwm.getImage(i);
            i = iwm.getImageObjectInterface(im, structureIdx);
        }
        if (i==null) i = iwm.getImageObjectInterface(null, structureIdx);
        return i;
    }
    public static void displayObjects(Selection s, InteractiveImage i) {
        ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
        i = fixIOI(i, s.getStructureIdx());
        if (i!=null) {
            Collection<Pair<SegmentedObject, BoundingBox>> objects = SelectionOperations.filterPairs(i.getObjects().stream(), s.getElementStrings(SegmentedObjectUtils.getPositions(i.getParents())));
            //Set<StructureObject> objects = s.getElements(StructureObjectUtils.getPositions(i.getParents()));
            //logger.debug("disp objects: #positions: {}, #objects: {}", StructureObjectUtils.getPositions(i.getParents()).size(), objects.size() );
            if (objects!=null) {
                //iwm.displayObjects(null, i.pairWithOffset(objects), s.getColor(true), false, false);
                iwm.displayObjects(null, objects, s.getColor(true), false, false);
            }
        }
    }
    
    public static void hideObjects(Selection s, InteractiveImage i) {
        ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
        i = fixIOI(i, s.getStructureIdx());
        if (i!=null) {
            //Set<StructureObject> objects = s.getElements(StructureObjectUtils.getPositions(i.getParents()));
            Collection<Pair<SegmentedObject, BoundingBox>> objects = SelectionOperations.filterPairs(i.getObjects().stream(), s.getElementStrings(SegmentedObjectUtils.getPositions(i.getParents())));
            if (objects!=null) {
                iwm.hideObjects(null, objects, false);
                //iwm.hideObjects(null, i.pairWithOffset(objects), false);
            }
        }
    }
    public static void displayTracks(Selection s, InteractiveImage i) {
        ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
        InteractiveImage ii = fixIOI(i, s.getStructureIdx());
        if (ii!=null) {
            Set<SegmentedObject> selTH = s.getElementsAsStream(SegmentedObjectUtils.getPositions(ii.getParents()).stream()).map(SegmentedObject::getTrackHead).collect(Collectors.toSet());
            ii.getObjects().stream().map(o -> o.key).map(SegmentedObject::getTrackHead).distinct().filter(selTH::contains).forEach( trackHead -> {
                List<SegmentedObject> track = SegmentedObjectUtils.getTrack(trackHead);
                iwm.displayTrack(null, ii, ii.pairWithOffset(track), s.getColor(true), false);
            });
        }
    }
    public static void hideTracks(Selection s, InteractiveImage i) {
        ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
        InteractiveImage ii = fixIOI(i, s.getStructureIdx());
        if (ii!=null) {
            Set<SegmentedObject> selTH = s.getElementsAsStream(SegmentedObjectUtils.getPositions(ii.getParents()).stream()).map(SegmentedObject::getTrackHead).collect(Collectors.toSet());
            List<SegmentedObject> tracks = ii.getObjects().stream().map(o -> o.key).map(SegmentedObject::getTrackHead).distinct().filter(selTH::contains).collect(Collectors.toList());
            if (tracks.isEmpty()) return;
            iwm.hideTracks(null, i, tracks, false);
        }
    }
    
    public static void displaySelection(Selection s, int parentStructureIdx, int displayStructureIdx) {
        // get all parent & create pseudo-track
        HashSet<SegmentedObject> parents = new HashSet<>();
        if (parentStructureIdx>=-1) for (SegmentedObject o : s.getAllElements()) parents.add(o.getParent(parentStructureIdx));
        else for (SegmentedObject o : s.getAllElements()) parents.add(o.getParent());
        List<SegmentedObject> parentList = new ArrayList<>(parents);
        if (parentList.isEmpty()) return;
        Collections.sort(parentList);
        InteractiveImageKey.TYPE t = ImageWindowManager.getDefaultInteractiveType();
        if (t==null) t = inferType(parentList.get(0).getBounds());
        InteractiveImage i = ImageWindowManagerFactory.getImageManager().getImageTrackObjectInterface(parentList, s.getStructureIdx(), t);
        ImageWindowManagerFactory.getImageManager().addImage(i.generateImage(displayStructureIdx, true), i ,displayStructureIdx, true);
    }
        
    public static void setMouseAdapter(final JList list) {
        list.addMouseListener( new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = list.locationToIndex(e.getPoint());
                    if (!list.isSelectedIndex(row)) list.setSelectedIndex(row);
                    //logger.debug("right button on row: {}, ctrl {} ctrl", row, ctrl);
                    if (list.isSelectedIndex(row)) {
                        JPopupMenu[] menu = new JPopupMenu[1];
                        Runnable showMenu = () -> menu[0].show(list, e.getX(), e.getY());
                        menu[0] = generateMenu(list, showMenu);
                        showMenu.run();
                    }
                }
                GUI.setNavigationButtonNames(list.getSelectedIndex()>=0);
            }
        });
    }
    
    private static JPopupMenu generateMenu(final JList list, Runnable showMenu) {
        JPopupMenu menu = new JPopupMenu("");
        final List<Selection> selectedValues = list.getSelectedValuesList();
        final List<Selection> allSelections = Utils.asList(list.getModel());
        int dispObjects=0;
        int dispTracks = 0;
        int highTracks = 0;
        int addObjects0 = 0;
        int addObjects1 = 0;
        for (Selection s : selectedValues) {
            if (s.isDisplayingObjects()) dispObjects++;
            if (s.isDisplayingTracks()) dispTracks++;
            if (s.isHighlightingTracks()) highTracks++;
            if (s.isActive(0)) addObjects0++;
            if (s.isActive(1)) addObjects1++;
        }
        final SelectionDAO dao = GUI.getDBConnection().getSelectionDAO();
        final MasterDAO db = GUI.getDBConnection();
        boolean readOnly= GUI.getDBConnection().isConfigurationReadOnly();
        if (selectedValues.size()==1) {
            final JCheckBoxMenuItem showImage = new JCheckBoxMenuItem("Display Selection as an Image");
            showImage.addActionListener((ActionEvent e) -> {
                SelectionUtils.displaySelection(selectedValues.get(0), -2, ImageWindowManagerFactory.getImageManager().getInteractiveStructure());
            });
            menu.add(showImage);
        }
        final JCheckBoxMenuItem displayObjects = new JCheckBoxMenuItem("Display Objects");
        displayObjects.setSelected(dispObjects==selectedValues.size());
        displayObjects.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            for (Selection s : selectedValues ) {
                s.setIsDisplayingObjects(displayObjects.isSelected());
                //dao.store(s); // optimize if necessary -> update
            }
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().updateSelectionListUI();
        });
        menu.add(displayObjects);
        
        final JCheckBoxMenuItem displayTracks = new JCheckBoxMenuItem("Display Tracks");
        displayTracks.setSelected(dispTracks==selectedValues.size());
        displayTracks.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            for (Selection s : selectedValues ) {
                s.setIsDisplayingTracks(displayTracks.isSelected());
                //dao.store(s); // optimize if necessary -> update
            }
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().updateSelectionListUI();
        });
        menu.add(displayTracks);
        
        final JCheckBoxMenuItem highlightTracks = new JCheckBoxMenuItem("Highlight in Track-Tree");
        highlightTracks.setSelected(highTracks==selectedValues.size());
        highlightTracks.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            //Set<Selection> switched = new HashSet<Selection>(selectedValues.size());
            for (Selection s : selectedValues ) {
                //if (s.isHighlightingTracks()!=highlightTracks.isSelected()) switched.add(s);
                s.setHighlightingTracks(highlightTracks.isSelected());
                //dao.store(s); // optimize if necessary -> update
            }
            GUI.getInstance().resetSelectionHighlight();
            GUI.getInstance().updateSelectionListUI();
        });
        menu.add(highlightTracks);
        final JCheckBoxMenuItem navigateMI = new JCheckBoxMenuItem("Enable Navigation");
        if (selectedValues.size()==1) navigateMI.setSelected(selectedValues.get(0).isNavigate());
        navigateMI.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            if (selectedValues.size()==1) {
                selectedValues.get(0).setNavigate(navigateMI.isSelected());
                for (Selection s : allSelections ) if (s!=selectedValues.get(0)) s.setNavigate(false);
            } else for (Selection s : allSelections ) s.setNavigate(false);
            GUI.getInstance().updateSelectionListUI();
        });
        menu.add(navigateMI);
        final JCheckBoxMenuItem addObjects0MI = new JCheckBoxMenuItem("Active Selection Group 0");
        addObjects0MI.setSelected(addObjects0==selectedValues.size());
        addObjects0MI.addActionListener((ActionEvent e) -> { 
            for (Selection s : selectedValues ) s.setActive(addObjects0MI.isSelected()?0:-1);
            GUI.getInstance().updateSelectionListUI();
        });
        menu.add(addObjects0MI);
        final JCheckBoxMenuItem addObjects1MI = new JCheckBoxMenuItem("Active Selection Group 1");
        addObjects1MI.setSelected(addObjects1==selectedValues.size());
        addObjects1MI.addActionListener((ActionEvent e) -> {
            for (Selection s : selectedValues ) s.setActive(addObjects1MI.isSelected()?1:-1);
            GUI.getInstance().updateSelectionListUI();
        });
        menu.add(addObjects1MI);
        menu.add(new JSeparator());
        JMenu colorMenu = new JMenu("Set Color");
        for (String s : Selection.colors.keySet()) {
            final String colorName = s;
            JMenuItem color = new JMenuItem(s);
            colorMenu.add(color);
            color.addActionListener((ActionEvent e) -> {
                if (selectedValues.isEmpty()) return;
                for (Selection s1 : selectedValues) {
                    s1.setColor(colorName);
                    dao.store(s1); // optimize if necessary -> update
                    if (s1.isDisplayingObjects()) {
                        hideObjects(s1, null);
                        displayObjects(s1, null);
                    }
                    if (s1.isDisplayingTracks()) {
                        hideTracks(s1, null);
                        displayTracks(s1, null);
                    }
                }
                list.updateUI();
            });
        }
        menu.add(colorMenu);
        JMenu setOC = new JMenu("Set Object Class");
        String[] ocNames = GUI.getDBConnection().getExperiment().experimentStructure.getObjectClassesAsString();
        for (int i = 0; i<ocNames.length; ++i) {
            final int ocIdx = i;
            JMenuItem oc = new JMenuItem(ocNames[i]);
            setOC.add(oc);
            oc.addActionListener((ActionEvent e) -> {
                if (selectedValues.isEmpty()) return;
                for (Selection s: selectedValues) {
                    s.setObjectClassIdx(ocIdx);
                    s.getMasterDAO().getSelectionDAO().store(s);
                }
                GUI.getInstance().populateSelections();
                GUI.updateRoiDisplayForSelections(null, null);
                GUI.getInstance().resetSelectionHighlight();
                list.updateUI();
                if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
            });
        }
        menu.add(setOC);
        JMenu dupObj = new JMenu("Duplicate Objects to...");
        for (int i = 0; i<ocNames.length; ++i) {
            final int ocIdx = i;
            int parentOCIdx = GUI.getDBConnection().getExperiment().experimentStructure.getParentObjectClassIdx(ocIdx);
            JMenuItem oc = new JMenuItem(ocNames[i]);
            dupObj.add(oc);
            oc.addActionListener((ActionEvent e) -> {

                SegmentedObjectFactory factory = getFactory(ocIdx);
                TrackLinkEditor editor = getEditor(ocIdx, null);
                if (selectedValues.isEmpty()) return;
                for (Selection s : selectedValues) {
                    Set<SegmentedObject> source = s.getAllElements();
                    logger.debug("Will duplicates {} elements from oc {} to oc {} (parent: {})", source.size(), s.getStructureIdx(), ocIdx, parentOCIdx);
                    Map<SegmentedObject, SegmentedObject> sourceMapParent = Duplicate.getSourceMapParents(source.stream().map(o -> o.getParent(parentOCIdx)), parentOCIdx, s.getStructureIdx());
                    Map<SegmentedObject, SegmentedObject> sourceMapDup = Duplicate.duplicate(source.stream(), ocIdx, factory, editor);
                    Duplicate.setParents(sourceMapDup,sourceMapParent, parentOCIdx, s.getStructureIdx(), true, factory);
                    // save to DB
                    sourceMapDup.values().stream().collect(Collectors.groupingBy(SegmentedObject::getPositionName)).forEach((p, toSave) -> {
                        ObjectDAO oDAO = db.getDao(p);
                        oDAO.store(toSave);
                    });
                    // update display
                    ImageWindowManagerFactory.getImageManager().resetObjects(null, ocIdx);
                    GUI.updateRoiDisplayForSelections(null, null);
                    GUI.updateTrackTree();
                }
                if (readOnly) Utils.displayTemporaryMessage("Changes will not be stored as database could not be locked", 5000);
            });
        }
        menu.add(dupObj);

        menu.add(new JSeparator());
        JMenuItem add = new JMenuItem("Add objects selected on active Kymograph");
        add.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            addCurrentObjectsToSelections(selectedValues, dao);
            list.updateUI();
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().resetSelectionHighlight();
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
        });
        menu.add(add);
        JMenu removeMenu = new JMenu("Remove...");
        JMenuItem clear = new JMenuItem("All objects");
        clear.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            for (Selection s : selectedValues ) {
                s.clear();
                dao.store(s);
            }
            list.updateUI();
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().resetSelectionHighlight();
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
        });
        removeMenu.add(clear);
        JMenuItem remove = new JMenuItem("All objects selected on active image");
        remove.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            removeCurrentObjectsFromSelections(selectedValues, dao);
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().resetSelectionHighlight();
            list.updateUI();
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
        });
        removeMenu.add(remove);
        
        JMenuItem removeFromParent = new JMenuItem("All objects from active image");
        removeFromParent.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            removeAllCurrentImageObjectsFromSelections(selectedValues, dao, false, false);
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().resetSelectionHighlight();
            list.updateUI();
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
        });
        removeMenu.add(removeFromParent);

        JMenuItem removeAfter = new JMenuItem("All objects from active image after selected frame");
        removeAfter.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            removeAllCurrentImageObjectsFromSelections(selectedValues, dao, true, true);
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().resetSelectionHighlight();
            list.updateUI();
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
        });
        removeMenu.add(removeAfter);
        JMenuItem removeBefore = new JMenuItem("All objects from active image before selected frame");
        removeBefore.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            removeAllCurrentImageObjectsFromSelections(selectedValues, dao, true, false);
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().resetSelectionHighlight();
            list.updateUI();
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
        });
        removeMenu.add(removeBefore);
        menu.add(removeMenu);

        JMenuItem duplicate = new JMenuItem("Duplicate");
        duplicate.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            String name = JOptionPane.showInputDialog("Duplicate Selection name:");
            if (SelectionUtils.validSelectionName(selectedValues.get(0).getMasterDAO(), name, false, true)) {
                Selection dup = selectedValues.get(0).duplicate(name);
                dup.getMasterDAO().getSelectionDAO().store(dup);
                GUI.getInstance().populateSelections();
                if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
                list.updateUI();
            }
        });
        menu.add(duplicate);
        if (selectedValues.size()!=1) duplicate.setEnabled(false);

        // operations
        JMenu OpMenu = new JMenu("Set Operations");
        menu.add(OpMenu);
        JMenuItem union = new JMenuItem("Union");
        union.addActionListener((ActionEvent e) -> {
            String name = JOptionPane.showInputDialog("Union Selection name:");
            if (SelectionUtils.validSelectionName(selectedValues.get(0).getMasterDAO(), name, false, true)) {
                Selection unionSel = SelectionOperations.union(name, selectedValues);
                unionSel.getMasterDAO().getSelectionDAO().store(unionSel);
                GUI.getInstance().populateSelections();
                if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
                GUI.updateRoiDisplayForSelections(null, null);
                GUI.getInstance().resetSelectionHighlight();
            }
        });
        union.setEnabled(selectedValues.size()>1 && Utils.objectsAllHaveSameProperty(selectedValues, Selection::getStructureIdx));
        OpMenu.add(union);

        JMenuItem intersection = new JMenuItem("Intersection");
        intersection.addActionListener((ActionEvent e) -> {
            String name = JOptionPane.showInputDialog("Union Selection name:");
            if (SelectionUtils.validSelectionName(selectedValues.get(0).getMasterDAO(), name, false, true)) {
                Selection interSel = SelectionOperations.intersection(name, selectedValues);
                interSel.getMasterDAO().getSelectionDAO().store(interSel);
                GUI.getInstance().populateSelections();
                if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
                GUI.updateRoiDisplayForSelections(null, null);
                GUI.getInstance().resetSelectionHighlight();
            }
        });
        intersection.setEnabled(selectedValues.size()>1 && Utils.objectsAllHaveSameProperty(selectedValues, Selection::getStructureIdx));
        OpMenu.add(intersection);

        JMenu diffMenu = new JMenu("Remove all from");
        List<Selection> selDiff = allSelections.stream()
                .filter(s->s.getStructureIdx()==selectedValues.get(0).getStructureIdx())
                .filter(s->!selectedValues.contains(s))
                .collect(Collectors.toList());
        for (Selection sel : selDiff) {
            JMenuItem diff = new JMenuItem(sel.getName());
            diff.addActionListener((ActionEvent e) -> selectedValues.forEach(s->{
                SelectionOperations.removeAll(s, sel);
                s.getMasterDAO().getSelectionDAO().store(s);
                GUI.updateRoiDisplayForSelections(null, null);
                GUI.getInstance().populateSelections();
                GUI.getInstance().resetSelectionHighlight();
                if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
            }));
            diffMenu.add(diff);
        }
        diffMenu.setEnabled(selectedValues.size()>=1 && Utils.objectsAllHaveSameProperty(selectedValues, Selection::getStructureIdx));
        OpMenu.add(diffMenu);

        // filters
        JMenu filterMenu = new JMenu("Filters");
        menu.add(filterMenu);

        JMenu trimMenu = new JMenu("Trim By");
        List<Selection> selTrim = allSelections.stream()
                .filter(s->!selectedValues.contains(s))
                .collect(Collectors.toList());
        for (Selection sel : selTrim) {
            JMenuItem trim = new JMenuItem(sel.getName());
            trim.addActionListener((ActionEvent e) -> selectedValues.forEach(s->{
                SelectionOperations.trimBy(s, sel);
                s.getMasterDAO().getSelectionDAO().store(s);
                GUI.updateRoiDisplayForSelections(null, null);
                GUI.getInstance().populateSelections();
                GUI.getInstance().resetSelectionHighlight();
                if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
            }));
            trimMenu.add(trim);
        }
        trimMenu.setEnabled(selectedValues.size()>=1);
        filterMenu.add(trimMenu);

        JMenu edgeContactMenu = new JMenu("Edge Contact");
        filterMenu.add(edgeContactMenu);
        BooleanParameter keepTouching = new BooleanParameter("Keep Contact", false);
        PropertyUtils.setPersistent(keepTouching, "filter_edge_keep");
        ConfigurationTreeGenerator.addToMenuAsSubMenu(keepTouching, edgeContactMenu);
        JMenu edgeContactOCMenu = new JMenu("Perform Filter");
        if (selectedValues.size()>=1) {
            for (int ocIdx = -1; ocIdx<db.getExperiment().getStructureCount(); ocIdx++) {
                if (ocIdx == selectedValues.get(0).getStructureIdx()) continue;
                int OCIdx = ocIdx;
                JMenuItem filter = new JMenuItem(ocIdx==-1 ? "ViewField" : db.getExperiment().getStructure(ocIdx).getName());
                filter.addActionListener((ActionEvent e) -> {
                    for (Selection s : selectedValues) {
                        SelectionOperations.edgeContactFilter(s, OCIdx, keepTouching.getSelected());
                        s.getMasterDAO().getSelectionDAO().store(s);
                    }
                    if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
                    GUI.updateRoiDisplayForSelections(null, null);
                    GUI.getInstance().populateSelections();
                    GUI.getInstance().resetSelectionHighlight();
                });
                edgeContactOCMenu.add(filter);
            }
        }

        edgeContactOCMenu.setEnabled(selectedValues.size()>=1 && Utils.objectsAllHaveSameProperty(selectedValues, Selection::getStructureIdx));
        edgeContactMenu.add(edgeContactOCMenu);

        JMenu shortTrackMenu = new JMenu("Short Tracks");
        filterMenu.add(shortTrackMenu);
        BooleanParameter keepShort = new BooleanParameter("Keep Short Tracks", true);
        BoundedNumberParameter trackLength = new BoundedNumberParameter("Track Length Threshold", 0, 2, 1, null);
        PropertyUtils.setPersistent(keepShort, "filter_size_keep");
        PropertyUtils.setPersistent(trackLength, "filter_size_length");
        ConfigurationTreeGenerator.addToMenuAsSubMenu(keepShort, shortTrackMenu);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(trackLength, shortTrackMenu);
        JMenuItem shortTrackFilter = new JMenuItem("Perform Filter");
        shortTrackFilter.addActionListener((ActionEvent e) -> {
            for (Selection s : selectedValues) {
                SelectionOperations.trackLengthFilter(s, trackLength.getIntValue(), keepShort.getSelected());
                s.getMasterDAO().getSelectionDAO().store(s);
            }
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().populateSelections();
            GUI.getInstance().resetSelectionHighlight();
        });
        shortTrackFilter.setEnabled(!selectedValues.isEmpty());
        shortTrackMenu.add(shortTrackFilter);

        JMenuItem trackEndFilter = new JMenuItem("Track Ends");
        trackEndFilter.addActionListener((ActionEvent e) -> {
            for (Selection s : selectedValues) {
                SelectionOperations.trackEndsFilter(s);
                s.getMasterDAO().getSelectionDAO().store(s);
            }
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().populateSelections();
            GUI.getInstance().resetSelectionHighlight();
        });
        trackEndFilter.setEnabled(!selectedValues.isEmpty());
        filterMenu.add(trackEndFilter);

        JMenuItem trackMergeFilter = new JMenuItem("Merge");
        trackMergeFilter.addActionListener((ActionEvent e) -> {
            for (Selection s : selectedValues) {
                SelectionOperations.trackConnectionFilter(s, true);
                s.getMasterDAO().getSelectionDAO().store(s);
            }
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().populateSelections();
            GUI.getInstance().resetSelectionHighlight();
        });
        trackMergeFilter.setEnabled(!selectedValues.isEmpty());
        filterMenu.add(trackMergeFilter);

        JMenuItem trackDivisionFilter = new JMenuItem("Division");
        trackDivisionFilter.addActionListener((ActionEvent e) -> {
            for (Selection s : selectedValues) {
                SelectionOperations.trackConnectionFilter(s, false);
                s.getMasterDAO().getSelectionDAO().store(s);
            }
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().populateSelections();
            GUI.getInstance().resetSelectionHighlight();
        });
        trackDivisionFilter.setEnabled(!selectedValues.isEmpty());
        filterMenu.add(trackDivisionFilter);

        // measurement filters
        JMenu measurementMenu = new JMenu("Measurement Filters");
        menu.add(measurementMenu);
        int selOcIdx = selectedValues.isEmpty()?-1:selectedValues.get(0).getStructureIdx();
        SimpleListParameter<MeasurementFilterParameter> filters = new SimpleListParameter<>("MeasurementFilters", new MeasurementFilterParameter(selOcIdx, false));
        filters.setParent(db.getExperiment());
        PropertyUtils.setPersistent(filters, "measurement_filters");
        Object[][] performFilter = filters.getChildren().stream().map(f -> {
            JMenuItem filter = new JMenuItem("Perform Filter...");
            filter.addActionListener((ActionEvent e) -> {
                for (Selection s : selectedValues) {
                    SelectionOperations.filter(s, f.getFilter());
                    s.getMasterDAO().getSelectionDAO().store(s);
                }
                if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
                GUI.updateRoiDisplayForSelections(null, null);
                GUI.getInstance().populateSelections();
                GUI.getInstance().resetSelectionHighlight();
            });
            return new Object[]{filter};
        }).toArray(Object[][]::new);
        ConfigurationTreeGenerator.addToMenuList(filters, measurementMenu, showMenu, performFilter, new Object[0]);
        measurementMenu.setEnabled(!selectedValues.isEmpty() && Utils.objectsAllHaveSameProperty(selectedValues, Selection::getStructureIdx));

        //
        JMenu getParentSelection = new JMenu("Create Parent/Child Selection");
        List<String> ocNamesWithRoot = new ArrayList<>(Arrays.asList(ocNames));
        ocNamesWithRoot.add(0, "Viewfield");
        for (int i = 0; i<ocNamesWithRoot.size(); ++i) {
            final int ocIdx = i-1;
            JMenuItem oc = new JMenuItem(ocNamesWithRoot.get(i));
            getParentSelection.add(oc);
            oc.addActionListener((ActionEvent e) -> {
                if (selectedValues.isEmpty()) return;
                String name = JOptionPane.showInputDialog("Selection name:");
                if (SelectionUtils.validSelectionName(selectedValues.get(0).getMasterDAO(), name, false, true)) {
                    Set<SegmentedObject> res = new HashSet<>();
                    for (Selection s : selectedValues) {
                        if (s.getMasterDAO().getExperiment().experimentStructure.isChildOf(ocIdx, s.getStructureIdx()))
                            s.getAllElements().stream().map(o -> o.getParent(ocIdx)).forEach(res::add);
                        else s.getAllElements().stream().flatMap(o -> o.getChildren(ocIdx, false)).forEach(res::add);
                    }
                    SelectionDAO sDAO = selectedValues.get(0).getMasterDAO().getSelectionDAO();
                    Selection s = sDAO.getOrCreate(name, false);
                    s.addElements(res);
                    sDAO.store(s);
                    GUI.getInstance().populateSelections();
                    GUI.updateRoiDisplayForSelections(null, null);
                    GUI.getInstance().resetSelectionHighlight();
                    list.updateUI();
                    if (readOnly)
                        Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
                }
            });
        }
        menu.add(getParentSelection);

        JMenuItem delete = new JMenuItem("Delete Selection");
        delete.addActionListener((ActionEvent e) -> {
            if (selectedValues.isEmpty()) return;
            DefaultListModel<Selection> model = (DefaultListModel<Selection>)list.getModel();
            for (Selection s : selectedValues ) dao.delete(s);
            for (Selection s : selectedValues) model.removeElement(s);
            list.updateUI();
            GUI.updateRoiDisplayForSelections(null, null);
            GUI.getInstance().resetSelectionHighlight();
            if (readOnly) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
        });
        menu.add(delete);

        return menu;
    }

    public static void addCurrentObjectsToSelections(Collection<Selection> selections, SelectionDAO dao) {
        if (selections.isEmpty()) return;
        List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjectsOrTracks(null);
        for (Selection s : selections ) {
            int[] structureIdx = s.getStructureIdx()==-2 ? new int[0] : new int[]{s.getStructureIdx()};
            List<SegmentedObject> objects = new ArrayList<>(sel);
            SegmentedObjectUtils.keepOnlyObjectsFromSameStructureIdx(objects, structureIdx);
            s.addElements(objects);
            dao.store(s);
        }
    }

    public static void removeCurrentObjectsFromSelections(Collection<Selection> selections, SelectionDAO dao) {
        List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjectsOrTracks(null);
        for (Selection s : selections ) {
            if (s.getStructureIdx()==-2) continue;
            List<SegmentedObject> currentList = new ArrayList<>(sel);
            SegmentedObjectUtils.keepOnlyObjectsFromSameStructureIdx(currentList, s.getStructureIdx());
            s.removeElements(currentList);
            dao.store(s);
        }
    }

    public static void removeAllCurrentImageObjectsFromSelections(Collection<Selection> selections, SelectionDAO dao, boolean limitFrame, boolean afterSelected) {
        ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
        InteractiveImage ioi = iwm.getCurrentImageObjectInterface();
        if (ioi==null) return;

        List<SegmentedObject> parents = ioi.getParents();
        if (limitFrame) {
            List<SegmentedObject> sel = iwm.getSelectedLabileObjectsOrTracks(iwm.getDisplayer().getCurrentImage2());
            if (sel.isEmpty()) return;
            if (afterSelected) {
                int frame = sel.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
                parents = parents.stream().filter(p -> p.getFrame()>=frame).collect(Collectors.toList());
            } else {
                int frame = sel.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
                parents = parents.stream().filter(p -> p.getFrame()<=frame).collect(Collectors.toList());
            }
            if (parents.isEmpty()) return;
        }
        for (Selection s : selections ) {
            s.removeChildrenOf(parents);
            dao.store(s);
        }
    }

    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
    private static TrackLinkEditor getEditor(int objectClassIdx, Set<SegmentedObject> modifiedObjects) {
        try {
            Constructor<TrackLinkEditor> constructor = TrackLinkEditor.class.getDeclaredConstructor(int.class, Set.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx, modifiedObjects, true);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
